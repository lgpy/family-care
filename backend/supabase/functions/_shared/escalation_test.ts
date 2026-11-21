/**
 * Escalation-selection fixtures (docs/domain/care-circle.md "Reminder
 * routing", D4/D7/D11/D24). Each test is one rule; the IO layer around
 * `planEscalations` is exercised by the pgTAP suite and the local stack.
 *
 * Run from `backend/supabase/functions/`:  deno test --allow-read
 */

import { assert, assertEquals } from './testing.ts';
import {
  circleRecipients,
  MESSAGES,
  planEscalations,
  type EscalationInput,
} from './escalation.ts';
import type { Regimen } from './doseSlots.ts';

const PATIENT = '40000000-0000-4000-8000-000000000001';
const CIRCLE = '20000000-0000-4000-8000-00000000000a';
const ALICE = '10000000-0000-4000-8000-000000000001'; // owner, primary
const BOB = '10000000-0000-4000-8000-000000000002'; // caregiver
const CAROL = '10000000-0000-4000-8000-000000000003'; // viewer

const REGIMEN: Regimen = {
  id: '60000000-0000-4000-8000-000000000001',
  medication_id: '50000000-0000-4000-8000-000000000001',
  schedule_type: 'daily',
  weekdays: null,
  interval_days: null,
  start_date: '2026-11-01',
  end_date: null,
  time_zone: 'Europe/Lisbon',
  times: [{ time: '08:00', quantity: 1 }],
};

function baseInput(overrides: Partial<EscalationInput> = {}): EscalationInput {
  return {
    now: '2026-11-16T09:00:00Z', // one hour after the 08:00 slot
    reminders: [{
      medication_id: REGIMEN.medication_id,
      patient_id: PATIENT,
      circle_id: CIRCLE,
      reminder_policy: 'primary_with_escalation',
      primary_caregiver_id: ALICE,
      escalation_delay_minutes: 30,
      missed_window_minutes: 120,
    }],
    regimens: [REGIMEN],
    entries: [],
    escalations: [],
    memberships: [
      { circle_id: CIRCLE, user_id: ALICE, role: 'owner' },
      { circle_id: CIRCLE, user_id: BOB, role: 'caregiver' },
      { circle_id: CIRCLE, user_id: CAROL, role: 'viewer' },
    ],
    profiles: [
      { id: ALICE, preferred_language: 'pt-PT' },
      { id: BOB, preferred_language: 'en' },
      { id: CAROL, preferred_language: 'pt-PT' },
    ],
    devices: [
      { user_id: ALICE, fcm_token: 'tok-alice' },
      { user_id: BOB, fcm_token: 'tok-bob' },
      { user_id: CAROL, fcm_token: 'tok-carol' },
    ],
    ...overrides,
  };
}

Deno.test('escalation: an overdue slot notifies the caregivers but never the primary or a viewer', () => {
  const plan = planEscalations(baseInput());

  assertEquals(plan.overdue.length, 1, 'one overdue slot');
  assertEquals(plan.notifications.length, 1, 'one notification');
  const notification = plan.notifications[0];
  assertEquals(notification.user_id, BOB);
  assertEquals(notification.tokens, ['tok-bob']);
  assertEquals(notification.language, 'en');
  assertEquals(notification.title, 'Family Care');
  assertEquals(notification.body, 'A dose needs attention');
  assertEquals(notification.tag, `escalation:${PATIENT}:2026-11-16T08:00:00Z`);
  assertEquals(notification.data, {
    type: 'escalation',
    patient_id: PATIENT,
    scheduled_local: '2026-11-16T08:00',
  });
  // No health data in the payload, and no name anywhere.
  assert(!JSON.stringify(notification).toLowerCase().includes('med one'));

  assertEquals(plan.escalations, [{
    regimen_id: REGIMEN.id,
    patient_id: PATIENT,
    scheduled_local: '2026-11-16T08:00',
    recipients: 1,
  }]);
});

Deno.test('escalation: a slot already in the ledger is skipped', () => {
  const plan = planEscalations(baseInput({
    escalations: [{
      regimen_id: REGIMEN.id,
      scheduled_local: '2026-11-16T08:00:00', // PostgREST form
    }],
  }));
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
  assertEquals(plan.overdue, []);
});

Deno.test('escalation: a confirmed slot is not escalated', () => {
  const plan = planEscalations(baseInput({
    entries: [{
      id: '70000000-0000-4000-8000-000000000001',
      regimen_id: REGIMEN.id,
      medication_id: REGIMEN.medication_id,
      scheduled_local: '2026-11-16T08:00',
      outcome: 'taken',
      actual_at: '2026-11-16T08:10:00Z',
      corrects_entry_id: null,
    }],
  }));
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
});

Deno.test('escalation: a slot past the missed window is not escalated', () => {
  const plan = planEscalations(baseInput({ now: '2026-11-16T10:00:01Z' }));
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
});

