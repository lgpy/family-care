/**
 * Which ledger rows a run may write once the pushes have gone out. A row says
 * "do not try this again": it must not be written while every attempt for the
 * slot ended in a transient failure, or no caregiver ever hears about it.
 */

import { assertEquals } from './testing.ts';
import { rowsToRecord, type SendAttempt } from './delivery.ts';

interface Row {
  key: string;
}
const rows: Row[] = [{ key: 'a' }, { key: 'b' }];
const keyOf = (row: Row) => row.key;
const keys = (attempts: SendAttempt[], input: Row[] = rows) =>
  rowsToRecord(input, keyOf, attempts).map(keyOf);

Deno.test('delivery: a slot with no attempt (no recipients or devices) is recorded', () => {
  assertEquals(keys([]), ['a', 'b']);
});

Deno.test('delivery: a slot delivered to one device is recorded', () => {
  assertEquals(keys([{ key: 'a', status: 'sent' }]), ['a', 'b']);
});

Deno.test('delivery: every attempt retryable means no row, so the next tick retries', () => {
  assertEquals(
    keys([{ key: 'a', status: 'retry' }, { key: 'a', status: 'retry' }]),
    ['b'],
  );
});

Deno.test('delivery: every attempt failed means no row', () => {
  assertEquals(keys([{ key: 'a', status: 'failed' }]), ['b']);
});

Deno.test('delivery: one device reached is enough even if another failed', () => {
  assertEquals(
    keys([{ key: 'a', status: 'retry' }, { key: 'a', status: 'sent' }]),
    ['a', 'b'],
  );
});

Deno.test('delivery: only dead tokens means nothing left to retry, so the row is written', () => {
  assertEquals(
    keys([{ key: 'a', status: 'unregistered' }, { key: 'a', status: 'invalid' }]),
    ['a', 'b'],
  );
});

Deno.test('delivery: a dead token plus a retryable one still retries', () => {
  assertEquals(
    keys([{ key: 'a', status: 'unregistered' }, { key: 'a', status: 'retry' }]),
    ['b'],
  );
});

Deno.test('delivery: slots are judged independently', () => {
  assertEquals(
    keys([{ key: 'a', status: 'retry' }, { key: 'b', status: 'sent' }]),
    ['b'],
  );
});
