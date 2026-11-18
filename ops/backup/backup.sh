#!/usr/bin/env bash
#
# Nightly independent backup of one Supabase project: dump -> manifest ->
# tar.gz -> age, and (unless --no-upload) upload to Backblaze B2.
# See docs/security-and-privacy.md §6 and D15.
#
#   ops/backup/backup.sh --db-url <url> --env <dev|prod> --output-dir <dir> [--no-upload]
#
# Only the encrypted `.age` file leaves this script. The plaintext dumps and the
# unencrypted tar live in a private temporary directory (umask 077) that an EXIT
# trap removes whether the run succeeded or not; they are never uploaded and
# never kept as an artifact. The database URL is never printed.
#
# The workflow passes SUPABASE_DB_URL in the environment and the environment
# name and output directory as flags; the flags exist so a local run can be
# explicit (README.md).
set -euo pipefail

umask 077

readonly BUCKET="family-care-backups"
readonly DEFAULT_ENDPOINT="https://s3.eu-central-003.backblazeb2.com"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: backup.sh --db-url <url> --env <dev|prod> --output-dir <dir> [--no-upload]

  --db-url       Postgres connection string of the project to dump.
                 Default: $SUPABASE_DB_URL
  --env          dev or prod; decides the object keys and the file name.
                 Default: $BACKUP_ENV
  --output-dir   Where the encrypted .age file is written (keep it outside the
                 repository). Default: $BACKUP_OUTPUT_DIR
  --no-upload    Stop after encryption; for local runs.
  -h, --help     This text.

Uploading needs B2_KEY_ID, B2_APPLICATION_KEY and (optionally) B2_S3_ENDPOINT;
see README.md. The age recipient is read from recipients.txt next to this file.
EOF
}

db_url="${SUPABASE_DB_URL:-}"
backup_env="${BACKUP_ENV:-}"
output_dir="${BACKUP_OUTPUT_DIR:-}"
upload=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --db-url)
      [[ $# -ge 2 ]] || die "--db-url needs a value"
      db_url="$2"
      shift 2
      ;;
    --env)
      [[ $# -ge 2 ]] || die "--env needs a value"
      backup_env="$2"
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || die "--output-dir needs a value"
      output_dir="$2"
      shift 2
      ;;
    --no-upload)
      upload=0
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$db_url" ]] || die "no database URL: pass --db-url or set SUPABASE_DB_URL"
case "$backup_env" in
  dev | prod) ;;
  *)
    usage >&2
    die "--env must be dev or prod (got '${backup_env}')"
    ;;
esac
[[ -n "$output_dir" ]] || die "no output directory: pass --output-dir or set BACKUP_OUTPUT_DIR"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
recipients_file="$script_dir/recipients.txt"
[[ -r "$recipients_file" ]] || die "missing $recipients_file"
recipient="$(grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$recipients_file" | head -n 1 | tr -d '[:space:]')"
[[ "$recipient" == age1* ]] || die "no age1... recipient in $recipients_file"

# The CLI is on PATH in CI (supabase/setup-cli). Locally it is usually npx:
#   SUPABASE_CLI='npx supabase@latest' ops/backup/backup.sh --no-upload ...
read -r -a supabase_cli <<< "${SUPABASE_CLI:-supabase}"
command -v "${supabase_cli[0]}" >/dev/null 2>&1 ||
  die "Supabase CLI not found: install it or set SUPABASE_CLI (e.g. 'npx supabase@latest')"
command -v age >/dev/null 2>&1 || die "age not found: install it (README.md)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found"
command -v tar >/dev/null 2>&1 || die "tar not found"

cli_version="$("${supabase_cli[@]}" --version 2>/dev/null | head -n 1 || true)"
[[ -n "$cli_version" ]] || cli_version="unknown"
git_sha="$(git -C "$script_dir" rev-parse --short HEAD 2>/dev/null || true)"
[[ -n "$git_sha" ]] || git_sha="unknown"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/family-care-backup.XXXXXX")"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

mkdir -p -- "$output_dir"

# roles.sql is small (role settings only; the CLI does not re-create the
# instance's built-in roles), schema.sql is everything except platform
# schemas, and data.sql is every schema -- including auth, so auth.users is in
# the backup (the gotcha in security-and-privacy §6).
log "dumping $backup_env: roles, schema, data"
"${supabase_cli[@]}" db dump --remote="$db_url" --file "$work_dir/roles.sql" --role-only
"${supabase_cli[@]}" db dump --remote="$db_url" --file "$work_dir/schema.sql"
"${supabase_cli[@]}" db dump --remote="$db_url" --file "$work_dir/data.sql" --use-copy --data-only

