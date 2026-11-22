# patient-info-gate report

Branch `fix/patient-info-circle-gate`, head `9393c6be7d62f790c3efae43b7efb1b7ef97686b`.
Commits: `4066d3c` (fix 1), `9393c6b` (fix 2). Not pushed.

## Fix 1: stale edit form deletes another caregiver's entries

**Confirmed** by reading, and by 12 failing tests before the fix. `PatientInfoEditViewModel` read the record once and `save` replaced every column, so a stale snapshot overwrote the lists. Conditions and contacts behaved the same. The jsonb-spacing path was also real: `formatTextList`/`formatContacts` write compact JSON and the stored text was compared as text.

**Changes**
- `PatientInfoRepository.kt`: new `saveChanges(patientId, loaded: PatientInfo?, edited)` with a default body `= save(patientId, edited)`. The default is needed because `app/` (`PatientFormViewModel`, and the app test `FakePatientInfoRepository` in `ui/Fakes.kt`) implements and calls the interface and is outside my files. `save` keeps its replace semantics, and `PatientFormViewModel` still uses it for a brand-new patient.
- `PatientInfoMerge.kt` (new): `planPatientInfoUpdate(loaded, edited, current)`.
  - Lists apply the user's difference to the current row: entries added are appended after the current order, entries removed are removed, entries added by others are kept. An edited entry counts as remove plus add. Matching is trimmed, and case-insensitive for allergies and conditions.
  - A scalar the user changed wins. A scalar the user did not change is left alone.
  - A column goes into the UPDATE only if its merged value differs from the current one. Lists are compared as parsed values, never as text, so Postgres' spaced jsonb no longer triggers uploads.
  - Validation (the 30-entry and 5-contact limits and so on) runs on the merged record, restricted to the columns being written. A legacy invalid value in an untouched column never blocks a save.
- `PowerSyncPatientInfoRepository.kt:56-110`: `saveChanges` reads the row inside the same `writeTransaction` as the write. No row means a whole INSERT. A merge with nothing to write queues nothing, and a failing merged validation returns `Invalid` with nothing queued. `save` now does its existence check inside the transaction too.
- `LocalPatientInfoSql.kt`: `UPDATE_PATIENT_INFO` is replaced by `updatePatientInfoSql(columns)`, which uses a whitelist of column names. `SELECT_PATIENT_INFO_BY_ID` is added.
- `PatientInfoParameters.kt`: `PatientInfoColumns` and `patientInfoColumnValues`.
- `PatientInfoEditViewModel.kt`: keeps `loadedInfo` (the snapshot), never refreshed, and calls `saveChanges(id, loadedInfo, info)`. A retry after a failed save still diffs against the original snapshot.

**Tests** (written first, failed, now pass)
- Repository (`PatientInfoRepositoryTest`, 19 added): a notes-only edit writes only `notes` while Penicillin stays; concurrent add with the user's addition appended; edited entry as remove plus add; concurrent removal (the user's removal applied to the current row, and an entry already removed by someone else not written again); a removal never removes an entry the user did not see; contacts merged; lists stored in the spaced Postgres format are not written by an unrelated edit; scalar changed by the user wins and untouched scalars stay; clearing a scalar writes null; nothing changed queues nothing; the 30-entry limit on the merged list; a form that started empty merges onto a row that synced meanwhile; no row inserts the whole record; a viewer is refused.
- View model (`PatientInfoEditViewModelTest`, 4 added): the snapshot is passed to the repository, a later sync does not refresh it, a form with no row passes a null snapshot, and a retry keeps the original snapshot.
- Test fake `Fakes.kt` (patient-info android) records `saveChanges` calls as `SaveCall(loaded, isChange)`.

