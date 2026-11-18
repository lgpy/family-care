-- Drop the M1 sync probe (build-plan §M2).
--
-- `core.sync_probe` was a throwaway table that proved the sign-in -> Supabase ->
-- PowerSync path and carried the first isolation tests
-- (20261117120000_core_sync_probe.sql). The M2 Android step no longer uses it:
-- the app now syncs `care_circles`, `memberships`, `profiles` and `patients`,
-- and every access rule is tested against those tables.
--
-- Dropping the table also drops its RLS policies, its grants (including the
-- `authenticated` DML grant), its audit trigger and replication membership.
-- `core.set_updated_at()` stays: the M2 tables use it.

-- Out of the publication first, so a replication connection never sees a table
-- that is about to disappear. Idempotent: a re-run (or a database where the
-- table was never added) simply skips it.
do $$
begin
  if exists (
    select 1
    from pg_publication_tables
    where pubname = 'powersync'
      and schemaname = 'core'
      and tablename = 'sync_probe'
  ) then
    alter publication powersync drop table core.sync_probe;
  end if;
end;
$$;

-- The table's own trigger, named explicitly before the table goes: dropping the
-- table would take it too, but this states which trigger belonged to the probe.
do $$
begin
  if to_regclass('core.sync_probe') is not null then
    drop trigger if exists sync_probe_set_updated_at on core.sync_probe;
  end if;
end;
$$;

drop table if exists core.sync_probe;
