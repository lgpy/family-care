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

## What exists so far (M1)

- One schema per module, starting with `core` (identity, care circle, access —
  `docs/domain/care-circle.md`).
- `core.sync_probe`: a throwaway table that proves the sign-in → Supabase →
  PowerSync path and carries the first isolation tests. It is dropped once the
  real `core` tables exist in M2.
- Deny by default (D13): RLS enabled and forced on every `core` table, nothing
  granted to `anon` or `PUBLIC`, owner-only policies for `authenticated`. The
  tests in `supabase/tests/` check this, including a generic check over
  `pg_class` so tables added later are covered automatically.
- The `powersync` publication over `core.sync_probe` (PowerSync replicates from
  it) and the Sync Streams config in `../powersync/`.
- `core` is listed in `api.schemas` in `config.toml` so PostgREST can serve it;
  access is still governed by the grants and RLS above. Because `core` is not
  `public`, a REST client must name the schema in `Accept-Profile` (reads) and
  `Content-Profile` (writes) headers. Verified against the local stack: inserting
  as user A returns 201 with `owner_id` = A, and user B reads `[]`.

## Tests

`npx supabase@latest test db` runs every `*.sql` file in `supabase/tests/` inside
a transaction that is rolled back. A file fails if any pgTAP assertion fails, and
also if its `select plan(n)` does not match the number of assertions — so a test
that silently stops running is itself a failure.

CI runs the same two commands in the `database` job of
`.github/workflows/ci.yml`, so a broken schema change fails the pull request.
