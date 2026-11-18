/**
 * Appointment change push planning fixtures (D4/D8/D29). Each test is one rule
 * of `planAppointmentChanged`; the IO layer around it is exercised by the pgTAP
 * suite and the local stack.
 *
 * Run from `backend/supabase/functions/`:  deno test --allow-read
 */

import { assert, assertEquals } from './testing.ts';
import {
  APPOINTMENT_MESSAGES,
  appointmentTag,
  planAppointmentChanged,
  type AppointmentChange,
  type AppointmentChangedInput,
} from './appointmentChanged.ts';
import type { Membership, Profile, PushDevice } from './escalation.ts';

const CIRCLE = '20000000-0000-4000-8000-00000000000a';
const OTHER_CIRCLE = '20000000-0000-4000-8000-00000000000b';
const PATIENT = '40000000-0000-4000-8000-000000000001';
const APPOINTMENT = '70000000-0000-4000-8000-000000000001';
const ALICE = '10000000-0000-4000-8000-000000000001'; // owner
const BOB = '10000000-0000-4000-8000-000000000002'; // caregiver
const CAROL = '10000000-0000-4000-8000-000000000003'; // viewer
const DAVE = '10000000-0000-4000-8000-000000000004'; // owner of another circle
const ERIN = '10000000-0000-4000-8000-000000000005'; // caregiver, no device

const APPOINTMENT_ROW: AppointmentChange = {
  id: APPOINTMENT,
  patient_id: PATIENT,
  circle_id: CIRCLE,
};

const MEMBERSHIPS: Membership[] = [
  { circle_id: CIRCLE, user_id: ALICE, role: 'owner' },
  { circle_id: CIRCLE, user_id: BOB, role: 'caregiver' },
  { circle_id: CIRCLE, user_id: CAROL, role: 'viewer' },
  { circle_id: CIRCLE, user_id: ERIN, role: 'caregiver' },
  { circle_id: OTHER_CIRCLE, user_id: DAVE, role: 'owner' },
];

const PROFILES: Profile[] = [
  { id: ALICE, preferred_language: 'en' },
  { id: BOB, preferred_language: 'pt-PT' },
  { id: CAROL, preferred_language: 'en' },
  { id: DAVE, preferred_language: 'en' },
  { id: ERIN, preferred_language: 'en' },
];

const DEVICES: PushDevice[] = [
  { user_id: ALICE, fcm_token: 'alice-token' },
  { user_id: BOB, fcm_token: 'bob-token-1' },
  { user_id: BOB, fcm_token: 'bob-token-2' },
  { user_id: CAROL, fcm_token: 'carol-token' },
  { user_id: DAVE, fcm_token: 'dave-token' },
];

function baseInput(overrides: Partial<AppointmentChangedInput> = {}): AppointmentChangedInput {
  return {
    appointment: APPOINTMENT_ROW,
    actor: ALICE,
    memberships: MEMBERSHIPS,
    profiles: PROFILES,
    devices: DEVICES,
    ...overrides,
  };
}

function recipientIds(input: AppointmentChangedInput): string[] {
  return planAppointmentChanged(input).notifications.map((row) => row.user_id);
}

Deno.test('a missing appointment plans nothing', () => {
  const plan = planAppointmentChanged(baseInput({ appointment: null }));
  assertEquals(plan.notifications, []);
  assertEquals(plan.recipients, 0);
});

Deno.test('the actor is never notified', () => {
  assertEquals(recipientIds(baseInput()), [BOB]);
  assertEquals(recipientIds(baseInput({ actor: BOB })), [ALICE]);
});

Deno.test('viewers and other circles are never notified', () => {
  const ids = recipientIds(baseInput({ actor: null }));
  assert(!ids.includes(CAROL), 'a viewer has a device but must not be a recipient');
  assert(!ids.includes(DAVE), 'another circle\'s owner must not be a recipient');
  assertEquals(ids, [ALICE, BOB]);
});

Deno.test('a null actor notifies every owner and caregiver with a device', () => {
  // Erin is a caregiver but has no registered device.
  assertEquals(recipientIds(baseInput({ actor: null })), [ALICE, BOB]);
});

Deno.test('a user without a device is skipped', () => {
  const ids = recipientIds(baseInput({ actor: null }));
  assert(!ids.includes(ERIN), 'no device means no notification');
});

Deno.test('one notification per user, one token per device', () => {
  const plan = planAppointmentChanged(baseInput());
  assertEquals(plan.notifications.length, 1);
  assertEquals(plan.notifications[0].user_id, BOB);
  assertEquals(plan.notifications[0].tokens, ['bob-token-1', 'bob-token-2']);
  assertEquals(plan.recipients, 1);
});

Deno.test('language follows each recipient profile, defaulting to pt-PT', () => {
  const plan = planAppointmentChanged(baseInput({ actor: null }));
  const byUser = new Map(plan.notifications.map((row) => [row.user_id, row]));

  assertEquals(byUser.get(ALICE)?.language, 'en');
  assertEquals(byUser.get(ALICE)?.body, APPOINTMENT_MESSAGES.en.body);
  assertEquals(byUser.get(BOB)?.language, 'pt-PT');
  assertEquals(byUser.get(BOB)?.body, 'Uma consulta foi marcada ou alterada');

  const noProfile = planAppointmentChanged(baseInput({
    actor: ALICE,
    profiles: [],
  }));
  assertEquals(noProfile.notifications[0].language, 'pt-PT');
});

Deno.test('notifications carry the tag, title and the type-only payload', () => {
  const plan = planAppointmentChanged(baseInput());
  const notification = plan.notifications[0];

  assertEquals(notification.tag, `appt-${APPOINTMENT}`);
  assertEquals(notification.tag, appointmentTag(APPOINTMENT));
  assertEquals(notification.title, 'Family Care');
  assertEquals(notification.data, { type: 'appointment_changed' });

  // D4: ids and a type only; nothing patient-related leaks into the payload.
  assertEquals(Object.keys(notification.data).sort(), ['type']);
});
