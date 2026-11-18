/**
 * Tiny assertion helpers instead of `jsr:@std/assert`: the Deno job must pass
 * offline (no registry fetch) and the assertions here are all we need.
 */

export function assert(condition: unknown, message = 'assertion failed'): asserts condition {
  if (!condition) throw new Error(message);
}

export function assertEquals(actual: unknown, expected: unknown, message = '') {
  if (!deepEqual(actual, expected)) {
    throw new Error(
      `${message ? message + ': ' : ''}expected ${JSON.stringify(expected)}, ` +
        `got ${JSON.stringify(actual)}`,
    );
  }
}

export function assertThrows(fn: () => unknown, messageIncludes?: string) {
  let threw = false;
  try {
    fn();
  } catch (error) {
    threw = true;
    const message = error instanceof Error ? error.message : String(error);
    if (messageIncludes && !message.includes(messageIncludes)) {
      throw new Error(
        `threw ${JSON.stringify(message)}, expected it to include ` +
          JSON.stringify(messageIncludes),
      );
    }
  }
  if (!threw) throw new Error('expected the call to throw');
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a === 'number' && typeof b === 'number') {
    return Number.isNaN(a) && Number.isNaN(b);
  }
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, index) => deepEqual(item, b[index]));
  }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const aKeys = Object.keys(a as Record<string, unknown>).sort();
    const bKeys = Object.keys(b as Record<string, unknown>).sort();
    if (aKeys.length !== bKeys.length) return false;
    if (!aKeys.every((key, index) => key === bKeys[index])) return false;
    return aKeys.every((key) =>
      deepEqual(
        (a as Record<string, unknown>)[key],
        (b as Record<string, unknown>)[key],
      )
    );
  }
  return false;
}
