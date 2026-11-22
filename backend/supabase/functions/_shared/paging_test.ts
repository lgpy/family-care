/**
 * PostgREST truncates a response at `max_rows` (1000, in config.toml and on
 * hosted Supabase) without an error. `fetchAllPages` pages until a short page.
 */

import { assertEquals } from './testing.ts';
import { fetchAllPages, PAGE_SIZE } from './paging.ts';

/** A fake table of `total` rows that honours `.range(from, to)` like PostgREST. */
function table(total: number) {
  const ranges: Array<[number, number]> = [];
  const fetchPage = (from: number, to: number) => {
    ranges.push([from, to]);
    const rows: number[] = [];
    for (let i = from; i <= Math.min(to, total - 1); i += 1) rows.push(i);
    return Promise.resolve({ data: rows, error: null });
  };
  return { ranges, fetchPage };
}

Deno.test('paging: a short first page is the whole result', async () => {
  const { ranges, fetchPage } = table(3);
  assertEquals(await fetchAllPages<number>(fetchPage), [0, 1, 2]);
  assertEquals(ranges, [[0, PAGE_SIZE - 1]]);
});

Deno.test('paging: an empty table is one request', async () => {
  const { ranges, fetchPage } = table(0);
  assertEquals(await fetchAllPages<number>(fetchPage), []);
  assertEquals(ranges.length, 1);
});

Deno.test('paging: more than 1000 rows are all returned, in order', async () => {
  const { ranges, fetchPage } = table(2500);
  const rows = await fetchAllPages<number>(fetchPage);
  assertEquals(rows.length, 2500);
  assertEquals(rows[0], 0);
  assertEquals(rows[2499], 2499);
  assertEquals(ranges, [[0, 999], [1000, 1999], [2000, 2999]]);
});

Deno.test('paging: an exact multiple of the page size ends on an empty page', async () => {
  const { ranges, fetchPage } = table(2000);
  assertEquals((await fetchAllPages<number>(fetchPage)).length, 2000);
  assertEquals(ranges.length, 3);
});

Deno.test('paging: a page size can be given', async () => {
  const { ranges, fetchPage } = table(5);
  assertEquals(await fetchAllPages<number>(fetchPage, 2), [0, 1, 2, 3, 4]);
  assertEquals(ranges, [[0, 1], [2, 3], [4, 5]]);
});

Deno.test('paging: a null page counts as empty', async () => {
  const rows = await fetchAllPages<number>(() => Promise.resolve({ data: null, error: null }));
  assertEquals(rows, []);
});

Deno.test('paging: an error on any page is thrown, not swallowed', async () => {
  const boom = new Error('boom');
  let calls = 0;
  let thrown: unknown = null;
  try {
    await fetchAllPages<number>((from) => {
      calls += 1;
      return Promise.resolve(
        from === 0
          ? { data: new Array<number>(PAGE_SIZE).fill(1), error: null }
          : { data: null, error: boom },
      );
    });
  } catch (error) {
    thrown = error;
  }
  assertEquals(thrown, boom);
  assertEquals(calls, 2);
});
