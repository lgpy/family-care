/**
 * Dose-slot calculation, ported from the Kotlin app for the server's
 * escalation job (D22: "dose-slot logic is duplicated between app (Kotlin) and
 * server (TypeScript), guarded by shared test vectors").
 *
 * The rules, in full, are `shared/test-vectors/README.md`; the vectors in
 * `shared/test-vectors/dose-slots.json` are the contract. Nothing here may
 * disagree with them, and none of it reads the phone's clock or zone: a
 * regimen's dates and wall-clock times are in the regimen's own IANA zone.
 *
 * Time-zone handling without Temporal: an offset lookup through
 * `Intl.DateTimeFormat` plus the algorithm java.time (and so the Kotlin app)
 * uses for local wall times:
 *
 * - **Overlap (fall back)**: two instants map to the same wall time. Both
 *   candidates validate; the **earlier** one (the larger UTC offset) wins.
 * - **Gap (spring forward)**: neither candidate validates. The wall time is
 *   moved **forward by the length of the gap**, which is the same instant as
 *   applying the pre-transition offset to the wall time.
 *
 * Both offsets are probed a day either side of the wall time, which is enough
 * for every real transition (none is within 24 h of another).
 */

export interface RegimenTime {
  time: string; // "HH:MM"
  quantity: number;
}

export interface Regimen {
  id: string;
  medication_id: string;
  schedule_type: 'daily' | 'weekdays' | 'every_n_days';
  weekdays: number | null;
  interval_days: number | null;
  start_date: string; // "YYYY-MM-DD"
  end_date: string | null;
  time_zone: string; // IANA id
  times: RegimenTime[];
}

/** One dose due at a concrete instant (the vector shape, nothing more). */
export interface DoseSlot {
  regimen_id: string;
  medication_id: string;
  /** Wall-clock time in the regimen's zone, minute precision, no zone. */
  scheduled_local: string;
  /** ISO-8601 UTC with seconds, e.g. "2026-11-16T08:00:00Z". */
  instant: string;
  quantity: number;
}

const MS_PER_DAY = 86_400_000;

// --- Civil dates (no zone) --------------------------------------------------

interface CivilDate {
  y: number;
  m: number; // 1-12
  d: number;
}

export function parseCivilDate(value: string): CivilDate {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) throw new Error(`not a local date: ${value}`);
  const y = Number(match[1]);
  const m = Number(match[2]);
  const d = Number(match[3]);
  // Round-trip through UTC so 2026-02-30 is rejected instead of rolled over.
  const ms = Date.UTC(y, m - 1, d);
  const check = new Date(ms);
  if (
    check.getUTCFullYear() !== y ||
    check.getUTCMonth() !== m - 1 ||
    check.getUTCDate() !== d
  ) {
    throw new Error(`not a real date: ${value}`);
  }
  return { y, m, d };
}

function dateMs(date: CivilDate): number {
  return Date.UTC(date.y, date.m - 1, date.d);
}

