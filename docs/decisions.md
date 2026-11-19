# Decision Log

Each decision records what was decided and why. To change one, add a new entry that supersedes it.

---

### D1 — Flutter for the client app — *superseded by D22*
- **Decision:** Build the app in Flutter (Dart).
- **Why:** Android now, web and iOS later from one codebase. Mature libraries for local databases, offline sync and local notifications. Good fit for a solo developer.
- **Alternatives considered:** Native Kotlin (Android only), Kotlin/Compose Multiplatform (less mature web/iOS, fewer sync options), React Native/Expo (weaker Android background notifications).

### D2 — Supabase (PostgreSQL) in an EU region as the backend
- **Decision:** Use Supabase for database, authentication, file storage and server functions, hosted in the EU.
- **Why:** The data is relational. Postgres Row-Level Security enforces access control inside the database. Supabase is open source, so we can self-host to leave. Signed DPA available.
- **Plan:** Start on the free tier; upgrade to Pro (~$25/month) when we rely on it (D20).
- **Alternatives considered:** Firebase (NoSQL fit is poor, more lock-in, harder GDPR story), custom backend (too much to build for an MVP), self-hosting (operational burden for health data).

### D3 — Offline-first with local SQLite + PowerSync
- **Decision:** The app reads and writes a local SQLite database; PowerSync syncs it with Postgres in the background.
- **Why:** The app must work without signal (pharmacy, hospital). Building our own sync would cost weeks of edge cases.
- **Note:** "Real-time" is a bonus on top of offline-first, not the requirement.

### D4 — Dose reminders are scheduled on the device
- **Decision:** Reminders are local notifications scheduled by the phone. Server push (FCM) is only used for coordination alerts (escalations, low stock, appointment changes).
- **Why:** Reminders must fire without network or server availability.
- **Consequences:** Must handle reboots, Android Doze and manufacturer battery optimisation. Push payloads contain no health data.

### D5 — Modular monolith with extension points
- **Decision:** One app and one backend, split into a shared core and independent feature modules.
- **Rules:** Modules depend on the core, never on each other. Modules reference patients by ID only. Shared screens (dashboard, calendar, timeline, tasks, notifications) are populated by modules registering contributions.
- **Why:** New features become new modules without changing existing ones. No microservice complexity.

### D6 — Append-only records for doses and stock
- **Decision:** Dose outcomes and stock changes are logged as immutable entries. Current state is derived from them.
- **Why:** Two offline phones never overwrite each other; history and auditability come for free.

### D7 — Reminder policy is a per-patient setting
- **Decision:** Each patient has a reminder policy:
  - **Grandparents:** *primary + escalation* — the primary caregiver is reminded; if the dose is unconfirmed after a delay, the other caregiver is alerted.
  - **Me and my mother:** *all caregivers* — both phones are reminded; first confirmation wins.
- **Why:** Calm and safe for the grandparents; simple for ourselves.
- **Superseded in part by D11** (policy for me and my mother).

