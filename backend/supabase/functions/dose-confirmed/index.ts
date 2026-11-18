/**
 * dose-confirmed: tell the other phones to clear a slot (build-plan §M4).
 *
 * The `meds.dose_log` AFTER INSERT trigger (pg_net, see the
 * push_and_escalation migration) posts `{ "dose_log_id": "..." }` here with the
 * shared secret in `X-Cron-Secret`. The function reads the row with the service
 * role and sends a **data-only, high-priority** message to every device of the
 * circle's owners/caregivers except the confirmer's own, so each phone can
 * cancel the local alarm's notification and the escalation.
 *
 * Payload: `type=clear, regimen_id, scheduled_local` - ids and a wall-clock
 * time only, no names and nothing health-related (D4).
 */

import { createClient } from 'jsr:@supabase/supabase-js@2';
import { normalizeScheduledLocal } from '../_shared/doseStatus.ts';
import { createFcmSender, parseServiceAccount } from '../_shared/fcm.ts';

function secretsEqual(given: string | null, expected: string): boolean {
  if (given === null) return false;
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

  let doseLogId: string | null = null;
  try {
    const body = await request.json() as { dose_log_id?: unknown };
    if (typeof body.dose_log_id === 'string') doseLogId = body.dose_log_id;
  } catch {
    return json(400, { error: 'invalid JSON body' });
  }
  if (doseLogId === null) {
    return json(400, { error: 'dose_log_id is required' });
  }

  try {
    const account = parseServiceAccount(serviceAccountJson);
    const sender = createFcmSender(account, { fetch });
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: doseRow, error: doseError } = await supabase
      .schema('meds')
      .from('dose_log')
      .select('id, regimen_id, medication_id, scheduled_local, confirmed_by')
      .eq('id', doseLogId)
      .maybeSingle();
    if (doseError) throw doseError;
    if (!doseRow) {
      // The row was deleted by a cascade between insert and this call: nothing
      // to clear.
      return json(200, { cleared: 0, devices: 0 });
    }

    const { data: medicationRow, error: medicationError } = await supabase
      .schema('meds')
      .from('medications')
      .select('patient_id')
      .eq('id', doseRow.medication_id)
      .maybeSingle();
    if (medicationError) throw medicationError;
    if (!medicationRow) {
      return json(200, { cleared: 0, devices: 0 });
    }

    const { data: patientRow, error: patientError } = await supabase
      .schema('core')
      .from('patients')
      .select('circle_id')
      .eq('id', medicationRow.patient_id)
      .maybeSingle();
    if (patientError) throw patientError;
    if (!patientRow) {
      return json(200, { cleared: 0, devices: 0 });
    }

    // Owners and caregivers of the circle; the confirmer's own phones are
    // excluded because that phone already knows.
    const { data: membershipRows, error: membershipError } = await supabase
      .schema('core')
      .from('memberships')
      .select('user_id')
      .eq('circle_id', patientRow.circle_id)
      .in('role', ['owner', 'caregiver']);
    if (membershipError) throw membershipError;

    const recipientIds = [...new Set(
      (membershipRows ?? [])
        .map((row: { user_id: string }) => row.user_id)
        .filter((userId: string) => userId !== doseRow.confirmed_by),
    )];

    if (recipientIds.length === 0) {
      return json(200, { cleared: 0, devices: 0 });
    }

    const { data: deviceRows, error: deviceError } = await supabase
      .schema('core')
      .from('push_devices')
      .select('user_id, fcm_token')
      .in('user_id', recipientIds);
    if (deviceError) throw deviceError;

    const data = {
      type: 'clear',
      regimen_id: doseRow.regimen_id as string,
      scheduled_local: normalizeScheduledLocal(
        doseRow.scheduled_local as string,
      ),
    };

    const deadTokens = new Set<string>();
    let sent = 0;

    for (const device of (deviceRows ?? []) as Array<{ fcm_token: string }>) {
      const result = await sender.send(device.fcm_token, {
        // Data-only: the app decides what to cancel; no visible notification.
        data,
        android: { priority: 'HIGH' },
      });

      if (result.status === 'sent') {
        sent += 1;
      } else if (result.status === 'unregistered' || result.status === 'invalid') {
        deadTokens.add(device.fcm_token);
      }
    }

    if (deadTokens.size > 0) {
      const { error: deleteError } = await supabase
        .schema('core')
        .from('push_devices')
        .delete()
        .in('fcm_token', [...deadTokens]);
      if (deleteError) {
        console.error(
          `dose-confirmed: could not delete dead devices: ${deleteError.message}`,
        );
      }
    }

    return json(200, {
      cleared: sent,
      devices: (deviceRows ?? []).length,
      dead_devices: deadTokens.size,
    });
  } catch (error) {
    console.error(
      `dose-confirmed failed: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
    return json(500, { error: 'clear broadcast failed' });
  }
});
