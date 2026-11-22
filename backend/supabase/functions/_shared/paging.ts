/**
 * Reading a table completely through PostgREST. A response is cut at
 * `max_rows` (1000 in `config.toml` and the hosted default) with no error, so a
 * read that can exceed it silently returns the oldest or an arbitrary 1000 rows.
 * `fetchAllPages` loops `.range()` until a short page.
 *
 * The caller supplies the query and MUST give it a stable, unique `.order(...)`
 * (the primary key, or the columns of the unique key): without one the pages may
 * overlap or skip rows.
 *
 *   await fetchAllPages<Row>((from, to) =>
 *     supabase.schema('meds').from('regimens').select('id, ...')
 *       .in('medication_id', ids).order('id').range(from, to)
 *   );
 */

/** PostgREST's `max_rows`; a page of this size is the largest it will return. */
export const PAGE_SIZE = 1000;

export interface Page<T> {
  data: T[] | null;
  error: unknown;
}

/** Every row the query returns, `pageSize` at a time. Throws the first error. */
export async function fetchAllPages<T>(
  fetchPage: (from: number, to: number) => PromiseLike<Page<T>>,
  pageSize: number = PAGE_SIZE,
): Promise<T[]> {
  const rows: T[] = [];
  for (let from = 0; ; from += pageSize) {
    const { data, error } = await fetchPage(from, from + pageSize - 1);
    if (error) throw error;
    const page = data ?? [];
    rows.push(...page);
    if (page.length < pageSize) return rows;
  }
}