### D8 — Bilingual: Portuguese (Portugal) and English
- **Decision:** All UI text translatable from day one. Per-user language. Notifications generated in the recipient's language. User-entered data is never translated.
- **Where the language lives:** `core.profiles.preferred_language` (one of `pt-PT`, `en`; `not null`). Choosing a language in More changes this phone's app language **and** writes the profile. Until someone chooses a language in the app, the phone's own language wins and is written to the profile; the profile is never applied back to a phone (every profile starts as `pt-PT`, so that would switch English phones to Portuguese unasked). "System default" is resolved on the phone before saving (Portuguese → `pt-PT`, anything else → `en`, the app's fallback), because the column cannot hold "follow the phone"; the device keeps following the phone until an explicit choice is made. Rows created by the server default to `pt-PT`.
- **Why:** Cheap now, painful to retrofit.

### D9 — Medication model: versioned regimens, derived "missed", stock as a ledger
- **Decision:** See [domain/medications.md](domain/medications.md).
  - Regimens are never edited in place; changes end the old one and start a new one.
  - "Missed" is computed, never stored.
  - Stock is the sum of movements; low stock is measured in days.
- **Why:** Accurate history for doctors, no offline conflicts, low-stock thresholds that work for any medication.

### D10 — Server-trusted encryption for the MVP (provisional)
- **Decision:** TLS in transit, provider encryption at rest, encrypted local database, strict database access control. No end-to-end encryption in the MVP.
- **Why:** End-to-end encryption would prevent server-side escalations, low-stock jobs, future AI features and account recovery.
- **Revisit:** Client-side encryption for documents, when the documents module is designed.

### D11 — Self-managed reminders for caregiver-patients (supersedes D7 for me and my mother)
- **Decision:** When a patient is also a user (me, my mother), the default policy is *self-managed*: `primary_with_escalation` with the primary set to the patient's own account. Only the patient's phone is reminded; the other caregiver is alerted only if the dose stays unconfirmed past the escalation delay.
- **Grandparents:** unchanged — `primary_with_escalation` with a chosen primary caregiver.
- **Why:** Each of us manages our own medication without notifying the other, while keeping a safety net. No new policy type needed: self-managed is a default, not a separate mechanism.
- **Resolves:** Q1.

### D12–D17 — Security and privacy
Details and reasoning in [security-and-privacy.md](security-and-privacy.md).
- **D12 — Authentication:** Sign in with Google only (MVP); caregivers' Google accounts must have 2-Step Verification; mandatory biometric/PIN app lock; MFA on all admin/provider accounts.
- **D13 — Authorization:** deny-by-default Row-Level Security on every table; one access function; PowerSync sync rules and database policies tested against the same access scenarios.
- **D14 — Audit:** all changes and security events logged by database triggers, append-only. Individual reads are not loggable offline-first; syncs and document downloads are.
- **D15 — Backups:** provider daily backups + nightly independent encrypted backups (database incl. auth schema, later files) to a second EU provider, undeletable by app/admin accounts; quarterly restore tests. RPO 24 h (accepted), RTO 1 day; no point-in-time recovery.
- **D16 — GDPR stance:** design as if GDPR fully applies even if the household exemption covers us. Per-patient export, deletion and archive; optional agreement note per patient; DPAs with all processors; defined retention.
- **D17 — No third-party analytics or tracking SDKs.** Crash reporting off or scrubbed of personal data.

### D18 — Manual APK installation (for now)
- **Decision:** Distribute signed release APKs installed by hand (via a private shared Drive folder). No Google Play for now.
- **Why:** Two users; no store account, review or policy forms needed.
- **Consequences:** Updates are manual. The release signing key must never be lost (stored in password manager + offline backup), or the app must be reinstalled. Switching to Google Play later is possible with the same key. Details: [build-plan §7](build-plan.md).

### D19 — Dose reminders use alarm-clock alarms, plus per-brand setup and a health check
- **Decision:** Schedule dose reminders as Android alarm-clock alarms (shows an alarm icon in the status bar). Guide the user through brand-specific battery settings (Honor/MagicOS, Samsung/One UI). Check on every app start that permissions and future alarms are in place.
- **Why:** Both phones (Honor 200, Samsung Galaxy A) come from makers known for closing background apps. Alarm-clock alarms are the most reliable type; the health check catches silent breakage; server escalation remains the final safety net. Details: [build-plan §8](build-plan.md).

### D20 — Start on free tiers; pay only when relied upon
- **Decision:** Build and start using the app entirely on free tiers (Supabase, PowerSync, Firebase, Backblaze B2, GitHub). At M7 decide on Supabase Pro (~$25/month), recommended once real data depends on it. PowerSync stays free unless deactivation becomes a real problem (then: self-host the free Open Edition rather than paying ~$49/month).
- **Why:** For two users the free limits are far above our needs. The real free-tier risks are pausing after a week of inactivity (avoided by daily use; the app works offline meanwhile) and no provider backups on Supabase Free (covered by our own nightly backup).
- **Consequence:** The independent backup job and a tested restore must be in place **before** real health data enters prod (moved forward from M7). Details: [build-plan §6](build-plan.md).
- **Supersedes:** the paid-tier plan in D2.

### D21 — Application ID: `io.github.lgpy.familycare`
- **Decision:** The Android application ID is `io.github.lgpy.familycare`.
- **Why:** Application IDs are conventionally a reversed domain the developer controls. Without an own domain, the GitHub Pages domain `lgpy.github.io` (owned via the GitHub account `lgpy`) is the accepted convention. Contains no health-related or personal information beyond the public handle.
- **Consequence:** Permanent. A later own domain does not require changing it.

### D22 — Native Android app in Kotlin + Jetpack Compose (supersedes D1)
- **Decision:** Build the client as a native Android app: Kotlin, Jetpack Compose, Gradle modules. Business rules live in **pure Kotlin `domain` modules** with no Android dependencies.
- **Why:** Preference for a native app. Most direct control over the hardest requirement — reliable reminders on Honor (MagicOS) and Samsung (One UI) — through AlarmManager, receivers and WorkManager without plugin layers. All required pieces exist natively: PowerSync Kotlin SDK with Supabase connector and local encryption, supabase-kt with Google sign-in via Credential Manager.
- **Future platforms:** the pure Kotlin domain modules are ready for Kotlin Multiplatform. iOS later: share them (with SwiftUI or Compose Multiplatform screens). Web later: a separate web client (shares the backend; domain logic can be compiled for the web via Kotlin Multiplatform or rewritten).
- **Trade-off accepted:** web and iOS cost more later than with Flutter.
- **Unchanged:** everything else (Supabase, PowerSync, local alarms, module rules, security). Dose-slot logic is still duplicated between app (Kotlin) and server (TypeScript), guarded by shared test vectors.

### D23 — Invitations are one-time codes shared as text, not links
- **Decision:** An invitation is a one-time code (32 Crockford base32 characters) that the owner shares as text through any messaging app. The invitee signs in with the invited Google account and pastes the code on the app's first screen ("Join with an invite code"). This supersedes the "invitations by link" wording in build-plan §M2 and domain/care-circle.md.
- **Why:** WhatsApp only makes `http(s)` URLs tappable, so a "link" would need a published web page and an App Link domain — more infrastructure than two users need. The code is hashed server-side, bound to the invited email, single-use and expiring, so a URL would add no security. Installing the APK is manual anyway (D18): the inviter sends the app separately, and there is no public URL to point at.
- **Consequence:** The share message says the app is sent separately and names the exact first-screen step; put a URL back only together with a real download page.
- **Revisit:** when moving to Google Play (or any public distribution with a stable URL), a link can wrap the same code.

### D24 — Removing a member clears their patient roles
- **Decision:** When an owner removes a member, the server (`core.remove_member`) clears that user as `primary_caregiver` **and** as `linked_user` of every patient in the circle. If a patient is left with `primary_with_escalation` and no primary caregiver, the app flags it: "Needs a primary caregiver — choose who gets the reminders" in the Patients list and at the top of the patient form, and the form refuses to save until one is chosen.
- **Why:** A removed member must not keep receiving reminders or stay tied to a patient; leaving the patient silently unreminded would be the dangerous option. Clearing the linked user makes the patient an ordinary patient (the form handles `linked_user = null`), and the flag makes the gap visible until someone chooses.
- **Server side:** implemented by a backend change in `core.remove_member`; the app side is the domain helper, the flag and the form rule.

### D25 — Reminders use alarm-clock alarms with `USE_EXACT_ALARM`
- **Decision:** Schedule dose reminders with `AlarmManager.setAlarmClock` (D19) under the `USE_EXACT_ALARM` permission, which Android grants at install to alarm/reminder apps. Still check `canScheduleExactAlarms()` in the health check, and fall back to an inexact `setAndAllowWhileIdle` alarm if a system build refuses it.
- **Why:** Alarm-clock alarms are the type phone makers are least willing to defer, and sideloading (D18) means Play policy review does not apply.
- **Revisit:** moving to Google Play requires `SCHEDULE_EXACT_ALARM` plus a user grant, with the setup screen guiding it.
- **Consequence:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and a brand-specific setup screen (build-plan §8) remain part of the reliability story; the health check reports each verifiable setting.

### D26 — Reminder notifications are private on the lock screen
- **Decision:** Dose reminders use one channel, `dose_reminders` (high importance, alarm-like sound). The notification is `VISIBILITY_PRIVATE` with a public version that says only "Family Care · A dose is due" (pt-PT "Family Care · Há uma toma para dar"). The private (unlocked) text may name the patient, the time and the count ("Grandma · 08:00 · 4 medications"); nothing is written to logs. Actions are "Given ✓" (confirms the whole group, one entry per medication) and "Snooze 15 min" (local only, does not delay escalation).
- **Why:** the lock screen is visible to anyone holding the phone, but once unlocked the reminder must be actionable at a glance. Group confirmation keeps the interaction to one tap without breaking the one-entry-per-medication storage rule.
- **Consequence:** no full-screen intent; the notification plus the alarm-clock alarm is enough. Tapping opens the existing dose-slot screen.

---

### D27 — M4 push plumbing: owner-only tokens, a server-side ledger, Vault for webhooks
- **Decision:** Server escalation (D4) is built from four small pieces rather than a job embedded in the app:
  - `core.push_devices` stores FCM tokens, one row per install, owner-only under RLS and **not** synced. Registration goes through `core.register_push_device(token)`, which reassigns a token that moved between accounts (a token identifies an install, not a person). Registrations are audited as security events **without the token** — device tokens are personal data and a bearer credential for that install.
  - `meds.escalations` is the server's "this slot was escalated" ledger, unique on `(regimen_id, scheduled_local)`. No API role has a privilege on it: the app derives "needs attention" from the dose log, and the ledger carries no information the phones need.
  - The two function URLs and the shared cron secret live in **Vault** (`escalate_url`, `dose_confirmed_url`, `push_cron_secret`), read by `security definer` helpers. `app.settings` would need a database restart and is not editable from SQL on hosted Supabase. A missing secret makes the trigger and the cron tick no-ops instead of failures, so local/CI never needs the edge runtime or network.
  - The clear broadcast is a pg_net `AFTER INSERT` trigger on `meds.dose_log` (versioned in the migration, not a dashboard webhook), and the five-minute tick is pg_cron. Both post only the minimum: the new dose-log id, or nothing at all.
- **Why:** Escalation must work when the primary's phone is off, which rules out anything the app schedules. Versioning the trigger and the cron job keeps them reviewable and reproducible; Vault keeps the secret out of the repo and out of migration history. A dead FCM token is deleted by the function rather than retried forever.
- **Trade-off accepted:** the functions are deployed with `verify_jwt = false` (pg_net cannot send a Supabase JWT), so they authenticate with a shared secret header and must reject everything else; the secret never appears in payloads, logs or audit rows. Until Vault is configured on a project, escalation and clear pushes are silently off — the README documents the setup as a human deploy step.
- **Consequence for D8:** notification text is generated per recipient from `core.profiles.preferred_language`, and payloads carry ids and wall-clock times only (no medication or patient names, D4).

### D28 — M5 stock: counts, refills and discards in a ledger; doses come from the dose log
- **Decision:** `meds.stock_movements` is an append-only ledger with three kinds: `count` (a stocktake — "there are N now", also the opening balance entered in the medication form), `refill` (+N) and `discard` (−N, reason). There is **no "dose" movement row**: current stock = the latest count + refills − discards after it − the effective `taken` quantities in `meds.dose_log` after it. A manual "adjustment" is a recount. The exact rules (baseline, corrections, null quantities, daily consumption per schedule type, days remaining with multiply-before-divide, low stock, shopping status) are in `shared/test-vectors/README.md` and `stock.json`, implemented in Kotlin and TypeScript (D22).
- **Shopping list** is derived (low-stock, not stopped medications), not stored. "Needs prescription first" when a prescription is required and its validity date is missing or before today; ticking an item records a `refill`.
- **Low-stock push** (D4): a daily server job sends one generic push per **low episode** to the circle's owners and caregivers (not viewers, not routed by reminder policy — stock is everyone's errand). `meds.stock_alerts` (server-only ledger, one row per medication while low) prevents repeats; the row is removed when the medication is no longer low, which re-arms the alert. Same Vault/shared-secret/pg_cron pattern as D27; payload carries only `type=stock_low`.
- **Why:** Deriving dose consumption from the dose log keeps one source of truth, works offline the moment a dose is confirmed, and needs no trigger to keep two ledgers consistent (a late-syncing or corrected dose simply changes the sum). Counts instead of signed adjustments match what a caregiver actually does: count the box.
- **Trade-off accepted:** two phones recording a count at the same time — the later `occurred_at` wins. Doses recorded before any count make stock negative; the UI shows 0 and the medication is low, which is the safe direction.

