/**
 * Escalation selection: which dose slots the five-minute job must escalate, to
 * whom, and with what text. Pure - no clock, no network, no database - so the
 * test fixtures in `escalation_test.ts` can pin every rule from
 * docs/domain/care-circle.md "Reminder routing" (D4, D7, D11, D24).
 *
 * Rules:
 * - Only `primary_with_escalation` escalates; `all_caregivers` is local-only
 *   reminders (the first confirmation clears the others) and `rota` is not
 *   built.
 * - A slot escalates when its status is `overdue` - past the escalation delay,
 *   before the missed window, no effective entry (the same derivation the
 *   phones use, D22).
 * - Recipients are the circle's owners and caregivers except the effective
 *   primary caregiver. Viewers are never notified. With no primary (D24) every
 *   caregiver is notified.
 * - One row per escalated slot is returned for `meds.escalations`, and the
 *   caller skips slots already in that ledger, which makes the job idempotent.
 * - Payloads carry ids and wall-clock times only: no medication or patient
 *   name, no language of the patient, nothing health-related (D4).
 */

import {
  doseSlots,
  slotsDueBetween,
  type DoseSlot,
  type Regimen,
} from './doseSlots.ts';
import { doseStatus, type DoseLogEntry } from './doseStatus.ts';

/** `meds.effective_reminder`: a medication with its effective settings. */
export interface EffectiveReminder {
  medication_id: string;
  patient_id: string;
  circle_id: string;
  reminder_policy: 'primary_with_escalation' | 'all_caregivers' | 'rota';
  primary_caregiver_id: string | null;
  escalation_delay_minutes: number;
  missed_window_minutes: number;
}

export interface Membership {
  circle_id: string;
  user_id: string;
  role: 'owner' | 'caregiver' | 'viewer';
}

export interface Profile {
  id: string;
  preferred_language: string | null;
}

export interface PushDevice {
  user_id: string;
  fcm_token: string;
}

export interface EscalationLedgerRow {
  regimen_id: string;
  scheduled_local: string;
}

export interface EscalationInput {
  /** The job's clock, an ISO instant. */
  now: string;
  reminders: EffectiveReminder[];
  regimens: Regimen[];
  /** Dose-log entries for the slots in the window (any slot; mismatches ignored). */
  entries: DoseLogEntry[];
  /** Slots already escalated (meds.escalations). */
  escalations: EscalationLedgerRow[];
  memberships: Membership[];
  profiles: Profile[];
  devices: PushDevice[];
}

export type Language = 'pt-PT' | 'en';

export interface EscalationNotification {
  user_id: string;
  tokens: string[];
  language: Language;
  title: string;
  body: string;
  /** Repeats for the same (patient, slot instant) replace on the phone. */
  tag: string;
  data: {
    type: 'escalation';
    patient_id: string;
    scheduled_local: string;
  };
}

export interface EscalationRecord {
  regimen_id: string;
  patient_id: string;
  scheduled_local: string;
  /** Users actually notified (0 when nobody has a registered device). */
  recipients: number;
}

export interface EscalationPlan {
  notifications: EscalationNotification[];
  escalations: EscalationRecord[];
  /** Every slot considered overdue, in the order they were processed. */
  overdue: DoseSlot[];
}

/** The generic, localized text (D8, D4). Never medication or patient names. */
export const MESSAGES: Record<Language, { title: string; body: string }> = {
  en: { title: 'Family Care', body: 'A dose needs attention' },
  'pt-PT': { title: 'Family Care', body: 'Uma toma precisa de atenção' },
};

/** Extra look-back beyond the widest missed window, for cron jitter. */
export const LOOKBACK_MARGIN_MINUTES = 15;

export function languageOf(profile: Profile | undefined): Language {
  return profile?.preferred_language === 'en' ? 'en' : 'pt-PT';
}

export function escalationTag(patientId: string, instant: string): string {
  return `escalation:${patientId}:${instant}`;
}

/**
 * Build the notifications and ledger rows for one run.
 *
 * The look-back is bounded by the widest effective missed window plus a small
 * margin: a slot older than that is `missed`, which is not escalated.
 */
