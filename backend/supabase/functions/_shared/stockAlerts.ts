/**
 * Low-stock alerting (M5, D4/D8/D26/D28): which active medications are low, who
 * gets the one push per low episode, and which ledger rows to write or clear.
 *
 * Pure - no clock, no network, no database - so `stockAlerts_test.ts` can pin
 * every rule. The IO layer is `stock-low/index.ts`.
 *
 * Rules (D28):
 * - Only active (not stopped) medications with a regimen are considered; the
 *   stock itself is derived by `stock.ts` (the shared vectors are the contract).
 * - One push per **low episode**: a medication that already has a
 *   `meds.stock_alerts` row is skipped until that row is cleared.
 * - The row is written even when nobody has a device, so the episode is not
 *   retried every day; it carries the number of users actually notified.
 * - A medication that is no longer low (or is stopped/deleted) has its row
 *   removed, which re-arms the alert.
 * - Recipients are the circle's owners and caregivers, never viewers, and not
 *   routed by reminder policy: stock is everyone's errand.
 * - Text is generic and localized per recipient (D8): no medication, patient or
 *   quantity in the payload, only `type=stock_low` (D4).
 */

import type { Regimen } from './doseSlots.ts';
import type { DoseLogEntry } from './doseStatus.ts';
import {
  stockSummary,
  type StockMedication,
  type StockMovement,
} from './stock.ts';
import {
  languageOf,
  type Language,
  type Membership,
  type Profile,
  type PushDevice,
} from './escalation.ts';

/** An active medication with the circle its recipients come from. */
export interface ActiveStockMedication extends StockMedication {
  id: string;
  patient_id: string;
  circle_id: string;
  /**
   * The patient is archived (D30): the medication is hidden from the job, so
   * it is never low here and its alert row is cleared like a stopped one.
   */
  patient_archived?: boolean;
}

/** A `meds.stock_movements` row as the job reads it (one medication each). */
export type StockMovementRow = StockMovement & { medication_id: string };

export interface StockAlertInput {
  /** The job's clock, an ISO instant. */
  now: string;
  /** Active medications only (`medications.stopped_at is null`). */
  medications: ActiveStockMedication[];
  /** Every regimen version of those medications. */
  regimens: Regimen[];
  movements: StockMovementRow[];
  /** Dose-log entries that can matter (after the oldest count at least). */
  entries: DoseLogEntry[];
  /** Medication ids already in `meds.stock_alerts`. */
  alerted: string[];
  memberships: Membership[];
  profiles: Profile[];
  devices: PushDevice[];
}

export interface StockAlertNotification {
  medication_id: string;
  user_id: string;
  tokens: string[];
  language: Language;
  title: string;
  body: string;
  /** Repeats replace the earlier low-stock notification on the phone. */
  tag: string;
  data: { type: 'stock_low' };
}

/** A `meds.stock_alerts` row to insert for a new low episode. */
export interface StockAlertRecord {
  medication_id: string;
  /** Users notified (0 when nobody has a registered device). */
  recipients: number;
}

export interface StockAlertPlan {
  notifications: StockAlertNotification[];
  /** Rows to insert for medications that are low and not yet alerted. */
  alerts: StockAlertRecord[];
  /** Alert rows to delete: no longer low, stopped or deleted (re-arms them). */
  cleared: string[];
  /** Medications that are low right now, alerted or not. */
  low: string[];
}

/** The generic, localized text (D8, D4). Never medication or patient names. */
export const STOCK_MESSAGES: Record<Language, { title: string; body: string }> = {
  en: { title: 'Family Care', body: 'A medication is running low' },
  'pt-PT': { title: 'Family Care', body: 'Um medicamento está a acabar' },
};

export function stockAlertTag(medicationId: string): string {
  return `stock-${medicationId}`;
}

/**
 * The local date of an instant in an IANA zone, as `YYYY-MM-DD`. Stock is
 * measured in days, so "today" is the patient's wall-clock day, not UTC.
 */
export function currentLocalDate(instant: string, timeZone: string): string {
  const date = new Date(instant);
  if (!Number.isFinite(date.getTime())) throw new Error(`not an instant: ${instant}`);

  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);

  const part = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((candidate) => candidate.type === type)?.value ?? '';

  return `${part('year')}-${part('month')}-${part('day')}`;
}

/**
 * The zone used to decide "today" for a medication: its latest regimen version
 * (the one in force, or the next one). Regimens for one medication never
 * overlap and are validated against `pg_timezone_names` by a trigger.
 */
