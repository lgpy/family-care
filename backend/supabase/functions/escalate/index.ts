/**
 * escalate: the five-minute server escalation job (build-plan §M4,
 * docs/domain/care-circle.md "Reminder routing", D4/D11/D24).
 *
 * Invoked by pg_cron through pg_net (see the push_and_escalation migration) with
 * the shared secret in `X-Cron-Secret`. It uses the service role because it
 * must read every circle's dose log and push devices, which no single user may.
 *
 * What it does, in order:
 * 1. Reads active medications with their effective reminder settings, the
 *    regimens, the dose log for a bounded look-back, the escalation ledger,
 *    memberships, profiles and devices.
 * 2. Hands all of it to `_shared/escalation.ts` (pure, unit-tested): overdue
 *    slots under `primary_with_escalation` that are not yet in the ledger, and
 *    the caregivers to notify (never the primary, never a viewer).
 * 3. Sends generic, localized, data-light pushes and records the ledger rows.
 *
 * A token FCM reports as dead is deleted from `core.push_devices`. Nothing here
 * logs a token, a message body or a patient/medication name.
 */

import { createClient } from 'jsr:@supabase/supabase-js@2';
import {
  LOOKBACK_MARGIN_MINUTES,
  planEscalations,
  type EffectiveReminder,
  type EscalationInput,
  type Membership,
  type Profile,
  type PushDevice,
} from '../_shared/escalation.ts';
import type { DoseLogEntry } from '../_shared/doseStatus.ts';
import type { Regimen } from '../_shared/doseSlots.ts';
import { createFcmSender, parseServiceAccount } from '../_shared/fcm.ts';
import { errorText } from '../_shared/log.ts';

const MS_PER_MINUTE = 60_000;
const DAY_MS = 24 * 60 * MS_PER_MINUTE;

