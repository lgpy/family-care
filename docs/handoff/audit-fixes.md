# Handoff: audit fixes (written 2026-11-22)

Temporary file. Delete `docs/handoff/` before `audit-fixes` is merged to `main`.

A repository audit on 2026-11-22 found one critical and about a dozen high-severity bugs. Six subagent lanes fixed the critical and high ones. Their work is merged on the branch `audit-fixes` and builds green, but it is not merged to `main`, not pushed, and it overlaps with uncommitted work from another thread. That overlap is the first thing to settle.

## Where everything is

| What | Where |
|---|---|
| Integrated result | branch `audit-fixes`, worktree `C:/Users/lgpy/repos/family-care-audit-fixes`, branched from `main` at `ce54165` |
| Lane branches (already merged into `audit-fixes`) | `fix/auth-sync-data-loss`, `fix/reminder-alarm-safety`, `fix/medication-logic-bugs`, `fix/edge-functions-push-bugs`, `fix/remove-member-archived-patients`, `fix/patient-info-circle-gate`; their worktrees are under `C:/Users/lgpy/.t3/worktrees/family-care/` |
| Lane reports (what each changed, tests, limits) | `docs/handoff/lane-reports/*.md` on this branch |
| Main checkout `C:/Users/lgpy/repos/family-care` | on `main`, with about 40 uncommitted files that belong to another thread (see below). Do not switch its branch or merge into it while that work is uncommitted. |
| Audit thread (full findings, including the ones not fixed) | T3 thread "Full Repository Audit", id `a740d743-9a31-4c39-99f3-c2589d17e704` |

Nothing is pushed. `main` is 2 commits ahead of `origin/main`, as it was before this work.

## Decide first: two threads fixed the same four bugs

The T3 thread "App UX Improvement Opportunities" (id `ed1dee24-f022-4735-a6a3-b3ed80b7cbe3`) was fixing bugs in the main checkout at the same time, uncommitted. Four of its fixes overlap with `audit-fixes`:

1. A reschedule dismisses a reminder that already fired.
2. A late-evening dose still overdue after midnight appears in no list.
3. "Give all" / "Give anyway" re-confirms slots that were already given.
4. Stopping a medication with a future last day stops it immediately.

