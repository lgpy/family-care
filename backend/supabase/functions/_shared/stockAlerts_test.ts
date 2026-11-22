/**
 * Low-stock alert planning fixtures (D4/D8/D26/D28). Each test is one rule of
 * `planStockAlerts`; the IO layer around it is exercised by the pgTAP suite and
 * the local stack.
 *
 * Run from `backend/supabase/functions/`:  deno test --allow-read
 */

import { assertEquals } from './testing.ts';
import {
  currentLocalDate,
  planStockAlerts,
  STOCK_MESSAGES,
  stockAlertTag,
  type ActiveStockMedication,
  type StockAlertInput,
} from './stockAlerts.ts';
import type { Regimen } from './doseSlots.ts';

const CIRCLE = '20000000-0000-4000-8000-00000000000a';
const PATIENT = '40000000-0000-4000-8000-000000000001';
const MED = '50000000-0000-4000-8000-000000000001';
const ALICE = '10000000-0000-4000-8000-000000000001'; // owner
const BOB = '10000000-0000-4000-8000-000000000002'; // caregiver
const CAROL = '10000000-0000-4000-8000-000000000003'; // viewer

const MEDICATION: ActiveStockMedication = {
  id: MED,
  patient_id: PATIENT,
  circle_id: CIRCLE,
  low_stock_threshold_days: 7,
  requires_prescription: false,
  prescription_valid_until: null,
  reminder_policy: 'all_caregivers',
  primary_caregiver_id: null,
};

const REGIMEN: Regimen = {
  id: '60000000-0000-4000-8000-000000000001',
  medication_id: MED,
  schedule_type: 'daily',
  weekdays: null,
  interval_days: null,
  start_date: '2026-11-01',
  end_date: null,
  time_zone: 'Europe/Lisbon',
  times: [{ time: '08:00', quantity: 1 }],
};

/** Four in stock, one a day: four days left, below the default seven. */
function baseInput(overrides: Partial<StockAlertInput> = {}): StockAlertInput {
  return {
    now: '2026-11-18T10:00:00Z', // 2026-11-18 in Lisbon
    medications: [MEDICATION],
    regimens: [REGIMEN],
    movements: [{
      id: 'a0000000-0000-4000-8000-000000000001',
      medication_id: MED,
      kind: 'count',
      quantity: 4,
      occurred_at: '2026-11-18T07:00:00Z',
    }],
    entries: [],
    alerted: [],
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
      { user_id: ALICE, fcm_token: 'token-alice' },
      { user_id: BOB, fcm_token: 'token-bob-1' },
      { user_id: BOB, fcm_token: 'token-bob-2' },
      { user_id: CAROL, fcm_token: 'token-carol' },
    ],
    ...overrides,
  };
}

Deno.test('stock-alerts: a new low episode pushes once to owners and caregivers', () => {
  const plan = planStockAlerts(baseInput());

  assertEquals(plan.low, [MED]);
  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 2 }]);
  assertEquals(plan.cleared, []);
  assertEquals(plan.notifications.length, 2);

  const [alice, bob] = plan.notifications;
  assertEquals(alice.user_id, ALICE);
  assertEquals(alice.language, 'pt-PT');
  assertEquals(alice.title, STOCK_MESSAGES['pt-PT'].title);
  assertEquals(alice.body, 'Um medicamento está a acabar');
  assertEquals(alice.tag, stockAlertTag(MED));
  assertEquals(alice.tokens, ['token-alice']);
  assertEquals(alice.data, { type: 'stock_low' });

  assertEquals(bob.user_id, BOB);
  assertEquals(bob.language, 'en');
  assertEquals(bob.body, 'A medication is running low');
  assertEquals(bob.tokens, ['token-bob-1', 'token-bob-2']);
});

Deno.test('stock-alerts: primary_only pushes go to the primary caregiver alone', () => {
  const plan = planStockAlerts(baseInput({
    medications: [{
      ...MEDICATION,
      reminder_policy: 'primary_only',
      primary_caregiver_id: ALICE,
    }],
  }));

  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 1 }]);
  assertEquals(plan.notifications.map((n) => n.user_id), [ALICE]);
});

Deno.test('stock-alerts: primary_only without a primary falls back to everyone', () => {
  const plan = planStockAlerts(baseInput({
    medications: [{
      ...MEDICATION,
      reminder_policy: 'primary_only',
      primary_caregiver_id: null,
    }],
  }));

  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 2 }]);
  assertEquals(plan.notifications.map((n) => n.user_id), [ALICE, BOB]);
});

Deno.test('stock-alerts: primary_only ignores a primary who is not a caregiver', () => {
  const plan = planStockAlerts(baseInput({
    medications: [{
      ...MEDICATION,
      reminder_policy: 'primary_only',
      primary_caregiver_id: CAROL, // a viewer
    }],
  }));

  // The row is still written, with 0 recipients, so the episode is not retried.
  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 0 }]);
  assertEquals(plan.notifications, []);
});

Deno.test('stock-alerts: an already alerted medication is not pushed again', () => {
  const plan = planStockAlerts(baseInput({ alerted: [MED] }));

  assertEquals(plan.low, [MED]);
  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, []);
});

Deno.test('stock-alerts: a recovered medication clears its alert row', () => {
  const plan = planStockAlerts(baseInput({
    alerted: [MED],
    movements: [{
      id: 'a0000000-0000-4000-8000-000000000001',
      medication_id: MED,
      kind: 'count',
      quantity: 100,
      occurred_at: '2026-11-18T07:00:00Z',
    }],
  }));

  assertEquals(plan.low, []);
  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, [MED]);
});