function secretsEqual(given: string | null, expected: string): boolean {
  if (given === null) return false;
  // Constant-time-ish: do not give an attacker a prefix oracle.
  if (given.length !== expected.length) return false;
  let difference = 0;
  for (let i = 0; i < given.length; i += 1) {
    difference |= given.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  return difference === 0;
}

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

Deno.serve(async (request) => {
  if (request.method !== 'POST') {
    return json(405, { error: 'method not allowed' });
  }

  const expectedSecret = Deno.env.get('ESCALATION_CRON_SECRET') ?? '';
  if (expectedSecret === '') {
    return json(503, { error: 'ESCALATION_CRON_SECRET is not configured' });
  }
  if (!secretsEqual(request.headers.get('X-Cron-Secret'), expectedSecret)) {
    return json(401, { error: 'unauthorized' });
  }

  const serviceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON');
  if (!serviceAccountJson) {
    return json(503, { error: 'FCM_SERVICE_ACCOUNT_JSON is not configured' });
  }

  const supabaseUrl = Deno.env.get('SUPABASE_URL') ?? '';
  const serviceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';
  if (supabaseUrl === '' || serviceKey === '') {
    return json(503, { error: 'Supabase service credentials are not configured' });
  }

  try {
    const account = parseServiceAccount(serviceAccountJson);
    const sender = createFcmSender(account, { fetch });
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const now = new Date().toISOString();
    const nowMs = Date.parse(now);

    // 1. Active medications with the coalesced policy/primary and the delays.
    const { data: reminderRows, error: reminderError } = await supabase
      .schema('meds')
      .from('effective_reminder')
      .select(
        'medication_id, patient_id, circle_id, reminder_policy, ' +
          'primary_caregiver_id, escalation_delay_minutes, missed_window_minutes',
      );
    if (reminderError) throw reminderError;

    const reminders = (reminderRows ?? []) as unknown as EffectiveReminder[];
    if (reminders.length === 0) {
      return json(200, { notifications: 0, escalations: 0 });
    }

    const medicationIds = [...new Set(reminders.map((r) => r.medication_id))];
    const circleIds = [...new Set(reminders.map((r) => r.circle_id))];

    // 2. Regimen versions for those medications (overlap is prevented by the
    //    M3 trigger; the window filter is in `slotsDueBetween`).
    const { data: regimenRows, error: regimenError } = await supabase
      .schema('meds')
      .from('regimens')
      .select(
        'id, medication_id, schedule_type, weekdays, interval_days, ' +
          'start_date, end_date, time_zone, times',
      )
      .in('medication_id', medicationIds);
    if (regimenError) throw regimenError;

    const regimens = (regimenRows ?? []) as unknown as Regimen[];
    if (regimens.length === 0) {
      return json(200, { notifications: 0, escalations: 0 });
    }

    const regimenIds = regimens.map((r) => r.id);

    // 3. Dose-log entries for a bounded look-back. `scheduled_local` is a wall
    //    clock in the regimen's zone, so the lower bound is widened by a day:
    //    extra rows are ignored by the slot-status derivation.
    const maxMissedWindow = reminders.reduce(
      (max, reminder) => Math.max(max, reminder.missed_window_minutes),
      0,
    );
    const lookbackMs =
      (Math.min(maxMissedWindow, 24 * 60) + LOOKBACK_MARGIN_MINUTES) * MS_PER_MINUTE;
    const lowerBound = new Date(nowMs - lookbackMs - DAY_MS)
      .toISOString()
      .slice(0, 19);

    const { data: entryRows, error: entryError } = await supabase
      .schema('meds')
      .from('dose_log')
      .select(
        'id, regimen_id, medication_id, scheduled_local, outcome, actual_at, ' +
          'corrects_entry_id',
      )
      .in('regimen_id', regimenIds)
      .gte('scheduled_local', lowerBound);
    if (entryError) throw entryError;

    // 4. The ledger that makes this job idempotent.
    const { data: escalationRows, error: escalationError } = await supabase
      .schema('meds')
      .from('escalations')
      .select('regimen_id, scheduled_local')
      .in('regimen_id', regimenIds);
    if (escalationError) throw escalationError;

    // 5. Who to notify: owners and caregivers, then their language and devices.
    const { data: membershipRows, error: membershipError } = await supabase
      .schema('core')
      .from('memberships')
      .select('circle_id, user_id, role')
      .in('circle_id', circleIds)
      .in('role', ['owner', 'caregiver']);
    if (membershipError) throw membershipError;

    const memberships = (membershipRows ?? []) as Membership[];
    const recipientIds = [...new Set(memberships.map((m) => m.user_id))];

    let profiles: Profile[] = [];
    let devices: PushDevice[] = [];
    if (recipientIds.length > 0) {
      const { data: profileRows, error: profileError } = await supabase
        .schema('core')
        .from('profiles')
        .select('id, preferred_language')
        .in('id', recipientIds);
      if (profileError) throw profileError;
      profiles = (profileRows ?? []) as Profile[];

      const { data: deviceRows, error: deviceError } = await supabase
        .schema('core')
        .from('push_devices')
        .select('user_id, fcm_token')
        .in('user_id', recipientIds);
      if (deviceError) throw deviceError;
      devices = (deviceRows ?? []) as PushDevice[];
    }

    const input: EscalationInput = {
      now,
      reminders,
      regimens,
      entries: (entryRows ?? []) as unknown as DoseLogEntry[],
      escalations: escalationRows ?? [],
      memberships,
      profiles,
      devices,
    };

    const plan = planEscalations(input);

    // 6. Send. A dead token is deleted; a transient failure leaves the ledger
    //    row unwritten, so the next tick retries while the slot is still
    //    overdue (a missed slot is never escalated, which bounds retries).
    const deadTokens = new Set<string>();
    let sent = 0;
    let failed = 0;

    for (const notification of plan.notifications) {
      for (const token of notification.tokens) {
        const result = await sender.send(token, {
          notification: { title: notification.title, body: notification.body },
          data: notification.data,
          android: {
            priority: 'HIGH',
            notification: {
              channel_id: 'escalations',
              tag: notification.tag,
            },
          },
        });

        if (result.status === 'sent') {
          sent += 1;
        } else if (result.status === 'unregistered' || result.status === 'invalid') {
          deadTokens.add(token);
        } else {
          failed += 1;
        }
      }
    }

    if (deadTokens.size > 0) {
      const { error: deleteError } = await supabase
        .schema('core')
        .from('push_devices')
        .delete()
        .in('fcm_token', [...deadTokens]);
      if (deleteError) {
        console.error(`escalate: could not delete dead devices: ${errorText(deleteError)}`);
      }
    }

    // 7. Record only the slots we attempted, after sending (duplicate beats
    //    lost). The unique key makes a concurrent tick harmless.
    if (plan.escalations.length > 0) {
      const { error: insertError } = await supabase
        .schema('meds')
        .from('escalations')
        .upsert(plan.escalations, {
          onConflict: 'regimen_id,scheduled_local',
          ignoreDuplicates: true,
        });
      if (insertError) throw insertError;
    }

    return json(200, {
      overdue: plan.overdue.length,
      escalations: plan.escalations.length,
      notifications: plan.notifications.length,
      sent,
      failed,
      dead_devices: deadTokens.size,
    });
  } catch (error) {
    // The message may name a table or an HTTP status; never a token or body.
    console.error(`escalate failed: ${errorText(error)}`);
    return json(500, { error: 'escalation run failed' });
  }
});
