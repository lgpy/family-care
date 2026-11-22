# core-data lane: auth/sync data-loss fixes

I fixed all three bugs, one commit each, on branch `fix/auth-sync-data-loss`. Head commit: `d53a203`. Nothing was pushed or merged. I did not edit `AppModule.kt` or any file outside `android/core/data`. Paths below are relative to `android/core/data/src/`.

## Fix 1: a failed refresh no longer signs the user out (`0e4b9a1`)

**Change**, in `main/.../auth/SupabaseAuthRepository.kt`:
- `RefreshFailure` is now resolved by a private `resolve()` (around lines 59-90):
  - first the last signed-in user this process saw (`lastSignedIn`, which is cleared on `SignedOut`);
  - otherwise the stored session, read with `auth.sessionManager.loadSessionOrNull()`. I checked with javap that `Auth.getSessionManager()` and `SessionManager.loadSessionOrNull` exist in auth-kt 3.8.0.
- New `UserSession.storedAuthState()`. It uses the session's user, or the `sub` claim of the access token when the session was saved without user info. The token is only decoded, not verified.
- If the very first status is `RefreshFailure`, the state starts as `Loading`, because reading the stored session is a suspend call.
- `NotAuthenticated` still maps to `SignedOut`. The KDoc is updated.

**Test:** new `test/.../auth/SupabaseAuthRepositoryTest.kt`, 5 tests. It runs a real supabase-kt `Auth` plugin with `MemorySessionManager` and `MemoryCodeVerifierCache`, and sets the status by hand with the internal `setSessionStatus`.
- Failed before the fix (each got `SignedOut`) and pass now:
  - `aFailedRefreshAfterSigningInKeepsTheCaregiverSignedIn`: Authenticated, then RefreshFailure.
  - `aColdStartThatCannotRefreshUsesTheStoredSession`: Initializing, then RefreshFailure, with a stored session.
  - `aStoredSessionWithoutUserInfoIsAttributedThroughItsToken`
- Passed before and after:
  - `aRejectedRefreshTokenStillSignsOut`: after sign-out, a later RefreshFailure does not bring the user back.
  - `aFailedRefreshWithNoStoredSessionIsSignedOut`

## Fix 2: uploads without a user token are retried, not dropped (`d4f4288`)

I read the code first and confirmed the report: the 42501 rule ran before the 401 rule.

**Change:**
- `main/.../sync/UploadFailure.kt`, `isPermanentUploadFailure`: `if (statusCode == UNAUTHORIZED) return false` now comes before any Postgres-code rule. The policy KDoc is updated. A 403/42501 is still permanent.
- `main/.../sync/SupabaseSyncConnector.kt`: `uploadData` now calls a new internal `uploadQueue(nextTransaction, hasUserToken, uploader, rejections)` (below the class). Before each transaction it checks `supabase.auth.currentAccessTokenOrNull() != null`. If there is no token it throws `MissingUserTokenException`, an `IllegalStateException`. That is not a REST error, so the retry policy treats it as transient and PowerSync retries. A new `PendingTransaction` class holds the writes and the `complete` callback.

**Tests:**
- `test/.../sync/UploadFailureTest.kt`:
  - `anUnauthenticatedRequestIsRetriedWhateverThePostgresCode` failed before, passes now.
  - `aPolicyDenialForASignedInUserStaysPermanent` passes.
- New `test/.../sync/SupabaseSyncConnectorTest.kt`, 3 tests: no token means nothing is sent, nothing is completed, nothing is recorded, and the error is transient; with a token every transaction is sent and completed; an empty queue does not need a token. Before the fix this file did not compile because `uploadQueue` did not exist yet.

## Fix 3: the owner of the local data is persisted (`d53a203`)

**Change:**
- New `main/.../sync/LocalOwnerStore.kt`:
  - `LocalOwner` has three values: `Unrecorded`, `Clean` and `User(id)`.
  - `LocalOwnerStore` is the interface the tests fake.
  - `FileLocalOwnerStore` writes a one-line file (`clean` or `user:<uuid>`) through a temp file and an atomic `Files.move`. An unreadable file counts as `Unrecorded`.
