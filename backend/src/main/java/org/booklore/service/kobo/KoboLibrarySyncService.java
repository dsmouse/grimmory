package org.booklore.service.kobo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookloreSyncToken;
import org.booklore.model.dto.kobo.*;
import org.booklore.model.entity.KoboLibrarySnapshotEntity;
import org.booklore.model.entity.KoboSnapshotBookEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.RequestUtils;
import org.booklore.util.kobo.BookloreSyncTokenGenerator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@AllArgsConstructor
@Service
@Slf4j
public class KoboLibrarySyncService {

    private final BookloreSyncTokenGenerator tokenGenerator;
    private final KoboLibrarySnapshotService koboLibrarySnapshotService;
    private final KoboEntitlementService entitlementService;
    private final UserBookProgressRepository userBookProgressRepository;
    private final KoboServerProxy koboServerProxy;
    private final ObjectMapper objectMapper;
    private final KoboSettingsService koboSettingsService;
    private final AppSettingService appSettingService;

    private Collection<Entitlement> getEntitlementsFromKoboStoreResponse(ResponseEntity<JsonNode> koboStoreResponse) {
         return Optional.ofNullable(koboStoreResponse.getBody())
                .map(body -> {
                    try {
                        List<Entitlement> results = new ArrayList<>();
                        if (body.isArray()) {
                            for (JsonNode node : body) {
                                if (node.has("NewEntitlement")) {
                                    results.add(objectMapper.treeToValue(node, NewEntitlement.class));
                                } else if (node.has("ChangedEntitlement")) {
                                    results.add(objectMapper.treeToValue(node, ChangedEntitlement.class));
                                } else {
                                    log.warn("Unknown entitlement type in Kobo response: {}", node);
                                }
                            }
                        }
                        return results;
                    } catch (Exception e) {
                        log.error("Failed to map Kobo response to Entitlement objects", e);
                        return Collections.<Entitlement>emptyList();
                    }
                })
                .orElse(Collections.emptyList());
    }


    private boolean isForwardingToKoboStore() {
        return appSettingService.getAppSettings().getKoboSettings().isForwardToKoboStore();
    }

    /**
     * Builds and writes the Kobo library sync response directly to the servlet
     * response. Deliberately does NOT mark anything as delivered (books synced,
     * reading states sent, snapshot finalized) until after the response has been
     * fully and successfully written to the client - see the write block at the
     * bottom of this method. Marking delivery eagerly at read time was the root
     * cause of a real production data-loss incident (Arcana incident
     * 20260805-grimmory-kobo-sync-crash, BUG-3): if writing the response failed for
     * any reason after the old code had already committed "synced", the device
     * would never actually receive the data but the DB would insist it had.
     * <p>
     * Writes synchronously to the raw {@link jakarta.servlet.http.HttpServletResponse}
     * instead of returning a {@code StreamingResponseBody} (Spring's async return
     * type for this) deliberately: {@code StreamingResponseBody} triggers Spring's
     * async request dispatch, which re-runs the entire security filter chain a
     * second time on completion. {@link org.booklore.config.security.filter.KoboAuthFilter}
     * runs after Spring Security's {@code WebAsyncManagerIntegrationFilter} in the
     * chain, so the {@code SecurityContext} captured for async propagation is
     * whatever was present *before* Kobo auth ran (empty) - the second pass then
     * fails authorization on an otherwise-successful response. Writing synchronously
     * avoids the async dispatch entirely, sidestepping that interaction rather than
     * trying to fix Spring Security's filter ordering.
     */
    public void syncLibrary(BookLoreUser user, String token) {
        HttpServletRequest request = RequestUtils.getCurrentRequest();
        BookloreSyncToken syncToken = Optional.ofNullable(tokenGenerator.fromRequestHeaders(request)).orElse(new BookloreSyncToken());
        String originalOngoingSyncPointId = syncToken.getOngoingSyncPointId();

        KoboLibrarySnapshotEntity currSnapshot = koboLibrarySnapshotService.findByIdAndUserId(syncToken.getOngoingSyncPointId(), user.getId()).orElseGet(() -> koboLibrarySnapshotService.create(user.getId()));
        Optional<KoboLibrarySnapshotEntity> prevSnapshot = koboLibrarySnapshotService.findByIdAndUserId(syncToken.getLastSuccessfulSyncPointId(), user.getId());

        List<Entitlement> entitlements = new ArrayList<>();
        boolean shouldContinueSync = false;

        Set<Long> addedOrChangedOrUnsyncedIds = new HashSet<>();
        Set<Long> removedIds = Collections.emptySet();
        Set<Long> statusSyncIds = Collections.emptySet();
        Set<Long> progressSyncIds = Collections.emptySet();

        if (prevSnapshot.isPresent()) {
            int maxRemaining = 100;
            List<KoboSnapshotBookEntity> removedAll = new ArrayList<>();
            List<KoboSnapshotBookEntity> changedAll = new ArrayList<>();

            koboLibrarySnapshotService.updateSyncedStatusForExistingBooks(prevSnapshot.get().getId(), currSnapshot.getId());

            Page<KoboSnapshotBookEntity> addedPage = koboLibrarySnapshotService.getNewlyAddedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), PageRequest.of(0, maxRemaining), user.getId());
            List<KoboSnapshotBookEntity> addedAll = new ArrayList<>(addedPage.getContent());
            maxRemaining -= addedPage.getNumberOfElements();
            shouldContinueSync = addedPage.hasNext();

