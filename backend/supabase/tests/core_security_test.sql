-- Schema-wide security tests for the `core` schema (build-plan §4 "Database
-- tests", security-and-privacy §3, D13).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- These are the assertions that are about the schema as a whole rather than
-- about one table's rows, so they live in one small file instead of being
-- repeated per table. Every query iterates pg_class rather than naming tables,
-- which is what makes a table added by a future migration covered
-- automatically (the "future tables" trap in T3).
--
-- Per-table behaviour (who sees which rows, which RPCs are allowed, the exact
-- Sync Streams queries, the audit log) is tested in core_care_circle_test.sql;
-- this file deliberately does not repeat it.
--
-- Everything happens in one transaction and is rolled back, so the tests need no
-- cleanup and never touch dev/prod data.

begin;

create extension if not exists pgtap with schema extensions;

select plan(9);

-- --- Schema-wide guarantees -------------------------------------------------

-- Guard against a vacuous suite: if `core` ever lost its tables the RLS and
-- privilege checks below would pass by looking at nothing.
select cmp_ok(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'core' and c.relkind in ('r', 'p')),
  '>', 0::bigint,
  'schema core contains at least one table (so the checks below are not vacuous)'
);

-- Deny by default (D13): RLS is both enabled and forced, so even the table
-- owner is subject to the policies. Superusers (migrations, the dashboard)
-- still bypass RLS, which is why the row-level tests impersonate real roles.
select is(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'core'
      and c.relkind in ('r', 'p')
      and not (c.relrowsecurity and c.relforcerowsecurity)),
  0::bigint,
  'every table in schema core has RLS both enabled and forced'
);

-- Nothing is granted to PUBLIC on any core table. Checked through the stored
-- ACL because a PUBLIC grant would still apply to anon and authenticated.
select is(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
     cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
    where n.nspname = 'core'
      and c.relkind in ('r', 'p')
      and a.grantee = 0),
  0::bigint,
  'no table in schema core grants anything to PUBLIC'
);

-- And anon holds no table privilege at all, whether granted directly or through
-- PUBLIC. New tables are covered because this iterates pg_class.
select is(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'core'
      and c.relkind in ('r', 'p')
      and has_table_privilege(
        'anon', c.oid, 'select,insert,update,delete,truncate,references,trigger')),
  0::bigint,
  'anon has no privilege on any table in schema core'
);

select ok(
  has_schema_privilege('authenticated', 'core', 'usage'),
  'authenticated can use schema core'
);

-- --- Replication ------------------------------------------------------------
-- The publication is what PowerSync replicates from: it must exist, and it must
-- contain exactly the tables the Sync Streams config reads. A table left in the
-- publication would be downloadable even if the app no longer expects it.
select is(
  (select count(*)::int from pg_publication where pubname = 'powersync'),
  1,
  'publication powersync exists'
);

select is(
  (select string_agg(schemaname || '.' || tablename, ',' order by schemaname, tablename)
     from pg_publication_tables
    where pubname = 'powersync'),
  'appointments.appointments,core.care_circles,core.memberships,core.patients,'
  'core.profiles,info.patient_info,meds.dose_log,meds.medications,'
  'meds.regimens,meds.stock_movements',
  'publication powersync contains exactly the M2 core tables, the M3-M5 meds tables and the M6 appointments/info tables'
);

-- --- The M1 sync probe is gone ----------------------------------------------
-- Dropped by 20261118130000_core_drop_sync_probe.sql once the M2 Android step
-- stopped using it. `to_regclass` returns null instead of raising, so this
-- assertion fails cleanly if the table ever comes back.
select ok(
  to_regclass('core.sync_probe') is null,
  'the M1 sync probe table no longer exists'
);

-- The probe's trigger used a shared function: the M2 tables still stamp
-- updated_at with it, so the drop must not have taken it along.
select ok(
  to_regprocedure('core.set_updated_at()') is not null,
  'core.set_updated_at() is kept for the M2 tables'
);

select * from finish();

rollback;
