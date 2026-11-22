/**
 * stock-low: the daily low-stock job (build-plan §M5,
 * docs/domain/medications.md "Stock", D4/D8/D26/D27/D28).
 *
 * Invoked by pg_cron through pg_net (`stock_low_daily`, 09:00) with the shared
 * secret in `X-Cron-Secret`, exactly like `escalate`. It uses the service role
 * because it must read every circle's medications and stock ledger, which no
 * single user may.
 *
 * What it does, in order:
 * 1. Reads the active (not stopped) medications, their regimens, their stock
 *    movements, the `meds.stock_alerts` ledger, and only the dose-log entries
 *    that can affect a stock figure (taken/correcting entries after each
 *    medication's latest count, or the whole history of a medication that has
 *    never been counted). Every read is paged past the 1000-row PostgREST cut
 *    (`_shared/paging.ts`).
 * 2. Hands all of it to `_shared/stockAlerts.ts` (pure, unit-tested): which
 *    medications are low, who gets the one push of the low episode, which
 *    ledger rows to write and which to clear.
 * 3. Sends generic, localized, data-light pushes and records the ledger rows.
 *
 * A token FCM reports as dead is deleted from `core.push_devices`. Nothing here
 * logs a token, a message body or a patient/medication name.
 */

import { createClient } from 'jsr:@supabase/supabase-js@2';
import type { SupabaseClient } from 'jsr:@supabase/supabase-js@2';
import {
  planStockAlerts,
  type ActiveStockMedication,
  type StockAlertInput,
  type StockMovementRow,
} from '../_shared/stockAlerts.ts';
import { latestCount } from '../_shared/stock.ts';
import { rowsToRecord, type SendAttempt } from '../_shared/delivery.ts';
import type { DoseLogEntry } from '../_shared/doseStatus.ts';
import type { Regimen } from '../_shared/doseSlots.ts';
import type { Membership, Profile, PushDevice, ReminderPolicy } from '../_shared/escalation.ts';
import { createFcmSender, parseServiceAccount } from '../_shared/fcm.ts';
import { errorText } from '../_shared/log.ts';
import { fetchAllPages, type Page } from '../_shared/paging.ts';

const ENTRY_COLUMNS =
  'id, regimen_id, medication_id, scheduled_local, outcome, actual_at, ' +
  'actual_quantity, corrects_entry_id';

/**
 * Dose-log entries for these medications, at or after `lowerBound` (every
 * entry when it is null). Paged, because a baseline count can be months old and
 * a truncated read would silently overstate stock.
 */
