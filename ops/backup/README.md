# Backups

The independent backup layer from [security-and-privacy §6](../../docs/security-and-privacy.md)
and D15: a nightly encrypted export of each Supabase project (dev and prod) to
Backblaze B2, plus the tooling to prove a backup restores.

Supabase Free has **no** provider backups (D20), so this job is the only backup
once real data is in use — it must be running, and one restore tested, before
that happens ([build-plan](../../docs/build-plan.md), gate after M4).

## What the nightly job does

[`.github/workflows/backup.yml`](../../.github/workflows/backup.yml) runs every
night at 02:17 UTC (and on demand) over `dev` and `prod` independently. For each
project, [`backup.sh`](backup.sh):

1. dumps `roles.sql` (role settings only), `schema.sql` and `data.sql` with the
   official Supabase CLI. The data dump covers **every schema including `auth`**,
   so the user accounts (`auth.users`) are inside the backup. Storage **file
   contents** are not (the gotcha in §6); the database rows that describe them
   are, and v2 adds the files themselves;
2. writes `manifest.txt` (UTC time, environment, CLI version, git sha, sha256 of
   each dump);
3. packs the four files into a tar.gz and encrypts it with **age** to the public
   key in [`recipients.txt`](recipients.txt);
4. uploads only the `.age` file with `aws s3api put-object` to
   `family-care-backups` (`<env>/daily/YYYY/MM/family-care-<env>-<stamp>.tar.gz.age`,
   plus a monthly copy on the 1st of the month) with a `Content-MD5` header,
   and checks that the returned ETag equals the file's MD5. The key is
   write-only (no read, no list), so the upload response is the verification.

The plaintext dumps and the unencrypted tar exist only in a private temporary
directory and are removed by an EXIT trap, successful run or not. They are never
uploaded and there is no `upload-artifact` step. The runner has no age private
key, so CI can create a backup but cannot read one; the B2 application key can
write and list, not read or delete. A failed run is a red workflow, and GitHub's
failure email is the alert.

A missing secret fails the job with an `::error::` line naming the secret; a
backup that silently did not happen is the one failure mode that matters.

## Secrets and variables

Set once by the owner (never by the job, never in the repository):

| Name | Kind | Value |
|---|---|---|
| `SUPABASE_DB_URL_DEV` | secret | dev **session pooler** connection string (IPv4 — runners have no IPv6) |
| `SUPABASE_DB_URL_PROD` | secret | prod session pooler connection string |
| `B2_KEY_ID` | secret | Backblaze application key id (write + list only) |
| `B2_APPLICATION_KEY` | secret | Backblaze application key |
| `B2_S3_ENDPOINT` | variable (optional) | S3 endpoint, default `https://s3.eu-central-003.backblazeb2.com` |

`AWS_DEFAULT_REGION` is `eu-central-003`. See
[setup checklist §7](../checklists/setup.md) for the `gh secret set` commands.

## Running a backup by hand

Local stacks are not what the job backs up, but the same script can dump one.
From the repository root, with a directory **outside** the repo:

```bash
SUPABASE_CLI='npx supabase@latest' ops/backup/backup.sh \
  --db-url "postgresql://postgres:postgres@127.0.0.1:54322/postgres" \
  --env dev --output-dir "$HOME/family-care-backups" --no-upload
```

`--no-upload` stops after encryption and prints the `.age` path. Without it the
script also uploads, which needs `B2_KEY_ID`, `B2_APPLICATION_KEY` and the AWS
CLI (preinstalled on GitHub runners).

Prerequisites: the Supabase CLI (`SUPABASE_CLI` defaults to `supabase`),
[age](https://age-encryption.org) (`winget install FiloSottile.age` on Windows,
`apt install age` on Ubuntu), `tar`, `sha256sum`, and for the restore test
Docker plus a scratch stack.

## Restoring

Restore tests and a real disaster restore are in [RESTORE.md](RESTORE.md).
[`restore-test.sh`](restore-test.sh) decrypts one backup into the scratch stack
at [`scratch/`](scratch/), verifies the manifest checksums, restores everything
and prints row counts; [`compare-counts.sql`](compare-counts.sql) produces the
same counts for the source so the two can be compared.

The age private key is in the password manager (KeePassXC) and on an offline
copy. It is never in the repository, in GitHub or on the runner.
