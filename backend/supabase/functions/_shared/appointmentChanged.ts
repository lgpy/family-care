/**
 * Appointment change push (M6, docs/domain/appointments.md "Change push",
 * D4/D8/D29): who hears that an appointment was added or changed, in which
 * language, and with which tag.
 *
 * Pure - no clock, no network, no database - so `appointmentChanged_test.ts`
 * can pin every rule. The IO layer is `appointment-changed/index.ts`.
 *
 * Rules:
 * - Recipients are the circle's owners and caregivers, never viewers, and never
 *   the person who made the change (their own phone already knows).
 * - A user with no registered device is skipped; a user with several devices is
 *   one notification with several tokens.
 * - Text is generic and localized per recipient (D8): no patient, doctor or
 *   place in the payload, only `type=appointment_changed` (D4).
 * - A missing appointment (deleted by a cascade between the trigger and the
 *   call) plans nothing: there is no circle to notify.
 */

import {
  languageOf,
  type Language,
  type Membership,
  type Profile,
  type PushDevice,
} from './escalation.ts';

/** The appointment row as the function reads it (patient's circle resolved). */
export interface AppointmentChange {
  id: string;
  patient_id: string;
  circle_id: string;
}

export interface AppointmentChangedInput {
  /** null when the row is gone (or the patient/circle could not be read). */
  appointment: AppointmentChange | null;
  /** `created_by` on insert, `updated_by` on update; null for server writes. */
  actor: string | null;
  /** Memberships of the appointment's circle (other circles are ignored). */
  memberships: Membership[];
  profiles: Profile[];
  devices: PushDevice[];
}

export interface AppointmentChangedNotification {
  user_id: string;
  tokens: string[];
  language: Language;
  title: string;
  body: string;
  /** Repeats for the same appointment replace the earlier notification. */
  tag: string;
  data: { type: 'appointment_changed' };
}

export interface AppointmentChangedPlan {
  notifications: AppointmentChangedNotification[];
  /** Users actually notified (those with at least one registered device). */
  recipients: number;
}

/** The generic, localized text (D8, D4). Never a patient, doctor or place. */
export const APPOINTMENT_MESSAGES: Record<Language, { title: string; body: string }> = {
  en: { title: 'Family Care', body: 'An appointment was added or changed' },
  'pt-PT': { title: 'Family Care', body: 'Uma consulta foi marcada ou alterada' },
};

export function appointmentTag(appointmentId: string): string {
  return `appt-${appointmentId}`;
}

export function planAppointmentChanged(
  input: AppointmentChangedInput,
): AppointmentChangedPlan {
  const { appointment } = input;
  if (!appointment) return { notifications: [], recipients: 0 };

  const tokensByUser = new Map<string, string[]>();
  for (const device of input.devices) {
    const tokens = tokensByUser.get(device.user_id);
    if (tokens) {
      tokens.push(device.fcm_token);
    } else {
      tokensByUser.set(device.user_id, [device.fcm_token]);
    }
  }

  const recipients = new Set<string>();
  for (const membership of input.memberships) {
    if (membership.circle_id !== appointment.circle_id) continue;
    if (membership.role !== 'owner' && membership.role !== 'caregiver') continue;
    if (input.actor !== null && membership.user_id === input.actor) continue;
    if (recipients.has(membership.user_id)) continue;

    const tokens = tokensByUser.get(membership.user_id);
    if (!tokens || tokens.length === 0) continue;

    recipients.add(membership.user_id);
  }

  const profileByUser = new Map(input.profiles.map((profile) => [profile.id, profile]));
  const tag = appointmentTag(appointment.id);

  const notifications = [...recipients].map((userId) => {
    const language = languageOf(profileByUser.get(userId));
    const text = APPOINTMENT_MESSAGES[language];
    return {
      user_id: userId,
      tokens: tokensByUser.get(userId) as string[],
      language,
      title: text.title,
      body: text.body,
      tag,
      data: { type: 'appointment_changed' as const },
    };
  });

  return { notifications, recipients: notifications.length };
}
