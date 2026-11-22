# SQL lane report

Branch `fix/remove-member-archived-patients`, head `3de3df6` (two commits on `ce54165`: `aaf8226` fix 1, `3de3df6` fix 2). Not pushed.

**Nothing was executed.** `docker ps` failed (no Docker daemon), so no local Supabase, no pgTAP run and no `db lint`. I checked both migrations and every test statement by reading them against the final schema. CI's database job is the first real run.

## Fix 1: remove a member linked to an archived patient

**Design.** `core.remove_member` now writes one row to a new server-only table, `core.member_removal_unlinks (txid xid8 default pg_current_xact_id(), circle_id, user_id)`, just before its patient UPDATE and deletes it right after. `core.check_patient_archive()` still refuses every change to an archived row, with one exception: the only changes are `primary_caregiver_id` and/or `linked_user_id` going from user X to null, and a marker row exists for (current top-level xact id, this patient's circle, X). Anything else still raises `42501 patient is archived`. That covers any other column, a non-null new value, or a missing marker.

**Why it meets the constraints.**
- **Clients can't use it.** The table has RLS enabled and forced, no policy, and no privilege for public, anon, authenticated or service_role. Only the migration role (BYPASSRLS, the owner of the SECURITY DEFINER functions) can write it. The row lives only inside the one RPC call and is gone before `remove_member` returns. Rows from an aborted transaction vanish with it, and xid8 ids are never reused. A client therefore can't change an archived patient through PostgREST, including clearing the two link columns, and that also holds after a removal in the same transaction.
- **No session setting** (`set_config` / `current_setting`) is involved.
- **Why not `current_user`.** Every trigger function on `core.patients` is SECURITY DEFINER: `check_patient`, `check_patient_archive`, `audit_row_change` and `apply_primary_caregiver_default`. So inside them `current_user` is always the owner and can't tell the RPC from a client.
- **Other triggers.** BEFORE triggers fire in name order: `patients_archive_check`, then `patients_check`, then `patients_set_updated_at`. `check_patient` accepts null links. `updated_at` is bumped, and the archive check ignores it as before. `patients_audit` (AFTER) writes a normal `update` / `change` row with actor = the owner. The `erasing_patient` skip doesn't apply.
- **Starting points.** I started from the final definitions: `check_patient_archive` from `20261123120000_patient_lifecycle.sql` (no later redefinition; `20261124…` only mentions it) and `remove_member` from `20261118140000_core_m2_fixes.sql`. Both keep `set search_path = ''`, and the grants and comments follow the existing style. The `remove_member` body is unchanged apart from the marker insert and delete.

**Files.**
- `backend/supabase/migrations/20261127120000_remove_member_archived_patients.sql`: new table `core.member_removal_unlinks`; redefines `core.check_patient_archive()` and `core.remove_member(uuid)`, plus their comments and grants.
- `backend/supabase/tests/remove_member_archived_test.sql`: new file, plan 25.
- `backend/README.md`: new "Fixes after M7" section and a paragraph under Tests.

**Tests** (plan 25):
- Before the removal: a caregiver can't null the primary or the linked user of an archived patient, and an owner can't null the primary or both links.
- A client can't insert into the marker table.
- The last-owner guard still raises `23514`.
- The removal succeeds for a member who is:
  - primary only, on an archived patient;
  - linked only, on an archived patient (whose other primary is kept);
  - both, on one active and one archived patient.
- After the removal:
  - no patient of the circle points at the removed member;
  - an archived patient without a link to them is untouched;
  - the archived set is unchanged;
  - each row minus `updated_at` and the two link columns equals its snapshot;
  - the cleanup is audited, and no marker row is left;
  - authenticated and service_role have no privilege on the table;
  - the owner and the caregiver still can't null links (theirs or another member's) or rename an archived patient, and those refused updates changed nothing.

The new core table also falls under `core_security_test.sql`'s pg_class checks (RLS forced, nothing granted to PUBLIC or anon). I expect it to pass them.

## Fix 2: a future `stopped_at` still counts as active

**Objects.**
- The only database object that tests `stopped_at` is `meds.effective_reminder`. Its final definition is in `20261123120000_patient_lifecycle.sql:200`, which redefined the one at `20261120…:245`.
- I redefined it with `where (m.stopped_at is null or m.stopped_at > now()) and p.archived_at is null`. The columns are the same and in the same order, so `create or replace view` is valid. `security_invoker` and the grants are unchanged, and the view comment is updated.
- No policy, check constraint or trigger references `stopped_at`. I grepped every migration, and `check_medication`, `dose_log_check` and the archive triggers don't touch it, so a future value is already accepted.
- The column comment is updated with `comment on column`.

**Files.**
- `backend/supabase/migrations/20261127130000_future_stopped_at.sql`
- `backend/supabase/tests/push_test.sql`: §7 inserts a medication with `stopped_at = now() + 1 day` and one with `now() - 1 day`, asserts the first is in the view and the second is not, and the plan goes from 60 to 62.
- `backend/README.md`

## Production risk

- **Fix 1** adds an empty table and replaces two functions. It takes no lock beyond the brief catalog locks. `create or replace function` keeps the existing owner and ACL.
- `xid8` and `pg_current_xact_id()` need Postgres 13 or later, which the Supabase images have.
- **Fix 2** changes behaviour at once for rows already in production. Every existing `stopped_at` is in the past (the app wrote `now()`), so nothing becomes active again. Only future-dated stops written by the new app are affected.
- **Order.** If the app ships future `stopped_at` before this migration is applied, those medications lose their reminders until it is.

## Edits needed outside my files

1. **`backend/supabase/functions/stock-low/index.ts:138`** still uses `.is('stopped_at', null)`. A medication with a future stop would drop out of the low-stock pushes and have its alert row cleared. It needs `.or('stopped_at.is.null,stopped_at.gt.<now ISO>')` or equivalent. The doc comments in `_shared/stockAlerts.ts:9,63` say "stopped_at is null". `escalate` reads the view, so it is covered.
2. **`docs/domain/patient-lifecycle.md`** says "The only allowed write to an archived patient is the owner clearing archived_at (and a full erase)". It should add "and `core.remove_member` clearing the removed member's primary/linked links".
3. **Pre-existing gap, not fixed:** `core.remove_member` doesn't clear `meds.medications.primary_caregiver_id` (the per-medication override) that points at the removed user, archived or not. The view's `coalesce` can still route reminders to the removed user. It's worth its own task. On archived patients it would also hit `assert_patient_writable`, so it would need the same marker.
4. **Pre-existing:** deleting an `auth.users` row linked to an archived patient fires `ON DELETE SET NULL` on `core.patients`, and the archive trigger would refuse it as before. This fix doesn't cover that path.
