# Kobo Store Fixtures

Test fixtures built from real production Kobo Store traffic, captured during the
2026-08-05/06 Kobo sync crash investigation. See the Arcana incident write-up
(`Arcana/Tech/Homenet/Incidencies/20260805-grimmory-kobo-sync-crash.md`) and
`Arcana/Tech/Field Notes/kobo-sync-protocol.md` for full context.

## `real-changed-product-metadata.json.fixture`

**Genuinely real, captured production data.** 43 unique `ChangedProductMetadata`
entries, deduplicated by title from `"Unknown entitlement type in Kobo response"`
warning logs (this is the only entitlement type that gets its full JSON dumped to
logs, since it's the one `KoboLibrarySyncService.getEntitlementsFromKoboStoreResponse`
doesn't recognize and drops — see BUG-6 in the incident doc). Real Kobo Store
purchased-library data (a Discworld/Pratchett collection plus a few others), real
field shapes, real DRM download-token lengths.

Use this for testing the entitlement-type filtering/drop path (BUG-6), and for
realistic raw-payload-size testing.

Total size: ~213KB compact JSON, closely matching the raw/dropped portion of a
real captured 452896-byte / 90-node Kobo Store response (43 of those 90 nodes
were this same unrecognized type).

## `adapted-new-entitlements.json.fixture`

**Adapted, not literally captured.** Same real `BookMetadata` content as above,
re-wrapped under a `NewEntitlement` → `BookEntitlement`/`BookMetadata` structure
(matching `org.booklore.model.dto.kobo.NewEntitlement`) instead of the real
`ChangedProductMetadata` wrapper. The `BookEntitlement` portion (`Id`,
`CrossRevisionId`, `RevisionId`, `Status`, etc.) is synthesized — reasonable
values, not observed traffic — because the actual `NewEntitlement`/
`ChangedEntitlement` types that flow into Booklore's own sync response were
never captured in raw form anywhere (they don't get logged; only unrecognized
types do). Real book content, synthetic entitlement wrapper.

Use this for testing the byte-budget/overcommit logic (the real BUG-1 fix) against
something that will actually deserialize as a recognized type and flow into the
response the way real forwarded entries do.

Total size: ~227KB, closely matching the real captured `recognizedBytes=221447`
for 47 entries in the same live diagnostic capture.

## What neither fixture replaces

A proper fake-upstream setup (chaining two grimmory instances — see the incident
doc's backlog note) would give genuine end-to-end protocol coverage instead of
fixtures assembled from log scraping. Not built yet as of this writing.
