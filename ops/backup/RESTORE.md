# Restore

A backup that has never been restored is not a backup
([security-and-privacy §6](../../docs/security-and-privacy.md), D15). Restore
**quarterly** and **before the M7 release**, and record each run in the log at
the bottom.

The private age identity lives in the password manager (KeePassXC) with an
offline copy. It is never in this repository, in GitHub or on the runner. Copy
it to a path outside the repo for the duration of the test and do not paste it
into a terminal that logs.

## 1. Quarterly restore test (scratch local stack)

The target is [`scratch/`](scratch/): project `family-care-restore`, Postgres 17,
ports 55321-55329. It is separate from the development stack in `backend/`
(project `family-care`, ports 54321-54329), so this can never overwrite the
working local database. It has no migrations folder on purpose — the backup is
the schema.

1. **Get the file.** Sign in to the Backblaze B2 web UI, open
   `family-care-backups`, and download the newest
   `<env>/daily/YYYY/MM/family-care-<env>-<stamp>.tar.gz.age`. The nightly job's
   application key cannot read, so the UI is the only way.
2. **Start the scratch stack empty.**

   ```bash
   npx supabase@latest start --workdir ops/backup/scratch
   ```

   If it was already running, reset it first (this deletes the previous restore,
   which is the point):

   ```bash
   npx supabase@latest stop --workdir ops/backup/scratch --no-backup
   npx supabase@latest start --workdir ops/backup/scratch
   ```

3. **Decrypt, verify and restore.** From the repository root:

   ```bash
   ops/backup/restore-test.sh \
     ~/Downloads/family-care-prod-20261118T021703Z.tar.gz.age \
     ~/family-care-backup-key/family-care-backup.agekey
   ```

   The script (a) decrypts into a private temp directory, (b) checks every
   `sha256` in `manifest.txt`, (c) refuses a scratch database that already holds
   app tables, (d) restores `roles.sql`, `schema.sql`, `SET
   session_replication_role = replica` and `data.sql` in one transaction with
   `ON_ERROR_STOP=1`, and (e) prints per-table row counts. It runs `psql` inside
   the scratch container as `supabase_admin`: a freshly initialised local stack
   does not grant the `postgres` role write access to every platform table
   (`storage.buckets_vectors`), which the data dump contains. A real restore
   into a hosted project connects as `postgres` (§2 step 3 below).

   `pg_dump` warns that `meds.dose_log` has circular foreign-key constraints.
   That is expected: `SET session_replication_role = replica` disables the
   constraint triggers for the data load, which is why the documented order puts
   it between `schema.sql` and `data.sql`.

4. **Compare with the source.** The same query file, run against the database
   that was backed up:

   ```bash
   # the local development stack; for a hosted project use psql with its URL, or
   # the SQL editor in the Supabase dashboard and the same query
   docker exec -i supabase_db_family-care psql -U postgres -d postgres \
     --file - < ops/backup/compare-counts.sql
   ```

   Counts must match table by table. A backup taken at 02:17 can legitimately
   differ from a live database by that night's writes; for a hosted project
   there are no writes while the family sleeps, so treat a mismatch as a
   failure and investigate.

5. **Check the accounts.** `auth.users` must be non-zero on prod and the owner's
   row(s) present:

   ```sql
   select count(*) as users from auth.users;
   select id, email, created_at from auth.users order by created_at limit 5;
   ```

6. **Spot-check app data** in the restored database, not just counts: a known
   patient, its circle, a few medications and recent dose-log rows.

7. **Stop and discard the scratch stack.**

   ```bash
   npx supabase@latest stop --workdir ops/backup/scratch --no-backup
   ```

8. **Record the result** in the log at the bottom of this file.

Passing means: checksums verified, restore exited 0, app-table and `auth.users`
counts equal the source, and the spot checks look right. Anything else is a
failed test, even if the file exists and is the right size.

## 2. Real disaster restore (new Supabase project)

Do this only when prod is actually lost. It takes the same backup, but ends with
a working app rather than a count table. Nothing here can be rehearsed against
prod; the quarterly test above is the rehearsal.

1. **Create the replacement project** in the Supabase dashboard: EU region, same
   Postgres major version (17), new database password in the password manager.
   Save the project ref — it is used everywhere below. Upgrade to Pro if the
   family depends on it (D20).
2. **Take the newest prod backup** from the B2 web UI (the most recent
   `prod/daily/...` file; the monthly copy is the fallback) and decrypt it:

   ```bash
   mkdir -p ~/family-care-restore && cd ~/family-care-restore
   age --decrypt -i ~/family-care-backup-key/family-care-backup.agekey \
     -o backup.tar.gz ~/Downloads/family-care-prod-<stamp>.tar.gz.age
   tar -xzf backup.tar.gz
   sha256sum --check <(grep -E '^[0-9a-f]{64}  ' manifest.txt)
   ```

