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
One-time invitation code to join a circle with a given role, bound to the invitee's email address. Shared as text through any messaging app (D23); accepting requires signing in with that Google account. Hashed server-side; expires; single use. No email provider needed.

Removing a member (D24) clears them as `primary_caregiver` and `linked_user` of the circle's patients. Affected patients show "needs a primary caregiver" — and refuse to save in the form — until someone chooses a new one. The server side lives in `core.remove_member`.

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

## Known limitations (M2)

Deliberately deferred; kept here so they are not lost.

- **One circle per user in the app.** All queries use the user's oldest membership (tie-broken by membership id). Accepting a second invitation is not supported in the UI; the server would keep both memberships and the app would keep showing the first circle.
- **Viewer role and role changes are not exposed in the UI.** The database and RPCs model `viewer` and `core.set_member_role`; the app only displays roles and never offers a change.
- **Reminder-policy settings live in the patient form**, not under More. The More screen has the family circle, the user's name, account, language, app lock and About.
- **No leave or delete circle.** Removing the last member is a server-side concern for a later milestone; the app has no "leave circle" action.
- **Audit rows with no circle are not visible to the owner policy.** Profile changes and security events carry no circle id, so the owner-facing audit view cannot see them. To be resolved with the Activity screen in M7.
- **Language is a per-account preference with a device half** (D8): More changes the phone's app language and writes `profiles.preferred_language`; without an in-app choice, the phone's language is written to the profile (never the other way round), so notifications use the language the user sees.
