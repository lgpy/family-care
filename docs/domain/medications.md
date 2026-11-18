# Domain: Medications

Feature module. Depends only on the core (patients, access, notifications, audit).

## Overview

```
Medication ──< Regimen (versioned) ──> dose slots (computed)
    │                                       │
    │                                       ▼
    └──< Stock movements  <── dose taken ── Dose log (taken / skipped)
```

## Medication — what it is
- Patient, name, active ingredient, strength (e.g. 500 mg), form (tablet, drops, injection…).
- **Stock unit:** what stock is counted in (tablet, ml, pen…).
- Instructions/notes (e.g. "take with food").
- `requires_prescription` flag; optional current prescription validity date.
- Low-stock threshold **in days** (default 7).
- Optional reminder-policy override (see [care-circle](care-circle.md)).

## Regimen — when and how much
- MVP schedule types:
  - fixed times every day
  - specific weekdays at fixed times
  - every N days at fixed times
- Start date, optional end date (treatment duration).
- **Quantity per time slot**, which can differ (e.g. 1 at 08:00, ½ at 21:00). Fractions allowed.
- Times stored as **local wall-clock time + time zone** (e.g. 08:00 Europe/Lisbon).
- **Versioned:** a regimen is never edited in place. A dose change ends the current regimen and starts a new one. History stays accurate ("5 mg until 3 March, 10 mg since").
- Deferred: "as needed" (PRN), tapering, every-N-hours.

## Dose slots — when each dose is due
- **Computed from the regimen, not stored.**
- The phone computes the next few days to schedule local reminders.
- The server computes the same slots to detect unconfirmed doses and escalate.
- A slot is identified by `(regimen_id, scheduled_local_datetime)`.

## Dose log — what happened
Each entry:
- slot identity (regimen + scheduled time)
- outcome: `taken` or `skipped` (+ reason: refused, not needed, unavailable, other)
- actual time, actual quantity
- confirmed by (user), given by (user / "someone else" + optional name)

Rules:
- **Append-only.** Corrections are new entries, not edits.
- **"Missed" is derived, never stored:** a slot past the missed window with no log entry. A late-syncing "taken" from an offline phone therefore simply resolves the slot. No conflict.
- **Double confirmation:** two entries for one slot show as one dose confirmed twice. Before saving, the app warns: "Already given by Mom at 08:04. Did you give another one?" (safety check against double dosing).
- **Group confirmation:** the UI confirms all doses of a patient's time slot in one tap; storage still has one entry per medication.

## Dose status (derived)

| Status | Condition |
|---|---|
| upcoming | before scheduled time |
| due | scheduled time passed, within escalation delay |
| overdue | past escalation delay, not confirmed (triggers escalation if policy says so) |
| missed | past missed window, not confirmed |
| taken / skipped | log entry exists |

## Stock — a ledger
Current stock = the latest stocktake + what came in − what went out since (D28):

| Movement | Effect | Source |
|---|---|---|
| count | sets the baseline ("there are N now") | Opening balance in the medication form, or a recount |
| refill | + | Shopping list purchase or manual entry |
| discard | − | Expired / lost (reason) |
| dose | − | **Not a row:** the effective `taken` quantities in the dose log after the latest count |

- Never a single number overwritten by two phones.
- **Days remaining** = current stock ÷ scheduled daily consumption from the active regimen (exact rules: `shared/test-vectors/README.md`).
- **Low stock** when days remaining ≤ threshold → dashboard card + shopping list entry + one push per low episode to owners and caregivers.

## Refills and shopping list
- A low-stock medication appears on the shopping list:
  - **Needs prescription first** if `requires_prescription` and the prescription is missing or expired → task "request prescription from GP".
  - **Ready to buy** otherwise (shows prescription validity).
- Ticking an item asks "How many did you buy?" and creates a `refill` stock movement.
- Portugal context: e-prescriptions have validity dates; refill lead time must include obtaining a new one.

## Events emitted
- `DoseConfirmed`, `DoseOverdue`, `StockLow`, `RegimenChanged`, `MedicationStopped`.

## Extension-point contributions
- Dashboard: "Needs attention" items, "Doses today", "Low stock".
- Tasks: request prescription, buy medication.
- Timeline: regimen started/changed/stopped.
- Notifications: dose reminder, escalation, low stock.
