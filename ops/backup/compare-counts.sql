-- Row counts for a restore test: every table in the app schemas (add a future
-- module's schema to the list) plus auth.users, which lives outside them.
--
-- The output is ordered and stable, so the same file can be run against the
-- source database and against the restored scratch database and the two
-- results diffed:
--
--   docker exec -i supabase_db_family-care psql -U postgres -d postgres \
--     --file - < ops/backup/compare-counts.sql
--
-- Read-only; psql needs no transaction flag.

select n.nspname as schema_name,
       c.relname as table_name,
       (xpath('/row/c/text()',
              query_to_xml(
                format('select count(*) as c from %I.%I', n.nspname, c.relname),
                false, true, '')))[1]::text::bigint as row_count
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where c.relkind in ('r', 'p')
   and (n.nspname in ('core', 'meds')
        or (n.nspname = 'auth' and c.relname = 'users'))
 order by n.nspname, c.relname;
