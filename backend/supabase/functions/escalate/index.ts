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
 *    regimens, the dose log and the escalation ledger for a bounded look-back,
 *    memberships, profiles and devices. Every read is paged past the 1000-row
 *    PostgREST cut (`_shared/paging.ts`).
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
  escalationSlotKey,
  LOOKBACK_MARGIN_MINUTES,
  planEscalations,
  type EffectiveReminder,
  type EscalationLedgerRow,
  type EscalationInput,
  type Membership,
  type Profile,
  type PushDevice,
} from '../_shared/escalation.ts';
import type { DoseLogEntry } from '../_shared/doseStatus.ts';
import type { Regimen } from '../_shared/doseSlots.ts';
import { createFcmSender, parseServiceAccount } from '../_shared/fcm.ts';
import { rowsToRecord, type SendAttempt } from '../_shared/delivery.ts';
import { errorText } from '../_shared/log.ts';
import { fetchAllPages, type Page } from '../_shared/paging.ts';

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
    const reminders = await fetchAllPages<EffectiveReminder>((from, to) =>
      supabase
        .schema('meds')
        .from('effective_reminder')
        .select(
          'medication_id, patient_id, circle_id, reminder_policy, ' +
            'primary_caregiver_id, escalation_delay_minutes, missed_window_minutes',
        )
        .order('medication_id')
        .range(from, to) as unknown as PromiseLike<Page<EffectiveReminder>>
    );
    if (reminders.length === 0) {
      return json(200, { notifications: 0, escalations: 0 });
    }

    const medicationIds = [...new Set(reminders.map((r) => r.medication_id))];
    const circleIds = [...new Set(reminders.map((r) => r.circle_id))];

    // 2. Regimen versions for those medications (overlap is prevented by the
    //    M3 trigger; the window filter is in `slotsDueBetween`).
    const regimens = await fetchAllPages<Regimen>((from, to) =>
      supabase
        .schema('meds')
        .from('regimens')
        .select(
          'id, medication_id, schedule_type, weekdays, interval_days, ' +
            'start_date, end_date, time_zone, times',
        )
        .in('medication_id', medicationIds)
        .order('id')
        .range(from, to) as unknown as PromiseLike<Page<Regimen>>
    );
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

    const entries = await fetchAllPages<DoseLogEntry>((from, to) =>
      supabase
        .schema('meds')
        .from('dose_log')
        .select(
          'id, regimen_id, medication_id, scheduled_local, outcome, actual_at, ' +
            'corrects_entry_id',
        )
        .in('regimen_id', regimenIds)
        .gte('scheduled_local', lowerBound)
        .order('id')
        .range(from, to) as unknown as PromiseLike<Page<DoseLogEntry>>
    );

    // 4. The ledger that makes this job idempotent. A row is written per
    //    escalated slot, so it grows without bound: read it with the same lower
    //    bound as the dose log (older slots are missed, never escalated) and
    //    page it, or recent slots fall outside a 1000-row cut and look new.
    const escalations = await fetchAllPages<EscalationLedgerRow>((from, to) =>
      supabase
        .schema('meds')
        .from('escalations')
        .select('regimen_id, scheduled_local')
        .in('regimen_id', regimenIds)
        .gte('scheduled_local', lowerBound)
        .order('regimen_id')
        .order('scheduled_local')
        .range(from, to) as unknown as PromiseLike<Page<EscalationLedgerRow>>
    );

    // 5. Who to notify: owners and caregivers, then their language and devices.
    const memberships = await fetchAllPages<Membership>((from, to) =>
      supabase
        .schema('core')
        .from('memberships')
        .select('circle_id, user_id, role')
        .in('circle_id', circleIds)
        .in('role', ['owner', 'caregiver'])
        .order('circle_id')
        .order('user_id')
        .range(from, to) as unknown as PromiseLike<Page<Membership>>
    );
    const recipientIds = [...new Set(memberships.map((m) => m.user_id))];

    let profiles: Profile[] = [];
    let devices: PushDevice[] = [];
    if (recipientIds.length > 0) {
      profiles = await fetchAllPages<Profile>((from, to) =>
        supabase
          .schema('core')
          .from('profiles')
          .select('id, preferred_language')
          .in('id', recipientIds)
          .order('id')
          .range(from, to) as unknown as PromiseLike<Page<Profile>>
      );

      devices = await fetchAllPages<PushDevice>((from, to) =>
        supabase
          .schema('core')
          .from('push_devices')
          .select('user_id, fcm_token')
          .in('user_id', recipientIds)
          .order('fcm_token')
          .range(from, to) as unknown as PromiseLike<Page<PushDevice>>
      );
    }

    const input: EscalationInput = {
      now,
      reminders,
      regimens,
      entries,
      escalations,
      memberships,
      profiles,
      devices,
    };

    const plan = planEscalations(input);

    // 6. Send. A dead token is deleted; a transient failure leaves the ledger
    //    row unwritten, so the next tick retries while the slot is still
    //    overdue (a missed slot is never escalated, which bounds retries).
    const deadTokens = new Set<string>();
    const attempts: SendAttempt[] = [];
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

        attempts.push({ key: escalationSlotKey(notification), status: result.status });

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

    // 7. Record the slots that were delivered to at least one device or had
    //    nothing to retry, after sending (duplicate beats lost). A slot whose
    //    every send failed transiently stays out of the ledger so the next tick
    //    retries it. The unique key makes a concurrent tick harmless.
    const recorded = rowsToRecord(plan.escalations, escalationSlotKey, attempts);
    if (recorded.length > 0) {
      const { error: insertError } = await supabase
        .schema('meds')
        .from('escalations')
        .upsert(recorded, {
          onConflict: 'regimen_id,scheduled_local',
          ignoreDuplicates: true,
        });
      if (insertError) throw insertError;
    }

    return json(200, {
      overdue: plan.overdue.length,
      escalations: recorded.length,
      to_retry: plan.escalations.length - recorded.length,
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
