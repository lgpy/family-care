# Security and Privacy

Status: **accepted** — decisions D12–D17. One open question (S2).

Health data is GDPR special-category data. Security is designed in, not added later. This document is deliberately sized for a small family app: protections are chosen for the risks that are actually likely.

---

## 1. Threat model

What we protect: medication, conditions, allergies, appointments, (later) documents — for four people, two of whom never use the app.

| # | Threat | Likelihood | Main protections |
|---|---|---|---|
| T1 | **Lost or stolen phone** | High | Device screen lock, encrypted local database, no health data in notifications, remote session revocation |
| T2 | **Account takeover** (phishing, compromised Google account) | Medium | Google 2-Step Verification required, new-device alerts, session management |
| T3 | **Access-control bug** (data visible to the wrong user/circle) | Medium | Deny-by-default database policies, one access function, automated isolation tests, sync rules tested against the same cases |
| T4 | **Misconfiguration / leaked secrets** (public bucket, admin key in the app) | Medium | Admin key only on the server, provider security checks, setup checklist, separate dev and prod projects |
| T5 | **Data loss** (bad migration, accidental deletion, provider incident, compromised account deleting data) | Medium | Daily provider backups + independent encrypted backups that the app's accounts cannot delete, soft-delete, restore tests |
| T6 | **Leaks through side channels** (push text, logs, crash reports, Android cloud backup, recent-apps screenshot) | Medium | Generic push text, no health data in logs, no analytics SDKs, scrubbed crash reports, Android auto-backup disabled |
| T7 | **Access after it should end** (a removed caregiver keeps data on their phone) | Low | Revocation removes server access immediately; synced local data is wiped on the device's next sync |
| T8 | **Provider breach or insider** | Low | EU hosting, DPAs, encryption at rest. Residual risk accepted for the MVP (D10) |

Out of scope: a compromised phone operating system, targeted state-level attackers.

---

## 2. Authentication (D12)

- **Sign in with Google** (via Supabase Auth) is the only login method in the MVP. No app passwords to forget or reuse.
- **The Google account becomes the key to the health data.** Every caregiver's Google account must have **2-Step Verification enabled** (checked during onboarding, by a setup checklist — the app cannot enforce it).
- Day-to-day the app opens **straight into the signed-in view**, with no second unlock screen. The phone's own screen lock is the barrier; the local database stays encrypted and signed-out users never see content.
- Invitations are bound to the invited email address: the caregiver must sign in with the Google account for that address.
- Recovery: Google handles account recovery; if a caregiver loses access, the owner removes and re-invites them.
- Notification on new device sign-in to the app; "Sign out all devices" in settings (lost phone).
- Google receives only that a sign-in happened (identity), never health data. Google is already a processor for push (FCM).
- Email + password can be added later (e.g. for an iOS/web user without a Google account) without changing the data model.

The same rule applies to the **admin accounts** that control the system: Supabase dashboard, PowerSync, Google/Firebase console, backup storage, domain/email — all with MFA. These accounts can bypass everything in the app.

---

## 3. Authorization (D13)

