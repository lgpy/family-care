# Medications lane report

Branch `fix/medication-logic-bugs`, head `aef660e`. Five commits on top of `b44df38`, one per fix, in order:
`d232d5a` (1), `552797a` (2), `9bcbe15` (3), `238d260` (4), `aef660e` (5).

All five bugs were confirmed by reading the code. I wrote the tests first and ran the red state for fixes 2 to 5, and for the repository part of fix 1. For the domain part of fix 1 the new tests did not compile before the new API existed.

M = android/feature/medications, K = src/main/kotlin/io/github/lgpy/familycare/feature/medications

## Fix 1: stopping with a future last day (confirmed)
- `stop()` now writes `stopped_at` = start of the day after `lastDay` in the regimen's zone, or `now` when that instant is already past.
  - New `RegimenRules.stopEffectiveAt(lastDay, zone, now)`: `M/domain/K/domain/RegimenRules.kt`.
  - Repository: `M/data/K/data/PowerSyncMedicationRepository.kt` (the `stoppedAt` line in `stop()`).
- `Medication.isStopped` is removed. It is replaced by `isStoppedAt(now)` (`stoppedAt != null && !stoppedAt.isAfter(now)`): `Medication.kt:82`.
  - Call sites updated:
    - `ReminderPlan.kt:97` uses the `now` that `plan` already has.
    - `PatientExportSources.kt:103-104` has a new defaulted `now` ctor param (app module).
    - `PatientMedicationsViewModel` has a new `now` param.
    - `MedicationDetailViewModel` has a new `now` param and a `stopped` field in the UI state.
    - `MedicationDetailScreen.kt` reads `uiState.stopped`.
  - `grep isStopped\b` over android/ is now empty.
- `SELECT_ACTIVE_MEDICATIONS` (`LocalMedsSql.kt:84`) is now `(m.stopped_at IS NULL OR m.stopped_at > ?)`. Parameters are `now().toString()` then the user id. Both watchers pass them: `PowerSyncMedicationRepository.activeMedications` and the two in `PowerSyncDoseLogRepository`.
  - It was the only `stopped_at IS NULL` filter in android/.
- Tests that failed before and pass now:
  - `MedsRepositoryTest`: `stopping with a future last day stops the medication at the start of the next day`.
    - Rewritten from the old test. It fails if `stop()` writes `now()`.
  - `MedsRepositoryTest`: `stopping with a last day in the past stops the medication now`.
  - `MedsRepositoryTest`: `a medication with a pending stop stays in the active list and in doses today`.
  - `RegimenRulesTest`: the `stopEffectiveAt` tests (zone, past, today).
  - `MedicationRulesTest`: `isStoppedAt` boundaries.
  - `ReminderPlanningTest`: `a medication with a pending stop is still planned until it takes effect`.
  - `PatientMedicationsViewModelTest` (2 tests) and `MedicationDetailViewModelTest` (2 tests): pending versus effective stop.
- `FakeMedicationRepository.stop` (`Fakes.kt`) now honours the contract.
  - On success it sets `stopped_at` and the regimen end date, updates the observed and patient flows, and drops the medication from the active flow only once `stopped_at <= fake.now`.
  - The fake has a new `now` property.
- `StockRepositoryTest` `stopped medications stay out of the shopping overviews` stopped with last day = today. That is now a pending stop, so the medication correctly stays. I changed it to `TODAY.minusDays(1)`, which stops now.

## Fix 2: every-N-days phase (confirmed)
- `RegimenRules.changeRegimen` now starts the new version at `phaseAlignedStart`: the first date on the old phase on or after `effectiveFrom`.
  - This applies only when both versions are every-N-days with the same N. Otherwise the start stays `effectiveFrom`.
  - The old version still ends at `effectiveFrom - 1`.
- Decision to check: when the aligned start falls after the inherited end date, the change is rejected with `REGIMEN_START_DATE`. The new version would have no days.
- Tests in `RegimenRulesTest`:
  - `a dose change keeps the phase of an every-N-days regimen`: weekly from Mon 2 Nov, change Wed 18 Nov, new start Mon 23 Nov. It also checks that the dose days across both versions are 2, 9, 16, 23 and 30 Nov.
  - `a change on a phase day ... starts on that day`.
  - `a change the day after a phase day starts on the next phase day`.
  - `a new interval or schedule type anchors at the effective date` (N changed, every-N to daily, daily to every-N).
  - `a phase-aligned start after the end date ... is rejected`.
- Red state: three of them failed with the old code. The phase-day case passes on the old code by design.

## Fix 3: change must be after today (confirmed)
- `RegimenRules.changeRegimen` takes a new required `now: Instant`. `effectiveFrom` must be after "today", where today is the later of today in the old version's zone and today in the new version's zone.
  - Using the later of the two zones is my conservative choice; the spec only said the regimen's zone.
  - The error is `REGIMEN_START_DATE`, the existing date error.