Deno.test('stock-alerts: a stopped medication is not considered and its row clears', () => {
  const plan = planStockAlerts(baseInput({ medications: [], alerted: [MED] }));

  assertEquals(plan.low, []);
  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, [MED]);
});

Deno.test('stock-alerts: an archived patient hides the medication and clears its row', () => {
  // D30: the job leaves the medication in place but marks the patient
  // archived; it must not push, and the episode re-arms when unarchived.
  const plan = planStockAlerts(baseInput({
    medications: [{ ...MEDICATION, patient_archived: true }],
    alerted: [MED],
  }));

  assertEquals(plan.low, []);
  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, [MED]);
});

Deno.test('stock-alerts: viewers never receive a stock push', () => {
  const plan = planStockAlerts(baseInput({
    devices: [
      { user_id: ALICE, fcm_token: 'token-alice' },
      { user_id: CAROL, fcm_token: 'token-carol' },
    ],
  }));

  assertEquals(plan.notifications.map((n) => n.user_id), [ALICE]);
  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 1 }]);
});

Deno.test('stock-alerts: zero devices still writes the alert row', () => {
  const plan = planStockAlerts(baseInput({ devices: [] }));

  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, [{ medication_id: MED, recipients: 0 }]);
});

Deno.test('stock-alerts: an unknown language falls back to pt-PT', () => {
  const plan = planStockAlerts(baseInput({
    profiles: [
      { id: ALICE, preferred_language: null },
      { id: BOB, preferred_language: 'fr' },
    ],
    devices: [
      { user_id: ALICE, fcm_token: 'token-alice' },
      { user_id: BOB, fcm_token: 'token-bob-1' },
    ],
  }));

  assertEquals(plan.notifications.map((n) => n.language), ['pt-PT', 'pt-PT']);
});

Deno.test('stock-alerts: a medication without a regimen is never low', () => {
  const plan = planStockAlerts(baseInput({ regimens: [], alerted: [MED] }));

  assertEquals(plan.low, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, [MED]);
});

Deno.test('stock-alerts: today is the local date in the regimen zone', () => {
  // 23:30 UTC on the 17th is already the 18th in Lisbon (UTC+0 in November, so
  // this one is a same-day check) and the 18th in Tokyo (UTC+9).
  assertEquals(currentLocalDate('2026-11-17T23:30:00Z', 'Europe/Lisbon'), '2026-11-17');
  assertEquals(currentLocalDate('2026-11-17T23:30:00Z', 'Asia/Tokyo'), '2026-11-18');
  assertEquals(currentLocalDate('2026-11-17T23:30:00Z', 'America/Sao_Paulo'), '2026-11-17');
  // Lisbon summer time is UTC+1.
  assertEquals(currentLocalDate('2026-07-01T23:30:00Z', 'Europe/Lisbon'), '2026-07-02');
});

Deno.test('stock-alerts: a medication stopped in the future is still active', () => {
  const plan = planStockAlerts(baseInput({
    medications: [{ ...MEDICATION, stopped_at: '2026-11-20T00:00:00Z' }],
  }));

  assertEquals(plan.low, [MED]);
  assertEquals(plan.alerts.map((a) => a.medication_id), [MED]);
});

Deno.test('stock-alerts: a medication whose stop has taken effect is skipped and its row clears', () => {
  for (const stoppedAt of ['2026-11-18T10:00:00Z', '2026-11-17T00:00:00Z']) {
    const plan = planStockAlerts(baseInput({
      medications: [{ ...MEDICATION, stopped_at: stoppedAt }],
      alerted: [MED],
    }));

    assertEquals(plan.low, [], stoppedAt);
    assertEquals(plan.notifications, [], stoppedAt);
    assertEquals(plan.alerts, [], stoppedAt);
    assertEquals(plan.cleared, [MED], stoppedAt);
  }
});

Deno.test('stock-alerts: a null stopped_at is active', () => {
  const plan = planStockAlerts(baseInput({
    medications: [{ ...MEDICATION, stopped_at: null }],
  }));

  assertEquals(plan.low, [MED]);
});

Deno.test('stock-alerts: a medication that was never counted is untracked, never low', () => {
  // No movement of any kind: the maths say 0 days left, but nobody has told
  // the app what is in the box. The app treats it as untracked (never low).
  const plan = planStockAlerts(baseInput({ movements: [], alerted: [MED] }));

  assertEquals(plan.low, []);
  assertEquals(plan.notifications, []);
  assertEquals(plan.alerts, []);
  assertEquals(plan.cleared, [MED]);
});

Deno.test('stock-alerts: a refill or discard alone makes a medication tracked', () => {
  for (const kind of ['refill', 'discard'] as const) {
    const plan = planStockAlerts(baseInput({
      movements: [{
        id: 'a0000000-0000-4000-8000-0000000000f1',
        medication_id: MED,
        kind,
        quantity: 1,
        occurred_at: '2026-11-18T07:00:00Z',
      }],
    }));

    // Nothing counted: stock is the refill (1) or the discard (-1), 1 or 0 days.
    assertEquals(plan.low, [MED], kind);
  }
});

Deno.test('stock-alerts: another medication\'s movements do not make this one tracked', () => {
  const plan = planStockAlerts(baseInput({
    movements: [{
      id: 'a0000000-0000-4000-8000-0000000000f2',
      medication_id: '50000000-0000-4000-8000-0000000000ff',
      kind: 'count',
      quantity: 4,
      occurred_at: '2026-11-18T07:00:00Z',
    }],
  }));

  assertEquals(plan.low, []);
});
