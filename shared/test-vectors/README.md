# Shared test vectors

Language-neutral fixtures for the dose-slot calculation and the derived dose
status. Two implementations must agree on them (D22):

- the Kotlin app, `android/feature/medications/domain` (M3), and
- the TypeScript server, which will compute the same slots for escalation (M4).

The app's tests read these files from the Gradle test classpath. The server
tests will read the same files, so **neither side generates them**: add a case
by hand, compute its expected values independently (paper, a zone table, a
different library), and change both implementations until they match. A vector
that was produced by dumping an implementation's output only tests that the
implementation is stable, not that it is right.

## Files

| File | What it covers |
|---|---|
| `dose-slots.json` | regimen versions → the slots due in a date range |
| `dose-status.json` | one slot + its log entries + a clock → the derived status |

## Encoding

- **Dates** are ISO-8601 local dates: `YYYY-MM-DD`.
- **Local slot times** are ISO-8601 local date-times with **minute precision and
  no zone**: `YYYY-MM-DDTHH:MM`. (A slot's identity is
  `(regimen_id, scheduled_local)`; it never carries an offset.)
- **Instants** are ISO-8601 UTC with a trailing `Z` and seconds:
  `YYYY-MM-DDTHH:MM:SSZ`.
- **Quantities** are JSON **numbers** in plain decimal notation with at most
  three decimal places, never strings and never exponent form: `1`, `0.5`,
  `0.125`. `1` and `1.0` are the same quantity; compare numerically, not as
  text. The database column is `numeric(6,3)` and the valid range is
  `0 < quantity ≤ 100`.
- Enums use the server values: `daily` / `weekdays` / `every_n_days`,
  `taken` / `skipped`, `refused` / `not_needed` / `unavailable` / `other`.
- Unknown fields must be ignored by a reader; unknown enum values are a test
  failure, not a default. The point of the vectors is to catch silent drift.

## `dose-slots.json`

`cases[]`:

| Field | Meaning |
|---|---|
| `name` | Unique, descriptive, stable (referenced in failures). |
| `regimens[]` | Regimen versions, already normalized (times sorted, quantities plain). |
| `from`, `to` | Local dates, **both inclusive**. |
| `expected[]` | Every slot, in the required order (below). |

A regimen object:

| Field | Meaning |
|---|---|
| `id`, `medication_id` | String ids; the medication is only carried through to the slot. |
| `schedule_type` | `daily`, `weekdays`, `every_n_days`. |
| `weekdays` | ISO bitmask, Monday = 1, Tuesday = 2, …, Sunday = 64; `1..127`; `null` unless `weekdays`. |
| `interval_days` | `2..365`, anchored on `start_date`; `null` unless `every_n_days`. |
| `start_date`, `end_date` | Local dates in `time_zone`; `end_date` inclusive or `null`. |
| `time_zone` | IANA zone id, e.g. `Europe/Lisbon`. |
| `times[]` | `{ "time": "HH:MM", "quantity": number }`, 1–12 entries, unique times, sorted. |

An expected slot: `regimen_id`, `scheduled_local`, `instant`, `quantity`.

### Rules the vectors assume

- **Schedule:** `weekdays` occurs when the bit for the date's ISO weekday is
  set. `every_n_days` occurs when
  `(date − start_date) in days % interval_days == 0`; the anchor is the start
  date, not the range start.
- **Bounds:** a regimen applies from `start_date` through `end_date`, both
  inclusive. Dates outside produce nothing.
- **Regimen dates are local dates in the regimen's `time_zone`.**
- **Slot identity is the local wall-clock time.** Each configured time on a
  qualifying date produces exactly one slot, even when a DST change makes two
  wall-clock times resolve to the same instant.
- **Ordering:** by `instant`, then `medication_id`, then `scheduled_local`, then
  `regimen_id`. (The first key is what the product needs; the rest make ties
  deterministic.)
- **Gap (spring forward):** a wall-clock time that does not exist is moved
  **forward by the length of the gap**. `Europe/Lisbon` 2026-03-29 01:30 (WET →
  WEST at 01:00, one-hour gap) becomes 02:30+01:00, so its instant is
  `2026-03-29T01:30:00Z`. Its `scheduled_local` stays `2026-03-29T01:30`.
- **Overlap (fall back):** an ambiguous wall-clock time uses the **earlier
  offset**. `Europe/Lisbon` 2026-10-25 01:30 (WEST → WET at 02:00) is
  01:30+01:00, so its instant is `2026-10-25T00:30:00Z`, not `01:30:00Z`.
- **Zones without DST in 2026** (e.g. `America/Sao_Paulo`, UTC−3) simply use
  their fixed offset; the vectors include one to catch a hard-coded Lisbon.

The Lisbon transitions in 2026 are 29 March 01:00 WET → 02:00 WEST and
25 October 02:00 WEST → 01:00 WET.

## `dose-status.json`

Top-level `escalation_delay_minutes` and `missed_window_minutes` are the
defaults (a patient's settings; 30 and 120 minutes). A case may override
either. A case has:

| Field | Meaning |
|---|---|
| `slot` | `regimen_id`, `medication_id`, `scheduled_local`, `instant`, `quantity`. |
| `entries[]` | Zero or more dose-log entries (server field names, below). |
| `now` | The clock, an instant. |
| `expected` | `{ "state": "...", "confirmation_count": n, "confirmed_twice": bool }`. |

Entries use the `meds.dose_log` shape: `id`, `regimen_id`, `medication_id`,
`scheduled_local`, `outcome`, `skip_reason`, `note`, `actual_at`,
`actual_quantity`, `confirmed_by`, `given_by_user_id`, `given_by_other`,
`given_by_name`, `corrects_entry_id`.

States: `upcoming`, `due`, `overdue`, `missed`, `taken`, `skipped`.

### Corrections

An entry named by another entry's `corrects_entry_id` is **void** and never
counts. The correcting entry counts. Corrections are new rows; nothing is edited
(D6). If two non-void entries remain, the slot is a double confirmation.

### Boundary rules (inclusive)

With `s = slot.instant`, `e = escalation_delay_minutes`, `m =
missed_window_minutes`:

| Condition | State |
|---|---|
| `now < s` | `upcoming` |
| `now == s` | `due` |
| `now <= s + e` | `due` (so `now == s + e` is still due) |
| `now == s + e + 1s` | `overdue` |
| `now <= s + m` | `overdue` (so `now == s + m` is still overdue) |
| `now == s + m + 1s` | `missed` |
| effective entry exists | `taken` / `skipped`, whatever the clock says |

The missed window is evaluated **before** the escalation delay, so a patient
whose missed window is shorter than the escalation delay still becomes `missed`
once the window has passed (see the last case). With the documented defaults
(30 < 120) the two orders agree.

When several effective entries exist, the state is the outcome of the **latest**
by `actual_at` (ties by `id`); `confirmation_count` counts all of them and
`confirmed_twice` is `confirmation_count > 1`. The UI shows one dose, plus
"Already given by …" / "confirmed twice".

## Adding a vector

1. Pick a name that says what rule it pins (`spring-forward-lisbon…`, not
   `case-7`).
2. Compute the expected values by hand, from the zone's rules — not by running
   the code under test.
3. Append the case to the file. Keep `expected[]` in the required order.
4. Run the Kotlin tests (`./gradlew :feature:medications:domain:test` from
   `android/`) and, once it exists, the server's vector test.
5. If an implementation disagrees, fix the implementation unless the vector
   itself is wrong — and then say why in the commit.

Do not renumber or rename existing cases: failures elsewhere point at them by
name.