Deno.test('escalation: a slot inside the escalation delay is not escalated yet', () => {
  const plan = planEscalations(baseInput({ now: '2026-11-16T08:20:00Z' }));
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
});

Deno.test('escalation: all_caregivers is local reminders only', () => {
  const input = baseInput();
  input.reminders[0].reminder_policy = 'all_caregivers';
  const plan = planEscalations(input);
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
});

Deno.test('escalation: primary_only never escalates to the other caregivers', () => {
  const input = baseInput();
  input.reminders[0].reminder_policy = 'primary_only';
  const plan = planEscalations(input);
  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations, []);
  assertEquals(plan.overdue, []);
});

Deno.test('escalation: a medication override routes only that medication', () => {
  const overrideMedication = '50000000-0000-4000-8000-000000000009';
  const input = baseInput();
  input.reminders = [
    // Patient policy is all_caregivers (no escalation).
    { ...input.reminders[0], reminder_policy: 'all_caregivers' },
    // This medication overrides it (and sets its own primary).
    {
      ...input.reminders[0],
      medication_id: overrideMedication,
      reminder_policy: 'primary_with_escalation',
      primary_caregiver_id: ALICE,
    },
  ];
  input.regimens = [
    REGIMEN,
    { ...REGIMEN, id: '60000000-0000-4000-8000-000000000009', medication_id: overrideMedication },
  ];

  const plan = planEscalations(input);
  assertEquals(plan.escalations.length, 1);
  assertEquals(plan.escalations[0].regimen_id, '60000000-0000-4000-8000-000000000009');
  assertEquals(plan.notifications.map((n) => n.user_id), [BOB]);
});

Deno.test('escalation: a null primary (D24) notifies every caregiver', () => {
  const input = baseInput();
  input.reminders[0].primary_caregiver_id = null;
  const plan = planEscalations(input);

  assertEquals(plan.notifications.map((n) => n.user_id), [ALICE, BOB]);
  // Carol is a viewer and stays out of it even now.
  assert(!plan.notifications.some((n) => n.user_id === CAROL));
  assertEquals(plan.escalations[0].recipients, 2);
});

Deno.test('escalation: the notification is localized per recipient profile', () => {
  const input = baseInput();
  input.reminders[0].primary_caregiver_id = null;
  input.profiles = [
    { id: ALICE, preferred_language: 'pt-PT' },
    { id: BOB, preferred_language: 'en' },
  ];
  const plan = planEscalations(input);

  const alice = plan.notifications.find((n) => n.user_id === ALICE);
  const bob = plan.notifications.find((n) => n.user_id === BOB);
  assert(alice && bob);
  assertEquals(alice.body, MESSAGES['pt-PT'].body);
  assertEquals(alice.body, 'Uma toma precisa de atenção');
  assertEquals(bob.body, MESSAGES.en.body);
});

Deno.test('escalation: a recipient without a device is skipped but the slot is recorded', () => {
  const input = baseInput();
  input.devices = [];
  const plan = planEscalations(input);

  assertEquals(plan.notifications, []);
  assertEquals(plan.escalations.length, 1);
  assertEquals(plan.escalations[0].recipients, 0);
});

Deno.test('escalation: a spring-forward slot uses the gap-shifted instant', () => {
  // Lisbon 2026-03-29 01:30 does not exist; the slot's instant is 01:30Z
  // (02:30+01:00). At 02:04Z it is 34 minutes overdue.
  const dstRegimen: Regimen = {
    ...REGIMEN,
    id: '60000000-0000-4000-8000-00000000000d',
    start_date: '2026-03-01',
    times: [{ time: '01:30', quantity: 1 }],
  };
  const plan = planEscalations(baseInput({
    now: '2026-03-29T02:04:00Z',
    regimens: [dstRegimen],
  }));

  assertEquals(plan.overdue.length, 1);
  assertEquals(plan.overdue[0].scheduled_local, '2026-03-29T01:30');
  assertEquals(plan.overdue[0].instant, '2026-03-29T01:30:00Z');
  assertEquals(plan.notifications[0].tag, `escalation:${PATIENT}:2026-03-29T01:30:00Z`);
  assertEquals(plan.notifications[0].data.scheduled_local, '2026-03-29T01:30');
});

Deno.test('escalation: recipients exclude the primary, viewers and the other circle', () => {
  assertEquals(
    circleRecipients(baseInput().memberships, CIRCLE, ALICE),
    [BOB],
  );
  assertEquals(
    circleRecipients(baseInput().memberships, CIRCLE, null),
    [ALICE, BOB],
  );
  assertEquals(
    circleRecipients([
      ...baseInput().memberships,
      { circle_id: '20000000-0000-4000-8000-00000000000b', user_id: 'other', role: 'owner' },
    ], CIRCLE, null),
    [ALICE, BOB],
  );
});
