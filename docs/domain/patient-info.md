# Domain: Patient info

Feature module (`android/feature/patient-info`, schema `info`). Depends only on the core — D5. Decision: D29. Text only; the basis for a future emergency card.

## What it holds (one record per patient)
- **Allergies** — a list of short entries, always visible as a banner on the patient profile.
- **Conditions** — a list of short entries, shown under the banner.
- **SNS number** (Portuguese national health service user number, 9 digits).
- **Family doctor** (name), **health centre**, and its **phone**.
- **Emergency contacts** — up to 5: name, relationship, phone.
- Free notes.

## Table `info.patient_info`

| Column | Type | Rules |
|---|---|---|
| `id` | uuid pk | **equals `patient_id`** (one row per patient; a deterministic id means two phones creating it at once converge on one row) |
| `patient_id` | uuid unique → `core.patients` on delete cascade | immutable |
| `allergies` | jsonb array of strings | ≤ 30 entries, each 1..100 chars, default `[]` |
| `conditions` | jsonb array of strings | ≤ 30 entries, each 1..200 chars, default `[]` |
| `sns_number` | text null | exactly 9 digits |
| `family_doctor`, `health_centre` | text null | ≤ 200 |
| `health_centre_phone` | text null | ≤ 30, digits, spaces, `+`, `-`, `(`, `)` |
| `emergency_contacts` | jsonb array of `{name, relationship, phone}` | ≤ 5; name 1..100 required, relationship ≤ 50 optional, phone 1..30 required (same charset) |
| `notes` | text null | ≤ 2000 |
| `created_at`, `updated_at`, `updated_by` | | `updated_by` set by trigger to `auth.uid()` |

Access: read = any member; insert/update = `write`; delete = `manage` (and cascade with the patient). Edits in place, last write wins (two people editing the same patient's info at the same moment is not a realistic conflict); every change audited (D14). Synced (stream `patient_info`).

## Screens
- **Patient profile header:** allergies banner (red, always visible when non-empty; "No known allergies" is not shown — absence is not a claim), conditions line.
- **Patient profile → Info** tab: SNS number, family doctor / health centre (tap to call), emergency contacts (tap to call), notes; "Edit" for owners/caregivers.
- No timeline entries (info edits are not clinical events).
