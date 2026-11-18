# Backend

Supabase (Postgres, auth, jobs) and PowerSync (sync) configuration for
family-care. The Android app lives in `android/` and is not needed to run any of
this.

```
backend/
├─ supabase/
│  ├─ config.toml         local stack config (project_id "family-care")
│  ├─ migrations/         versioned schema, one schema per module
│  ├─ functions/          edge functions (escalate, dose-confirmed, stock-low) + Deno tests
│  └─ tests/              pgTAP database tests (access isolation, ...)
└─ powersync/
   ├─ sync-config.yaml    what each phone downloads (Sync Streams)
   ├─ check-sync-tests.sh  fails when a stream is not covered by a pgTAP test
   └─ README.md           how to deploy it + the manual replication-role SQL
```

## Prerequisites

- Docker Desktop (the Supabase CLI runs Postgres, auth, Studio etc. in containers).
- Node.js, only to run the CLI through `npx`.

No secrets are needed locally and none are committed: `supabase start` creates
its own throwaway keys for the local stack. `.env` files, `.branches/` and
`.temp/` are ignored by `backend/supabase/.gitignore`.

## Running locally

All commands run from `backend/`.

```bash
npx supabase@latest start           # start the local stack (Postgres, auth, Studio, ...)
npx supabase@latest status          # URLs, ports and local keys
npx supabase@latest db reset        # re-apply migrations + seed from scratch
npx supabase@latest test db         # run the pgTAP tests in supabase/tests/
npx supabase@latest db lint --level error --fail-on error   # schema lint
npx supabase@latest stop --no-backup
```

`supabase start` applies `supabase/migrations/` in filename order; a new
migration is added with `npx supabase@latest migration new <name>` (never edit a
migration that has already been applied to dev).

`db reset` and `test db` both wipe/recreate local data — that is fine, it is
throwaway. Do not run them against a linked remote project.

Studio: <http://127.0.0.1:54323>. Database: `postgresql://postgres:postgres@127.0.0.1:54322/postgres`.

## Migrations reach dev before prod

Database changes go through versioned migrations, applied to **dev first** and
then tested against a restored copy of prod before production (build-plan §5).
Nothing in this repository links to a remote project: linking and `db push` are
done by hand, on purpose, so a stray command cannot touch prod. Dev holds
invented test data only — never real health data.

## What exists so far (M1–M6)

M1 (walking skeleton):

- One schema per module, starting with `core` (identity, care circle, access —
  `docs/domain/care-circle.md`).
- `core.sync_probe` (M1, removed): a throwaway table that proved the
  sign-in → Supabase → PowerSync path and carried the first isolation tests. The
  M2 Android step stopped using it, so
  `migrations/20261118130000_core_drop_sync_probe.sql` takes it out of the
  `powersync` publication and drops it; the schema-wide security checks it
  introduced live on in `tests/core_security_test.sql`.
- The `powersync` publication and the Sync Streams config in `../powersync/`.
- `core` and `meds` are listed in `api.schemas` in `config.toml` so PostgREST
  can serve them; access is still governed by the grants and RLS. From M6 the
  same list also has `appointments` and `info`. That file
  only configures the local stack: on a **hosted** project add `core`, `meds`,
  `appointments` and `info` under
  Dashboard → Integrations → Data API → Settings → *Exposed schemas* (done on
  dev and prod), or the app and edge functions get `PGRST106 Invalid schema`. Because they
  are not `public`, a REST client must name the schema in `Accept-Profile`
  (reads) and `Content-Profile` (writes) headers.

M2 (care circle) — `migrations/20261118120000_core_care_circle.sql`:

- Tables in `core`: `care_circles`, `memberships` (owner / caregiver / viewer),
  `profiles` (auto-created from `auth.users` on signup), `patients` (linked
  user, reminder policy, primary caregiver, delays, agreement note, archive
  stamp), `invitations` and `patient_access_overrides` (modelled, unused), plus
  the append-only `audit_log`.
- One access function: `core.can(patient_id, action)` with actions `read`
  (any member), `write` (owner/caregiver) and `manage` (owner). It delegates to
  `core.can_circle(circle_id, action)` / `core.circle_role(circle_id)`, and every
  patient-data policy calls it. The helpers are `security definer` with
  `set search_path = ''`, which is what keeps the `memberships` policy from
  recursing.
