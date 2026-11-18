#!/usr/bin/env bash
#
# Restore test for the nightly backups (security-and-privacy §6, build-plan §4):
# decrypt one backup, verify its manifest, restore it into the scratch local
# Supabase stack, and print row counts to compare with the source.
#
#   ops/backup/restore-test.sh <backup.tar.gz.age> <age-identity-file>
#
# The age private key comes from the password manager (plus offline copy) and is
# never in this repository. The decrypted dumps live in a private temporary
# directory (umask 077) that an EXIT trap removes.
#
# The target is ops/backup/scratch (project_id family-care-restore, ports
# 55321-55329), never backend/supabase. The scratch stack is started if needed;
# a database that already holds app tables is refused, because a restore test
# must begin from an empty project. To reset it:
#
#   npx supabase@latest stop --workdir ops/backup/scratch --no-backup
#   npx supabase@latest start --workdir ops/backup/scratch
#
# The checklist this feeds is RESTORE.md.
set -euo pipefail

umask 077

readonly SCRATCH_DB_URL="postgresql://postgres:postgres@127.0.0.1:55322/postgres"
readonly SCRATCH_CONTAINER="supabase_db_family-care-restore"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: restore-test.sh <backup-file.tar.gz.age> <age-identity-file>

Decrypts the backup, checks the manifest's sha256 sums, restores roles, schema
and data (including auth.users) into the scratch local Supabase stack, and
prints per-table row counts. Compare them with the source, then stop the stack:

  npx supabase@latest stop --workdir ops/backup/scratch --no-backup
EOF
}

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 2
fi

backup_file="$1"
identity_file="$2"
[[ -f "$backup_file" ]] || die "no such backup file: $backup_file"
[[ -f "$identity_file" ]] || die "no such age identity file: $identity_file"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
scratch_dir="$script_dir/scratch"
[[ -f "$scratch_dir/supabase/config.toml" ]] || die "missing $scratch_dir/supabase/config.toml"

command -v age >/dev/null 2>&1 || die "age not found: install it (README.md)"
command -v tar >/dev/null 2>&1 || die "tar not found"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/family-care-restore.XXXXXX")"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

log "decrypting $(basename -- "$backup_file")"
age --decrypt --identity "$identity_file" --output "$work_dir/bundle.tar.gz" "$backup_file"

log "unpacking"
tar -xzf "$work_dir/bundle.tar.gz" -C "$work_dir"
rm -f -- "$work_dir/bundle.tar.gz"

for file in manifest.txt roles.sql schema.sql data.sql; do
  [[ -s "$work_dir/$file" ]] || die "the backup is missing $file"
done

# The manifest lists the checksums in sha256sum format, after a "sha256:" line.
grep -E '^[0-9a-f]{64}  ' "$work_dir/manifest.txt" >"$work_dir/SHA256SUMS"
[[ -s "$work_dir/SHA256SUMS" ]] || die "the manifest has no sha256 checksums"
log "verifying checksums"
(cd "$work_dir" && sha256sum --check --strict SHA256SUMS)

log "manifest: $(grep -E '^(created_at|environment|supabase_cli|git_sha)=' "$work_dir/manifest.txt" | tr '\n' ' ')"

# `supabase` is on PATH in CI; locally it is usually npx:
#   SUPABASE_CLI='npx supabase@latest' ops/backup/restore-test.sh ...
read -r -a supabase_cli <<< "${SUPABASE_CLI:-supabase}"
if ! "${supabase_cli[@]}" status --workdir "$scratch_dir" >/dev/null 2>&1; then
  log "scratch stack is not running; starting it (the first run pulls images)"
  "${supabase_cli[@]}" start --workdir "$scratch_dir"
fi

# No psql on PATH in Git Bash on Windows, so run it inside the scratch database
# container. It connects as `supabase_admin` (the local image's superuser): a
# freshly initialised local stack does not grant the `postgres` role write
# access to every platform table (storage.buckets_vectors and friends), which a
# data dump legitimately contains. A hosted restore uses `postgres` instead.
if docker container inspect "$SCRATCH_CONTAINER" >/dev/null 2>&1; then
  psql_mode="docker"
  scratch_psql() {
    docker exec -i "$SCRATCH_CONTAINER" psql --username supabase_admin --dbname postgres "$@"
  }
elif command -v psql >/dev/null 2>&1; then
  psql_mode="native"
  scratch_psql() {
    PGPASSWORD=postgres psql --dbname "$SCRATCH_DB_URL" "$@"
  }
else
  die "no psql on PATH and no $SCRATCH_CONTAINER container; is the scratch stack started?"
fi

app_tables="$(scratch_psql --no-align --tuples-only --command \
  "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname in ('core', 'meds') and c.relkind in ('r', 'p')" |
  tr -d '[:space:]')"
if [[ "$app_tables" != "0" ]]; then
  die "the scratch database already has $app_tables app tables; start it clean with
  ${supabase_cli[*]} stop --workdir $scratch_dir --no-backup
  ${supabase_cli[*]} start --workdir $scratch_dir"
fi

log "restoring roles, schema and data into the scratch database"
if [[ "$psql_mode" == "native" ]]; then
  scratch_psql \
    --single-transaction --variable ON_ERROR_STOP=1 \
    --file "$work_dir/roles.sql" \
    --file "$work_dir/schema.sql" \
    --command 'SET session_replication_role = replica' \
    --file "$work_dir/data.sql"
else
  # The dumps are on the host and the container cannot read them, so pipe them
  # in one stream in the same order: roles, schema, replica mode, data.
  {
    cat "$work_dir/roles.sql"
    cat "$work_dir/schema.sql"
    printf 'SET session_replication_role = replica;\n'
    cat "$work_dir/data.sql"
  } | scratch_psql --single-transaction --variable ON_ERROR_STOP=1
fi

log "row counts in the restored database (compare with the source):"
if [[ "$psql_mode" == "native" ]]; then
  scratch_psql --variable ON_ERROR_STOP=1 --file "$script_dir/compare-counts.sql"
else
  scratch_psql --variable ON_ERROR_STOP=1 <"$script_dir/compare-counts.sql"
fi

log "restore test finished; get the source counts with:"
log "  docker exec -i supabase_db_family-care psql -U postgres -d postgres --file - < $script_dir/compare-counts.sql"
log "then stop the scratch stack: ${supabase_cli[*]} stop --workdir $scratch_dir --no-backup"