### D29 — M6: push and patient screens move to the core; appointments and patient info are their own modules
- **Core push routing:** the single `FirebaseMessagingService`, FCM token registration and the message `type` router move from the medications feature into `core/notifications`. Features register `PushHandler`s for their own types (medications: `escalation`, `clear`, `stock_low`; appointments: `appointment_changed`); an unknown type is ignored. One APK can only have one FCM service, so this is the only D5-compliant way for a second feature to receive pushes.
- **Patient profile:** a real patient screen hosted by the app, filled by contributions from `core/ui` contracts: a `PatientHeader` (patient-info: allergies banner + conditions), `PatientTab`s (medications, appointments, info) and `TimelineSource`s feeding an app-owned **History** tab (medications: regimen started/changed/stopped; appointments: each appointment). Tapping a patient opens the profile; editing the patient moves to a menu action.
- **Appointments** (`appointments` schema, `docs/domain/appointments.md`): local wall-clock + zone like regimens; edits in place, cancel instead of delete; optional companion; follow-up link. Reminders are local alarm-clock alarms at 20:00 the evening before and 1 h before, on the companion's phone or, without one, every owner's and caregiver's — never a viewer's. A change push (D4) goes to owners and caregivers except the person who made the change, generic text, `type=appointment_changed`. Agenda becomes a bottom-bar tab; Today gets the "Next 7 days" card (order 200).
- **Patient info** (`info` schema, `docs/domain/patient-info.md`): one row per patient with `id = patient_id`, text and small JSON lists, edited in place, synced to members.
- **Why:** the alternative to moving push into the core was duplicating it per feature, which Android does not allow for the FCM service. The patient screen is the place the MVP doc always described (tabs + History), and contributions keep the app shell ignorant of features (D5). Separate schemas keep each module's tables, grants and tests together, as M3 did for `meds`.
- **Trade-off accepted:** both caregivers get reminders for every appointment unless a companion is set; patient info is last-write-wins.

