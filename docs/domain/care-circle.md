# Domain: Care Circle and Access

Part of the core. Every feature module depends on it.

## Entities

```
User ──< Membership >── Care Circle ──< Patient
                (role)                    │
                                          └── linked_user (optional)
```

### User
An account that can log in. Has a preferred language and notification devices.
MVP: me and my mother.

### Patient
A person whose health is managed. **Not** an account.
- Belongs to exactly one care circle.
- Optional `linked_user`: when the patient is also a user (me, my mother). A grandparent could later claim their profile without data migration.
- Holds per-patient settings: reminder policy, primary caregiver, escalation delay.

### Care circle
The unit of sharing and isolation ("our family"). Joining a circle gives access to its patients. Circles never see each other's data.

### Membership
Links a user to a circle with a role.

| Role | Can | MVP |
|---|---|---|
| `owner` | Everything, including managing members and deleting data | ✓ (me) |
| `caregiver` | Full day-to-day use: medications, doses, appointments, patient info | ✓ (my mother) |
| `viewer` | Read-only | Modelled, not exposed |

### Patient access override (modelled, not built)
Optional per-patient grant that restricts or extends a member's access (e.g. "aunt sees only Grandparent 2"). The table exists from the start; the MVP gives every member access to every patient in the circle.

### Invitation
Invitation link to join a circle with a given role, bound to the invitee's email address. Shared through any messaging app; accepting requires signing in with that Google account. Expires; single use. No email provider needed.

## Access control

- One core function answers: *can user U perform action A on patient P?*
- Every module's Row-Level Security policies call it. Changing the permission logic changes it everywhere.
- Every table carrying patient data has a `patient_id` (directly or via its parent), so policies are uniform.

## Reminder routing

Reminder policy is a per-patient setting (D7, D11), optionally overridden per medication.

| Policy | Behaviour | Used for |
|---|---|---|
| `primary_with_escalation` | Primary caregiver is reminded. If unconfirmed after the escalation delay, other caregivers get an alert (server push). | Grandparents (primary = chosen caregiver); me and my mother (primary = ourselves — "self-managed", D11) |
| `all_caregivers` | Every caregiver is reminded. First confirmation wins; other notifications are cleared. | Available, not used by default |
| `rota` | Who is on duty depends on day/time. | Not built |

Defaults when creating a patient:
- Patient with a `linked_user` → `primary_with_escalation`, primary = the linked user (self-managed).
- Patient without a linked user → `primary_with_escalation`, primary = chosen when creating the patient.

Settings per patient (defaults to validate, Q3):
- `primary_caregiver`
- `escalation_delay` — default 30 minutes
- `missed_window` — after this, an unconfirmed dose shows as missed; default 2 hours

Notes:
- Escalation runs on the server, so it works even if the primary's phone is off.
- A phone that is offline can't have its notification cleared remotely. When opened, it shows who already confirmed the dose.
- Snooze is local to one phone, not synced, and does not delay escalation.