            Page<KoboSnapshotBookEntity> changedPage = Page.empty();
            if (addedPage.isLast() && maxRemaining > 0) {
                changedPage = koboLibrarySnapshotService.getChangedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), PageRequest.of(0, maxRemaining));
                changedAll.addAll(changedPage.getContent());
                maxRemaining -= changedPage.getNumberOfElements();
                shouldContinueSync = shouldContinueSync || changedPage.hasNext();
            }

            Page<KoboSnapshotBookEntity> removedPage = Page.empty();
            if (changedPage.isLast() && maxRemaining > 0) {
                removedPage = koboLibrarySnapshotService.getRemovedBooks(prevSnapshot.get().getId(), currSnapshot.getId(), user.getId(), PageRequest.of(0, maxRemaining));
                removedAll.addAll(removedPage.getContent());
                shouldContinueSync = shouldContinueSync || removedPage.hasNext();
            }

            Set<Long> addedIds = addedAll.stream().map(KoboSnapshotBookEntity::getBookId).collect(Collectors.toSet());
            Set<Long> changedIds = changedAll.stream().map(KoboSnapshotBookEntity::getBookId).collect(Collectors.toSet());
            removedIds = removedAll.stream().map(KoboSnapshotBookEntity::getBookId).collect(Collectors.toSet());

            addedOrChangedOrUnsyncedIds.addAll(addedIds);
            addedOrChangedOrUnsyncedIds.addAll(changedIds);

            entitlements.addAll(entitlementService.generateNewEntitlements(addedIds, token));
            entitlements.addAll(entitlementService.generateChangedEntitlements(changedIds, token, false));
            entitlements.addAll(entitlementService.generateChangedEntitlements(removedIds, token, true));

            if (!shouldContinueSync) {
                ReadingStatesToSync readingStates = computeReadingStatesToSync(user.getId(), currSnapshot.getId());
                entitlements.addAll(readingStates.changedStates());
                statusSyncIds = readingStates.statusSyncIds();
                progressSyncIds = readingStates.progressSyncIds();
                entitlements.addAll(entitlementService.generateTags());
            }
        } else {
            int maxRemaining = 100;
            List<KoboSnapshotBookEntity> snapshotBookEntities = new ArrayList<>();
            while (maxRemaining > 0) {
                Page<KoboSnapshotBookEntity> page = koboLibrarySnapshotService.getUnsyncedBooks(currSnapshot.getId(), PageRequest.of(0, maxRemaining));
                snapshotBookEntities.addAll(page.getContent());
                maxRemaining -= page.getNumberOfElements();
                shouldContinueSync = page.hasNext();
                if (!shouldContinueSync || page.getNumberOfElements() == 0) break;
            }
            addedOrChangedOrUnsyncedIds = snapshotBookEntities.stream().map(KoboSnapshotBookEntity::getBookId).collect(Collectors.toSet());
            entitlements.addAll(entitlementService.generateNewEntitlements(addedOrChangedOrUnsyncedIds, token));

            if (!shouldContinueSync) {
                ReadingStatesToSync readingStates = computeReadingStatesToSync(user.getId(), currSnapshot.getId());
                entitlements.addAll(readingStates.changedStates());
                statusSyncIds = readingStates.statusSyncIds();
                progressSyncIds = readingStates.progressSyncIds();
                entitlements.addAll(entitlementService.generateTags());
            }
        }

        if (!shouldContinueSync && isForwardingToKoboStore()) {
            ResponseEntity<JsonNode> koboStoreResponse = null;
            try {
                koboStoreResponse = koboServerProxy.proxyCurrentRequest(null, true);
            } catch (Exception e) {
                log.warn("Failed to get response from Kobo /v1/library/sync, fallback to noproxy", e);
            }

            if (koboStoreResponse != null) {
                entitlements.addAll(getEntitlementsFromKoboStoreResponse(koboStoreResponse));

                String upstreamContinueSyncHeader = koboStoreResponse.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNC);
                String upstreamKoboSyncTokenHeader = koboStoreResponse.getHeaders().getFirst(KoboHeaders.X_KOBO_SYNCTOKEN);

                if (upstreamKoboSyncTokenHeader != null) {
                    syncToken = tokenGenerator.fromBase64(upstreamKoboSyncTokenHeader);
                }

                shouldContinueSync = "continue".equalsIgnoreCase(upstreamContinueSyncHeader);
            }
        }

        if (shouldContinueSync) {
            syncToken.setOngoingSyncPointId(currSnapshot.getId());
        } else {
            syncToken.setOngoingSyncPointId(null);
            syncToken.setLastSuccessfulSyncPointId(currSnapshot.getId());
        }

        byte[] responseBytes = objectMapper.writeValueAsBytes(entitlements);

        boolean finalShouldContinueSync = shouldContinueSync;
        String currSnapshotId = currSnapshot.getId();
        String prevSnapshotId = prevSnapshot.map(KoboLibrarySnapshotEntity::getId).orElse(null);
        Long userId = user.getId();

        HttpServletResponse response = RequestUtils.getCurrentResponse();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setHeader(KoboHeaders.X_KOBO_SYNC, shouldContinueSync ? "continue" : "");
        response.setHeader(KoboHeaders.X_KOBO_SYNCTOKEN, tokenGenerator.toBase64(syncToken));

        try {
            response.getOutputStream().write(responseBytes);
            response.getOutputStream().flush();
        } catch (IOException e) {
            log.warn("KOBO_SYNC_DELIVERY: failed to write response to client (bytes={}); not marking anything as delivered, device will retry", responseBytes.length, e);
            return;
        }

        log.info("KOBO_SYNC_DELIVERY: response written successfully (bytes={}), committing delivery-confirmed writes: addedOrChangedOrUnsynced={} removed={} statusSync={} progressSync={} finalizing={}",
                responseBytes.length, addedOrChangedOrUnsyncedIds.size(), removedIds.size(),
                statusSyncIds.size(), progressSyncIds.size(), !finalShouldContinueSync);
        try {
            koboLibrarySnapshotService.markBooksSyncedAfterDelivery(currSnapshotId, addedOrChangedOrUnsyncedIds);
            koboLibrarySnapshotService.recordRemovedBooksAfterDelivery(currSnapshotId, userId, removedIds);
            koboLibrarySnapshotService.markReadingStatesSentAfterDelivery(statusSyncIds, progressSyncIds);
            if (!finalShouldContinueSync) {
                koboLibrarySnapshotService.finalizeRoundAfterDelivery(prevSnapshotId, originalOngoingSyncPointId, userId);
            }
            log.info("KOBO_SYNC_DELIVERY: delivery-confirmed writes committed successfully for snapshot={}", currSnapshotId);
        } catch (Exception e) {
            log.error("KOBO_SYNC_DELIVERY: response was written to the client but the delivery-confirmed writes FAILED for snapshot={} - device believes it has this data, DB does not reflect that. Needs investigation.", currSnapshotId, e);
        }
    }

    private record ReadingStatesToSync(List<ChangedReadingState> changedStates, Set<Long> statusSyncIds, Set<Long> progressSyncIds) {
        static ReadingStatesToSync empty() {
            return new ReadingStatesToSync(Collections.emptyList(), Collections.emptySet(), Collections.emptySet());
        }
    }

    private ReadingStatesToSync computeReadingStatesToSync(Long userId, String snapshotId) {
        List<UserBookProgressEntity> booksNeedingSync =
                userBookProgressRepository.findAllBooksNeedingKoboSync(userId, snapshotId);

        if (!koboSettingsService.getCurrentUserSettings().isTwoWayProgressSync()) {
            booksNeedingSync = booksNeedingSync.stream()
                    .filter(p -> needsStatusSync(p) || needsKoboProgressSync(p))
                    .toList();
        }

        if (booksNeedingSync.isEmpty()) {
            return ReadingStatesToSync.empty();
        }

        List<ChangedReadingState> changedStates = entitlementService.generateChangedReadingStates(booksNeedingSync);

        Set<Long> statusSyncIds = new HashSet<>();
        Set<Long> progressSyncIds = new HashSet<>();
        for (UserBookProgressEntity progress : booksNeedingSync) {
            if (needsStatusSync(progress)) {
                statusSyncIds.add(progress.getId());
            }
            if (needsProgressSync(progress)) {
                progressSyncIds.add(progress.getId());
            }
        }

        log.info("Prepared {} reading states to sync to Kobo", changedStates.size());
        return new ReadingStatesToSync(changedStates, statusSyncIds, progressSyncIds);
    }

    private boolean needsStatusSync(UserBookProgressEntity progress) {
        Instant modifiedTime = progress.getReadStatusModifiedTime();
        if (modifiedTime == null) {
            return false;
        }
        Instant sentTime = progress.getKoboStatusSentTime();
        return sentTime == null || modifiedTime.isAfter(sentTime);
    }

    private boolean needsKoboProgressSync(UserBookProgressEntity progress) {
        Instant sentTime = progress.getKoboProgressSentTime();
        Instant receivedTime = progress.getKoboProgressReceivedTime();
        return receivedTime != null && (sentTime == null || receivedTime.isAfter(sentTime));
    }

    private boolean needsProgressSync(UserBookProgressEntity progress) {
        if (needsKoboProgressSync(progress)) {
            return true;
        }

        if (koboSettingsService.getCurrentUserSettings().isTwoWayProgressSync()
                && progress.getEpubProgressPercent() != null) {
            Instant sentTime = progress.getKoboProgressSentTime();
            Instant lastReadTime = progress.getLastReadTime();
            if (lastReadTime != null && (sentTime == null || lastReadTime.isAfter(sentTime))) {
                return true;
            }
        }

        return false;
    }
}
