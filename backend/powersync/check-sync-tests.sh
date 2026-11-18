#!/usr/bin/env bash
# Drift check: every Sync Streams query in sync-config.yaml must be executed by
# the pgTAP sync-rule tests.
#
# Why: PowerSync reads are governed by sync-config.yaml while writes are
# governed by RLS (security-and-privacy §3, D13). The two rule sets must agree,
# so the tests in supabase/tests/core_care_circle_test.sql run the *stream
# queries themselves* as each test user. This script fails if a stream is edited
# without editing the test (or the other way round), which is exactly how the
# two halves would silently drift apart.
#
# Matching rule: each `query:` value is taken verbatim, `auth.user_id()` is
# replaced by `(select auth.uid())` (the PowerSync auth parameter does not exist
# in Postgres; auth.uid() reads the same JWT `sub` claim), and whitespace runs
# are collapsed on both sides. Everything else - tables, columns, filters,
# aliases - must match character for character.
#
# Queries must stay on ONE line: a `query: |` block is rejected instead of
# silently skipped.
#
# Run from backend/:  bash powersync/check-sync-tests.sh
# CI runs the same command in the `database` job of .github/workflows/ci.yml.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
config="$here/sync-config.yaml"
tests_dir="$here/../supabase/tests"

if [[ ! -f "$config" ]]; then
  echo "FAIL: $config not found" >&2
  exit 1
fi

shopt -s nullglob
test_files=("$tests_dir"/*.sql)
shopt -u nullglob

if [[ "${#test_files[@]}" -eq 0 ]]; then
  echo "FAIL: no pgTAP test files in $tests_dir" >&2
  exit 1
fi

# Collapse all whitespace once: line wrapping is not a behavioural difference.
# Every test file is searched, so a stream may be covered where it fits best
# (core_care_circle_test.sql holds the M2 stream queries).
test_sql="$(cat "${test_files[@]}" | tr -s '[:space:]' ' ')"

count=0
failed=0

while IFS= read -r line; do
  raw="${line#*query:}"
  query="$(printf '%s' "$raw" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

  if [[ -z "$query" || "$query" == "|" || "$query" == ">" || "$query" == "|-" || "$query" == ">-" ]]; then
    echo "FAIL: multi-line or empty stream query - keep every query: on one line:" >&2
    echo "  $line" >&2
    failed=1
    continue
  fi

  expected="${query//auth.user_id()/(select auth.uid())}"
  expected="$(printf '%s' "$expected" | tr -s '[:space:]' ' ')"
  expected="${expected# }"
  expected="${expected% }"

  count=$((count + 1))

  case "$test_sql" in
    *"$expected"*) ;;
    *)
      echo "FAIL: this sync stream query is not executed by any pgTAP test in $tests_dir:" >&2
      echo "  $query" >&2
      echo "  (expected, after substitution: $expected)" >&2
      failed=1
      ;;
  esac
done < <(grep -E '^[[:space:]]*query:' "$config")

if [[ "$count" -eq 0 ]]; then
  echo "FAIL: no query: entries found in $config" >&2
  exit 1
fi

if [[ "$failed" -ne 0 ]]; then
  exit 1
fi

echo "OK: $count Sync Streams queries are covered by ${#test_files[@]} pgTAP test file(s) in $tests_dir"