for file in roles.sql schema.sql data.sql; do
  [[ -s "$work_dir/$file" ]] || die "the dump produced an empty $file"
done

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
stamp="${created_at//[-:]/}"

{
  printf '# family-care backup manifest\n'
  printf 'created_at=%s\n' "$created_at"
  printf 'environment=%s\n' "$backup_env"
  printf 'supabase_cli=%s\n' "$cli_version"
  printf 'git_sha=%s\n' "$git_sha"
  printf 'sha256:\n'
  for file in roles.sql schema.sql data.sql; do
    printf '%s  %s\n' "$(sha256sum -- "$work_dir/$file" | cut -d' ' -f1)" "$file"
  done
} >"$work_dir/manifest.txt"

log "packing"
tar -czf "$work_dir/bundle.tar.gz" -C "$work_dir" roles.sql schema.sql data.sql manifest.txt
rm -f -- "$work_dir/roles.sql" "$work_dir/schema.sql" "$work_dir/data.sql"

encrypted="$output_dir/family-care-$backup_env-$stamp.tar.gz.age"
log "encrypting to $(basename -- "$encrypted")"
age --encrypt --recipient "$recipient" --output "$encrypted" "$work_dir/bundle.tar.gz"
rm -f -- "$work_dir/bundle.tar.gz"
[[ -s "$encrypted" ]] || die "encryption produced no output"

if [[ "$upload" -eq 0 ]]; then
  log "no upload requested (--no-upload)"
  printf '%s\n' "$encrypted"
  exit 0
fi

endpoint="${B2_S3_ENDPOINT:-$DEFAULT_ENDPOINT}"
key_id="${AWS_ACCESS_KEY_ID:-${B2_KEY_ID:-}}"
app_key="${AWS_SECRET_ACCESS_KEY:-${B2_APPLICATION_KEY:-}}"
[[ -n "$key_id" ]] || die "no B2 key id: set B2_KEY_ID (or AWS_ACCESS_KEY_ID)"
[[ -n "$app_key" ]] || die "no B2 application key: set B2_APPLICATION_KEY (or AWS_SECRET_ACCESS_KEY)"
command -v aws >/dev/null 2>&1 || die "AWS CLI not found (it is preinstalled on GitHub runners)"

export AWS_ACCESS_KEY_ID="$key_id"
export AWS_SECRET_ACCESS_KEY="$app_key"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-eu-central-003}"
# AWS CLI >= 2.23 adds CRC checksum headers by default, which S3-compatible
# stores such as B2 have rejected; only send checksums when an API requires one.
export AWS_REQUEST_CHECKSUM_CALCULATION="${AWS_REQUEST_CHECKSUM_CALCULATION:-when_required}"
export AWS_RESPONSE_CHECKSUM_VALIDATION="${AWS_RESPONSE_CHECKSUM_VALIDATION:-when_required}"

# Object Lock buckets expect an integrity header on PutObject; Content-MD5 is
# the one every S3-compatible store accepts, and it also proves the bytes
# arrived intact.
command -v openssl >/dev/null 2>&1 || die "openssl not found (needed for Content-MD5)"
content_md5="$(openssl dgst -md5 -binary "$encrypted" | openssl base64 -A)"

content_md5_hex="$(openssl dgst -md5 -r "$encrypted" | cut -d' ' -f1)"

# The key is write-only (no readFiles, no listFiles), so it cannot look the
# object up afterwards. Verification is the upload itself: B2 rejects the put
# if the bytes do not match Content-MD5, and the returned ETag (the object's
# MD5 for a single-part upload) must equal ours.
upload_object() {
  local key="$1" etag
  log "uploading $key"
  etag="$(aws s3api put-object \
    --endpoint-url "$endpoint" \
    --bucket "$BUCKET" \
    --key "$key" \
    --content-md5 "$content_md5" \
    --body "$encrypted" \
    --query ETag --output text)"
  etag="${etag//\"/}"
  [[ "$etag" == "$content_md5_hex" ]] ||
    die "$key: B2 returned ETag $etag, expected $content_md5_hex"
  log "verified $key (md5 $content_md5_hex)"
}

daily_key="$backup_env/daily/${created_at:0:4}/${created_at:5:2}/$(basename -- "$encrypted")"
upload_object "$daily_key"

# On the first of the month the same file also becomes that month's copy, which
# the lifecycle rules keep for a year (retention is B2's job; this job cannot
# delete anything).
if [[ "${created_at:8:2}" == "01" ]]; then
  monthly_key="$backup_env/monthly/${created_at:0:4}/family-care-$backup_env-${created_at:0:4}${created_at:5:2}.tar.gz.age"
  upload_object "$monthly_key"
fi

log "done"
printf '%s\n' "$encrypted"