- Writes: `patients` and `care_circles` (rename only) are direct DML through
  PostgREST with RLS. `memberships` and `invitations` have **no** DML grant for
  `authenticated`; they change only through the `security definer` RPCs
  `core.create_circle`, `core.create_invitation`, `core.accept_invitation`,
  `core.revoke_invitation`, `core.remove_member` and `core.set_member_role`.
  Invitation codes are 160 random bits (Crockford base32, `XXXX-XXXX-XXXX-XXXX`),
  returned once in plaintext; only `sha256` of the normalised code is stored,
  and `authenticated` has no `SELECT` privilege on `token_hash`.
- Audit (D14): generic triggers on all core data tables write `core.audit_log`
  (actor, circle, patient, table, record, action, `old_data`/`new_data`,
  `event_kind`; `token_hash` is stripped). The log is append-only — no client
  write grants, and a `BEFORE UPDATE/DELETE/TRUNCATE` guard raises for every
  role, including the table owner; the only exception is the retention purge
  below. Circle owners can read their circle's rows; it is not synced.
- Deny by default (D13) unchanged: RLS enabled **and forced** on every `core`
  table, nothing granted to `anon` or `PUBLIC`, policies scoped
  `to authenticated`. The tests check this generically over `pg_class`, so a
  table added later is covered automatically.
- The `powersync` publication covers the four synced core tables
  (`care_circles`, `memberships`, `profiles`, `patients`); invitations,
  overrides and the audit log are server-only.
  `migrations/20261118130000_core_drop_sync_probe.sql` removes the M1 probe
  from the publication and drops the table, keeping
  `core.set_updated_at()` (the M2 and M3 tables use it).

M3 (medications) — `migrations/20261119120000_meds.sql`:

- Schema `meds`: `medications` (patient, name, active ingredient, strength,
  form, stock unit, instructions, prescription validity, low-stock threshold in
  days, optional reminder-policy override and primary-caregiver override,
  stopped stamp), `regimens` (versioned schedule) and `dose_log` (append-only
  outcome records). Stock, low-stock and shopping are **M5** (below). Enums
  `medication_form`, `schedule_type`, `skip_reason` and
  `dose_outcome` use the names the Kotlin domain maps.
- **Versioned regimens (D9):** every column except `end_date` is immutable; a
  trigger also allows only setting `end_date` or moving it earlier, never
  clearing it, and refuses overlapping date ranges for one medication
  (inclusive, open end = infinity). `times` is a 1..12 element ascending jsonb
  array of `{"time":"HH:MM","quantity":n}` with at most 3 decimals; the ISO
  weekday bitmask (Monday=1 … Sunday=64) and `interval_days` must match the
  schedule type; the IANA `time_zone` is validated against `pg_timezone_names`.
- **Append-only dose log (D6):** only `SELECT`/`INSERT` are granted, a `BEFORE
  UPDATE` trigger raises for every role (cascades still delete), `confirmed_by`
  must be the caller, `medication_id` must match the regimen, `given_by_user_id`
  must be a circle member, `given_by_user_id`/`given_by_other` are exclusive,
  `skip_reason` is required exactly for skipped doses, and a correction must
  reference an entry with the same regimen and slot. Nothing is edited or
  deleted by the app; corrections are new rows.
- RLS mirrors the M2 rules: `core.can(patient_id, action)` on medications;
  regimens and the dose log resolve the patient through
  `meds.patient_of_medication()` (security definer). Any member reads,
  owner/caregiver writes, only an owner deletes a medication; there is no
  client `DELETE` on regimens or the dose log.
- Audit (D14): `meds.audit_row_change()` writes `core.audit_log` with the
  derived patient and circle for all three tables, including dose log inserts.
  It lives in `meds` (not `core`) to keep the module direction core ← feature:
  the core function only knows how to derive a circle from a `circle_id`
  column, which these tables do not have.
- The `powersync` publication adds `meds.medications`, `meds.regimens` and
  `meds.dose_log`. The streams walk medications → `core.patients` →
  `core.memberships`; `dose_log` is unbounded for now (a time window is noted
  in `powersync/sync-config.yaml`).

M2 fixes — `migrations/20261118140000_core_m2_fixes.sql` (deployed after the
care-circle migration; it uses `create or replace` and never edits the already
applied file):

