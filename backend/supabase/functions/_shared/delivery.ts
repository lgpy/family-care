/**
 * Which ledger rows a job may write after it has sent its pushes (escalate,
 * stock-low). Pure, so `delivery_test.ts` can pin it; the IO layer only collects
 * the attempts.
 *
 * A ledger row means "this was dealt with, do not try again". It is written when
 * the notification reached at least one device, or when there was nothing to
 * retry: no recipient, no device, or only tokens FCM reported dead (those are
 * deleted, so a retry could never reach anyone). It is NOT written when every
 * attempt for the slot ended in a retryable or failed result, so the next tick
 * tries again (a slot that becomes missed is no longer escalated, which bounds
 * the retries).
 */

import type { FcmResult } from './fcm.ts';

/** The outcome of one push to one token. */
export type SendStatus = FcmResult['status'];

/** One push to one device for the slot or episode identified by `key`. */
export interface SendAttempt {
  key: string;
  status: SendStatus;
}

/** The rows of `rows` whose key may be written to the ledger. */
export function rowsToRecord<T>(
  rows: T[],
  keyOf: (row: T) => string,
  attempts: SendAttempt[],
): T[] {
  const delivered = new Set<string>();
  const needsRetry = new Set<string>();
  for (const attempt of attempts) {
    if (attempt.status === 'sent') delivered.add(attempt.key);
    else if (attempt.status === 'retry' || attempt.status === 'failed') {
      needsRetry.add(attempt.key);
    }
  }
  return rows.filter((row) => {
    const key = keyOf(row);
    return delivered.has(key) || !needsRetry.has(key);
  });
}
