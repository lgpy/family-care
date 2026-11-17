#!/bin/sh
#
# Enable this repository's versioned git hooks (build-plan M1: secret protection).
#
# Points git at .githooks/ so the gitleaks pre-commit hook runs for every commit
# in this clone, and checks that gitleaks itself is installed. Safe to re-run.
#
#   sh scripts/install-hooks.sh
#
# Disable again with:  git config --unset core.hooksPath

set -u

repo_root=$(cd "$(dirname "$0")/.." && pwd) || {
	echo "install-hooks: cannot resolve the repository root." >&2
	exit 1
}
cd "$repo_root" || {
	echo "install-hooks: cannot enter $repo_root." >&2
	exit 1
}

hook=".githooks/pre-commit"
if [ ! -f "$hook" ]; then
	echo "install-hooks: $hook is missing; nothing to install." >&2
	exit 1
fi

# The hook must be executable; git stores the bit in the index, so a clone on
# Linux/macOS only runs it if the mode was committed. Local chmod is enough here
# on Windows (Git for Windows runs hooks with its bundled sh regardless), but
# note the mode if it is not yet committed.
chmod +x "$hook" 2>/dev/null || true
if git rev-parse --git-dir >/dev/null 2>&1; then
	index_mode=$(git ls-files -s -- "$hook" 2>/dev/null | awk '{ print $1 }')
	if [ -n "$index_mode" ] && [ "$index_mode" != "100755" ]; then
		echo "install-hooks: note: $hook is mode $index_mode in the index."
		echo "  Commit the executable bit so Linux/macOS clones can run it:"
		echo "    git add --chmod=+x $hook"
	fi
fi

previous=$(git config --get core.hooksPath 2>/dev/null || true)
if [ "$previous" = ".githooks" ]; then
	echo "install-hooks: core.hooksPath already set to .githooks."
else
	if [ -n "$previous" ]; then
		echo "install-hooks: replacing existing core.hooksPath '$previous' with '.githooks'."
	fi
	git config core.hooksPath .githooks || {
		echo "install-hooks: could not set core.hooksPath." >&2
		exit 1
	}
	echo "install-hooks: core.hooksPath set to .githooks."
fi

if ! command -v gitleaks >/dev/null 2>&1; then
	cat >&2 <<'EOF'

install-hooks: WARNING — gitleaks is not installed.
The pre-commit hook fails closed, so every commit will be blocked until it is.

  Windows   winget install --id Gitleaks.Gitleaks -e
            (alternatives: choco install gitleaks / scoop install gitleaks)
  macOS     brew install gitleaks
  Linux     download the release binary from
            https://github.com/gitleaks/gitleaks/releases

Then re-run this script to confirm.
EOF
	exit 1
fi

echo "install-hooks: gitleaks $(gitleaks version 2>/dev/null | head -n 1) found."
echo "install-hooks: done — staged changes are scanned before every commit."