function readEntries(
  supabase: SupabaseClient,
  medicationIds: string[],
  lowerBound: string | null,
): Promise<DoseLogEntry[]> {
  return fetchAllPages<DoseLogEntry>((from, to) => {
    let query = supabase
      .schema('meds')
      .from('dose_log')
      .select(ENTRY_COLUMNS)
      .in('medication_id', medicationIds);
    if (lowerBound !== null) query = query.gte('actual_at', lowerBound);
    return query.order('id').range(from, to) as unknown as PromiseLike<Page<DoseLogEntry>>;
  });
}

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

    // 1. Active medications. Stopped medications are not on the shopping list
    //    (D28); their alert rows are cleared below so a restart re-arms them.
    interface MedicationRow {
      id: string;
      patient_id: string;
      requires_prescription: boolean;
      prescription_valid_until: string | null;
      low_stock_threshold_days: number;
      reminder_policy: ReminderPolicy | null;
      primary_caregiver_id: string | null;
    }
    const medicationData = await fetchAllPages<MedicationRow>((from, to) =>
      supabase
        .schema('meds')
        .from('medications')
        .select(
          'id, patient_id, requires_prescription, prescription_valid_until, ' +
            'low_stock_threshold_days, reminder_policy, primary_caregiver_id',
        )
        .is('stopped_at', null)
        .order('id')
        .range(from, to) as unknown as PromiseLike<Page<MedicationRow>>
    );

    // 1b. The alert ledger, unfiltered: rows for stopped or deleted medications
    //     must be cleared too, and they are not in `medicationIds`.
    const alertRows = await fetchAllPages<{ medication_id: string }>((from, to) =>
      supabase
        .schema('meds')
        .from('stock_alerts')
        .select('medication_id')
        .order('medication_id')
        .range(from, to) as unknown as PromiseLike<Page<{ medication_id: string }>>
    );
    const alerted = alertRows.map((row) => row.medication_id);

    if (medicationData.length === 0) {
      // Nothing is active: every alert row is stale and is re-armed. Without
      // active medications there is no circle or dose log to read either.
      if (alerted.length > 0) {
        const { error: clearError } = await supabase
          .schema('meds')
          .from('stock_alerts')
          .delete()
          .in('medication_id', alerted);
        if (clearError) throw clearError;
      }
      return json(200, {
        low: 0,
        alerts: 0,
        cleared: alerted.length,
        notifications: 0,
        sent: 0,
        failed: 0,
        dead_devices: 0,
      });
    }

    const medicationIds = medicationData.map((row) => row.id);
    const patientIds = [...new Set(medicationData.map((row) => row.patient_id))];

    // 2. The circle each patient belongs to, so recipients can be resolved,
    //    plus the patient's reminder settings (the medication's override wins).
    interface PatientRow {
      id: string;
      circle_id: string;
      archived_at: string | null;
      reminder_policy: ReminderPolicy;
      primary_caregiver_id: string | null;
    }
    const patientRows = await fetchAllPages<PatientRow>((from, to) =>
      supabase
        .schema('core')
        .from('patients')
        .select(
          'id, circle_id, archived_at, reminder_policy, primary_caregiver_id',
        )
        .in('id', patientIds)
        .order('id')
        .range(from, to) as unknown as PromiseLike<Page<PatientRow>>
    );

    const patientById = new Map(patientRows.map((row) => [row.id, row]));

    const archivedPatients = new Set(
      patientRows.filter((row) => row.archived_at !== null).map((row) => row.id),
    );

    const medications: ActiveStockMedication[] = medicationData
      .filter((row) => patientById.has(row.patient_id))
      .map((row) => {
        const patient = patientById.get(row.patient_id)!;
        return {
          id: row.id,
          patient_id: row.patient_id,
          circle_id: patient.circle_id,
          patient_archived: archivedPatients.has(row.patient_id),
          low_stock_threshold_days: row.low_stock_threshold_days,
          requires_prescription: row.requires_prescription,
          prescription_valid_until: row.prescription_valid_until,
          // The same coalesce as meds.effective_reminder (D7/D11).
          reminder_policy: row.reminder_policy ?? patient.reminder_policy,
          primary_caregiver_id:
            row.primary_caregiver_id ?? patient.primary_caregiver_id,
        };
      });

    const circleIds = [...new Set(medications.map((row) => row.circle_id))];

    // 3. Regimens (every version: a dose may belong to an older one) and the
    //    stock ledger.
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

    const movements = await fetchAllPages<StockMovementRow>((from, to) =>
      supabase
        .schema('meds')
        .from('stock_movements')
        .select('id, medication_id, kind, quantity, occurred_at')
        .in('medication_id', medicationIds)
        .order('id')
        .range(from, to) as unknown as PromiseLike<Page<StockMovementRow>>
    );

    // 4. The dose-log entries that can change a stock figure. A baseline count
    //    already contains everything at or before its instant, so only later
    //    entries matter. The read is bounded by the oldest baseline; a
    //    medication that was never counted keeps its whole history (its stock
    //    is negative from the beginning of time).
    const countedIds: string[] = [];
    const uncountedIds: string[] = [];
    let lowerBoundMs: number | null = null;
    for (const medication of medications) {
      const mine = movements.filter((row) => row.medication_id === medication.id);
      const baseline = latestCount(mine);
      if (baseline === null) {
        uncountedIds.push(medication.id);
      } else {
        countedIds.push(medication.id);
        const baselineMs = Date.parse(baseline.occurred_at);
        if (lowerBoundMs === null || baselineMs < lowerBoundMs) {
          lowerBoundMs = baselineMs;
        }
      }
    }
    const lowerBound = lowerBoundMs === null
      ? null
      : new Date(lowerBoundMs).toISOString();

    const entries: DoseLogEntry[] = [];

    if (countedIds.length > 0 && lowerBound !== null) {
      entries.push(...await readEntries(supabase, countedIds, lowerBound));
    }

    if (uncountedIds.length > 0) {
      entries.push(...await readEntries(supabase, uncountedIds, null));
    }

    // 5. Who to notify: the circles' owners and caregivers, then their language
    //    and devices. Viewers are never told (D28).
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
    const recipientIds = [...new Set(memberships.map((row) => row.user_id))];

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

    const input: StockAlertInput = {
      now,
      medications,
      regimens,
      movements,
      entries,
      alerted,
      memberships,
      profiles,
      devices,
    };

    const plan = planStockAlerts(input);

    // 6. Send. A dead token is deleted; a transient failure leaves the ledger
    //    row unwritten, so tomorrow's run retries while the medication is low.
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
              channel_id: 'stock_alerts',
              tag: notification.tag,
            },
          },
        });

        attempts.push({ key: notification.medication_id, status: result.status });

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
        console.error(`stock-low: could not delete dead devices: ${errorText(deleteError)}`);
      }
    }

    // 7. Record the episodes that were delivered to at least one device or had
    //    nothing to retry, and re-arm the recovered ones. An episode whose every
    //    send failed transiently stays out of the ledger, so tomorrow's run
    //    retries. The primary key makes a concurrent run harmless (duplicate
    //    beats lost).
    const recorded = rowsToRecord(plan.alerts, (row) => row.medication_id, attempts);
    if (recorded.length > 0) {
      const { error: insertError } = await supabase
        .schema('meds')
        .from('stock_alerts')
        .upsert(
          recorded.map((row) => ({
            medication_id: row.medication_id,
            recipients: row.recipients,
          })),
          { onConflict: 'medication_id', ignoreDuplicates: true },
        );
      if (insertError) throw insertError;
    }

    if (plan.cleared.length > 0) {
      const { error: clearError } = await supabase
        .schema('meds')
        .from('stock_alerts')
        .delete()
        .in('medication_id', plan.cleared);
      if (clearError) throw clearError;
    }

    return json(200, {
      low: plan.low.length,
      alerts: recorded.length,
      to_retry: plan.alerts.length - recorded.length,
      cleared: plan.cleared.length,
      notifications: plan.notifications.length,
      sent,
      failed,
      dead_devices: deadTokens.size,
    });
  } catch (error) {
    // The message may name a table or an HTTP status; never a token or body.
    console.error(`stock-low failed: ${errorText(error)}`);
    return json(500, { error: 'stock alert run failed' });
  }
});
