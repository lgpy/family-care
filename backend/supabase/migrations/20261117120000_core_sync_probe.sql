-- M1 walking skeleton: one throwaway test table, synced through PowerSync and
-- protected by Row-Level Security (build-plan M1, security-and-privacy §3, D13).
--
-- This is a probe, not a data model: it exists so the sign-in -> Supabase ->
-- PowerSync path and the isolation tests can be proven end to end. It will be
-- dropped once the real `core` tables arrive in M2.

-- One schema per module (build-plan §1): `core` holds identity, care circle and
-- access. Only `core` exists in M1.
create schema if not exists core;

comment on schema core is
  'Core module (identity, care circle, access). See docs/domain/care-circle.md.';

-- `id uuid`: PowerSync requires every synced table to have a primary key named
-- `id`; it is a uuid here so the app can generate ids offline.
-- `owner_id` defaults to the caller so an insert cannot forget it; the RLS
-- INSERT policy below still checks that it matches the caller.
create table core.sync_probe (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null
    references auth.users (id) on delete cascade
    default auth.uid(),
  note text not null
    constraint sync_probe_note_length check (char_length(note) between 1 and 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table core.sync_probe is
  'M1 throwaway probe: proves PowerSync sync plus RLS isolation. Drop it once the real core tables exist (M2).';

comment on column core.sync_probe.owner_id is
  'auth.users id that created the row; RLS limits every operation to this user.';

-- updated_at is maintained by the database, never by the client (a phone clock
-- can be wrong). Used by the real tables from M2 onwards.
create or replace function core.set_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

comment on function core.set_updated_at() is
  'BEFORE UPDATE trigger: stamps updated_at with the server clock.';

create trigger sync_probe_set_updated_at
  before update on core.sync_probe
  for each row
  execute function core.set_updated_at();

-- Deny by default (D13): RLS on, and forced so even the table owner is subject.
-- Superusers (migrations, the Supabase dashboard) still bypass RLS, which is
-- why the tests exercise the non-superuser roles instead.
alter table core.sync_probe enable row level security;
alter table core.sync_probe force row level security;

-- Privileges: nothing for anon or PUBLIC. `authenticated` gets DML on the table
-- only, and the policies below decide which rows.
revoke all on schema core from public;
revoke all on schema core from anon;
revoke all on core.sync_probe from public;
revoke all on core.sync_probe from anon;

grant usage on schema core to authenticated;
grant select, insert, update, delete on core.sync_probe to authenticated;

-- Policies are scoped `to authenticated` so anon is denied even if a future
-- migration forgets a GRANT. `(select auth.uid())` is evaluated once per
-- statement instead of once per row (Supabase's recommended form).
create policy sync_probe_select_own
  on core.sync_probe
  for select
  to authenticated
  using (owner_id = (select auth.uid()));

create policy sync_probe_insert_own
  on core.sync_probe
  for insert
  to authenticated
  with check (owner_id = (select auth.uid()));

-- USING decides which rows may be updated; WITH CHECK stops an update from
-- handing the row to another user (owner_id = someone else would raise 42501).
create policy sync_probe_update_own
  on core.sync_probe
  for update
  to authenticated
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()));

create policy sync_probe_delete_own
  on core.sync_probe
  for delete
  to authenticated
  using (owner_id = (select auth.uid()));

-- PowerSync replicates through a logical replication publication. The service's
-- replication role and password are NOT created here (they are secrets:
-- security-and-privacy §4); see backend/powersync/README.md for the SQL that is
-- run by hand against dev and prod.
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'powersync') then
    create publication powersync;
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'powersync'
      and schemaname = 'core'
      and tablename = 'sync_probe'
  ) then
    alter publication powersync add table core.sync_probe;
  end if;
end;
$$;
