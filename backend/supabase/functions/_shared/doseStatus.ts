/**
 * Derived dose status, ported from the Kotlin app
 * (`android/feature/medications/domain/DoseStatus.kt`) for the escalation job
 * (D22). The contract is `shared/test-vectors/dose-status.json`; the boundary
 * rules and correction semantics are in `shared/test-vectors/README.md`.
 *
 * States: `upcoming`, `due`, `overdue`, `missed`, `taken`, `skipped`.
 *
 * With `s = slot.instant`, `e = escalation delay`, `m = missed window`:
 * - `now < s` -> upcoming; `now == s` -> due.
 * - `now <= s + e` -> due; one second more -> overdue.
 * - `now <= s + m` -> overdue; one second more -> missed.
 * - An effective entry always wins over the clock (a late confirmation is
 *   `taken`, not `missed`).
 *
 * An entry named by another entry's `corrects_entry_id` is void and never
 * counts; the correcting entry counts. With several effective entries the
 * latest by `actual_at` (ties by id) decides taken vs skipped, and the count is
 * reported so the app can say "confirmed twice".
 */

import type { DoseSlot } from './doseSlots.ts';

export type DoseOutcome = 'taken' | 'skipped';

/** The `meds.dose_log` shape the vectors use (snake_case, server values). */
export interface DoseLogEntry {
  id: string;
  regimen_id: string;
  medication_id: string;
  scheduled_local: string;
  outcome: DoseOutcome;
  actual_at: string;
  corrects_entry_id: string | null;
  skip_reason?: string | null;
  note?: string | null;
  actual_quantity?: number | null;
  confirmed_by?: string | null;
  given_by_user_id?: string | null;
  given_by_other?: boolean;
  given_by_name?: string | null;
}

export type DoseState =
  | 'upcoming'
  | 'due'
  | 'overdue'
  | 'missed'
  | 'taken'
  | 'skipped';

export interface DoseStatus {
  state: DoseState;
  confirmation_count: number;
  confirmed_twice: boolean;
  /** Effective entries, oldest first (the vector contract does not include it). */
  effective_entries: DoseLogEntry[];
}

/** The slot identity is `(regimen_id, scheduled_local)`, at minute precision. */
export function normalizeScheduledLocal(value: string): string {
  // "2026-11-16T08:00:00" (PostgREST timestamptz-less output) and
  // "2026-11-16T08:00" (vectors) are the same slot.
  return value.length >= 16 ? value.slice(0, 16) : value;
}

export function sameSlot(
  regimenId: string,
  scheduledLocal: string,
  entry: DoseLogEntry,
): boolean {
  return entry.regimen_id === regimenId &&
    normalizeScheduledLocal(entry.scheduled_local) ===
      normalizeScheduledLocal(scheduledLocal);
}

export function effectiveEntries(
  regimenId: string,
  scheduledLocal: string,
  entries: DoseLogEntry[],
): DoseLogEntry[] {
  const voided = new Set(
    entries.map((entry) => entry.corrects_entry_id).filter((id): id is string => !!id),
  );
  return entries
    .filter((entry) =>
      sameSlot(regimenId, scheduledLocal, entry) && !voided.has(entry.id)
    )
    .sort((a, b) => {
      const byActual = Date.parse(a.actual_at) - Date.parse(b.actual_at);
      if (byActual !== 0) return byActual;
      return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
}

export interface DoseStatusOptions {
  escalationDelayMinutes: number;
  missedWindowMinutes: number;
}

/**
 * The status of one slot at `now`.
 *
 * @param entries Entries for any slot; only this slot's identity is considered.
 * @param now Instant as an ISO string or epoch milliseconds.
 */
export function doseStatus(
  slot: DoseSlot,
  entries: DoseLogEntry[],
  now: string | number,
  options: DoseStatusOptions,
): DoseStatus {
  const { escalationDelayMinutes, missedWindowMinutes } = options;
  if (escalationDelayMinutes < 0) {
    throw new Error('An escalation delay cannot be negative');
  }
  if (missedWindowMinutes < 0) {
    throw new Error('A missed window cannot be negative');
  }

  const effective = effectiveEntries(
    slot.regimen_id,
    slot.scheduled_local,
    entries,
  );
  if (effective.length > 0) {
    const deciding = effective[effective.length - 1];
    if (deciding.outcome !== 'taken' && deciding.outcome !== 'skipped') {
      throw new Error(`unknown dose outcome: ${String(deciding.outcome)}`);
    }
    return {
      state: deciding.outcome,
      confirmation_count: effective.length,
      confirmed_twice: effective.length > 1,
      effective_entries: effective,
    };
  }

  const nowMs = typeof now === 'number' ? now : Date.parse(now);
  const slotMs = Date.parse(slot.instant);
  if (!Number.isFinite(nowMs)) throw new Error(`not an instant: ${String(now)}`);

  const escalationEndMs = slotMs + escalationDelayMinutes * 60_000;
  const missedEndMs = slotMs + missedWindowMinutes * 60_000;

  let state: DoseState;
  if (nowMs < slotMs) {
    state = 'upcoming';
  } else if (nowMs > missedEndMs) {
    // Checked before the escalation delay: a missed window shorter than the
    // delay still turns missed once the window is past.
    state = 'missed';
  } else if (nowMs <= escalationEndMs) {
    state = 'due';
  } else {
    state = 'overdue';
  }

  return {
    state,
    confirmation_count: 0,
    confirmed_twice: false,
    effective_entries: [],
  };
}