The stop designs differ. That thread stores `stopped_at` as the last second of the last day; `audit-fixes` stores the start of the following day (in the regimen's time zone). Both keep the server rule below, so the SQL and edge-function changes on `audit-fixes` work with either.

Recommendation: keep `audit-fixes` for these four, because it is committed and tested together with the server side, and rebase the other thread's remaining work (notification-permission prompt, UX changes, new strings) onto it. Expect conflicts in `Medication.kt`, `ReminderPlan.kt`, `LocalMedsSql.kt`, `PowerSyncMedicationRepository.kt`, `PowerSyncDoseLogRepository.kt`, `DosesTodayViewModel.kt`, `NeedsAttentionViewModel.kt`, `DoseReminderScheduler.kt`, `ReminderReceivers.kt`, `AppointmentReminderScheduler.kt`, `AppointmentReminderPlan.kt` and their tests.

Whichever stop implementation is kept, check its active-medications query for this bug, which `audit-fixes` had and fixed: `stopped_at` is compared as text, and a synced `timestamptz` can arrive as `2026-11-19 23:00:00+00` (space, not `T`; see `parseServerInstant` in `android/core/data/.../circle/CircleRows.kt`). A space sorts before `T`, so a pending stop looked already past for the whole UTC day of the stop. The fix is `replace(m.stopped_at, ' ', 'T') > ?` in `SELECT_ACTIVE_MEDICATIONS` (`android/feature/medications/data/.../LocalMedsSql.kt`), with the test `a pending stop in the server's timestamp format is still active on its last day` in `MedsRepositoryTest.kt`.

One incident to know about: on 2026-11-22 at about 19:38 UTC the main checkout was briefly switched to `audit-fixes` with four lanes merged, then switched back to `main` a few minutes later. The other thread's uncommitted files were not changed, but files under `android/core/data`, `android/feature/patient-info`, `backend/` and `CircleGateViewModel.kt` held the lane versions during that window. A build or test that thread ran in that window may have used them; re-run its checks before trusting them.

## What is on `audit-fixes`

| Area | Fixes | Lane report |
|---|---|---|
| Auth and sync (`android/core/data`) | A failed token refresh while offline no longer reads as signed out (it used to wipe the local database and the unsynced upload queue). Uploads are not sent without a user token, and a 401 is always retried. The owner of the local data is persisted, so a cold start while signed out, or a different user, clears first. | `core-data.md` |
| Reminders (medications and appointments) | A fired reminder stays until its dose is confirmed or missed (appointments: until the start time). A session still loading in a cold process is awaited instead of treated as signed out. "Given" from the notification keeps the notification when the write fails. A read error no longer cancels every alarm. | `reminders.md` |
| Medication logic | Stop takes effect after the last day (`isStoppedAt(now)` replaces `isStopped`). A dose change keeps the phase of an every-N-days regimen. A regimen change must be effective after today. Still-due or overdue groups from earlier days show in Needs attention. "Give anyway" writes only slots with no entry. | `medications.md` |
| Edge functions | A ledger row is written only when a push was delivered or nothing is left to retry. Every read is paged past the 1000-row cut and the escalation ledger read is bounded. `stock-low` honours a future `stopped_at`, skips medications that were never counted, and does stock arithmetic in exact thousandths. Five cases appended to `shared/test-vectors/stock.json`. | `functions.md` |
| SQL (two new migrations) | `core.remove_member` works when the member is linked to an archived patient (server-only marker table `core.member_removal_unlinks`, no session setting). `meds.effective_reminder` treats a future `stopped_at` as active. | `sql.md` |
| Patient info and circle gate | A save writes only the columns the user changed, and list edits are merged onto the row's current value, so another caregiver's allergy survives a stale form. The circle gate's "Working…" clears when the circle arrives or the account changes. | `patient-info-gate.md` |
| Repo | `.gitleaks.toml` no longer allowlists `docs/` and `ops/` (full-history scan still clean). `docs/domain/patient-lifecycle.md` mentions the `remove_member` exception. | none (done at integration) |

Shared contract across app, SQL and edge functions: `stopped_at` is the instant a stop takes effect, and a medication is stopped only when `stopped_at` is not null and `stopped_at <= now`.

## Verification state

Run from the worktree `C:/Users/lgpy/repos/family-care-audit-fixes`.

| Check | Command | Result on 2026-11-22 |
|---|---|---|
| Android build, unit tests, lint | `cd android && JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot" ./gradlew build --continue` | BUILD SUCCESSFUL; 1033 tests, 0 failures, 16 skipped |
| Edge functions | `cd backend/supabase/functions && npx --no-install deno test --allow-read` | 109 passed, 0 failed |
| Edge function types and lint | `npx --no-install deno check escalate/index.ts dose-confirmed/index.ts stock-low/index.ts appointment-changed/index.ts` and `npx --no-install deno lint` | clean |
| Secrets | `gitleaks git . --config .gitleaks.toml --redact --log-opts=--all` | no leaks |

The default `java` on this PC is JDK 8 and fails the build; set `JAVA_HOME` as shown. Deno is not on PATH; `npx --no-install deno` works.

Not verified:
- The two SQL migrations and the 27 new pgTAP assertions were never executed: there is no Docker on this PC. CI's database job is the first real run. Read `lane-reports/sql.md` before applying to production.
- The 16 skipped tests are the upload tests that need a local Supabase (`FAMILYCARE_SUPABASE_URL`, `FAMILYCARE_SUPABASE_KEY`).
- Nothing was run on a phone or emulator. The reminder receivers and schedulers are wired by reading only; the decisions they call are unit-tested.
- The four edge-function `index.ts` entrypoints have no tests; only the extracted `_shared` helpers do.

## Before shipping

Apply migration `20261127130000_future_stopped_at.sql` and deploy `stock-low` before the app build that writes a future `stopped_at` reaches phones. Otherwise a medication stopped with a future last day loses server escalation and low-stock alerts until they land.

## Design calls made without asking, to confirm or change

- A regimen change must be effective tomorrow or later, so "change it from tonight's dose" is not possible. "Today" is the later of today in the old and the new regimen's time zones.
- A medication with a pending stop shows as active, with no label saying it will stop.
- A failed "Given" tap on the notification leaves the notification in place with no error text (no new strings were allowed in the lanes).
- Needs attention shows any still-due or overdue group from the last 7 days, not only yesterday's.
- Patient info: when other caregivers' additions push a list past its limit (30 entries, 5 contacts), the save fails with the existing "invalid" error. An edited contact moves to the end of the list.
- On upgrade, an install with data but no owner record adopts the signed-in user without clearing. It cannot detect a previous user's data left behind by the old build.
- The new auth tests set the session status through a supabase-kt internal API (`setSessionStatus`); a library upgrade may break them.

## Audit findings not fixed

High:
- A rejected regimen change is half-applied and later dose confirmations against it are dropped (`UploadTransaction.kt`, `PowerSyncMedicationRepository.changeRegimen`).
- Signing out discards unsynced writes with no warning (`SyncService.disconnectAndClear`).
- `core.remove_member` and `core.set_member_role` leave stale links in `meds.medications.primary_caregiver_id` and `appointments.appointments.companion_user_id`; later writes to those rows fail and reminders route to nobody.
- The first patient-info save from a device that has not synced the row still overwrites the server record (`PatientInfoSyncedTables.kt`, `duplicateInsert = UPDATE_EXISTING`).

Medium:
- Deleting an auth user fails for anyone who created a regimen or stock movement (the `on delete set null` hits the append-only triggers), and deleting the sole owner bypasses the last-owner guard.
- `backend/supabase/config.toml` has email sign-up enabled without confirmation, which would void the invitation's email binding if the hosted project matches.
- `fcm.ts` deletes a device on `INVALID_ARGUMENT` or a bare 404, and has no fetch timeout.
- Editing an appointment from a stale form writes `cancelled_at = NULL`; editing a medication writes a stale `stopped_at`.
- Notification "Given" on a partly confirmed group writes nothing and still clears the notification (`ReminderReceivers.kt`, the `any {}` check).
- The test `DosesTodayViewModelTest` "a taken group reports the giver and the time" fails when the JVM time zone is not Lisbon-offset; `DosesTodayViewModel` uses the device zone.
- The refresh token is stored unencrypted (TODO in `SupabaseAuthRepository.kt`). Release builds are not minified.
- CI: actions pinned by tag, backup secrets visible to every step, `backup.sh` uses only the first age recipient, upload tests can skip and still pass.
- `docs/security-and-privacy.md` claims new-device sign-in alerts, 30-day soft delete and a yearly archive review; none exist.
- Tracked strays: `ops/meds-catalog/__pycache__/build.cpython-314.pyc`, `android/recents.png`, `android/shot.png`.

About 80 further medium and low findings (accessibility, pt-PT wording, PDF export, navigation, appointment edits) are only in the audit thread's subagent reports.

## Finishing up

1. Settle the overlap above and get `main` to a committed state.
2. From the main checkout: `git merge audit-fixes` (or rebase the other thread's work onto it), resolve conflicts, rerun the commands in "Verification state".
3. Delete `docs/handoff/`.
4. Let CI run the pgTAP job before applying the migrations to production.
5. Clean up: `git worktree remove` each lane worktree and `C:/Users/lgpy/repos/family-care-audit-fixes`, then `git branch -d` the six `fix/*` branches and `audit-fixes`.
