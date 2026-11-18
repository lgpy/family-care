/**
 * One-line summaries of thrown values for `console.error`, so a Supabase
 * (PostgREST) error object logs as its message instead of "[object Object]".
 *
 * A PostgREST error is a plain object (`{ message, details, hint, code }`), not
 * an `Error`, which is why `String(error)` loses everything. Only `message` and
 * `code` are read on purpose: `details` and `hint` can echo request data, and
 * these functions must never log a token, a message body or patient data
 * (D4, D27).
 */
export function errorText(error: unknown): string {
  if (error instanceof Error) return error.message;

  if (typeof error === 'object' && error !== null) {
    const { message, code } = error as { message?: unknown; code?: unknown };
    const text = typeof message === 'string' && message !== '' ? message : '';
    const suffix = typeof code === 'string' && code !== '' ? ` (code ${code})` : '';
    if (text !== '' || suffix !== '') {
      return `${text === '' ? 'request failed' : text}${suffix}`;
    }
  }

  if (
    typeof error === 'string' || typeof error === 'number' ||
    typeof error === 'boolean'
  ) {
    return String(error);
  }

  return 'unknown error';
}
