# Vision and MVP

## Vision

One private place for a family to manage the healthcare of several relatives: medications, appointments, medical information and records, shared between the people who look after them.

## Users

- **Caregivers (accounts):** me and my mother. We administer medications and manage appointments.
- **Patients (profiles):** me, my mother, Grandparent 1, Grandparent 2.
  - Patients do not need accounts. The grandparents never use the app.
  - A caregiver can also be a patient (linked user ↔ patient profile).
- Doses are *usually* given by one of the two caregivers. Occasionally by someone else (neighbour, nurse, the patient), which is recorded but needs no account.

There is no "patient mode": the app is designed for caregivers.

## Platforms

- MVP: **Android only** (both caregivers use Android).
- MVP is a **native Android app** (Kotlin, D22).
- Later: web and iOS, sharing the backend and the pure Kotlin business rules (Kotlin Multiplatform); their screens are built separately.
- Languages: **Portuguese (Portugal) and English** from day one.

## Guiding principles

1. **Reliability over features.** A missed reminder is worse than a missing feature. Reminders must work offline.
2. **Security and privacy are foundational**, not added later. Health data is GDPR special-category data.
3. **Simple but extensible.** New features are added as new modules, not by editing existing ones.
4. **Low-effort daily use.** If logging a dose or a refill takes more than a tap or two, it will stop being done.

## MVP scope

1. **Accounts and care circle**
   - Two caregivers, four patient profiles, invitations by link (shared via WhatsApp etc.), accepted by signing in with the invited Google account.
   - Roles `owner` and `caregiver` (others modelled, not exposed).
   - Reminder policy per patient (see [care-circle](domain/care-circle.md)).
2. **Medications**
   - Medication + regimen: fixed daily times, specific weekdays, every N days, start/end date, quantity per time slot.
   - Reminders scheduled locally on the phone, with "Given" / "Snooze" actions in the notification.
   - Confirm a whole time slot in one tap; per-medication exceptions (skipped + reason).
   - Missed doses derived automatically; escalation to the other caregiver.
   - Stock ledger, days remaining, low-stock alert (threshold in days).
   - Shopping list, including "needs prescription first" and refill entry on purchase.
3. **Appointments and examinations**
   - Create/edit, preparation notes (e.g. fasting), reminders (evening before, 1 hour before).
   - Shared upcoming list across all patients.
4. **Basic patient information**
   - Allergies (always visible), conditions, emergency contacts, SNS number, family doctor. Text only.
5. **Dashboard ("Today")**
   - Needs attention (overdue/unconfirmed doses, low stock), today's doses, next 7 days of appointments.
6. **Foundations (invisible, non-negotiable)**
   - Offline-first, database-enforced access control, audit log, backups, app lock, bilingual UI.
   - Sign in with Google + app lock, per-patient export / delete / archive, optional agreement note per patient (see [security-and-privacy](security-and-privacy.md)).

### Suggested first build slice

Care circle + medications end-to-end, including real reminders on a real phone. It proves the hardest parts first: sync, offline, notifications and access control.

## Deferred (v2 and later)

| Feature | Reason for deferral |
|---|---|
| Document upload and storage (prescriptions, reports, results) | Brings file encryption and separate backup work |
| Full calendar (month view) | List is enough initially |
| "As needed" (PRN) and tapering regimens | More complex scheduling |
| Fine-grained per-patient permissions | Not needed for two caregivers; data model already supports it |
| Web client, then iOS | Not needed by current users |
| Emergency card | Built on top of patient info |
| Measurements (blood pressure, glucose, weight…) | First new module; validates the extension points |
| Symptoms, nutrition/hydration, reports/analytics | Later modules |
| Pharmacy integrations, AI document organisation | Later; may influence the encryption model (see D10 in the decision log) |