- The repository passes `now()` (`PowerSyncMedicationRepository.kt`).
- `ChangeRegimenViewModel`: `today` is now `(ZoneId) -> LocalDate` and is read in `regimen.timeZone`. The same date error is raised before the write. The default date is still tomorrow.
- Tests:
  - `RegimenRulesTest`: `a change effective today is rejected` (today at 15:00), `... yesterday ...`, `... tomorrow is accepted`, `today is the day in the regimen's zone, not in UTC`.
  - `MedsRepositoryTest`: `a change effective today or earlier is refused`.
  - `ChangeRegimenViewModelTest`: `a change effective today or yesterday is rejected before the write`, `today is read in the regimen's time zone`.
- The existing repository test `changing a regimen ends the old version...` used effectiveFrom 10 Nov, which is now past against its fixed clock (19 Nov). I moved it to 20 Nov.
- All existing `changeRegimen` calls in `RegimenRulesTest` got `now = NOW`.
- Red state: three domain tests failed with the rule removed.

## Fix 4: overdue after midnight (confirmed)
- `NeedsAttentionViewModel`: groups from `recentDoses` whose aggregate state is DUE or OVERDUE become rows, oldest first, above today's rows. Today's filter (OVERDUE or MISSED) is unchanged.
  - They leave when confirmed, or when MISSED. MISSED ones are in `missedEarlier`, and the Missed list already shows them.
  - I took any earlier day in the 7-day window, not only yesterday. Anything not missed yet needs a row.
  - Row presentation is the existing `toUi`.
- `PowerSyncDoseLogRepository.dosesToday` is unchanged. `recentDoses` already carries the data.
- Tests:
  - `NeedsAttentionViewModelTest`: statuses are derived with `DoseStatusRules` at a fixed 00:20, 00:10 and 01:10, for a 23:00 dose.
    - OVERDUE shows.
    - DUE shows.
    - MISSED leaves and appears in `missedEarlier`.
    - Confirmed leaves.
    - Give confirms that group.
    - Earlier rows come before today's.
  - `MissedDosesViewModelTest`: `a dose still overdue is not missed yet`.
  - `MedsRepositoryTest`: `a late dose still overdue at 00_20 is in recent doses and not in doses today`, against the real PowerSync database with a fixed 00:20 clock.
- Red state: 5 of the new Needs attention tests failed before the fix.

## Fix 5: "Give anyway" (confirmed)
- New `DoseGroupUi.pendingGroup()` (`DosesTodayViewModel.kt`) returns the group reduced to slots with no effective entry, or null.
  - Both view models' `save` now confirm only that. With nothing pending, nothing is written.
- `giveAnyway()` in both view models now re-reads the group from the current card state. A race where another phone confirms more slots while the dialog is open therefore cannot double-write. If the group has left the card, nothing is written.
- Tests:
  - `DosesTodayViewModelTest`: `give anyway writes only the slots without an entry`, `... does not write a second entry for a skipped slot`, `... reads the slots at the time of the tap...`.
  - `NeedsAttentionViewModelTest`: `give anyway writes only the slots without an entry`, `... writes nothing once every slot has an entry`.
  - `DosesTodayViewModelTest`: the existing `warns before confirming...` asserted the bug (all slots already confirmed, then `giveAnyway` wrote 1 confirmation). It now asserts 0.
- `FakeDoseLogRepository` already records `confirm` calls, so it needed no change.
- Red state: six tests failed with the old code (the five new ones and the updated `warns before confirming...`).

## Gradle commands (JDK 21, from `android/`)
Run with `--rerun --console=plain --continue`; everything was green on the final tree (`aef660e`).

| Command | Result | Tests |
|---|---|---|
| `:feature:medications:domain:test` | BUILD SUCCESSFUL | 177, 0 failed |
| `:feature:medications:data:testDebugUnitTest` | BUILD SUCCESSFUL | 38, 0 failed |
| `:feature:medications:data:jvmtests:test` | BUILD SUCCESSFUL | 40 run, 10 skipped (Supabase upload tests, expected), 0 failed |
| `:feature:medications:android:testDebugUnitTest` | BUILD SUCCESSFUL | 168, 0 failed |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL | 154, 0 failed |

Counts are from the JUnit result XML.

## Not done or not verified
- Server and TypeScript lanes must treat a medication as stopped only when `stopped_at <= now`. I did not touch the server, the vectors or the docs. `docs/domain/medications.md` and `docs/ux` still describe `stopped_at` loosely.
- **Visual question (out of scope, as instructed):**
  - A medication with a pending stop looks active, with no label, in the list and on the detail screen.
  - The yesterday rows in Needs attention show only the time. Nothing says it was yesterday.
- **`stopped_at` format:** the active SQL compares `stopped_at` against an ISO-Z string. Phone-written values (`Instant.toString`) compare correctly. A server-formatted timestamp with a space or a `+00` offset would compare wrongly only within the same calendar day. `parseServerInstant` suggests both formats can occur, so please check what PowerSync actually delivers for `timestamptz`. I could not verify this without a server.
- **Stale active query:** `SELECT_ACTIVE_MEDICATIONS` is evaluated with the time the watch was created. A medication whose pending stop passes later leaves the SQL result only on the next table change. Its regimen end date already stops slots and reminders. The medication list and detail screen use the clock-aware `isStoppedAt`.
- **Edits outside my files:** none. `PatientExportSources.kt` is the call-site change only. `Fakes.kt` and the test files are in my lane.
- **Line endings:** the working-tree files are CRLF and git normalises them. I did not change line endings.
