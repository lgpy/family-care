# Git hooks

Versioned hooks for this repository. They are not active until each clone opts in:

```sh
sh scripts/install-hooks.sh
```

That sets `core.hooksPath` to `.githooks` and checks that [gitleaks](https://github.com/gitleaks/gitleaks) is installed.

## `pre-commit` — secret scan

Runs gitleaks over the **staged** changes only, with this repo's [`.gitleaks.toml`](../.gitleaks.toml) and redacted output:

```sh
gitleaks git --staged --config .gitleaks.toml --redact --verbose
```

(v8.19 renamed the old `gitleaks protect --staged` to `gitleaks git --staged`; the hook detects the installed version and uses the right one.)

Rules:

- **Fails closed.** If gitleaks is missing, the hook blocks the commit and prints install instructions. A skipped scan is not an option by default.
- **Staged changes only.** It is a fast local gate; the `secrets` job in CI still scans the full history on every push.
- **Redacted.** The finding is shown so you can find it, but the secret itself is masked.

If a finding is a genuine false positive, allowlist it in `.gitleaks.toml` (paths or regexes) rather than skipping the hook. `git commit --no-verify` exists for emergencies only — CI will still catch the commit.

## Notes for Windows

The hook is POSIX `sh` and Git for Windows runs it with its bundled shell, so no extra tooling is needed. The file must keep **LF** line endings (`.editorconfig`: `end_of_line = lf`): a CRLF-converted script fails with a bare "cannot execute" error.

To run the scan by hand without committing:

```sh
gitleaks git --staged --config .gitleaks.toml --redact --verbose
```

To turn the hooks off again:

```sh
git config --unset core.hooksPath
```
