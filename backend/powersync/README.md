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

The streams, in `sync-config.yaml`:

```
care_circles: SELECT * FROM "core"."care_circles"
              WHERE id IN (SELECT circle_id FROM "core"."memberships"
                           WHERE user_id = auth.user_id())
memberships:  SELECT * FROM "core"."memberships"
              WHERE circle_id IN (SELECT circle_id FROM "core"."memberships"
                                  WHERE user_id = auth.user_id())
profiles:     SELECT * FROM "core"."profiles"
              WHERE id IN (SELECT user_id FROM "core"."memberships"
                           WHERE circle_id IN (
                             SELECT circle_id FROM "core"."memberships"
                             WHERE user_id = auth.user_id()))
patients:     SELECT * FROM "core"."patients"
              WHERE circle_id IN (SELECT circle_id FROM "core"."memberships"
                                  WHERE user_id = auth.user_id())
```

(Shown wrapped for reading; each `query:` is one line in the file.)

- `auth.user_id()` is the `sub` claim of the Supabase JWT — the signed-in
  `auth.users.id`, which is what the `user_id` columns store. It is a *signed*
  auth parameter, so a client cannot ask for someone else's bucket.
- The access rule is `core.memberships`, mirroring `core.circle_role()` /
  `core.can()` in the database (D13): a user downloads exactly the circles they
  belong to, all memberships and patient rows of those circles, and the profiles
  of co-members. A removed member stops matching the inner subquery, so their
  data leaves the phone on the next sync (T7).
- Filtering uses the `IN (SELECT ...)` subquery form, which is the documented
  way to traverse related tables in Sync Streams queries
  ([Writing Queries → Using Subqueries](https://docs.powersync.com/sync/streams/queries#using-subqueries),
  [overview](https://docs.powersync.com/sync/streams/overview)). Nested
  subqueries are supported, which is how `profiles` reaches the user's circles.
- A schema other than `public` must be quoted in the query (`"core"."patients"`),
  per [Schemas and Connections](https://docs.powersync.com/sync/advanced/schemas-and-connections).
- The client-side table is the unqualified name (`patients`); the `core` prefix
  is only how the source database is addressed.
- `auto_subscribe: true` because the app always needs its own rows offline.
  Streams that are only needed on one screen should stay on-demand and be
  subscribed by the client.
- Deliberately not synced: `invitations` (bearer codes and invitee emails stay
  on the server), `patient_access_overrides` (modelled, unused in the MVP) and
  `audit_log` (server-side, owner-only Activity screen).

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
   Also add **JWT Audience** `authenticated`: Supabase access tokens carry
   `aud: authenticated`, and without it PowerSync rejects them
   (`PSYNC_S2105 Unexpected "aud" claim value`), so uploads succeed but
   downloads never arrive and local rows vanish after the next checkpoint.
4. Check the instance **Health** view shows replication running, then sign in on
   a phone and confirm a row appears locally.

Self-hosted PowerSync instead reads the same file through its instance config:
`sync_config: path: sync-config.yaml`.

Client-side use (Android): auto-subscribed streams need no explicit subscribe;
read the local tables `care_circles`, `memberships`, `profiles` and `patients`.
Every synced table has a primary key named `id` (uuid) because PowerSync
requires that name.

## Manual setup: replication role (a secret, so not a migration)

The `powersync` publication is created by the M1 migration
(`backend/supabase/migrations/20261117120000_core_sync_probe.sql`) and extended
by the migrations that add synced tables; the M1 probe table itself was dropped
in M2 (`20261118130000_core_drop_sync_probe.sql`), the publication stays. The
replication **role**
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

## Testing the streams: the drift check

Build-plan §4 lists "sync-rule tests: test users download exactly what they
should" as its own level of testing. Reads come from this file and writes from
RLS, so the two rule sets are tested against the same scenarios
(security-and-privacy §3): `supabase/tests/core_care_circle_test.sql` executes
the **exact queries above** as each test user (substituting `auth.user_id()` with
`(select auth.uid())`, which reads the same JWT `sub` claim; the tests set
`request.jwt.claims` and run with RLS bypassed, as the replication role does).

`check-sync-tests.sh` closes the loop: it extracts every `query:` from
`sync-config.yaml` and fails unless that string (after the substitution, with
whitespace collapsed) appears in a pgTAP test file. Editing a stream without
editing its test fails the check, and CI runs it in the `database` job of
`.github/workflows/ci.yml`. Run it from `backend/`:

```bash
bash powersync/check-sync-tests.sh
```

Consequences worth knowing:

- Keep every `query:` on **one line**. A `query: |` block is rejected by the
  check rather than silently skipped.
- Add a new stream and the check fails until an assertion runs it in a test — so
  a stream cannot ship untested.
- The check proves the tested SQL and the deployed SQL are the same text; it
  cannot prove the PowerSync Service parses it. Deploying to dev validates the
  queries against the source database (step 2 above), which is the other half.