- **Deny by default.** Every table has Row-Level Security enabled with no access unless a policy grants it.
- All patient-data policies call the single core function *can user U do action A on patient P* (see [care-circle](domain/care-circle.md)).
- The database admin key is used only by server jobs, never shipped in the app.
- **Two rule sets must agree:** with PowerSync, *reads* are governed by its sync rules (what each user's phone downloads), while *writes* go through Supabase and its database policies. Both are tested against the same access scenarios:
  - caregiver sees all patients in their circle
  - user in another circle sees nothing
  - removed caregiver loses access (server immediately, device on next sync)
  - viewer cannot write
- Isolation tests run automatically on every change to the database schema.

---

## 4. Encryption

| Where | How | Notes |
|---|---|---|
| In transit | TLS (HTTPS) everywhere | Certificate pinning not used: risk of locking users out outweighs benefit at this scale |
| Server database and files | Provider encryption at rest (AES-256) | Server-trusted model (D10) |
| Phone | Local database encrypted (PowerSync's SQLite3MultipleCiphers package); key stored in Android Keystore | Cached documents (v2) also encrypted |
| Server secrets (push keys, etc.) | Supabase Vault / environment secrets | Never in the repository |
| Our own backups | Encrypted before upload with a key we hold | Key stored in a password manager + offline paper copy |

End-to-end encryption remains out of the MVP (D10). Revisit for documents.

---

## 5. Audit (D14)

### What is logged
- **All changes** to health data: who, when, which patient, which record, action, before/after values. Written by database triggers so no code path can skip it.
- **Security events:** logins, failed logins, MFA changes, invitations, membership/role changes, data exports, deletions, session revocations.
- **Document downloads** (v2): each time a download link is issued.

### What cannot be logged — an honest limitation
With offline-first, data is read from the **phone's local database**, so the server never sees individual reads. "Who looked at Grandma's allergies on Tuesday" is not knowable. What *is* knowable: which user's devices synced which patients' data, and when. For documents, downloads are logged because each one needs a server-issued link.

### Rules
- The audit log is **append-only**: nobody can edit or delete entries through the app or the database API, including the owner.
- It contains health data itself, so it has the same protection and a retention period (§7).
- The owner sees an **Activity** screen in the app (readable history of changes and security events).

---

## 6. Backups and recovery (D15)

Targets:
- **Maximum data loss (RPO): 24 hours.** Mitigated further because recent changes usually also exist on the phones.
- **Maximum downtime (RTO): 1 day.** Reminders keep working offline in the meantime (D4).

| Layer | What | Retention |
|---|---|---|
| 1. Provider | Supabase daily backups (only on the Pro plan, see D20) | 7 days |
| 2. Independent | Nightly encrypted export of the **whole database including the auth schema** (and, from v2, all stored files) to a second EU provider | 30 daily + 12 monthly |
| 3. Protection | Backup bucket write-only for the backup job, with object lock / versioning: a compromised app or admin account cannot delete backups | — |

- **Restore test every quarter** into a scratch project, following a written checklist. A backup that has never been restored is not a backup.
- **Soft-delete** for user-facing deletions (recoverable for 30 days), separate from GDPR erasure.
- Point-in-time recovery (~$100/month extra) is not justified at this scale.
- Gotchas recorded: storage files are *not* in database backups; user accounts live in the `auth` schema and must be included.

---

## 7. GDPR (D16)

### Does it apply?
While only the family uses it, the app probably falls under the **household exemption** (GDPR Art. 2(2)(c)). We design as if GDPR applies anyway because:
- it is the right standard for health data,
- the providers we use are data processors regardless,
- if anyone outside the family ever uses it, we are already compliant.

### Patients without accounts
The grandparents' data is managed on their behalf. Each patient record has an **optional agreement note** (free text, never blocks using the app), for example:
- "Told Grandma about the app, she agreed — 16 Nov 2026", or
- "Managed by Mom as family caregiver", or
- a reference to a legal representative, if one is ever appointed (in Portugal, the *maior acompanhado* regime).

Recommendation, not a requirement: simply tell each grandparent that their medication and appointments are kept in a private family app, and note it. For family use there is no formal consent procedure to follow.

### Rights, as features
| Right | Feature |
|---|---|
| Access / portability | **Export per patient**: machine-readable (JSON) + human-readable (PDF) |
| Rectification | Normal editing (history kept in the audit log) |
| Erasure | **Delete patient**: the owner erases through one RPC (`core.erase_patient()`), which hard-deletes all their data and their prior audit rows, and keeps only a minimal record that a deletion happened (no clinical data). Backups expire it naturally within their retention period (30 daily / 12 monthly, at most a year) |
| Restriction | **Archive patient**: read-only, no reminders, hidden from Today |

### Data minimisation
- Only fields with a clear use. No national ID beyond the SNS number.
- **No third-party analytics or tracking SDKs (D17).**
- Crash reporting either off or with all personal data scrubbed.
- Android auto-backup to Google Drive disabled for the app's data.

### Processors
| Processor | Role | Check |
|---|---|---|
| Supabase | Database, auth, storage | DPA signed; EU region; US company → covered by EU–US Data Privacy Framework / SCCs |
| PowerSync | Sync service | DPA; EU region (verify at setup) or self-host |
| Google (FCM) | Push delivery | Payload contains no health data; device tokens are personal data |
| Email provider | Auth and alert emails | EU-based provider preferred |
| Backup storage | Independent backups | EU provider; data encrypted before upload |

### Retention
- Active patients: kept while in care.
- Archived patients: kept until explicitly deleted; the app reminds yearly to review.
- Audit log: 6 years for change history (aligns with typical health-record practice); security events 1 year.
- Backups: per §6.

### Incident response (lightweight runbook)
1. Contain: revoke sessions/keys, rotate secrets.
2. Assess: what data, which patients, how.
3. Inform the affected family members. If GDPR applies in full: notify the CNPD (Portuguese supervisory authority) within 72 hours where required.
4. Record what happened and what changed.

---

## 8. Operational security

- Separate **dev and prod** projects. **No real health data in dev** — use invented test patients.
- Secrets in environment/vault only; repository scanned for secrets with gitleaks (before each commit and in automated checks).
- Dependencies updated regularly; provider security advisor checked before each release.
- Rate limiting on auth endpoints (provider default + review).
- Android: auto-backup disabled, optional screenshot blocking on sensitive screens, notification text generic on the lock screen.
- Database migrations reviewed and tested against a restored backup copy before production.

---

## Open questions (security)

- ~~**S1 — Login method.**~~ Resolved: Sign in with Google (D12), no app-specific unlock screen.
- **S2 — Grandparents' agreement.** Undecided how to approach it. Handled by an *optional* agreement note per patient (§7), so it does not block anything. Revisit if circumstances change (e.g. a legal representative is appointed).
- ~~**S3 — Data-loss tolerance.**~~ Resolved: up to 24 hours accepted; no point-in-time recovery (D15).