**Out of scope, and whether my change helps**
- First save from a device that has not synced the row (INSERT, then `UPDATE_EXISTING` on the server): policy unchanged, and this is still a whole-row overwrite on a server conflict, so no better there. It is slightly better in one case: if the row has synced locally by the time the user saves, the save now sees it inside the transaction and merges instead of inserting. It is not worse.
- A merge can only add entries someone else removed or added. It cannot see a row that exists only on the server.

**Known edges**
- When others' additions push a list over its limit (30 entries, or 5 contacts), the save fails with the existing `Invalid` error for that list. The form does not show the entries it could not see, so the user must reopen it.
- Merged contacts that are edited move to the end of the list ("append additions").
- The parsed lists drop entries `parseTextList` and `parseContacts` cannot read, and a written list column no longer contains them. This is the same as before the change.

## Fix 2: circle gate stays on "Working…" for the next account

**Confirmed** by reading and by 6 failing tests before the fix. `CircleGateViewModel` is activity-scoped (`koinViewModel()` under `setContent`), and `busy = true` was only cleared by a failure.

**Changes** (`CircleGateViewModel.kt`)
- A collector in `init` follows the signed-in user id (`collectLatest`). On a user change it bumps `accountGeneration` and resets `actionState`. It also collects `setupState` and resets `actionState` when it reaches `HasCircle`.
- `createCircle` and `joinWithCode` now share `runAction`.
  - A success keeps `busy` only if `HasCircle` has not already arrived, so a sync that outruns the RPC response no longer sticks. `creatingACircleKeepsWorkingUntilTheRowsSync` still passes.
  - A result for an earlier account (generation mismatch) is dropped, so A's late result never reaches B.
- `uiState`: wrapped in `flatMapLatest` on the user id with `onStart { emit(Loading()) }`. This closes the replay: a new account starts from `Loading` instead of A's cached `HasCircle`. If the repository still holds a stale value and emits it immediately, `stateIn` conflation can hide that `Loading`. The real `setupState` already depends on `authState`, so it does not.
- Cost: the new collector keeps `setupState` collected for the life of the view model, not only while the gate is on screen. That is needed to end "Working…" after a success.

**Tests** (`CircleGateViewModelTest`, 7 added; test-local `MutableAuth`, `SlowCircles` and `SetupFrom` because `Fakes.kt` is not mine): A then B via sign-out; direct A to B switch clears busy and the last error; busy ends when the circle rows arrive; joined then removed (busy false and the form usable again); create finishing after the rows synced; a late result for the signed-out account not shown to B; B does not see A's circle after the user change (stale `uiState`).

## Gradle (JDK 21, from `android/`)
All on the final tree, all `BUILD SUCCESSFUL`:
- `:feature:patient-info:domain:test`: 16 tests, 0 failed.
- `:feature:patient-info:data:jvmtests:test`: 25 tests, 0 failed.
- `:feature:patient-info:android:testDebugUnitTest`: 34 tests, 0 failed.
- `:app:testDebugUnitTest`: 161 tests, 0 failed (includes `CircleGateViewModelTest`, 24).
- Last combined run: `./gradlew :feature:patient-info:domain:test :feature:patient-info:data:jvmtests:test :feature:patient-info:android:testDebugUnitTest :app:testDebugUnitTest --continue`, `BUILD SUCCESSFUL in 1m 52s`. The three patient-info tasks were `UP-TO-DATE` in it, because their inputs had not changed since they ran green after commit `4066d3c`, and the counts above come from their result XML.
- Before-fix runs failed as described: 12 of 25 in jvmtests, and 7 of 24 in `CircleGateViewModelTest`.

## Not done or not verified
- No on-device or emulator run. No test of the screen, only view model and repository tests (the repository tests use a real PowerSync database).
- I did not re-run the replay test against the old view model to watch it fail. It failed in an earlier form for other reasons, and the old code has no reset.

## Edits needed outside my files
None required. Optional: `app/src/test/.../ui/Fakes.kt` `FakePatientInfoRepository` could override `saveChanges`. It compiles and works through the default.