export function planEscalations(input: EscalationInput): EscalationPlan {
  const nowMs = Date.parse(input.now);
  if (!Number.isFinite(nowMs)) throw new Error(`not an instant: ${input.now}`);

  const maxMissedWindow = input.reminders.reduce(
    (max, reminder) => Math.max(max, reminder.missed_window_minutes),
    0,
  );
  const lookbackMs =
    (Math.min(maxMissedWindow, 24 * 60) + LOOKBACK_MARGIN_MINUTES) * 60_000;
  const windowStartMs = nowMs - lookbackMs;

  const alreadyEscalated = new Set(
    input.escalations.map((row) =>
      // PostgREST renders timestamp without zone as "2026-11-16T08:00:00".
      `${row.regimen_id}|${row.scheduled_local.slice(0, 16)}`
    ),
  );

  const profileByUser = new Map(input.profiles.map((p) => [p.id, p]));
  const tokensByUser = new Map<string, string[]>();
  for (const device of input.devices) {
    const tokens = tokensByUser.get(device.user_id) ?? [];
    tokens.push(device.fcm_token);
    tokensByUser.set(device.user_id, tokens);
  }

  const regimensByMedication = new Map<string, Regimen[]>();
  for (const regimen of input.regimens) {
    const list = regimensByMedication.get(regimen.medication_id) ?? [];
    list.push(regimen);
    regimensByMedication.set(regimen.medication_id, list);
  }

  const notifications: EscalationNotification[] = [];
  const escalations: EscalationRecord[] = [];
  const overdue: DoseSlot[] = [];

  for (const reminder of input.reminders) {
    if (reminder.reminder_policy !== 'primary_with_escalation') continue;

    const regimens = regimensByMedication.get(reminder.medication_id) ?? [];
    if (regimens.length === 0) continue;

    const slots = slotsDueBetween(regimens, windowStartMs, nowMs);
    if (slots.length === 0) continue;

    const recipients = circleRecipients(
      input.memberships,
      reminder.circle_id,
      reminder.primary_caregiver_id,
    );

    for (const slot of slots) {
      if (
        alreadyEscalated.has(
          `${slot.regimen_id}|${slot.scheduled_local.slice(0, 16)}`,
        )
      ) {
        continue;
      }

      const status = doseStatus(slot, input.entries, nowMs, {
        escalationDelayMinutes: reminder.escalation_delay_minutes,
        missedWindowMinutes: reminder.missed_window_minutes,
      });
      if (status.state !== 'overdue') continue;

      overdue.push(slot);
      const tag = escalationTag(reminder.patient_id, slot.instant);
      let notified = 0;

      for (const userId of recipients) {
        const tokens = tokensByUser.get(userId);
        if (!tokens || tokens.length === 0) continue;
        const language = languageOf(profileByUser.get(userId));
        const text = MESSAGES[language];
        notifications.push({
          user_id: userId,
          tokens: [...tokens],
          language,
          title: text.title,
          body: text.body,
          tag,
          data: {
            type: 'escalation',
            patient_id: reminder.patient_id,
            scheduled_local: slot.scheduled_local,
          },
        });
        notified += 1;
      }

      escalations.push({
        regimen_id: slot.regimen_id,
        patient_id: reminder.patient_id,
        scheduled_local: slot.scheduled_local,
        recipients: notified,
      });
    }
  }

  // Deterministic order: patient, instant, then regimen/slot.
  notifications.sort((a, b) =>
    a.user_id < b.user_id
      ? -1
      : a.user_id > b.user_id
      ? 1
      : a.tag < b.tag
      ? -1
      : a.tag > b.tag
      ? 1
      : a.data.scheduled_local < b.data.scheduled_local
      ? -1
      : a.data.scheduled_local > b.data.scheduled_local
      ? 1
      : 0
  );
  escalations.sort((a, b) =>
    a.patient_id === b.patient_id
      ? a.scheduled_local === b.scheduled_local
        ? a.regimen_id < b.regimen_id ? -1 : a.regimen_id > b.regimen_id ? 1 : 0
        : a.scheduled_local < b.scheduled_local ? -1 : 1
      : a.patient_id < b.patient_id ? -1 : 1
  );

  return { notifications, escalations, overdue };
}

/**
 * Owners and caregivers of the circle, minus the primary. A null primary (D24:
 * a removed member) means everyone - the safe direction.
 */
export function circleRecipients(
  memberships: Membership[],
  circleId: string,
  primaryCaregiverId: string | null,
): string[] {
  const recipients: string[] = [];
  for (const membership of memberships) {
    if (membership.circle_id !== circleId) continue;
    if (membership.role !== 'owner' && membership.role !== 'caregiver') continue;
    if (primaryCaregiverId !== null && membership.user_id === primaryCaregiverId) {
      continue;
    }
    if (!recipients.includes(membership.user_id)) recipients.push(membership.user_id);
  }
  return recipients.sort();
}

/** Slots in the look-back window for one medication, for the IO layer. */
export function slotsInWindow(
  regimens: Regimen[],
  now: string,
  missedWindowMinutes: number,
): DoseSlot[] {
  const nowMs = Date.parse(now);
  const lookbackMs =
    (Math.min(missedWindowMinutes, 24 * 60) + LOOKBACK_MARGIN_MINUTES) * 60_000;
  return slotsDueBetween(regimens, nowMs - lookbackMs, nowMs);
}

// Re-exported for callers that only import this module.
export { doseSlots };
