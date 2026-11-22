# Reminders lane: report

Branch `fix/reminder-alarm-safety`, head `d51874a` (on top of `205582f`, base `ce54165`). Not pushed.
Paths: M = `android/feature/medications`, A = `android/feature/appointments`.

## Result
All four module test suites pass on the final tree (one Gradle run, `BUILD SUCCESSFUL in 1m 38s`):

    ./gradlew :feature:medications:domain:test :feature:medications:android:testDebugUnitTest :feature:appointments:domain:test :feature:appointments:android:testDebugUnitTest --continue

| module | tests | failures |
|---|---|---|
| medications domain | 175 | 0 |
| medications android | 164 | 0 |
| appointments domain | 35 | 0 |
| appointments android | 55 | 0 |

New tests: `ReminderOpenGroupsTest` (11), `AppointmentOpenRemindersTest` (11), `ReminderDecisionsTest` (14), `AppointmentReminderDecisionsTest` (10).

Other Gradle commands:
- `:feature:medications:domain:test --tests "*ReminderOpenGroupsTest*"`: `BUILD FAILED`, `Unresolved reference 'openGroups'` (red).
- Same command after implementing: 1 failure, a bug in my test (`.single()` on two future groups). Fixed; full medications domain suite then passed, 175/175.
- `:feature:appointments:domain:test --tests "*AppointmentOpenRemindersTest*"`: `BUILD FAILED`, `Unresolved reference 'openReminders'` / `'idsToCancel'` (red).

## Commits
1. `205582f` domain only (Fix 1 decisions + tests).
2. `d51874a` everything in the two android modules. **Fixes 1 (apply), 2, 3 and 4 are in one commit**: they edit the same functions in the same files, so a per-fix split was not practical.

## Fix 1: reschedule dismissed fired reminders
- Medications domain `ReminderPlan.kt:156` `ReminderPlanning.openGroups` (pure): groups whose time has passed, with at least one slot that has no effective entry and is not `DoseState.MISSED` per `DoseStatusRules` using the **patient's** `missedWindowMinutes`; same stopped/patient/archived/routing checks as `plan`. `ReminderPlan.kt:218` `idsToCancel(candidates, planned, open)` is the sweep decision.
- Appointments domain `AppointmentReminderPlan.kt:141` `openReminders`: reminders already past, kept while `now <= start` (or `start + durationMinutes` when set), appointment not cancelled / present / routed here. A moved appointment gets new instants, hence new ids, so the old ones fall out. `:166` `idsToCancel`. `plan` now shares a private `remindersOf` helper.
- Scheduler apply: M `DoseReminderScheduler.kt:141` `reschedule()`, A `AppointmentReminderScheduler.kt:125` `reschedule()`. Both the alarm sweep and the notification sweep use `idsToCancel`. The stored scheduled set keeps still-open ids so they can be cancelled later.
- Tests that failed before / pass now: all of `ReminderOpenGroupsTest` and `AppointmentOpenRemindersTest` (compile-red, then green). Covered: due slot stays open, missed boundary (120 min inclusive, per-patient window), taken/skipped closes, partly confirmed keeps remaining slots, stopped/archived/unknown patient, routing, id equals the id the plan gave, sweep keeps planned+open only; appointments: until start, until end with duration, cancelled, deleted, moved, routing.
- Covered only by reading (no test): that `reschedule()` actually calls `AlarmManager.cancel`/`NotificationManager.cancel` only for the returned ids.

## Fix 2: `AuthState.Loading` treated as signed out
- Pure helper `ReminderDecisions.resolveAuth` (M `ReminderDecisions.kt`) and `AppointmentReminderDecisions.resolveAuth` (A `AppointmentReminderDecisions.kt`): waits for the first non-`Loading` state, 4 s timeout (`AUTH_TIMEOUT_MILLIS`), returns `Loading` on timeout. `shouldPost`: only resolved `SignedOut` is silent.
- Entry points: `ReminderReceivers.kt` `DoseReminderReceiver` (awaits auth; timeout or no Koin posts from the intent; dose-log check and pill-look loading each bounded to 2 s and, if unreadable, the reminder is still posted). `DoseActionReceiver` Give also awaits auth before writing. `AppointmentReminderReceivers.kt` `AppointmentReminderReceiver` now uses `goAsync()` + coroutine (it was synchronous). `readPlan()` in both schedulers (`DoseReminderScheduler.kt:86`, `AppointmentReminderScheduler.kt:88`) is what `reschedule()` uses, so boot, `TIME_SET`, package-replaced, the daily alarm, push worker and coordinator all wait; `Loading` after timeout = `Unavailable` = change nothing. Resolved `SignedOut` = `cancelAll()`.
- Tests: `resolveAuth` returns immediately when resolved, waits then returns the resolved state, times out as `Loading` at 4000 ms virtual time (not `SignedOut`); `shouldPost` matrix (both modules).
- Covered only by reading: the receivers' wiring and `goAsync()` behaviour; that 4 s + 2 s + 2 s fits the real broadcast window (about 10 s).

## Fix 3: Given ignored a failed write
- `ReminderReceivers.kt` `DoseActionReceiver` Give: `ReminderDecisions.shouldCancelAfterGive(alreadyConfirmed, write)` = already confirmed or `MedsResult.Success`. Failure, exception, or no Koin: notification stays. No new text.
- Also changed (same pattern, not asked): Snooze with no Koin used to cancel the notification without scheduling a snooze; now it cancels only after `snooze()` ran.
- Tests: success cancels, already-confirmed cancels, `NotAllowed`/`NotFound`/`NoCircle`/`Unknown` keep, no attempt keeps.
- Covered only by reading: that the receiver passes the real `confirm` result into the decision.

## Fix 4: read error cancelled everything (confirmed by reading)
- Both `reschedule()` used `runCatching { currentPlan() }.getOrNull()` so an exception became "no plan". Now `guardedRead`: exception gives `PlanRead.Unavailable` and nothing is touched; only a real `NoPlan` (signed out, no role) cancels. Appointments: the patient-names read is inside the guarded read too (previously a failure there emptied the plan and cancelled all alarms).
- Tests: throwing read gives `Unavailable`, real answers pass through, `CancellationException` is rethrown (both modules).

## Notes for the integrator / outside my files
- `ReminderPlan.kt:97` `if (medication.isStopped) return@forEach` in `plan` is untouched, as asked. **`openGroups` has its own copy of that line at `:168`** and also uses `isStopped`. The lane that changes the stopped rule must change both places (or I can extract a shared predicate once that lane lands).
- The brief said the missed window is per regimen; in this code it is per **patient** (`Patient.missedWindowMinutes`, used by `DoseStatusRules`). `openGroups` uses the patient's.
- Not done: an alarm that was due but lost (phone off at the time) is not re-armed after boot; a past group is only kept, not re-fired. Re-firing would need a way to tell "already shown and dismissed" apart.
- `ReminderHealth.kt:88` and `PushWorkers.kt:98` call `currentPlan()` / `reschedule()`; they now wait up to 4 s on `Loading`. No edit needed.
- No edit needed outside my files. No strings, layout or test dependencies were added.
- Git warns that two CRLF working-copy files will be normalised to LF on commit; the index is LF.