- `main/.../sync/SyncLifecycle.kt` (KDoc rewritten), with an in-memory `connectedUserId` so a user is connected once per process:
  - **Signed in as the recorded owner:** keep the data and connect.
  - **Signed in as a different recorded user, or while this process has already connected someone else:** clear first.
  - **Ordering:** the owner is always written *before* `connect`, so a process killed mid-sync leaves a correct record.
  - **Signed out:** clear unless the record is `Clean` and nothing is connected, then write `Clean`.
  - **No record (`Unrecorded`) while signed in:** adopt that user without clearing. This covers the upgrade case.
  - **No record while signed out:** clear.
- `main/.../sync/SyncService.kt`: `PowerSyncSyncService` takes a new `owners: LocalOwnerStore` parameter.
- `main/.../DataLayer.kt`: passes `FileLocalOwnerStore(File(context.noBackupFilesDir, "sync-local-owner"))`. The `DataLayer.create` signature did not change, so `AppModule` did not need editing.

**Tests:**
- `test/.../sync/SyncLifecycleTest.kt`, 17 tests. The existing 9 now run with a fake store. New restart cases use a new `SyncLifecycle` over the same store:
  - `aRestartSignedInAsTheOwnerKeepsTheData`
  - `aRestartSignedOutClearsThePreviousUsersData`
  - `aRestartAsAnotherUserClearsBeforeConnecting`
  - `aProcessKilledBeforeTheWipeIsClearedOnTheNextStart`
  - `aRestartAfterACleanSignOutDoesNotClearAgain`
  - `anUpgradeSignedInAdoptsThatUserWithoutClearing`
  - `anUpgradeSignedOutClears`
  - `aMissingRecordAfterTheFirstDecisionIsTreatedAsData`
- New `test/.../sync/FileLocalOwnerStoreTest.kt`, 3 tests.
- Two existing tests changed:
  - `signingOutWithoutLocalDataDoesNotClear` became `signingOutWithACleanDatabaseDoesNotClear`. With no record, a signed-out start now clears.
  - `aFailedConnectIsRetriedOnTheNextSignIn` now expects the owner to be recorded as user-a after the failed connect.
- Before the fix the new tests did not compile, because there was no store. The behaviour they check (a new instance that starts signed out clears) was missing from the old code, which returned early when `localOwnerId == null`.

## Gradle commands

All run from `android/` with JDK 21 (`JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot"`).

| When | Command | Result |
|---|---|---|
| Fix 1 tests, before the fix | `./gradlew :core:data:testDebugUnitTest --tests '*SupabaseAuthRepositoryTest*'` | 5 tests, 3 failed (expected `SignedIn`, got `SignedOut`) |
| After Fix 1 | `./gradlew :core:data:testDebugUnitTest` | BUILD FAILED, only on the new Fix 2 test `anUnauthenticatedRequestIsRetriedWhateverThePostgresCode`, as intended; all others passed, including SupabaseAuthRepositoryTest 5/5 |
| After Fix 2 | `./gradlew :core:data:testDebugUnitTest :core:data:jvmtests:test` | BUILD SUCCESSFUL |
| Fix 3 tests, before the fix | `./gradlew :core:data:testDebugUnitTest` | compile errors (`FileLocalOwnerStore` and `LocalOwner` unresolved) |
| **Final tree** | `./gradlew :core:data:testDebugUnitTest :core:data:jvmtests:test` | **BUILD SUCCESSFUL in 1m 17s** |

Final counts:
- `core:data`: 98 tests, 0 failures.
- `core:data:jvmtests`: 26 tests, 0 failures. The 6 `LocalSupabaseUploadTest` tests are skipped because there is no local Supabase, as expected.

I did not run the `app` unit tests, because `AppModule.kt` was not touched.

## Limits and things to review

- **Choice made in Fix 3:** a missing record while signed in is always adopted, unless this process has already connected a different user. The brief said both "no record while local data may exist → clear" and "an upgrade with no record → adopt". The two cannot be told apart on disk, so I applied the clear rule only where the process knows it has synced someone else.
- **Fix 1 relies on a supabase-kt internal:** the tests use `setSessionStatus`, which is marked `@SupabaseInternal`, so they can break on a supabase-kt upgrade. The production code uses only public API.
- **Stored user id:** the owner file holds the user id in plain text, in `noBackupFilesDir`. The Supabase session file already stores the same id, unencrypted.
- **Not tested on a device:** I did not run any of this on a device or emulator, and I did not run the Supabase-backed upload tests (no Docker, as instructed).
- **Edits needed outside my files:** none. If the docs describe the owner of the local data as in-memory, they should be updated; I found no such mention in `docs/`.
