/**
 * Stock derivation (M5, D28): current stock from the append-only stock ledger
 * and the dose log, the active regimen's scheduled daily consumption, days
 * remaining, low stock and the shopping-list status.
 *
 * The contract is `shared/test-vectors/stock.json`; the rules are written out in
 * `shared/test-vectors/README.md` ("stock.json (M5)"). The same rules are
 * implemented in the Kotlin app (D22). Pure: no clock, no database, no network -
 * `today` is a parameter, so every rule can be pinned by the shared vectors.
 *
 * Summary of the rules:
 * - The **baseline** is the `count` with the latest `occurred_at` (ties: the
 *   greater id wins); without a count it is 0 at the beginning of time.
 * - Stock = baseline + refills - discards after it - the **effective** `taken`
 *   quantities with `actual_at` after it. Movements and doses at or before the
 *   baseline instant are already inside the count.
 * - An entry named by another entry's `corrects_entry_id` is void; every
 *   non-void `taken` entry deducts (a double confirmation deducts twice).
 * - A `taken` entry without `actual_quantity` deducts its slot's regimen
 *   quantity (0 when the time is not in the regimen).
 * - Stock is not clamped: it can go negative, and the UI shows `max(stock, 0)`.
 * - Days remaining uses the active regimen and multiplies before dividing.
 */

import type { Regimen } from './doseSlots.ts';
import { normalizeScheduledLocal, type DoseLogEntry } from './doseStatus.ts';

export type StockMovementKind = 'count' | 'refill' | 'discard';

/** The `meds.stock_movements` shape the vectors use. */
export interface StockMovement {
  id: string;
  kind: StockMovementKind;
  /** In the medication's stock unit; a count may be 0, refill/discard are > 0. */
  quantity: number;
  /** ISO-8601 UTC instant. */
  occurred_at: string;
}

/** The medication fields the stock rules need. */
export interface StockMedication {
  low_stock_threshold_days: number;
  requires_prescription: boolean;
  /** ISO local date, inclusive: valid through this day. */
  prescription_valid_until: string | null;
}

export type ShoppingStatus = 'needs_prescription' | 'ready_to_buy';

/** Three decimals, like `numeric(9,3)`, so sums do not carry binary dust. */
function round3(value: number): number {
  return Math.round(value * 1000) / 1000;
}

/**
 * The baseline count: the one with the latest `occurred_at`, ties broken by the
 * greater id (identical instants must still be deterministic).
 */
export function latestCount(movements: StockMovement[]): StockMovement | null {
  let baseline: StockMovement | null = null;
  for (const movement of movements) {
    if (movement.kind !== 'count') continue;
    if (baseline === null) {
      baseline = movement;
      continue;
    }
    const candidate = Date.parse(movement.occurred_at);
    const current = Date.parse(baseline.occurred_at);
    if (
      candidate > current ||
      (candidate === current && movement.id > baseline.id)
    ) {
      baseline = movement;
    }
  }
  return baseline;
}

/** Ids named by another entry's `corrects_entry_id`: void, never counted. */
export function voidedEntryIds(entries: DoseLogEntry[]): Set<string> {
  const voided = new Set<string>();
  for (const entry of entries) {
    if (entry.corrects_entry_id) voided.add(entry.corrects_entry_id);
  }
  return voided;
}

/** The regimen quantity of the entry's slot, for a `taken` entry with no amount. */
export function slotQuantity(
  entry: DoseLogEntry,
  regimens: Regimen[],
): number {
  const regimen = regimens.find((candidate) => candidate.id === entry.regimen_id);
  if (!regimen) return 0;
  const time = normalizeScheduledLocal(entry.scheduled_local).slice(11, 16);
  const slot = regimen.times.find((candidate) => candidate.time === time);
  return slot ? slot.quantity : 0;
}

/**
 * Current stock for one medication.
 *
 * @param movements The medication's movements (any order; other medications'
 *   rows must be filtered out by the caller).
 * @param doseLog The medication's dose-log entries; corrections may never
 *   share a slot with the entry they void, so pass every relevant entry.
 * @param regimens Every regimen version of the medication (older versions may
 *   be the one a late dose belonged to).
 */
export function currentStock(
  movements: StockMovement[],
  doseLog: DoseLogEntry[],
  regimens: Regimen[] = [],
): number {
  const baseline = latestCount(movements);
  const baselineMs = baseline === null ? -Infinity : Date.parse(baseline.occurred_at);

  let stock = baseline === null ? 0 : baseline.quantity;

  for (const movement of movements) {
    if (movement === baseline) continue;
    // "After the baseline": at or before it is already reflected in the count.
    if (Date.parse(movement.occurred_at) <= baselineMs) continue;
    if (movement.kind === 'refill') stock += movement.quantity;
    else if (movement.kind === 'discard') stock -= movement.quantity;
  }

  const voided = voidedEntryIds(doseLog);
  for (const entry of doseLog) {
    if (voided.has(entry.id)) continue;
    if (entry.outcome !== 'taken') continue;
    if (Date.parse(entry.actual_at) <= baselineMs) continue;
    stock -= entry.actual_quantity ?? slotQuantity(entry, regimens);
  }

  return round3(stock);
}