### D30 — M7: archive is enforced read-only, erasure goes through one RPC, export happens on the phone
- **Archive** (owner only) makes a patient read-only in the database for every role and hides it from Today, Agenda, Shopping, reminders and server pushes; it stays synced so it can be unarchived.
- **Erase** is `core.erase_patient()`, the only way to delete a patient: it suppresses auditing of the patient's rows, deletes their earlier audit rows and writes one minimal `patient.erased` security event. Before M7 a delete would have left the full health record in `core.audit_log.old_data` for six years, contradicting security-and-privacy §7.
- **Export** (owners and caregivers) is built on the phone from synced data as JSON and a doctor-readable PDF, shared through a FileProvider; it is logged first through `core.log_patient_export()`, so it needs a connection.
- **Activity** (owners) reads the audit log through `core.circle_activity()`, which returns short labels only — never `old_data`/`new_data` — and includes members' sign-in events.
- **Why:** the audit log is the one place health data could outlive an erasure; routing deletes through a single function makes that rule testable. Exporting on the phone works with the data the user can already see and needs no new server surface beyond the audit call.
- **Trade-off accepted:** export requires a connection (to log it); backups keep erased data until they expire (at most a year).
- Details: `docs/domain/patient-lifecycle.md`.

---


- ~~**Q1 — Default policy for caregiver-patients.**~~ Resolved by D11: self-managed.
- **Q2 — Security and GDPR deep-dive.** Drafted in [security-and-privacy.md](security-and-privacy.md) D12–D17 accepted. Only S2 (grandparents' agreement) remains open, non-blocking.
- **Q3 — Default timings.** Escalation delay 30 min, missed window 2 h, low-stock threshold 7 days, appointment reminders evening before + 1 h before. To validate in use.
