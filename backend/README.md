# Backend

Supabase (Postgres, auth, jobs) and PowerSync (sync) configuration for
family-care. The Android app lives in `android/` and is not needed to run any of
this.

```
backend/
├─ supabase/
│  ├─ config.toml         local stack config (project_id "family-care")
│  ├─ migrations/         versioned schema, one schema per module
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

## What exists so far (M1–M2)

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
- `core` is listed in `api.schemas` in `config.toml` so PostgREST can serve it;
  access is still governed by the grants and RLS above. Because `core` is not
  `public`, a REST client must name the schema in `Accept-Profile` (reads) and
  `Content-Profile` (writes) headers.

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
  (actor, circle, patient, table, record, action, `old_data`/`new_data`;
  `token_hash` is stripped). The log is append-only — no client write grants,
  and a `BEFORE UPDATE/DELETE/TRUNCATE` guard raises for every role, including
  the table owner. Circle owners can read their circle's rows; it is not synced.
- Deny by default (D13) unchanged: RLS enabled **and forced** on every `core`
  table, nothing granted to `anon` or `PUBLIC`, policies scoped
  `to authenticated`. The tests check this generically over `pg_class`, so a
  table added later is covered automatically.
- The `powersync` publication covers the four synced tables (`care_circles`,
  `memberships`, `profiles`, `patients`); invitations, overrides and the audit
  log are server-only. `migrations/20261118130000_core_drop_sync_probe.sql`
  removes the M1 probe from the publication and drops the table, keeping
  `core.set_updated_at()` (the M2 tables use it).

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
exactly the four synced tables, and the M1 `sync_probe` table is gone while
`core.set_updated_at()` remains.

The same file also runs the **exact Sync Streams queries** from
`../powersync/sync-config.yaml` as each test user, so the read half and the
write half are tested against the same scenarios. `../powersync/check-sync-tests.sh`
extracts every `query:` from the YAML and fails if any of them (with
`auth.user_id()` replaced by `(select auth.uid())`) does not appear verbatim in a
test file. CI runs it in the `database` job, so editing one side without the
other fails the pull request.

CI runs the same commands (the drift check, `supabase test db` and `db lint`) in
the `database` job of `.github/workflows/ci.yml`, so a broken schema change or a
stream/test mismatch fails the pull request.