export function medicationTimeZone(regimens: Regimen[]): string {
  let latest = regimens[0];
  for (const regimen of regimens) {
    if (
      regimen.start_date > latest.start_date ||
      (regimen.start_date === latest.start_date && regimen.id > latest.id)
    ) {
      latest = regimen;
    }
  }
  return latest.time_zone;
}

/** Owners and caregivers of the circle; viewers never receive stock pushes. */
export function stockRecipients(
  memberships: Membership[],
  circleId: string,
): string[] {
  const recipients = new Set<string>();
  for (const membership of memberships) {
    if (membership.circle_id !== circleId) continue;
    if (membership.role !== 'owner' && membership.role !== 'caregiver') continue;
    recipients.add(membership.user_id);
  }
  return [...recipients].sort();
}

function groupBy<T>(rows: T[], key: (row: T) => string): Map<string, T[]> {
  const grouped = new Map<string, T[]>();
  for (const row of rows) {
    const value = key(row);
    const list = grouped.get(value) ?? [];
    list.push(row);
    grouped.set(value, list);
  }
  return grouped;
}

/** Build the notifications, new ledger rows and rows to clear for one run. */
export function planStockAlerts(input: StockAlertInput): StockAlertPlan {
  const nowMs = Date.parse(input.now);
  if (!Number.isFinite(nowMs)) throw new Error(`not an instant: ${input.now}`);

  const profilesByUser = new Map(input.profiles.map((p) => [p.id, p]));
  const tokensByUser = new Map<string, string[]>();
  for (const device of input.devices) {
    const tokens = tokensByUser.get(device.user_id) ?? [];
    tokens.push(device.fcm_token);
    tokensByUser.set(device.user_id, tokens);
  }

  const regimensByMedication = groupBy(input.regimens, (r) => r.medication_id);
  const movementsByMedication = groupBy(input.movements, (m) => m.medication_id);
  const entriesByMedication = groupBy(input.entries, (e) => e.medication_id);
  const alreadyAlerted = new Set(input.alerted);

  const notifications: StockAlertNotification[] = [];
  const alerts: StockAlertRecord[] = [];
  const low: string[] = [];

  for (const medication of input.medications) {
    // An archived patient is hidden from every server job (D30). It is not
    // low here, so its ledger row falls out through `cleared` below.
    if (medication.patient_archived) continue;

    const regimens = regimensByMedication.get(medication.id) ?? [];
    // No regimen means no scheduled consumption, so days remaining is unknown
    // and the medication can never be low.
    if (regimens.length === 0) continue;

    const today = currentLocalDate(
      input.now,
      medicationTimeZone(regimens),
    );
    const summary = stockSummary(
      movementsByMedication.get(medication.id) ?? [],
      entriesByMedication.get(medication.id) ?? [],
      regimens,
      medication,
      today,
    );
    if (!summary.low) continue;

    low.push(medication.id);
    if (alreadyAlerted.has(medication.id)) continue;

    const tag = stockAlertTag(medication.id);
    let notified = 0;
    for (const userId of stockRecipients(input.memberships, medication.circle_id)) {
      const tokens = tokensByUser.get(userId);
      if (!tokens || tokens.length === 0) continue;
      const language = languageOf(profilesByUser.get(userId));
      const text = STOCK_MESSAGES[language];
      notifications.push({
        medication_id: medication.id,
        user_id: userId,
        tokens: [...tokens],
        language,
        title: text.title,
        body: text.body,
        tag,
        data: { type: 'stock_low' },
      });
      notified += 1;
    }

    // Written even with 0 recipients: the episode must not repeat every day.
    alerts.push({ medication_id: medication.id, recipients: notified });
  }

  const lowSet = new Set(low);
  // Everything in the ledger that is not low right now: recovered, stopped or
  // deleted. A cascade already removed the deleted medication's row; deleting
  // again is harmless.
  const cleared = [...new Set(input.alerted)].filter((id) => !lowSet.has(id)).sort();

  notifications.sort((a, b) =>
    a.medication_id === b.medication_id
      ? a.user_id === b.user_id
        ? a.tag < b.tag ? -1 : a.tag > b.tag ? 1 : 0
        : a.user_id < b.user_id ? -1 : 1
      : a.medication_id < b.medication_id ? -1 : 1
  );
  alerts.sort((a, b) =>
    a.medication_id < b.medication_id ? -1 : a.medication_id > b.medication_id ? 1 : 0
  );
  low.sort();

  return { notifications, alerts, cleared, low };
}
