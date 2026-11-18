/**
 * The M4 guard of D22: the TypeScript port must agree with every shared vector
 * that the Kotlin app is tested against. Read the files directly from
 * `shared/test-vectors/` - they are the contract, so nothing is copied or
 * generated here.
 *
 * Run from `backend/supabase/functions/`:
 *   deno test --allow-read
 */

import { assertEquals } from './testing.ts';
import { doseSlots, type Regimen } from './doseSlots.ts';
import { doseStatus, type DoseLogEntry } from './doseStatus.ts';

const vectorsDir = new URL('../../../../shared/test-vectors/', import.meta.url);

interface SlotVectorCase {
  name: string;
  regimens: Regimen[];
  from: string;
  to: string;
  expected: {
    regimen_id: string;
    scheduled_local: string;
    instant: string;
    quantity: number;
  }[];
}

interface SlotVectors {
  cases: SlotVectorCase[];
}

interface StatusVectorCase {
  name: string;
  slot: {
    regimen_id: string;
    medication_id: string;
    scheduled_local: string;
    instant: string;
    quantity: number;
  };
  entries: DoseLogEntry[];
  now: string;
  escalation_delay_minutes?: number;
  missed_window_minutes?: number;
  expected: {
    state: string;
    confirmation_count: number;
    confirmed_twice: boolean;
  };
}

interface StatusVectors {
  escalation_delay_minutes: number;
  missed_window_minutes: number;
  cases: StatusVectorCase[];
}

const slotVectors: SlotVectors = JSON.parse(
  await Deno.readTextFile(new URL('dose-slots.json', vectorsDir)),
);
const statusVectors: StatusVectors = JSON.parse(
  await Deno.readTextFile(new URL('dose-status.json', vectorsDir)),
);

for (const testCase of slotVectors.cases) {
  Deno.test(`dose-slots: ${testCase.name}`, () => {
    const actual = doseSlots(testCase.regimens, testCase.from, testCase.to);
    // The vectors carry exactly these four fields per slot; unknown fields are
    // ignored by readers, extra fields here would be drift.
    assertEquals(
      actual.map((slot) => ({
        regimen_id: slot.regimen_id,
        scheduled_local: slot.scheduled_local,
        instant: slot.instant,
        quantity: slot.quantity,
      })),
      testCase.expected,
      testCase.name,
    );
  });
}

for (const testCase of statusVectors.cases) {
  Deno.test(`dose-status: ${testCase.name}`, () => {
    const result = doseStatus(testCase.slot, testCase.entries, testCase.now, {
      escalationDelayMinutes: testCase.escalation_delay_minutes ??
        statusVectors.escalation_delay_minutes,
      missedWindowMinutes: testCase.missed_window_minutes ??
        statusVectors.missed_window_minutes,
    });
    assertEquals(
      {
        state: result.state,
        confirmation_count: result.confirmation_count,
        confirmed_twice: result.confirmed_twice,
      },
      testCase.expected,
      testCase.name,
    );
  });
}
