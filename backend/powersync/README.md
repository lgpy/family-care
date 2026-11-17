# PowerSync configuration

What each user's phone is allowed to **download**. Writes are a separate matter:
they go through Supabase, where Row-Level Security decides (D13,
[security-and-privacy §3](../../docs/security-and-privacy.md)). The two rule sets
must agree, and this folder is where the read half lives.

```
backend/powersync/
└─ sync-config.yaml    Sync Streams: the deployed sync configuration
```

## Sync Streams, not legacy Sync Rules

`sync-config.yaml` uses **Sync Streams** (`config: edition: 3`). That is the
format the PowerSync documentation recommends for new projects
([overview](https://docs.powersync.com/sync/streams/overview),
[quickstart](https://docs.powersync.com/sync/streams/quickstart)). The older
`bucket_definitions` **Sync Rules** format still exists but is documented as
legacy ([Sync Rules (Legacy)](https://docs.powersync.com/sync/rules/overview)),
so nothing here uses it.

The M1 stream, in `sync-config.yaml`:

```
sync_probe: SELECT * FROM "core"."sync_probe" WHERE owner_id = auth.user_id()
```

- `auth.user_id()` is the `sub` claim of the Supabase JWT — the signed-in
  `auth.users.id`, which is what `owner_id` stores. It is a *signed* auth
  parameter, so a client cannot ask for someone else's bucket.
- A schema other than `public` must be quoted in the query (`"core"."sync_probe"`),
  per [Schemas and Connections](https://docs.powersync.com/sync/advanced/schemas-and-connections).
- The client-side table is `sync_probe`; the `core` prefix is only how the
  source database is addressed.
- `auto_subscribe: true` because the app always needs its own rows offline.
  Streams that are only needed on one screen should stay on-demand and be
  subscribed by the client.

## Deploying

PowerSync Cloud (the instance created in M0):

1. Open the [dashboard](https://dashboard.powersync.com/), select the project and
   instance (**dev** first, **prod** only after dev works).
2. **Sync Streams** → edit the YAML → paste the contents of `sync-config.yaml`
   → **Deploy**. Deploying validates the queries against the source database, so
   a typo or a table missing from the publication fails here rather than
   silently syncing nothing.
3. The instance needs two things that are **not** in this repository:
   - the source database connection (host, database, user, password);
   - JWT verification for Supabase auth. The dashboard's **Set Up with Supabase**
     flow does both. Done by hand, point the instance at the project's JWKS URL
     (`https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json`) so
     `auth.user_id()` resolves.
   Wait for each **Deploying** to finish before saving another page: saving
   Client Auth while the database connection was still deploying wiped the
   connection on dev (2026-11-17). The dev instance uses "Connect Postgres
   Manually" (direct host `db.<ref>.supabase.co:5432`, user `powersync_role`,
   SSL verify-full) plus **Use Supabase Auth** and the JWKS URI.
4. Check the instance **Health** view shows replication running, then sign in on
   a phone and confirm a row appears locally.

Self-hosted PowerSync instead reads the same file through its instance config:
`sync_config: path: sync-config.yaml`.

Client-side use (Android, M1): subscribe to `sync_probe` (auto-subscribed streams
need nothing) and read the local `sync_probe` table. Only the `id` column must be
`text`-compatible on the client: PowerSync requires a primary key named `id`.

## Manual setup: replication role (a secret, so not a migration)

The `powersync` publication is created by the migration
(`backend/supabase/migrations/*_core_sync_probe.sql`). The replication **role**
is not: it carries a password, and secrets never live in the repository
(security-and-privacy §4). Run this by hand, once, against **dev** and later
**prod**, with the password taken from the password manager:

```sql
-- Password: generate with `openssl rand -base64 32`, store in the password manager.
create role powersync_role with replication bypassrls login password '<password>';

-- Read-only access to the module schemas that PowerSync replicates.
grant usage on schema core to powersync_role;
grant select on all tables in schema core to powersync_role;

-- Cover tables added by future migrations (they are created by the `postgres`
-- role, which is the role that must own this default privilege).
alter default privileges in schema core grant select on tables to powersync_role;
```

`bypassrls` is required because PowerSync reads rows for *many* users from one
connection; the per-user filtering happens in the sync config above, never in
Row-Level Security. Do not grant write access, and do not reuse this role for the
app.

If replication later fails with "permission denied for table ...", the table was
added after `alter default privileges` and needs an explicit `grant select`.

## Known gap (M1)

Build-plan §4 lists "sync-rule tests: test users download exactly what they
should" as its own level of testing. Today only the database half is automated
(`backend/supabase/tests/`). Sync Streams can be checked with the PowerSync
[Sync Diagnostics Client](https://docs.powersync.com/tools/diagnostics-client):
connect as each test user and confirm that user A sees A's rows and nothing of
user B's. Automating that belongs with M2, when the streams stop being a
throwaway probe.
