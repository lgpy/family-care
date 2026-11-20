# UX: Screens and Daily Use

Colour, type, sizing and the shared components: [design-system.md](design-system.md).
The approved visual reference is [mockups/scaling.html](mockups/scaling.html).

Wireframes are rough sketches to validate the domain model, not visual design.

## Navigation

```
[ Today ]  [ Patients ]  [ Agenda ]  [ Shopping ]  [ More ]
```

## 1. Today (dashboard)

Sorted by urgency, not by module. Each section is a card contributed by a module.

```
┌──────────────────────────────────────────┐
│ Today · Mon 16 Nov        [All ▾ patients]│
├──────────────────────────────────────────┤
│ ⚠ NEEDS ATTENTION                         │
│  Grandma · 08:00 · 4 meds · not confirmed │
│     (escalated to you at 08:30)  [Give ✓] │
│  Metformin (Grandpa) · 5 days left        │
├──────────────────────────────────────────┤
│ 💊 DOSES TODAY                            │
│  08:00 ✓ Grandpa (3)   by Mom 08:04       │
│  13:00 ○ Grandma (2)            [Give ✓]  │
│  21:00 ○ You (1) · Mom (2) · Grandma (1)  │
├──────────────────────────────────────────┤
│ 📅 NEXT 7 DAYS                            │
│  Wed · Grandpa · Blood test · 08:30 fast! │
│  Fri · Mom · Cardiology · Hosp. Santa X   │
└──────────────────────────────────────────┘
```

## 2. Dose confirmation

- **"Give ✓"** confirms all doses of that patient's time slot in one tap.
- Opening the slot allows exceptions (e.g. one pill refused → skipped, reason "refused").
- Recording "given by someone else" is available.
- **Notification actions:** "Given ✓" and "Snooze 15 min", usable without opening the app.

## 3. Patient profile

```
Grandma Maria                       [⋯]
🔴 Allergies: Penicillin · Ibuprofen     ← always visible
Conditions: Hypertension, Type 2 diabetes
[ Medications ] [ Appointments ] [ Info ] [ History ]
```

- **Info:** emergency contacts, SNS number, family doctor. Basis for a future emergency card.
- **History:** timeline filled by all modules (regimen changes, appointments, later measurements and documents).

## 4. Add medication (one screen)

Pick the medicine from the catalogue (or type it), check the drawn pill look, tap the times of day, then **type** how much is taken each time and, optionally, how many there are now (D34). The amount is drawn in that same pill look — one pill per whole unit plus a partly filled pill, so 1¼ is a pill and a quarter — and the count shows how many days it lasts at those amounts, which is what the low-stock warning uses. Quick chips cover the usual answers: ½, 1 or 2 per dose, and a whole pack for the count.

Dates, an amount per time, the low-stock threshold in days (default 7) and who is reminded (inherited from the patient) stay under **More options** with defaults.

## 5. Shopping

```
NEEDS PRESCRIPTION FIRST
  ☐ Metformin 850mg (Grandpa) · 5 days left  [Request from GP]
READY TO BUY
  ☐ Atorvastatin 20mg (Mom) · 9 days left · prescription valid until 03/2027
```

Ticking an item asks "How many did you buy?" and records a refill in the stock ledger.

## 6. Agenda

- Appointments across all patients as a list, filterable by patient. Month view in v2.
- Each appointment: type (consultation, blood test, exam, checkup), place, doctor, preparation notes (e.g. fasting, bring previous results), reminders (evening before, 1 hour before), follow-up.

## 7. More

Circle members and invitations · reminder policy per patient · language · data export.

## Walkthrough: one morning

1. **08:00** — Grandma's 4 medications are due. Policy *primary + escalation*, primary = Mom. Only Mom's phone is reminded. She's out; nothing confirmed.
2. **08:30** — The server sees the slot unconfirmed past the escalation delay and pushes to me: "A dose needs attention" (no health data in the text).
3. **08:35** — I give the pills; Grandma refuses one. I log 3 taken + 1 skipped (refused), given by me.
4. **08:35** — Stock decreases by 3. Metformin falls to 5 days (< 7) → `StockLow` → shown on Today for both of us; shopping list shows "Needs prescription first".
5. **09:10** — Mom opens the app. Her notification is gone; Today shows "Given by you at 08:35".

### What the walkthrough added to the model
- Group confirmation per time slot (UI only; storage stays per medication).
- "Given by someone else".
- Separate settings for escalation delay and missed window.
- Snooze is local only and doesn't delay escalation.
