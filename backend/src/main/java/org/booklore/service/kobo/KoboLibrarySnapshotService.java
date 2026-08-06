package org.booklore.service.kobo;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.mapper.BookEntityToKoboSnapshotBookMapper;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ShelfType;
import org.booklore.repository.KoboDeletedBookProgressRepository;
import org.booklore.repository.KoboLibrarySnapshotRepository;
import org.booklore.repository.KoboSnapshotBookRepository;
import org.booklore.repository.ShelfRepository;
import org.booklore.repository.UserBookProgressRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@AllArgsConstructor
@Service
public class KoboLibrarySnapshotService {

    private final KoboLibrarySnapshotRepository koboLibrarySnapshotRepository;
    private final KoboSnapshotBookRepository koboSnapshotBookRepository;
    private final ShelfRepository shelfRepository;
    private final BookEntityToKoboSnapshotBookMapper mapper;
    private final KoboDeletedBookProgressRepository koboDeletedBookProgressRepository;
    private final UserBookProgressRepository userBookProgressRepository;
    private final KoboCompatibilityService koboCompatibilityService;
    private final AuthenticationService authenticationService;

    @Transactional(readOnly = true)
    public Optional<KoboLibrarySnapshotEntity> findByIdAndUserId(String id, Long userId) {
        return koboLibrarySnapshotRepository.findByIdAndUserId(id, userId);
    }

