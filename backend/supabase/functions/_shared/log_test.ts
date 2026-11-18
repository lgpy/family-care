/**
 * The error-summary helper (`errorText`) used by the three edge functions.
 *
 * Run from `backend/supabase/functions/`:  deno test --allow-read
 */

import { assertEquals } from './testing.ts';
import { errorText } from './log.ts';

Deno.test('errorText: an Error keeps its message', () => {
  assertEquals(errorText(new Error('boom')), 'boom');
});

Deno.test('errorText: a PostgREST-shaped object logs message and code', () => {
  assertEquals(
    errorText({
      message: 'duplicate key value violates unique constraint',
      details: 'Key (id)=(...) already exists.',
      hint: null,
      code: '23505',
    }),
    'duplicate key value violates unique constraint (code 23505)',
  );
});

Deno.test('errorText: a code alone still says something', () => {
  assertEquals(errorText({ code: '42501' }), 'request failed (code 42501)');
});

Deno.test('errorText: primitives survive unchanged', () => {
  assertEquals(errorText('plain failure'), 'plain failure');
  assertEquals(errorText(42), '42');
});

Deno.test('errorText: never stringifies an object to [object Object]', () => {
  assertEquals(errorText({}), 'unknown error');
  assertEquals(errorText(null), 'unknown error');
  assertEquals(errorText(undefined), 'unknown error');
});