function formatCivilDate(ms: number): string {
  const date = new Date(ms);
  const y = String(date.getUTCFullYear()).padStart(4, '0');
  const m = String(date.getUTCMonth() + 1).padStart(2, '0');
  const d = String(date.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** ISO weekday for a civil date: Monday = 1 ... Sunday = 7. */
function isoWeekday(ms: number): number {
  // getUTCDay(): Sunday = 0. Shift so Monday = 1, Sunday = 7.
  return ((new Date(ms).getUTCDay() + 6) % 7) + 1;
}

function parseClock(value: string): { h: number; mi: number } {
  const match = /^(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error(`not a wall-clock time: ${value}`);
  const h = Number(match[1]);
  const mi = Number(match[2]);
  if (h > 23 || mi > 59) throw new Error(`not a wall-clock time: ${value}`);
  return { h, mi };
}

function formatScheduledLocal(ms: number, time: string): string {
  return `${formatCivilDate(ms)}T${time}`;
}

// --- Zone offsets -----------------------------------------------------------

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatterFor(zone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(zone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatters.set(zone, formatter);
  }
  return formatter;
}

interface ZonedParts extends CivilDate {
  h: number;
  mi: number;
  s: number;
}

function partsAt(zone: string, utcMs: number): ZonedParts {
  const parts: Record<string, string> = {};
  for (const part of formatterFor(zone).formatToParts(new Date(utcMs))) {
    if (part.type !== 'literal') parts[part.type] = part.value;
  }
  const result = {
    y: Number(parts.year),
    m: Number(parts.month),
    d: Number(parts.day),
    h: Number(parts.hour),
    mi: Number(parts.minute),
    s: Number(parts.second),
  };
  if (
    !Number.isFinite(result.y) || !Number.isFinite(result.m) ||
    !Number.isFinite(result.d) || !Number.isFinite(result.h) ||
    result.h > 23
  ) {
    throw new Error(`could not read the local time in ${zone}`);
  }
  return result;
}

/** The zone's UTC offset, in milliseconds, at a given instant. */
export function offsetMsAt(zone: string, utcMs: number): number {
  const p = partsAt(zone, utcMs);
  const asUtc = Date.UTC(p.y, p.m - 1, p.d, p.h, p.mi, p.s);
  // The formatter has second resolution; compare whole seconds.
  return asUtc - Math.floor(utcMs / 1000) * 1000;
}

export interface LocalDateTime {
  y: number;
  m: number;
  d: number;
  h: number;
  mi: number;
}

/**
 * Resolve a wall-clock time in a zone to an instant, using the rules the
 * vectors pin down (see the file header).
 */
export function localToInstantMs(zone: string, local: LocalDateTime): number {
  const wallMs = Date.UTC(local.y, local.m - 1, local.d, local.h, local.mi);
  const offsetBefore = offsetMsAt(zone, wallMs - MS_PER_DAY);
  const offsetAfter = offsetMsAt(zone, wallMs + MS_PER_DAY);

  if (offsetBefore === offsetAfter) {
    return wallMs - offsetBefore;
  }

  const candidateBefore = wallMs - offsetBefore;
  const candidateAfter = wallMs - offsetAfter;
  const beforeValid = offsetMsAt(zone, candidateBefore) === offsetBefore;
  const afterValid = offsetMsAt(zone, candidateAfter) === offsetAfter;

  if (beforeValid && afterValid) {
    // Overlap: the same wall time happens twice. Earlier instant = larger
    // offset (java.time's "earlier offset" rule).
    return Math.min(candidateBefore, candidateAfter);
  }
  if (beforeValid) return candidateBefore;
  if (afterValid) return candidateAfter;

  // Gap: the wall time does not exist. Shifting it forward by the gap length
  // gives the instant that uses the pre-transition offset.
  return candidateBefore;
}

/** The local date in a zone at an instant, as "YYYY-MM-DD". */
export function localDateAt(zone: string, utcMs: number): string {
  const p = partsAt(zone, utcMs);
  return formatCivilDate(Date.UTC(p.y, p.m - 1, p.d));
}

/** ISO-8601 UTC with seconds, the format the vectors use. */
export function toInstantString(utcMs: number): string {
  return `${new Date(utcMs).toISOString().slice(0, 19)}Z`;
}

// --- Schedule ---------------------------------------------------------------

/** Whether a regimen occurs on a civil date (both bounds inclusive). */
export function occursOn(regimen: Regimen, date: CivilDate): boolean {
  const ms = dateMs(date);
  const startMs = dateMs(parseCivilDate(regimen.start_date));
  if (ms < startMs) return false;
  if (regimen.end_date !== null) {
    const endMs = dateMs(parseCivilDate(regimen.end_date));
    if (ms > endMs) return false;
  }
  switch (regimen.schedule_type) {
    case 'daily':
      return true;
    case 'weekdays':
      return regimen.weekdays !== null &&
        (regimen.weekdays & (1 << (isoWeekday(ms) - 1))) !== 0;
    case 'every_n_days': {
      if (regimen.interval_days === null) return false;
      const days = (ms - startMs) / MS_PER_DAY;
      return days % regimen.interval_days === 0;
    }
    default:
      // Unknown enum values are a failure, not a silent default (vectors README).
      throw new Error(
        `unknown schedule_type: ${(regimen as { schedule_type: string }).schedule_type}`,
      );
  }
}

const slotOrder = (a: DoseSlot, b: DoseSlot): number => {
  const byInstant = Date.parse(a.instant) - Date.parse(b.instant);
  if (byInstant !== 0) return byInstant;
  if (a.medication_id !== b.medication_id) {
    return a.medication_id < b.medication_id ? -1 : 1;
  }
  if (a.scheduled_local !== b.scheduled_local) {
    return a.scheduled_local < b.scheduled_local ? -1 : 1;
  }
  if (a.regimen_id !== b.regimen_id) return a.regimen_id < b.regimen_id ? -1 : 1;
  return 0;
};

/**
 * Every slot due between `from` and `to` (local dates, both inclusive) for the
 * given regimen versions. Mirrors `DoseSlotRules.doseSlots` in Kotlin.
 */
export function doseSlots(
  regimens: Regimen[],
  from: string,
  to: string,
): DoseSlot[] {
  const fromMs = dateMs(parseCivilDate(from));
  const toMs = dateMs(parseCivilDate(to));
  if (toMs < fromMs) return [];

  const slots: DoseSlot[] = [];
  for (const regimen of regimens) {
    const startMs = dateMs(parseCivilDate(regimen.start_date));
    const endMs = regimen.end_date === null ? null : dateMs(parseCivilDate(regimen.end_date));
    const firstMs = Math.max(fromMs, startMs);
    const lastMs = endMs === null ? toMs : Math.min(toMs, endMs);
    if (lastMs < firstMs) continue;

    const times = [...regimen.times].sort((a, b) =>
      a.time < b.time ? -1 : a.time > b.time ? 1 : 0
    );

    for (let dayMs = firstMs; dayMs <= lastMs; dayMs += MS_PER_DAY) {
      const date: CivilDate = {
        y: new Date(dayMs).getUTCFullYear(),
        m: new Date(dayMs).getUTCMonth() + 1,
        d: new Date(dayMs).getUTCDate(),
      };
      if (!occursOn(regimen, date)) continue;

      for (const time of times) {
        const { h, mi } = parseClock(time.time);
        const instantMs = localToInstantMs(regimen.time_zone, { ...date, h, mi });
        slots.push({
          regimen_id: regimen.id,
          medication_id: regimen.medication_id,
          scheduled_local: formatScheduledLocal(dayMs, time.time),
          instant: toInstantString(instantMs),
          quantity: time.quantity,
        });
      }
    }
  }

  return slots.sort(slotOrder);
}

/**
 * Every slot whose instant falls between `fromMs` and `toMs` (both inclusive).
 * Mirrors `DoseSlotRules.slotsDueBetween`: the local date range is widened by a
 * day on each side before filtering, because a slot near midnight (or moved by
 * a gap) can belong to a neighbouring local date.
 */
export function slotsDueBetween(
  regimens: Regimen[],
  fromMs: number,
  toMs: number,
): DoseSlot[] {
  if (toMs < fromMs) return [];

  const slots: DoseSlot[] = [];
  for (const regimen of regimens) {
    const firstDate = localDateAt(regimen.time_zone, fromMs - MS_PER_DAY);
    const lastDate = localDateAt(regimen.time_zone, toMs + MS_PER_DAY);
    for (const slot of doseSlots([regimen], firstDate, lastDate)) {
      const ms = Date.parse(slot.instant);
      if (ms >= fromMs && ms <= toMs) slots.push(slot);
    }
  }

  return slots.sort(slotOrder);
}