/**
 * The regimen that applies on `today`: `start_date <= today <= end_date` (no
 * end date = ongoing). With several, the latest `start_date` wins; a tie is
 * broken by id so the answer is deterministic.
 */
export function activeRegimen(
  regimens: Regimen[],
  today: string,
): Regimen | null {
  let active: Regimen | null = null;
  for (const regimen of regimens) {
    if (regimen.start_date > today) continue;
    if (regimen.end_date !== null && regimen.end_date < today) continue;
    if (
      active === null || regimen.start_date > active.start_date ||
      (regimen.start_date === active.start_date && regimen.id > active.id)
    ) {
      active = regimen;
    }
  }
  return active;
}

/** ISO bitmask weekday count: Monday = 1 ... Sunday = 64. */
export function popcount(bitmask: number): number {
  let count = 0;
  let remaining = bitmask;
  while (remaining > 0) {
    count += remaining & 1;
    remaining >>= 1;
  }
  return count;
}

/** The active regimen's scheduled daily consumption in stock units. */
export function dailyConsumption(regimen: Regimen | null): number {
  if (regimen === null) return 0;

  let quantity = 0;
  for (const time of regimen.times) quantity += time.quantity;

  switch (regimen.schedule_type) {
    case 'daily':
      return quantity;
    case 'weekdays': {
      // A bitmask of 0 is rejected by the CHECK constraint; treat it as no
      // consumption rather than dividing by zero.
      const days = popcount(regimen.weekdays ?? 0);
      return days === 0 ? 0 : (quantity * days) / 7;
    }
    case 'every_n_days':
      return regimen.interval_days && regimen.interval_days > 0
        ? quantity / regimen.interval_days
        : 0;
    default:
      // Unknown schedule types are a test failure, not a default.
      throw new Error(
        `unknown schedule type: ${String((regimen as { schedule_type?: unknown }).schedule_type)}`,
      );
  }
}

/**
 * Whole days of stock left, or null when nothing is scheduled.
 *
 * `floor(stock × P / (Q × K))` with P the period in days and K the occurrences
 * per period. Multiplying first matters: `18 × 7 / 3` is exactly 42, while
 * `18 / (3/7)` can floor to 41 in floating point.
 */
export function daysRemaining(
  stock: number,
  regimen: Regimen | null,
): number | null {
  if (regimen === null) return null;

  let quantity = 0;
  for (const time of regimen.times) quantity += time.quantity;

  let periodDays: number;
  let occurrences: number;
  switch (regimen.schedule_type) {
    case 'daily':
      periodDays = 1;
      occurrences = 1;
      break;
    case 'weekdays': {
      periodDays = 7;
      occurrences = popcount(regimen.weekdays ?? 0);
      break;
    }
    case 'every_n_days':
      periodDays = regimen.interval_days ?? 0;
      occurrences = 1;
      break;
    default:
      throw new Error(
        `unknown schedule type: ${String((regimen as { schedule_type?: unknown }).schedule_type)}`,
      );
  }

  // Consumption of 0 -> unknown, never "0 days".
  if (quantity === 0 || occurrences === 0 || periodDays === 0) return null;
  // Empty or overdrawn stock is out of stock today.
  if (stock <= 0) return 0;

  return Math.floor((stock * periodDays) / (quantity * occurrences));
}

/** Low stock when the days left are known and at or below the threshold. */
export function isLow(
  days: number | null,
  lowStockThresholdDays: number,
): boolean {
  return days !== null && days <= lowStockThresholdDays;
}

/**
 * What the shopping list says for a low medication (D28): "needs prescription
 * first" when a prescription is required and its validity is missing or before
 * today (valid through the date itself), otherwise "ready to buy". Not low means
 * no entry.
 */
export function shoppingStatus(
  medication: StockMedication,
  low: boolean,
  today: string,
): ShoppingStatus | null {
  if (!low) return null;
  const validUntil = medication.prescription_valid_until;
  const expired = validUntil === null || validUntil < today;
  return medication.requires_prescription && expired
    ? 'needs_prescription'
    : 'ready_to_buy';
}

export interface StockSummary {
  stock: number;
  daily_consumption: number;
  days_remaining: number | null;
  low: boolean;
  shopping_status: ShoppingStatus | null;
  /** The regimen the consumption came from (null when none is active). */
  regimen: Regimen | null;
}

/** Everything the dashboard, the shopping list and the daily job need. */
export function stockSummary(
  movements: StockMovement[],
  doseLog: DoseLogEntry[],
  regimens: Regimen[],
  medication: StockMedication,
  today: string,
): StockSummary {
  const regimen = activeRegimen(regimens, today);
  const stock = currentStock(movements, doseLog, regimens);
  const days = daysRemaining(stock, regimen);
  const low = isLow(days, medication.low_stock_threshold_days);

  return {
    stock,
    daily_consumption: dailyConsumption(regimen),
    days_remaining: days,
    low,
    shopping_status: shoppingStatus(medication, low, today),
    regimen,
  };
}