3. **Restore into the new project.** Use its **session pooler** connection
   string (IPv4) in place of the local URL:

   ```bash
   psql "<session-pooler-url>" --single-transaction --variable ON_ERROR_STOP=1 \
     --file roles.sql --file schema.sql \
     --command 'SET session_replication_role = replica' --file data.sql
   ```

   The schema includes the `core`, `meds` and `powersync` publication objects;
   the data includes `auth.users`, so people can sign in with the same Google
   accounts. Do **not** run `supabase db push` or `db reset` into this database:
   the schema is already the one in the backup, and the migration history is not
   in it. From then on, apply only new migrations by hand.
4. **Vault secrets.** The CLI's dump excludes the `vault` schema entirely, so
   the secret values are not in the backup and must be re-created deliberately.
   In the SQL editor:

   ```sql
   select vault.create_secret('https://<new-ref>.supabase.co/functions/v1/escalate', 'escalate_url');
   select vault.create_secret('https://<new-ref>.supabase.co/functions/v1/dose-confirmed', 'dose_confirmed_url');
   select vault.create_secret('<ESCALATION_CRON_SECRET>', 'push_cron_secret');
   ```

   The names come from `core.push_setting()` in
   `backend/supabase/migrations/20261120120000_push_and_escalation.sql`.
   Without them the webhooks are silent no-ops, which looks like "push stopped
   working", not like an error.
5. **pg_cron jobs.** `cron` is not part of a database dump, so re-check and
   re-create:

   ```sql
   select jobid, jobname, schedule, command from cron.job order by jobname;
   ```

   Expect `core_purge_audit_log` (`23 3 * * *`, `select core.purge_audit_log()`)
   and `push_escalation_tick` (`*/5 * * * *`,
   `select core.enqueue_escalation_push()`). If either is missing:

   ```sql
   select cron.schedule('core_purge_audit_log', '23 3 * * *', 'select core.purge_audit_log()');
   select cron.schedule('push_escalation_tick', '*/5 * * * *', 'select core.enqueue_escalation_push()');
   ```

   Enable `pg_cron` and `pg_net` first if the dashboard does not already have
   them.
6. **Edge functions and their secrets.** From `backend/`:

   ```bash
   npx supabase@latest secrets set --project-ref <new-ref> \
     FCM_SERVICE_ACCOUNT_JSON="$(cat /path/to/service-account.json)" \
     ESCALATION_CRON_SECRET="<the value stored in Vault above>"
   npx supabase@latest functions deploy escalate dose-confirmed --project-ref <new-ref>
   ```

   `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are supplied by the runtime.
   `verify_jwt = false` for both is already in `backend/supabase/config.toml`.
7. **PowerSync.** Create/point the instance at the new project: replication role
   `powersync_role`, database connection, Supabase Auth + JWKS, deploy
   `backend/powersync/sync-config.yaml` (steps in
   `backend/powersync/README.md`). Test that a sync with a real test account
   returns rows.
8. **Google sign-in.** In Google Cloud, make sure the OAuth client for the
   release key still exists and add the new redirect URL if Supabase asks for
   one; in the new Supabase project enable the Google provider with the Web
   client id/secret, and confirm the Android client's SHA-1 is the release key's.
9. **App configuration.** Update `android/environments/` for the new project URL
   and publishable key (and the PowerSync URL), rebuild, and install the signed
   APK on both phones. Nothing secret goes in that file. The nightly backup
   workflow's `SUPABASE_DB_URL_PROD` secret must also be replaced with the new
   project's session pooler URL, or every night will fail loudly until it is.
10. **Verify before telling anyone it is back.** Sign in on one phone, check the
    patients and today's doses, confirm a dose and watch the other phone's
    notification clear, and run the row counts from §1 step 4 against the new
    project. Then run the next six nights' backup runs green.

## Restore test log

| Date | Backup file | Environment | Result | Who |
|---|---|---|---|---|
| 2026-11-18 | `dev/daily/2026/11/family-care-dev-20261118T154327Z.tar.gz.age` (first nightly-job run; SHA-1 matched B2) | dev | **Passed.** Manifest checksums OK; restored into the scratch stack with no errors; row counts identical to live dev for all 13 tables (auth.users 2, core.patients 4, core.memberships 2, core.audit_log 20, …); row fingerprints (md5 of all rows) identical for auth.users, core.patients, core.memberships, core.audit_log. App not pointed at the scratch stack (local stack has no PowerSync). | Claude + Leonardo |
