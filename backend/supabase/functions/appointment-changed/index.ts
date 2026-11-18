/**
 * appointment-changed: tell the other phones that an appointment was added or
 * changed (build-plan §M6, docs/domain/appointments.md "Change push", D4/D27/D29).
 *
 * The `appointments.appointments` AFTER INSERT/UPDATE trigger (pg_net, see the
 * appointment_push migration) posts `{ "appointment_id": "...", "actor": "..." }`
 * here with the shared secret in `X-Cron-Secret`. The function reads the row with
 * the service role and sends a **visible, localized** notification to every
 * device of the circle's owners/caregivers except the person who made the change.
 *
 * Payload: `type=appointment_changed` only - no patient, doctor, place or title
 * (D4). Tapping it opens the Agenda.
 */

import { createClient } from 'jsr:@supabase/supabase-js@2';
import {
  planAppointmentChanged,
  type AppointmentChange,
} from '../_shared/appointmentChanged.ts';
import type { Membership, Profile, PushDevice } from '../_shared/escalation.ts';
import { createFcmSender, parseServiceAccount } from '../_shared/fcm.ts';
import { errorText } from '../_shared/log.ts';

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

  let appointmentId: string | null = null;
  let actor: string | null = null;
  try {
    const body = await request.json() as { appointment_id?: unknown; actor?: unknown };
    if (typeof body.appointment_id === 'string') appointmentId = body.appointment_id;
    if (typeof body.actor === 'string') actor = body.actor;
  } catch {
    return json(400, { error: 'invalid JSON body' });
  }
  if (appointmentId === null) {
    return json(400, { error: 'appointment_id is required' });
  }

  try {
    const account = parseServiceAccount(serviceAccountJson);
    const sender = createFcmSender(account, { fetch });
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: appointmentRow, error: appointmentError } = await supabase
      .schema('appointments')
      .from('appointments')
      .select('id, patient_id')
      .eq('id', appointmentId)
      .maybeSingle();
    if (appointmentError) throw appointmentError;

    // A deleted appointment (cascade) has no circle left to notify.
    let appointment: AppointmentChange | null = null;

    if (appointmentRow) {
      const { data: patientRow, error: patientError } = await supabase
        .schema('core')
        .from('patients')
        .select('id, circle_id')
        .eq('id', appointmentRow.patient_id)
        .maybeSingle();
      if (patientError) throw patientError;

      if (patientRow) {
        appointment = {
          id: appointmentRow.id as string,
          patient_id: appointmentRow.patient_id as string,
          circle_id: patientRow.circle_id as string,
        };
      }
    }

    const memberships: Membership[] = [];
    let profiles: Profile[] = [];
    let devices: PushDevice[] = [];

    if (appointment) {
      const { data: membershipRows, error: membershipError } = await supabase
        .schema('core')
        .from('memberships')
        .select('circle_id, user_id, role')
        .eq('circle_id', appointment.circle_id)
        .in('role', ['owner', 'caregiver']);
      if (membershipError) throw membershipError;
      memberships.push(...((membershipRows ?? []) as Membership[]));

      const recipientIds = [...new Set(memberships.map((row) => row.user_id))];

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
    }

    const plan = planAppointmentChanged({
      appointment,
      actor,
      memberships,
      profiles,
      devices,
    });

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
              channel_id: 'appointment_updates',
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
        console.error(
          `appointment-changed: could not delete dead devices: ${errorText(deleteError)}`,
        );
      }
    }

    return json(200, {
      notifications: plan.notifications.length,
      devices: devices.length,
      sent,
      failed,
      dead_devices: deadTokens.size,
    });
  } catch (error) {
    // The message may name a table or an HTTP status; never a token or body.
    console.error(`appointment-changed failed: ${errorText(error)}`);
    return json(500, { error: 'appointment change broadcast failed' });
  }
});
