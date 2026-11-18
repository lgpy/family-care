# Domain: Appointments

Feature module (`android/feature/appointments`, schema `appointments`). Depends only on the core (patients, access, notifications, audit) — D5. Decision: D29.

## Appointment — what it is
- Patient, **kind** (`consultation`, `blood_test`, `exam`, `checkup`, `other`), optional **title** (e.g. "Cardiologia", "Análises ao sangue").
- **When:** local wall-clock start (`starts_local`, minute precision) + IANA `time_zone` — the same convention as regimens, so a trip abroad or a DST change never moves an appointment. Optional duration in minutes.
- Place, doctor, **preparation** (e.g. "fasting from 22:00, bring previous results"), notes. Text only.
- Optional **companion**: the owner/caregiver who takes the patient. Decides who gets the reminders.
- Optional **follow-up of**: the earlier appointment this one follows up ("schedule follow-up" pre-fills a new appointment from a past one).
- **Cancelled** is a timestamp (`cancelled_at`), not a delete: the history keeps it. Past = derived (`starts_local` before now in its zone).
- Edits are in place (unlike regimens, nothing derived depends on an appointment's history); the audit log keeps every change (D14).

## Table `appointments.appointments`

| Column | Type | Rules |
|---|---|---|
| `id` | uuid pk | client-generated |
| `patient_id` | uuid → `core.patients` on delete cascade | immutable |
| `kind` | enum `appointments.appointment_kind` | |
| `title` | text null | ≤ 200 |
| `starts_local` | timestamp (no zone) | whole minute |
| `time_zone` | text | in `pg_timezone_names` |
| `duration_minutes` | int null | 1..1440 |
| `place`, `doctor` | text null | ≤ 200 each |
| `preparation` | text null | ≤ 1000 |
| `notes` | text null | ≤ 2000 |
| `companion_user_id` | uuid null | must be an owner/caregiver of the patient's circle |
| `follow_up_of` | uuid null → `appointments.appointments` on delete set null | same patient, not itself |
| `cancelled_at` | timestamptz null | |
| `created_at`, `created_by` | timestamptz, uuid default `auth.uid()` | `created_by` = caller when authenticated |
| `updated_at`, `updated_by` | timestamptz, uuid | set by trigger on update (`updated_by` = `auth.uid()`) |

Access: read = any member (`core.can(patient_id,'read')`); insert/update = `write` (owner/caregiver); delete = `manage` (owner). Synced to the circle's members (stream `appointments`).

## Reminders (local, D4/D19)
- Two per appointment: **20:00 local on the day before**, and **1 hour before** the start. A reminder whose instant is already past is skipped. Cancelled appointments have none.
- On the phone of the **companion** if set; otherwise on every owner's and caregiver's phone. Never on a viewer's phone.
- Alarm-clock alarms like dose reminders, horizon 8 days, rescheduled on app start, sync changes, boot, time/zone change and daily.
- Notification channel `appointment_reminders`, private on the lock screen (D26): public text is generic ("Appointment reminder"); the private text names patient, title/kind, time and place.

## Change push (D4, D27 pattern)
- After an insert, or an update that changes `starts_local`, `time_zone`, `place`, `companion_user_id` or `cancelled_at`, for an appointment that is not more than a day in the past: a pg_net trigger posts the appointment id to the `appointment-changed` edge function.
- Recipients: the circle's owners and caregivers **except the person who made the change**. Generic text per recipient language: "An appointment was added or changed" / "Uma consulta foi marcada ou alterada". Payload `type=appointment_changed` only. Channel `appointment_updates`; tapping opens the Agenda.

## Screens
- **Agenda** tab: upcoming appointments across all patients (grouped by day), filter by patient, "Show past" toggle. Month view is v2.
- **Patient profile → Appointments** tab: that patient's upcoming + past.
- Appointment detail/edit, "Cancel appointment", "Schedule follow-up".
- **Today:** "Next 7 days" card (dashboard order 200).
- **History** timeline entries: each appointment (past and upcoming), cancelled ones marked.
