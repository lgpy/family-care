/**
 * The M5 guard of D22: the TypeScript stock port must agree with every shared
 * stock vector that the Kotlin app is tested against. The file is read from
 * `shared/test-vectors/` - it is the contract, so nothing is copied or
 * generated here.
 *
 * Run from `backend/supabase/functions/`:
 *   deno test --allow-read
 */

import { assertEquals } from './testing.ts';
import { stockSummary, type StockMedication, type StockMovement } from './stock.ts';
import type { Regimen } from './doseSlots.ts';
import type { DoseLogEntry } from './doseStatus.ts';

const vectorsDir = new URL('../../../../shared/test-vectors/', import.meta.url);

interface StockVectorCase {
  name: string;
  today: string;
  medication: StockMedication;
  regimens: Regimen[];
  movements: StockMovement[];
  dose_log: DoseLogEntry[];
  expected: {
    stock: number;
    daily_consumption: number;
    days_remaining: number | null;
    low: boolean;
    shopping_status: string | null;
  };
}

const vectors: { cases: StockVectorCase[] } = JSON.parse(
  await Deno.readTextFile(new URL('stock.json', vectorsDir)),
);

/** The README fixes the tolerance at 1e-9 for every vector number. */
function assertClose(actual: number, expected: number, message: string) {
  const difference = Math.abs(actual - expected);
  if (!(difference <= 1e-9)) {
    throw new Error(
      `${message}: expected ${expected}, got ${actual} (difference ${difference})`,
    );
  }
}

for (const testCase of vectors.cases) {
  Deno.test(`stock: ${testCase.name}`, () => {
    const actual = stockSummary(
      testCase.movements,
      testCase.dose_log,
      testCase.regimens,
      testCase.medication,
      testCase.today,
    );

    assertClose(actual.stock, testCase.expected.stock, `${testCase.name} stock`);
    assertClose(
      actual.daily_consumption,
      testCase.expected.daily_consumption,
      `${testCase.name} daily_consumption`,
    );

    if (testCase.expected.days_remaining === null) {
      assertEquals(
        actual.days_remaining,
        null,
        `${testCase.name} days_remaining`,
      );
    } else {
      assertClose(
        actual.days_remaining as number,
        testCase.expected.days_remaining,
        `${testCase.name} days_remaining`,
      );
    }

    assertEquals(actual.low, testCase.expected.low, `${testCase.name} low`);
    assertEquals(
      actual.shopping_status,
      testCase.expected.shopping_status,
      `${testCase.name} shopping_status`,
    );
  });
}