    @Transactional
    public KoboLibrarySnapshotEntity create(Long userId) {
        KoboLibrarySnapshotEntity snapshot = KoboLibrarySnapshotEntity.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .build();

        List<KoboSnapshotBookEntity> books = mapBooksToKoboSnapshotBook(getKoboShelf(userId), snapshot);
        snapshot.setBooks(books);

        return koboLibrarySnapshotRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public Page<KoboSnapshotBookEntity> getUnsyncedBooks(String snapshotId, Pageable pageable) {
        return koboSnapshotBookRepository.findBySnapshot_IdAndSyncedFalse(snapshotId, pageable);
    }

    @Transactional
    public void updateSyncedStatusForExistingBooks(String previousSnapshotId, String currentSnapshotId) {
        List<KoboSnapshotBookEntity> list = koboSnapshotBookRepository.findUnchangedBooksBetweenSnapshots(previousSnapshotId, currentSnapshotId);
        List<Long> unchangedBooks = list.stream()
                .map(KoboSnapshotBookEntity::getBookId)
                .toList();

        if (!unchangedBooks.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(currentSnapshotId, unchangedBooks);
        }
    }

    @Transactional(readOnly = true)
    public Page<KoboSnapshotBookEntity> getNewlyAddedBooks(String previousSnapshotId, String currentSnapshotId, Pageable pageable, Long userId) {
        return koboSnapshotBookRepository.findNewlyAddedBooks(previousSnapshotId, currentSnapshotId, true, pageable);
    }

    @Transactional(readOnly = true)
    public Page<KoboSnapshotBookEntity> getRemovedBooks(String previousSnapshotId, String currentSnapshotId, Long userId, Pageable pageable) {
        return koboSnapshotBookRepository.findRemovedBooks(previousSnapshotId, currentSnapshotId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<KoboSnapshotBookEntity> getChangedBooks(String previousSnapshotId, String currentSnapshotId, Pageable pageable) {
        return koboSnapshotBookRepository.findChangedBooks(previousSnapshotId, currentSnapshotId, pageable);
    }

    /**
     * Marks books synced. Must only be called after the response containing their
     * entitlements has been confirmed successfully delivered to the device - see
     * KoboLibrarySyncService's post-write completion handling. Calling this before
     * delivery is confirmed is the exact bug this method exists to avoid repeating
     * (Arcana incident 20260805-grimmory-kobo-sync-crash, BUG-3).
     */
    @Transactional
    public void markBooksSyncedAfterDelivery(String snapshotId, Collection<Long> bookIds) {
        if (!bookIds.isEmpty()) {
            koboSnapshotBookRepository.markBooksSynced(snapshotId, new ArrayList<>(bookIds));
        }
    }

    /**
     * Records removed-book progress entries. Must only be called after delivery is
     * confirmed - see {@link #markBooksSyncedAfterDelivery}.
     */
    @Transactional
    public void recordRemovedBooksAfterDelivery(String currentSnapshotId, Long userId, Collection<Long> bookIds) {
        if (!bookIds.isEmpty()) {
            List<KoboDeletedBookProgressEntity> progressEntities = bookIds.stream()
                    .map(bookId -> KoboDeletedBookProgressEntity.builder()
                            .bookIdSynced(bookId)
                            .snapshotId(currentSnapshotId)
                            .userId(userId)
                            .build())
                    .toList();

            koboDeletedBookProgressRepository.saveAll(progressEntities);
        }
    }

    /**
     * Finalizes a completed (non-continuing) sync round: retires the previous
     * snapshot and clears stale deleted-book-progress tracking for the round that's
     * now superseded. Must only be called after delivery is confirmed - see
     * {@link #markBooksSyncedAfterDelivery}.
     */
    @Transactional
    public void finalizeRoundAfterDelivery(String prevSnapshotId, String originalOngoingSyncPointId, Long userId) {
        if (prevSnapshotId != null) {
            deleteById(prevSnapshotId);
        }
        if (originalOngoingSyncPointId != null) {
            koboDeletedBookProgressRepository.deleteBySnapshotIdAndUserId(originalOngoingSyncPointId, userId);
        }
    }

    /**
     * Marks reading-state sync timestamps. Must only be called after delivery is
     * confirmed - see {@link #markBooksSyncedAfterDelivery}. Re-fetches by id rather
     * than accepting entities directly, since the entities were read in an earlier,
     * already-closed transaction.
     */
    @Transactional
    public void markReadingStatesSentAfterDelivery(Set<Long> statusSyncIds, Set<Long> progressSyncIds) {
        if (statusSyncIds.isEmpty() && progressSyncIds.isEmpty()) {
            return;
        }
        Set<Long> allIds = new HashSet<>(statusSyncIds);
        allIds.addAll(progressSyncIds);
        List<UserBookProgressEntity> entities = userBookProgressRepository.findAllById(allIds);

        Instant sentTime = Instant.now();
        for (UserBookProgressEntity entity : entities) {
            if (statusSyncIds.contains(entity.getId())) {
                entity.setKoboStatusSentTime(sentTime);
            }
            if (progressSyncIds.contains(entity.getId())) {
                entity.setKoboProgressSentTime(sentTime);
            }
        }
        userBookProgressRepository.saveAll(entities);
    }

    private ShelfEntity getKoboShelf(Long userId) {
        return shelfRepository
                .findByUserIdAndName(userId, ShelfType.KOBO.getName())
                .orElseThrow(() -> new NoSuchElementException(
                        String.format("Shelf '%s' not found for user %d", ShelfType.KOBO.getName(), userId)
                ));
    }

    private List<KoboSnapshotBookEntity> mapBooksToKoboSnapshotBook(ShelfEntity shelf, KoboLibrarySnapshotEntity snapshot) {
        Long userId = snapshot.getUserId();

        return shelf.getBookEntities().stream()
                .filter(book -> isBookOwnedByUser(book, userId))
                .filter(koboCompatibilityService::isBookSupportedForKobo)
                .map(book -> {
                    KoboSnapshotBookEntity snapshotBook = mapper.toKoboSnapshotBook(book);
                    snapshotBook.setSnapshot(snapshot);
                    snapshotBook.setFileHash(book.getPrimaryBookFile().getCurrentHash());
                    snapshotBook.setMetadataUpdatedAt(book.getMetadataUpdatedAt());
                    return snapshotBook;
                })
                .toList();
    }

    private boolean isBookOwnedByUser(BookEntity book, Long userId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user.getPermissions().isAdmin()) {
            return true;
        }
        return book.getLibrary()
                .getUsers()
                .stream()
                .map(BookLoreUserEntity::getId)
                .anyMatch(id -> Objects.equals(id, userId));
    }

    public void deleteById(String id) {
        koboLibrarySnapshotRepository.deleteById(id);
    }

}