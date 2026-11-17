# Architecture

## Components

```
┌──────────────────── Mobile app (native Android: Kotlin + Jetpack Compose) ───────────┐
│  Feature modules (UI + logic)                                                         │
│      ↓ read/write                                                                     │
│  Local SQLite (encrypted) ── source of truth for the UI                               │
│      ↕ PowerSync (background sync, offline write queue)                               │
│  Local notification scheduler (dose and appointment reminders, works offline)         │
└──────────────────────────────────────┬────────────────────────────────────────────────┘
                                       │ HTTPS
┌──────────────────────────────────────▼────────────────────────────────────────────────┐
│ Supabase (EU) — modular monolith                                                      │
│  • Auth (Sign in with Google, sessions)                                               │
│  • Core: care circle, access control, audit, notifications, attachments               │
│  • Feature modules: medications, appointments, patient info (later: measurements…)    │
│  • Scheduled jobs: escalation of unconfirmed doses, low-stock alerts                  │
│  • Push sender (FCM) — generic text only                                              │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ PostgreSQL + Row-Level Security │ Private object storage (later) │ Independent backups │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

| Component | Responsibility |
|---|---|
| Mobile app | UI, encrypted local database, offline writes, local reminders |
| Sync (PowerSync) | Two-way sync between SQLite and Postgres, per-user data scoping |
| Auth | Accounts, Sign in with Google, sessions (D12) |
| Care circle | Patients, caregivers, roles, invitations, reminder policies |
| Access control | One function decides "can user U do action A on patient P?" |
| Feature modules | Medications, appointments, patient info; future modules |
| Jobs | Time-based server rules (escalation, low stock) |
| Notifications | Local reminders on device; FCM push for coordination |
| Audit | Append-only record of changes, written by database triggers |
| Storage | Documents (v2), private bucket, signed short-lived URLs |

## Stack

| Concern | Choice | Decision |
|---|---|---|
| Client | Native Android: Kotlin + Jetpack Compose; pure Kotlin domain modules (Multiplatform-ready) | D22 |
| Backend, database, auth, storage | Supabase (Postgres), EU region | D2 |
| Offline and sync | SQLite + PowerSync | D3 |
| Reminders | Local notifications (exact alarms) | D4 |
| Coordination push | Firebase Cloud Messaging (delivery only) | D4 |
| Encryption | TLS, encryption at rest, encrypted local DB | D10 |

Estimated running cost: €0/month on free tiers while building; ~€25/month once Supabase is upgraded to Pro (see [build-plan §6](build-plan.md), D20).

## Modular structure

### Core (stable, rarely changes)
- **Identity:** users, profiles, language preference.
- **Care circle:** circles, memberships, patients, invitations, reminder policies.
- **Access control:** the single permission check used by every module.
- **Audit:** append-only change log.
- **Notifications:** delivery of local and push notifications, routing per policy.
- **Attachments:** file storage service used by modules (v2).

### Feature modules
- MVP: `medications`, `appointments`, `patient-info`.
- Later: `documents`, `measurements`, `symptoms`, `nutrition`, `reports`, …

### Rules
1. Modules depend on the core, **never on each other**.
2. Modules reference patients (and other core entities) **by ID only**.
3. Each module owns its own tables (one Postgres schema per module, e.g. `meds`, `appointments`) and its own Row-Level Security policies, which call the core access function.
4. Modules communicate through **domain events** (e.g. `DoseConfirmed`, `StockLow`, `AppointmentScheduled`). Initially a database table processed by jobs; no message broker.
5. Shared screens are filled through **extension points**. Each module can register:
   - dashboard cards
   - tasks ("needs attention" items, shopping list entries)
   - notification types
   - calendar/agenda entries
   - patient timeline entries
   - settings sections

Adding a module (e.g. measurements) = new schema + new app feature package + registrations. No edits to existing modules.

### Design note for future modules
Health measurements (blood pressure, glucose, weight, temperature…) should be **one generic module**: a measurement has a type, one or more values, a unit and a timestamp. Not one module per measurement type.

## Cross-cutting requirements

- **Security:** Row-Level Security on every table with automated isolation tests; Sign in with Google with 2-Step Verification required; mandatory app lock (biometric/PIN); encrypted local database; no health data in logs or push payloads.
- **Time:** schedules stored as local wall-clock time with a time zone, so daylight saving changes don't shift reminders.
- **Localisation:** pt-PT and en; per-user language.
- **Backups:** provider daily backups, plus an independent weekly encrypted export of database *and* files to a second EU provider. Restores tested. (Detailed in [security-and-privacy.md](security-and-privacy.md).)
- **GDPR:** EU hosting, DPAs with all processors (Supabase, PowerSync, Google/FCM), data export and deletion per patient, retention policy, audit log. (Detailed in [security-and-privacy.md](security-and-privacy.md).)
