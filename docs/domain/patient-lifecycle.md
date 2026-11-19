# Patient lifecycle: archive, erase, export, activity (M7)

Decision: D30. Core feature (patients, audit) — no feature module owns it, but each module's tables are covered.

## Archive
- **Who:** owners only (`core.can(patient, 'manage')`). Unarchive likewise.
- **Effect:** `core.patients.archived_at` set. The patient becomes **read-only for everyone**: the database refuses insert/update/delete on that patient's rows in `meds.*`, `appointments.appointments`, `info.patient_info` and `core.patient_access_overrides` (one trigger per table calling `core.assert_patient_writable(patient_id)`, error `42501`, message `patient is archived`). The only allowed write to an archived patient is the owner clearing `archived_at` (and a full erase).
- **Hidden:** from Today (all cards), the Agenda, Shopping, dose and appointment reminders, the server's escalation, low-stock and appointment-change pushes. Still synced (so it can be unarchived and viewed). The Patients list shows archived patients in a collapsed "Archived" section; the profile opens read-only with an "Archived" banner.

## Erase (delete a patient)
- **Who:** owners only. Through one RPC; a direct `delete from core.patients` is refused for API roles from M7 on (the RPC is the only path), so the audit rules below always hold.
- `core.erase_patient(p_patient_id uuid) returns void`, security definer:
  1. checks `core.can(p_patient_id, 'manage')`;
  2. sets a transaction-local flag (`set_config('familycare.erasing_patient', <id>, true)`) that every audit trigger checks: rows belonging to the patient being erased are **not** audited;
  3. deletes the patient's existing `core.audit_log` rows (`patient_id = p_patient_id`) — the append-only guard allows this only while the flag names that patient;
  4. writes **one** minimal audit row: `event_kind = 'security'`, `action = 'patient.erased'`, `table_name = 'patients'`, `record_id = p_patient_id`, `circle_id`, `actor_id`, `patient_id = null`, `old_data`/`new_data` null;
  5. deletes the patient; cascades remove everything else.
- Backups keep the data until they expire (30 daily / 12 monthly) — documented in security-and-privacy §7.
- The app shows a two-step confirm ("type the patient's name") and says it cannot be undone and that backups expire within a year.
- Fix in the same migration: audit rows for **cascaded child deletes outside an erase** (e.g. deleting a medication deletes its regimens and dose log) must keep `patient_id`/`circle_id` — today the parent row is already gone when the child's audit trigger runs. The parent's `BEFORE DELETE` trigger stashes its patient id in a transaction-local setting that the child audit functions fall back to.

## Export
- **Who:** owners and caregivers. Viewers cannot.
- **Where:** the phone, from the local synced data (all of that patient's rows in `patients`, `patient_info`, `medications`, `regimens`, `dose_log`, `stock_movements`, `appointments`). Server-only operational tables (`meds.escalations`, `meds.stock_alerts`) and the audit log are not part of a patient export.
- **Formats** (one action, user picks):
  - **JSON** — `{"format":"family-care-patient-export","version":1,"exported_at","patient":{…},"patient_info":{…},"medications":[{…,"regimens":[…],"stock_movements":[…]}],"dose_log":[…],"appointments":[…]}`, column names as in the database.
  - **PDF** — a readable summary for a doctor: name, allergies (prominent), conditions, SNS number, family doctor, emergency contacts; active medications with schedule and current stock; stopped medications; upcoming and last 12 months of appointments; dose log of the last 90 days (date, time, medication, taken/skipped). Generated with Android's `PdfDocument`, A4, in the app's language.
- Files are written to the app's cache (`cache/exports/`), shared through a `FileProvider` with `ACTION_SEND`, and deleted the next time an export starts or the app starts.
- **Logged** (security-and-privacy §5): before generating, the app calls `core.log_patient_export(p_patient_id uuid, p_format text)` (`'json'|'pdf'`), which writes a `security` audit row (`action = 'patient.exported'`, `new_data = {"format": …}`, no health data). Export therefore needs a connection; offline the app says so.

## Activity (owner only)
- More → Activity, visible to owners. Online (the audit log is not synced).
- `core.circle_activity(p_circle_id uuid, p_before timestamptz default null, p_limit int default 50)` returns rows `(id, occurred_at, event_kind, action, table_name, record_id, actor_id, actor_name, patient_id, patient_name, label)`, newest first, `occurred_at < p_before` for paging, `p_limit` capped at 200. Owner of the circle only (`core.can_circle(p_circle_id, 'manage')`), else error `42501`.
  - Includes the circle's audit rows **and** the security events (`auth.sign_in`, `auth.session_ended`) of the circle's current members (those rows have no `circle_id`).
  - `label` is a short, non-clinical identifier derived server-side: medication name, appointment title or kind, patient display name, member name — never doses, notes, allergies or other field values. `old_data`/`new_data` are never returned.
- The app maps `(table_name, action)` to localized sentences: "Leonardo added medication Metformin for Avó", "Mãe signed in", "Leonardo exported Avó's data (PDF)", "Leonardo erased a patient".

## Account
- More → Account gets **"Sign out of all devices"** (Supabase global sign-out: revokes every refresh token of the user). Account deletion stays out of the MVP (an owner removes a member instead).
- More → About shows the app version (`versionName (versionCode)`).