- `core.remove_member` clears `primary_caregiver_id` / `linked_user_id` on the
  circle's patients where they point at the removed user, in the same
  transaction. The updates are ordinary audited patient changes; no replacement
  primary is chosen, so the app asks the owner for one ("needs a primary
  caregiver", D11).
- D11 default: a `BEFORE INSERT` trigger (named so it runs before
  `patients_check`) sets the primary caregiver to the linked user when the
  policy is `primary_with_escalation`, the primary is empty and the linked user
  is an owner or caregiver. A linked viewer leaves it null.
- Security events: invitations and membership/role changes are classified
  `event_kind = 'security'`; a trigger on `auth.sessions` logs `auth.sign_in`
  and `auth.session_ended` (user, event, time only — no session id, tokens,
  IP or user agent) and swallows exceptions so auth can never fail because of
  the log. Failed logins and MFA changes happen in Google/GoTrue before the
  database, so they are covered by Supabase Auth logs, not this table.
- Retention (security-and-privacy §7): change history 6 years, security events
  1 year. `core.purge_audit_log()` is security definer, not executable by any
  API role, and is the only code path the append-only guard lets delete; pg_cron
  runs it daily (`core_purge_audit_log`, 03:23 UTC).

## M4 (push and escalation)

Database — `migrations/20261120120000_push_and_escalation.sql`:

- `core.push_devices`: one row per app install (`fcm_token` unique, `platform`
  `android`, optional `app_language`, `last_seen_at`). Device tokens are
  personal data: RLS is owner-only (`user_id = auth.uid()`), a co-member — even
  the circle owner — sees nothing, and the table is **not** synced. Clients
  register through `core.register_push_device(token)` (upsert by token; a token
  that moved between accounts is reassigned, because a token identifies an
  install) and remove it with `core.unregister_push_device(token)` (own rows
  only). Registration is audited as `event_kind = 'security'` with
  `circle_id = null`, and **the token is redacted from both JSON documents**
  (`core.audit_push_device()`); only the device row id, user id, platform and
  language reach the log.
- `meds.escalations`: the ledger that makes the job idempotent, unique on
  `(regimen_id, scheduled_local)`, with `patient_id`, `escalated_at` and
  `recipients` (users notified, possibly 0). Server-only: no privilege for
  `authenticated`/`anon`, no RLS policies, not synced.
- `meds.effective_reminder` (view, `security_invoker`): active medications with
  `coalesce(medication override, patient policy)` and
  `coalesce(medication primary, patient primary)`, plus the patient's
  `escalation_delay_minutes` / `missed_window_minutes`. Stopped medications are
  excluded. Granted to `service_role` only.
- Webhook plumbing, versioned rather than clicked together: `core.push_setting`
  reads a Vault secret, `core.post_to_function` posts JSON with
  `X-Cron-Secret`, and both no-op (never raise) when Vault, the secret or
  `pg_net` is missing — the local/CI default. `meds.dose_log` gets an `AFTER
  INSERT` trigger that posts only the new row id to `dose-confirmed`; pg_cron
  runs `select core.enqueue_escalation_push()` every five minutes.
- `service_role` is granted exactly what the two functions read (schema usage,
  `push_devices`, `memberships`, `profiles`, `patients`, `medications`,
  `regimens`, `dose_log`, the view, and read/insert/update on the ledger).

Edge functions — `supabase/functions/`:

- `_shared/doseSlots.ts` + `_shared/doseStatus.ts`: the TypeScript port of the
  M3 dose-slot and dose-status rules, including Lisbon DST (gap → shift forward
  by the gap; overlap → earlier offset) and the inclusive boundaries. They are
  the M4 half of D22 and are asserted against **every** fixture in
  `shared/test-vectors/` by `_shared/vectors_test.ts`.
- `_shared/escalation.ts`: the pure selection logic (policy/primary coalescing,
  overdue-only, ledger skip, owners/caregivers minus the primary, viewer never
  notified, null primary → all caregivers per D24, per-recipient language).
- `_shared/fcm.ts`: FCM HTTP v1 sender. Signs the OAuth assertion with WebCrypto
  RS256, caches the access token, and classifies responses: `UNREGISTERED` /
  `INVALID_ARGUMENT` are dead tokens (the caller deletes the device row), 429/5xx
  and transport errors are retries. It never logs a token, a message body or a
  response body.
- `escalate/index.ts`: the cron entry point. Requires `X-Cron-Secret` ==
  `ESCALATION_CRON_SECRET`, uses the service role, reads a bounded look-back
  (widest missed window + 15 minutes), sends generic localized notifications
  (title "Family Care"; body "A dose needs attention" / "Uma toma precisa de
  atenção"; channel `escalations`, tag per patient + slot instant) with only
  `type=escalation, patient_id, scheduled_local` in `data`, then writes the
  ledger rows.
- `dose-confirmed/index.ts`: on a dose-log insert, sends a **data-only**
  high-priority `type=clear, regimen_id, scheduled_local` to every device of the
  circle's owners/caregivers except the confirmer's own, so the other phones
  cancel the local reminder and the escalation.

### Deploying the M4 push functions (human steps)

Firebase and Supabase hold the credentials; nothing here is committed. Do this
on **dev** first, never against prod without the build-plan §5 gate.

1. Firebase console → Project settings → **Service accounts** → *Generate new
   private key*. That JSON is the FCM credential.
2. Create the shared secret and store both in the function environment:

   ```bash
   # from backend/
   npx supabase@latest secrets set \
     --project-ref <dev-ref> \
     FCM_SERVICE_ACCOUNT_JSON="$(cat /path/to/service-account.json)" \
     ESCALATION_CRON_SECRET="$(openssl rand -hex 32)"
   ```

   `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are provided by the edge
   runtime; do not set them by hand.

3. Store the function URLs and the **same** cron secret in Vault (SQL editor on
   dev). The trigger and the cron job read these names; absent secrets are a
   no-op, which is the local default:

   ```sql
   select vault.create_secret('https://<dev-ref>.supabase.co/functions/v1/escalate', 'escalate_url');
   select vault.create_secret('https://<dev-ref>.supabase.co/functions/v1/dose-confirmed', 'dose_confirmed_url');
   select vault.create_secret('<the ESCALATION_CRON_SECRET value>', 'push_cron_secret');
   ```

4. Enable the **pg_net** and **pg_cron** extensions (Dashboard → Database →
   Extensions) if the project does not have them. The migration creates both
   when it can and catches the failure otherwise; without `pg_net` the
   webhooks do nothing.
5. Deploy both functions. `verify_jwt = false` is set for them in `config.toml`
   on purpose: pg_net cannot send a Supabase JWT, so they authenticate with the
   shared secret header instead.

   ```bash
   npx supabase@latest functions deploy escalate dose-confirmed --project-ref <dev-ref>
   ```

6. Smoke test on dev: insert a dose row for a slot and watch
   `select * from net._http_response order by id desc limit 5;` (pg_net records
   the response), and check the function logs in the dashboard. The cron tick
   runs at `*/5`; `select * from cron.job_run_details order by start_time desc
   limit 5;` shows it.

### Running the M4 tests locally

Deno is not required system-wide; `npx deno` fetches it. From `backend/`:

```bash
npx supabase@latest db reset          # applies the push migration
npx supabase@latest test db           # pgTAP incl. supabase/tests/push_test.sql
bash powersync/check-sync-tests.sh    # stream/test drift (unchanged by M4)

cd supabase/functions
npx --yes deno test --allow-read      # shared vectors + escalation + FCM + stock
npx --yes deno check escalate/index.ts dose-confirmed/index.ts stock-low/index.ts
```

`--allow-read` is only for reading `shared/test-vectors/` outside the function
directory. `npx supabase@latest functions serve` is optional: the database side
of the webhooks is exercised by `push_test.sql` with Vault configured inside the
rolled-back transaction, so nothing needs the edge runtime to pass.

## M5 (stock and shopping)

Database — `migrations/20261121120000_stock.sql`:

- `meds.stock_movements`: the append-only stock ledger (D6/D28). `kind` is
  `count` (a stocktake and the baseline, may be 0), `refill` (+N) or `discard`
  (−N, reason required and non-blank). `quantity` is `numeric(9,3)` in the
  medication's `stock_unit`, 0..100000 with refill/discard > 0; `occurred_at`
  comes from the phone, `created_by` defaults to the caller and a trigger
  refuses a different value when the caller is authenticated. Only
  `SELECT`/`INSERT` are granted; a `BEFORE UPDATE` trigger raises for every role,
  exactly like the dose log, and a wrong movement is corrected by a new count.
  There is deliberately no "dose" movement: taken doses come from
  `meds.dose_log`.
- Current stock is **derived, never stored**: the latest `count` (the baseline)
  plus refills minus discards after it, minus the effective `taken` quantities
  in the dose log after it. The exact rules — baseline ties, corrections, a
  `taken` entry with no `actual_quantity`, consumption per schedule type, days
  remaining with multiply-before-divide, low stock and the shopping status — are
  written out in `shared/test-vectors/README.md` and pinned by
  `shared/test-vectors/stock.json` for the Kotlin app and the server (D22).
- RLS mirrors the other meds tables through `meds.patient_of_medication()` +
  `core.can()`: any member reads, owner/caregiver appends, no client updates or
  deletes. Audit (`meds.audit_row_change()`) records the derived patient and
  circle for every movement.
- `meds.stock_alerts`: the server-only ledger of low episodes, one row per
  medication (`medication_id` primary key, `alerted_at`, `recipients`). No API
  privilege, no policies and not synced, like `meds.escalations`. The row is
  removed when the medication is no longer low, which re-arms the alert.
- `stock_movements` joins the `powersync` publication (the phone derives stock
  locally); `stock_alerts` does not.
- Daily job plumbing in the same Vault/pg_net/pg_cron shape as M4:
  `core.enqueue_stock_low_push()` posts to the URL in Vault under
  `stock_low_url` with the shared `push_cron_secret`, and pg_cron runs it daily
  at `0 9 * * *` (`stock_low_daily`). Absent secrets, pg_net or pg_cron make it
  a no-op.
- `service_role` gains `SELECT` on the movement ledger and
  `SELECT`/`INSERT`/`DELETE` on the alert ledger; nothing else widens.

Edge functions — `supabase/functions/`:

- `_shared/stock.ts`: the TypeScript stock rules (baseline, effective dose
  entries, active regimen, daily consumption, days remaining, low stock,
  shopping status). `_shared/stock_vectors_test.ts` asserts every case in
  `shared/test-vectors/stock.json`, numbers within 1e-9.
- `_shared/stockAlerts.ts`: the pure planning of the daily job — which
  medications are low, one push per low episode, owners/caregivers only (never
  viewers, not routed by reminder policy), the text in each recipient's
  language, an alert row even with zero devices, and the rows to clear for
  recovered/stopped medications. Unit-tested in `_shared/stockAlerts_test.ts`.
- `stock-low/index.ts`: the cron entry point. Same `X-Cron-Secret` check as
  `escalate`, service role. Reads active medications with their regimens, the
  stock and alert ledgers, and only the dose-log entries that can change a stock
  figure (after the oldest baseline count). "Today" is the current date in each
  regimen's zone. Sends generic localized notifications (channel
  `stock_alerts`, tag `stock-<medication_id>`, `data` = `{"type":"stock_low"}`),
  writes the new alert rows, clears recovered ones, deletes dead tokens and
  returns counts.
- `_shared/log.ts`: `errorText()`, used by all three functions in
  `console.error`. It reads a PostgREST error object's `message`/`code`
  (never `details`, a body or a token) instead of logging `[object Object]`.

### Deploying the M5 stock-low function (human steps)

Do this on **dev** first, never against prod without the build-plan §5 gate.

1. The secrets are the M4 ones: `ESCALATION_CRON_SECRET` and
   `FCM_SERVICE_ACCOUNT_JSON` are already in the function environment, so
   nothing new goes to `secrets set`.
2. Store the function URL in Vault (SQL editor on dev). The shared secret is the
   existing `push_cron_secret`, whose value is `ESCALATION_CRON_SECRET`:

   ```sql
   select vault.create_secret('https://<dev-ref>.supabase.co/functions/v1/stock-low', 'stock_low_url');
   ```

   Without `stock_low_url` the job is a silent no-op, which looks like "no low
   stock", not like an error.
3. Deploy. `verify_jwt = false` is set for it in `config.toml` for the same
   reason as the M4 functions (pg_net cannot send a Supabase JWT; the function
   checks the shared secret itself).

   ```bash
   npx supabase@latest functions deploy stock-low --project-ref <dev-ref>
   ```

4. Smoke test on dev:

   ```sql
   select * from cron.job where jobname = 'stock_low_daily';
   select * from cron.job_run_details order by start_time desc limit 5;
   select * from net._http_response order by id desc limit 5;
   select * from meds.stock_alerts;
   ```

## M6 (appointments and patient info)

Database — `migrations/20261122120000_appointments_and_info.sql`. Two
independent modules in their own schemas (D5), each with its own grants, policies,
audit trigger and publication membership. The appointment **change push**
(D27/D29) is a separate migration (task 2): nothing here posts to pg_net.

- `appointments.appointments`: one row per appointment. `kind` is
  `appointments.appointment_kind` (`consultation`, `blood_test`, `exam`,
  `checkup`, `other`); `starts_local` is a whole-minute wall-clock timestamp and
  `time_zone` an IANA zone (the `meds.regimens` convention, so a DST change or a
  trip abroad never moves an appointment). Optional `title`, `duration_minutes`
  (1..1440), `place`, `doctor`, `preparation`, `notes`, `companion_user_id`,
  `follow_up_of` and `cancelled_at`; text lengths follow the table in
  `docs/domain/appointments.md`.
- Appointment rules live in `appointments.check_appointment()` (BEFORE
  INSERT/UPDATE): the time zone must exist in `pg_timezone_names`; `created_by`
  is the caller on insert when authenticated (no spoofing); `patient_id` never
  changes; on update `updated_at = now()` and `updated_by = auth.uid()`;
  `companion_user_id` must be an owner or caregiver of the patient's circle (a
  viewer or an outsider is refused, null means everyone is reminded);
  `follow_up_of` must be another appointment of the same patient.
- Cancelling is `cancelled_at`, not a delete (the history keeps cancelled
  appointments). A cancelled row can still be edited; the audit log keeps every
  change.
- `info.patient_info`: one row per patient, `id = patient_id` (deterministic, so
  two offline creates converge), `patient_id` unique and cascading with the
  patient. `allergies` and `conditions` are jsonb arrays (0..30 entries, 1..100 /
  1..200 characters), `emergency_contacts` is a jsonb array of 0..5
  `{name, relationship, phone}`, `sns_number` is exactly 9 digits,
  `health_centre_phone` allows digits, spaces, `+`, `-`, `(`, `)`. The jsonb
  rules are immutable functions (`info.text_list_is_valid`, `info.contacts_are_valid`)
  used by CHECK constraints, like `meds.times_are_valid`. `info.check_patient_info()`
  keeps `id`/`patient_id` immutable and sets `updated_at`/`updated_by` on update.
- RLS mirrors the other modules through `core.can(patient_id, action)`: any
  member reads, owner/caregiver inserts and updates, only an owner deletes
  (appointments too — the app cancels instead). RLS is enabled **and forced**;
  nothing is granted to `anon`/`PUBLIC`; `authenticated` gets
  `select/insert/update/delete`. `service_role` gets `USAGE` on the schema and
  `SELECT` on `appointments.appointments` for the M6 change push.
- Audit (D14): `appointments.audit_row_change()` and `info.audit_row_change()`
  write `core.audit_log` with the actor and the patient/circle derived from
  `patient_id`. They live in their own schemas (not `core`) for the module rule.
- The `powersync` publication adds `appointments.appointments` and
  `info.patient_info`; the streams `appointments` and `patient_info` in
  `../powersync/sync-config.yaml` walk patient → circle → membership exactly like
  `patients`, auto-subscribed, so every member (viewers included) has them
  offline.
- `appointments` and `info` are in `api.schemas` in `config.toml`. On the
  **hosted** projects they must also be added under
  Dashboard → Integrations → Data API → Settings → *Exposed schemas*, or every
  request gets `PGRST106 Invalid schema`.

## Tests

`npx supabase@latest test db` runs every `*.sql` file in `supabase/tests/` inside
a transaction that is rolled back. A file fails if any pgTAP assertion fails, and
also if its `select plan(n)` does not match the number of assertions — so a test
that silently stops running is itself a failure.

`core_care_circle_test.sql` covers the scenarios in security-and-privacy §3
(caregiver sees the circle, other circles see nothing, a removed caregiver
loses access immediately, viewers cannot write, only owners manage members and
invitations), plus invitations (wrong email, expired, reused, revoked, hashed
only, unreadable `token_hash`), the last-owner rule, patient linked/primary
membership rules, and the audit log (rows written with actor and before/after;
append-only against clients and the table owner).

`core_security_test.sql` holds the schema-wide guarantees instead of one table
at a time, iterating `pg_class`: at least one table exists, RLS is enabled
**and forced** on every `core` table, nothing is granted to `PUBLIC` or `anon`,
`authenticated` may use the schema, the `powersync` publication contains
exactly the ten synced tables, and the M1 `sync_probe` table is gone while
`core.set_updated_at()` remains.

`core_m2_fixes_test.sql` covers the follow-up migration: removed-member cleanup
(primary/linked cleared, other patients and circles untouched, change audited),
both D11 default branches (caregiver set, viewer left null), sign-in/session-end
security events, the 6-year/1-year retention purge and its privileges, the
owner-only `patient_access_overrides` rules, circle rename by non-owners,
cross-circle patient insert, and the invitation role/email rules.

`meds_test.sql` covers the M3 tables: the same isolation scenarios (owner and
caregiver read/write, another circle sees and writes nothing, a viewer reads but
cannot write, a removed caregiver loses access, anon is denied), medication and
primary-caregiver checks, regimen versioning/end-date/overlap rules, the
schedule shape and `times` validation, the append-only dose log (no
update/delete, confirmation spoofing, medication/regimen mismatch, skip-reason
and given-by rules, correction slots) and the audit rows' actor/patient/circle.

`push_test.sql` covers M4: push devices are owner-only (a co-member sees none and
cannot insert/update/delete another's row), re-registering a token reassigns it
instead of duplicating it, unregistering only touches the caller's rows, the
token key and value never reach `core.audit_log` (and registrations are security
events), the escalation ledger is unreadable and unwritable for `authenticated`,
its slot key and patient/regimen checks fire, the effective-settings view
coalesces overrides and hides stopped medications, and the webhooks are a no-op
without Vault and queue exactly one pg_net request (URL, secret header) with it.
The cron job's schedule and command are asserted too.

`stock_test.sql` covers M5: the movement constraints (a count may be 0, refill
and discard must be > 0, a discard needs a non-blank reason, the quantity range
and the reason length), `created_by` spoofing, append-only refusal for
`authenticated`, the table owner and `service_role`, RLS per role (owner and
caregiver read and append, a viewer reads but cannot append, another circle and
a user without a circle see and write nothing), the alert ledger being
unreadable for every API role, the exact sync-stream query as each user, the
audit row's derived patient/circle/actor, the Vault no-op and queued request,
and the `stock_low_daily` cron job.

`appointments_test.sql` covers M6 appointments: the enum values, forced RLS and
privileges, the text/minutes/duration/time-zone checks, `patient_id`
immutability, `created_by` spoofing, the companion (viewer and outsider refused)
and follow-up (self and other patient refused) rules, `updated_by` on update,
RLS per role (delete only for an owner), the exact sync-stream query as each
user, and the audit rows for insert/update/delete.

`info_test.sql` covers M6 patient info: forced RLS and privileges, `id =
patient_id` and the one-row-per-patient rule, both immutability rules, every
validator (allergy/condition counts and lengths, the SNS number, the phone
charset, the emergency contacts), the defaults, `updated_by` on update, RLS per
role (delete only for an owner), the exact sync-stream query as each user, and
the audit rows for insert/update/delete.

The Deno suite in `supabase/functions/` is the D22 guard: every case in
`shared/test-vectors/dose-slots.json`, `dose-status.json` and `stock.json` is
asserted against the TypeScript port, plus the pure escalation selection
(overdue once, ledger skip, confirmed/missed/due skipped, `all_caregivers`
local-only, medication override, null primary, viewer excluded, DST slot), the
pure low-stock planning (one push per episode, recovered/stopped rows cleared,
viewers excluded, language per recipient, zero devices), the error-log helper and
the FCM sender against a mocked `fetch` (JWT signature, token cache, dead-token
and retry classification).

`core_care_circle_test.sql`, `meds_test.sql`, `stock_test.sql` and (M6)
`appointments_test.sql` / `info_test.sql` also run the **exact Sync Streams
queries** from `../powersync/sync-config.yaml` as each test user, so the
read half and the write half are tested against the same scenarios.
`../powersync/check-sync-tests.sh` extracts every `query:` from the YAML and
fails if any of them (with `auth.user_id()` replaced by `(select auth.uid())`)
does not appear verbatim in a test file. CI runs it in the `database` job, so
editing one side without the other fails the pull request.

CI runs the same commands (the drift check, `supabase test db` and `db lint`) in
the `database` job of `.github/workflows/ci.yml`, so a broken schema change or a
stream/test mismatch fails the pull request. The `functions` job installs Deno,
runs `deno test --allow-read` (the shared vectors, escalation selection, stock
planning and FCM sender) and type-checks the three deployable functions.
