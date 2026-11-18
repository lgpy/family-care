-- M2 care circle (build-plan §M2): circles, memberships, roles, patients,
-- profiles, invitations, the single access function and the append-only audit
-- log. See docs/domain/care-circle.md, docs/security-and-privacy.md §3 and §5.
--
-- Everything lives in `core` and follows the M1 conventions
-- (20261117120000_core_sync_probe.sql): RLS enabled *and forced* on every
-- table, nothing granted to anon/PUBLIC, policies scoped `to authenticated`,
-- `(select auth.uid())`, `set search_path = ''` on every function, and the
-- `powersync` publication for replicated tables.
--
-- `core.sync_probe` is deliberately left alone: the Android app still uses it
-- until the M2 Android step, which drops it.

-- --- Types ------------------------------------------------------------------

create type core.member_role as enum ('owner', 'caregiver', 'viewer');
comment on type core.member_role is
  'Membership role. viewer is modelled, not exposed in the app until later.';

create type core.reminder_policy as enum
  ('primary_with_escalation', 'all_caregivers', 'rota');
comment on type core.reminder_policy is
  'Per-patient reminder routing (docs/domain/care-circle.md). rota is modelled, not built.';

-- Types are usable by PUBLIC by default, so revoke explicitly like tables.
revoke all on type core.member_role from public;
revoke all on type core.member_role from anon;
grant usage on type core.member_role to authenticated;

revoke all on type core.reminder_policy from public;
revoke all on type core.reminder_policy from anon;
grant usage on type core.reminder_policy to authenticated;

-- --- Tables -----------------------------------------------------------------

create table core.care_circles (
  id uuid primary key default gen_random_uuid(),
  name text not null
    constraint care_circles_name_length check (char_length(name) between 1 and 100),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table core.care_circles is
  'The unit of sharing and isolation. Only an owner may rename it; creation goes through core.create_circle().';

create table core.memberships (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references core.care_circles (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  role core.member_role not null default 'caregiver',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memberships_circle_user_key unique (circle_id, user_id)
);

comment on table core.memberships is
  'Which user belongs to which circle, with a role. Clients never write it directly: use the core.* RPCs (create_circle, accept_invitation, remove_member, set_member_role).';

create index memberships_user_id_idx on core.memberships (user_id);
create index memberships_circle_id_idx on core.memberships (circle_id);

create table core.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null
    constraint profiles_display_name_length check (char_length(display_name) between 1 and 100),
  preferred_language text not null default 'pt-PT'
    constraint profiles_preferred_language check (preferred_language in ('pt-PT', 'en')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table core.profiles is
  'Public face of a user (name, language). auth.users cannot be synced, so co-members read names from here; rows are auto-created by a trigger on auth.users.';

comment on column core.profiles.id is
  'Same uuid as auth.users.id. A user updates only their own row; co-members of any shared circle may read it.';

create table core.patients (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references core.care_circles (id) on delete cascade,
  display_name text not null
    constraint patients_display_name_length check (char_length(display_name) between 1 and 100),
  linked_user_id uuid references auth.users (id) on delete set null,
  reminder_policy core.reminder_policy not null default 'primary_with_escalation',
  primary_caregiver_id uuid references auth.users (id) on delete set null,
  escalation_delay_minutes integer not null default 30
    constraint patients_escalation_delay_range check (escalation_delay_minutes between 0 and 1440),
  missed_window_minutes integer not null default 120
    constraint patients_missed_window_range check (missed_window_minutes between 1 and 1440),
  agreement_note text
    constraint patients_agreement_note_length
    check (agreement_note is null or char_length(agreement_note) <= 2000),
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table core.patients is
  'A person whose health is managed. Not an account: linked_user_id optionally ties it to one. A trigger enforces that linked/primary users belong to the circle and that circle_id never moves.';

comment on column core.patients.linked_user_id is
  'Optional auth.users id when the patient also uses the app; must be a member of this circle.';
comment on column core.patients.primary_caregiver_id is
  'Who is reminded first for primary_with_escalation; must be an owner or caregiver of this circle.';

create index patients_circle_id_idx on core.patients (circle_id);
create index patients_linked_user_id_idx on core.patients (linked_user_id);

create table core.patient_access_overrides (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references core.patients (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  access_level text not null default 'read'
    constraint patient_access_overrides_access_level
    check (access_level in ('read', 'write')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint patient_access_overrides_patient_user_key unique (patient_id, user_id)
);

comment on table core.patient_access_overrides is
  'Modelled, not used: per-patient grant/restriction that a later milestone can build on. core.can() deliberately ignores it in the MVP (every member sees every patient in the circle), and only circle owners may touch it.';

create table core.invitations (
  id uuid primary key default gen_random_uuid(),
  circle_id uuid not null references core.care_circles (id) on delete cascade,
  email text not null
    constraint invitations_email_lowercase check (email = lower(email))
    constraint invitations_email_length check (char_length(email) between 3 and 320),
  role core.member_role not null
    constraint invitations_role_not_owner check (role <> 'owner'),
  token_hash bytea not null unique,
  created_by uuid not null references auth.users (id) on delete cascade,
  expires_at timestamptz not null default now() + interval '7 days',
  accepted_at timestamptz,
  accepted_by uuid references auth.users (id) on delete set null,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table core.invitations is
  'Invitation to join a circle, bound to an email address; single use and expiring. Only the sha256 of the normalised code is stored - the plaintext code is returned once by core.create_invitation() and never persisted.';

comment on column core.invitations.token_hash is
  'sha256 of the upper-cased code with spaces and dashes stripped. Clients have no SELECT privilege on this column.';
comment on column core.invitations.email is
  'Stored lower-case; accepting requires the caller''s confirmed auth.users email to match.';

create index invitations_circle_id_idx on core.invitations (circle_id);

create table core.audit_log (
  id bigint generated always as identity primary key,
  occurred_at timestamptz not null default now(),
  actor_id uuid,
  circle_id uuid,
  patient_id uuid,
  table_name text not null,
  record_id uuid,
  action text not null,
  old_data jsonb,
  new_data jsonb
);

comment on table core.audit_log is
  'Append-only change log (security-and-privacy §5, D14). Written by core.audit_row_change() triggers. No inserts/updates/deletes for clients, and a guard trigger refuses UPDATE/DELETE/TRUNCATE for every role, including the table owner. Owners of a circle may read its rows. Not synced by PowerSync.';

comment on column core.audit_log.actor_id is
  'auth.uid() at the time of the change; null for server jobs (e.g. the signup profile trigger). Intentionally not a foreign key: the log keeps its raw values after a user is deleted.';

-- Deliberately no foreign keys on audit_log: an FK would need ON DELETE SET
-- NULL on records that are deleted (patient erasure, circle deletion), which
-- is an UPDATE of an append-only row and would be blocked by the guard below.

-- --- updated_at -------------------------------------------------------------
-- core.set_updated_at() is defined by the M1 migration and reused unchanged.

create trigger care_circles_set_updated_at
  before update on core.care_circles
  for each row execute function core.set_updated_at();

create trigger memberships_set_updated_at
  before update on core.memberships
  for each row execute function core.set_updated_at();

create trigger profiles_set_updated_at
  before update on core.profiles
  for each row execute function core.set_updated_at();

create trigger patients_set_updated_at
  before update on core.patients
  for each row execute function core.set_updated_at();

create trigger patient_access_overrides_set_updated_at
  before update on core.patient_access_overrides
  for each row execute function core.set_updated_at();

create trigger invitations_set_updated_at
  before update on core.invitations
  for each row execute function core.set_updated_at();

-- --- Access functions -------------------------------------------------------
-- One place for the permission logic (docs/domain/care-circle.md "Access
-- control"). All are SECURITY DEFINER, owned by the migration role, which has
-- BYPASSRLS on Supabase (locally: `postgres` has rolbypassrls, checked with
-- pg_roles). That is what breaks the recursion: a policy on `memberships` calls
-- these functions, which read `memberships` without re-entering RLS.

create or replace function core.circle_role(p_circle_id uuid)
returns core.member_role
language sql
stable
security definer
set search_path = ''
as $$
  select m.role
  from core.memberships m
  where m.circle_id = p_circle_id
    and m.user_id = (select auth.uid());
$$;

comment on function core.circle_role(uuid) is
  'Role of the signed-in user in a circle, or null when they are not a member. Security definer so RLS policies can call it without recursing.';

create or replace function core.can_circle(p_circle_id uuid, p_action text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case p_action
    when 'read' then core.circle_role(p_circle_id) is not null
    when 'write' then core.circle_role(p_circle_id) in ('owner', 'caregiver')
    when 'manage' then core.circle_role(p_circle_id) = 'owner'
    else false
  end;
$$;

comment on function core.can_circle(uuid, text) is
  'Can the signed-in user do `read` (any member), `write` (owner/caregiver) or `manage` (owner) in this circle? Unknown actions are denied.';

create or replace function core.can(p_patient_id uuid, p_action text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(
    (select core.can_circle(p.circle_id, p_action) from core.patients p where p.id = p_patient_id),
    false
  );
$$;

comment on function core.can(uuid, text) is
  'The single access question: can the signed-in user do `read` | `write` | `manage` on this patient? Every patient-data policy must call it. Patient access overrides are ignored in the MVP.';

create or replace function core.shares_circle_with(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_user_id = (select auth.uid())
    or exists (
      select 1
      from core.memberships mine
      join core.memberships theirs on theirs.circle_id = mine.circle_id
      where mine.user_id = (select auth.uid())
        and theirs.user_id = p_user_id
    );
$$;

comment on function core.shares_circle_with(uuid) is
  'True when the signed-in user shares at least one circle with the given user (used by the profiles policy).';

revoke all on function core.circle_role(uuid) from public;
revoke all on function core.circle_role(uuid) from anon;
grant execute on function core.circle_role(uuid) to authenticated;

revoke all on function core.can_circle(uuid, text) from public;
revoke all on function core.can_circle(uuid, text) from anon;
grant execute on function core.can_circle(uuid, text) to authenticated;

revoke all on function core.can(uuid, text) from public;
revoke all on function core.can(uuid, text) from anon;
grant execute on function core.can(uuid, text) to authenticated;

revoke all on function core.shares_circle_with(uuid) from public;
revoke all on function core.shares_circle_with(uuid) from anon;
grant execute on function core.shares_circle_with(uuid) to authenticated;

-- --- Patient integrity ------------------------------------------------------

create or replace function core.check_patient()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role core.member_role;
begin
  if tg_op = 'UPDATE' and new.circle_id is distinct from old.circle_id then
    raise exception 'a patient cannot move to another care circle'
      using errcode = '23514';
  end if;

  if new.linked_user_id is not null then
    if not exists (
      select 1 from core.memberships m
      where m.circle_id = new.circle_id and m.user_id = new.linked_user_id
    ) then
      raise exception 'linked_user_id must be a member of the patient''s care circle'
        using errcode = '23514';
    end if;
  end if;

  if new.primary_caregiver_id is not null then
    select m.role into v_role
    from core.memberships m
    where m.circle_id = new.circle_id and m.user_id = new.primary_caregiver_id;

    if v_role is null or v_role not in ('owner', 'caregiver') then
      raise exception 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle'
        using errcode = '23514';
    end if;
  end if;

  return new;
end;
$$;

comment on function core.check_patient() is
  'BEFORE INSERT/UPDATE on core.patients: circle never changes; linked_user_id must be a circle member; primary_caregiver_id must be an owner or caregiver of the circle.';

create trigger patients_check
  before insert or update on core.patients
  for each row execute function core.check_patient();

-- --- Profiles: auto-created for each new auth user --------------------------

create or replace function core.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_display_name text;
begin
  v_display_name := coalesce(
    nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
    nullif(btrim(new.raw_user_meta_data ->> 'name'), ''),
    nullif(split_part(coalesce(new.email, ''), '@', 1), ''),
    'Unknown'
  );

  insert into core.profiles (id, display_name)
  values (new.id, left(v_display_name, 100))
  on conflict (id) do nothing;

  return new;
end;
$$;

comment on function core.handle_new_user() is
  'AFTER INSERT on auth.users: creates the core.profiles row (name from raw_user_meta_data full_name/name, else the email local part). auth.users cannot be synced, so this is how co-members learn names.';

create trigger on_auth_user_created_profile
  after insert on auth.users
  for each row execute function core.handle_new_user();

-- Backfill for accounts created before this migration (dev/prod already have
-- the M1 test accounts). Same name rules as the trigger.
insert into core.profiles (id, display_name)
select
  u.id,
  left(coalesce(
    nullif(btrim(u.raw_user_meta_data ->> 'full_name'), ''),
    nullif(btrim(u.raw_user_meta_data ->> 'name'), ''),
    nullif(split_part(coalesce(u.email, ''), '@', 1), ''),
    'Unknown'
  ), 100)
from auth.users u
on conflict (id) do nothing;

-- --- Audit ------------------------------------------------------------------

create or replace function core.audit_log_is_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'core.audit_log is append-only: % is not allowed', tg_op
    using errcode = '42501';
end;
$$;

comment on function core.audit_log_is_append_only() is
  'BEFORE UPDATE/DELETE/TRUNCATE on core.audit_log: always raises. Not security definer and not bypassable by the table owner.';

create trigger audit_log_no_update
  before update on core.audit_log
  for each row execute function core.audit_log_is_append_only();

create trigger audit_log_no_delete
  before delete on core.audit_log
  for each row execute function core.audit_log_is_append_only();

create trigger audit_log_no_truncate
  before truncate on core.audit_log
  for each statement execute function core.audit_log_is_append_only();

create or replace function core.audit_row_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old jsonb;
  v_new jsonb;
  v_record_id uuid;
  v_circle_id uuid;
  v_patient_id uuid;
begin
  if tg_op = 'INSERT' then
    v_new := to_jsonb(new);
    v_record_id := new.id;
  elsif tg_op = 'UPDATE' then
    v_old := to_jsonb(old);
    v_new := to_jsonb(new);
    v_record_id := new.id;
  else
    v_old := to_jsonb(old);
    v_record_id := old.id;
  end if;

  -- Never log the invitation secret (only its hash is stored, but it is still
  -- a bearer credential for a future invitation lookup).
  if tg_table_name = 'invitations' then
    v_old := v_old - 'token_hash';
    v_new := v_new - 'token_hash';
  end if;

  -- Derive the circle the change belongs to.
  if tg_table_name = 'care_circles' then
    v_circle_id := v_record_id;
  elsif tg_table_name = 'patient_access_overrides' then
    v_patient_id := coalesce((v_new ->> 'patient_id')::uuid, (v_old ->> 'patient_id')::uuid);
    select p.circle_id into v_circle_id from core.patients p where p.id = v_patient_id;
  else
    v_circle_id := coalesce((v_new ->> 'circle_id')::uuid, (v_old ->> 'circle_id')::uuid);
  end if;

  if tg_table_name = 'patients' then
    v_patient_id := v_record_id;
  end if;

  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action, old_data, new_data
  )
  values (
    (select auth.uid()), v_circle_id, v_patient_id, tg_table_name, v_record_id,
    lower(tg_op), v_old, v_new
  );

  return null;
end;
$$;

comment on function core.audit_row_change() is
  'Generic AFTER INSERT/UPDATE/DELETE trigger writing one core.audit_log row with actor (auth.uid()), circle, patient, before/after JSON. token_hash is removed from invitation rows.';

create trigger care_circles_audit
  after insert or update or delete on core.care_circles
  for each row execute function core.audit_row_change();

create trigger memberships_audit
  after insert or update or delete on core.memberships
  for each row execute function core.audit_row_change();

create trigger profiles_audit
  after insert or update or delete on core.profiles
  for each row execute function core.audit_row_change();

create trigger patients_audit
  after insert or update or delete on core.patients
  for each row execute function core.audit_row_change();

create trigger patient_access_overrides_audit
  after insert or update or delete on core.patient_access_overrides
  for each row execute function core.audit_row_change();

create trigger invitations_audit
  after insert or update or delete on core.invitations
  for each row execute function core.audit_row_change();

-- Trigger functions are never called directly by a role (triggers run them with
-- the owner's privileges), so remove the default PUBLIC EXECUTE grant like
-- every other object in this schema.
revoke all on function core.check_patient() from public;
revoke all on function core.handle_new_user() from public;
revoke all on function core.audit_row_change() from public;
revoke all on function core.audit_log_is_append_only() from public;

-- --- RLS: deny by default ---------------------------------------------------

alter table core.care_circles enable row level security;
alter table core.care_circles force row level security;
alter table core.memberships enable row level security;
alter table core.memberships force row level security;
alter table core.profiles enable row level security;
alter table core.profiles force row level security;
alter table core.patients enable row level security;
alter table core.patients force row level security;
alter table core.patient_access_overrides enable row level security;
alter table core.patient_access_overrides force row level security;
alter table core.invitations enable row level security;
alter table core.invitations force row level security;
alter table core.audit_log enable row level security;
alter table core.audit_log force row level security;

-- Privileges. Tables that are written only through security-definer RPCs
-- (memberships, invitations) get no INSERT/UPDATE/DELETE grant at all, so a
-- missing policy can never become a write path.

revoke all on core.care_circles from public;
revoke all on core.care_circles from anon;
grant select, update on core.care_circles to authenticated;

revoke all on core.memberships from public;
revoke all on core.memberships from anon;
grant select on core.memberships to authenticated;

revoke all on core.profiles from public;
revoke all on core.profiles from anon;
grant select, update on core.profiles to authenticated;

revoke all on core.patients from public;
revoke all on core.patients from anon;
grant select, insert, update, delete on core.patients to authenticated;

revoke all on core.patient_access_overrides from public;
revoke all on core.patient_access_overrides from anon;
grant select, insert, update, delete on core.patient_access_overrides to authenticated;

revoke all on core.invitations from public;
revoke all on core.invitations from anon;
-- Column-level SELECT: everything except token_hash. A client must name its
-- columns (`select=id,email,...`); `select=*` is rejected by PostgREST. The
-- only successful writes are the security-definer RPCs below.
grant select (
  id, circle_id, email, role, created_by, expires_at,
  accepted_at, accepted_by, revoked_at, created_at, updated_at
) on core.invitations to authenticated;

revoke all on core.audit_log from public;
revoke all on core.audit_log from anon;
grant select on core.audit_log to authenticated;

-- care_circles: co-members read; only an owner renames. No direct insert or
-- delete (core.create_circle() owns creation).
create policy care_circles_select_member
  on core.care_circles
  for select
  to authenticated
  using (core.can_circle(id, 'read'));

create policy care_circles_update_owner
  on core.care_circles
  for update
  to authenticated
  using (core.can_circle(id, 'manage'))
  with check (core.can_circle(id, 'manage'));

-- memberships: readable by everyone in the circle (the app shows the family
-- list); writing happens only through the RPCs.
create policy memberships_select_member
  on core.memberships
  for select
  to authenticated
  using (core.can_circle(circle_id, 'read'));

-- profiles: yourself always, plus everyone in a circle you share.
create policy profiles_select_self_or_comember
  on core.profiles
  for select
  to authenticated
  using (
    id = (select auth.uid())
    or core.shares_circle_with(id)
  );

-- You may edit only your own profile, and cannot hand it to someone else.
create policy profiles_update_self
  on core.profiles
  for update
  to authenticated
  using (id = (select auth.uid()))
  with check (id = (select auth.uid()));

-- patients: the single access function decides. Insert needs 'write' on the
-- circle because the row does not exist yet; update/delete go through
-- core.can() so future per-patient rules apply automatically.
create policy patients_select_can_read
  on core.patients
  for select
  to authenticated
  using (core.can(id, 'read'));

create policy patients_insert_can_write
  on core.patients
  for insert
  to authenticated
  with check (core.can_circle(circle_id, 'write'));

create policy patients_update_can_write
  on core.patients
  for update
  to authenticated
  using (core.can(id, 'write'))
  with check (core.can(id, 'write'));

create policy patients_delete_can_manage
  on core.patients
  for delete
  to authenticated
  using (core.can(id, 'manage'));

-- patient_access_overrides: owner of the circle only (not used by the MVP;
-- core.can() ignores the table).
create policy patient_access_overrides_select_owner
  on core.patient_access_overrides
  for select
  to authenticated
  using (core.can(patient_id, 'manage'));

create policy patient_access_overrides_insert_owner
  on core.patient_access_overrides
  for insert
  to authenticated
  with check (core.can(patient_id, 'manage'));

create policy patient_access_overrides_update_owner
  on core.patient_access_overrides
  for update
  to authenticated
  using (core.can(patient_id, 'manage'))
  with check (core.can(patient_id, 'manage'));

create policy patient_access_overrides_delete_owner
  on core.patient_access_overrides
  for delete
  to authenticated
  using (core.can(patient_id, 'manage'));

-- invitations: owners see the list (without token_hash, see the column
-- grant). Created, accepted and revoked only through the RPCs.
create policy invitations_select_owner
  on core.invitations
  for select
  to authenticated
  using (core.can_circle(circle_id, 'manage'));

-- audit_log: read-only, and only for owners of the circle the change belongs
-- to (the future Activity screen). Profile rows have no circle, so they are
-- not exposed this way.
create policy audit_log_select_owner
  on core.audit_log
  for select
  to authenticated
  using (circle_id is not null and core.can_circle(circle_id, 'manage'));

-- --- RPCs -------------------------------------------------------------------
-- All are SECURITY DEFINER: they are the only way to create circles, invite,
-- accept, revoke and change roles.

create or replace function core.create_circle(p_name text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
  v_circle_id uuid;
begin
  if v_user is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  if p_name is null or char_length(btrim(p_name)) not between 1 and 100 then
    raise exception 'circle name must be 1-100 characters' using errcode = '22023';
  end if;

  insert into core.care_circles (name) values (btrim(p_name))
  returning id into v_circle_id;

  insert into core.memberships (circle_id, user_id, role)
  values (v_circle_id, v_user, 'owner');

  return v_circle_id;
end;
$$;

comment on function core.create_circle(text) is
  'Creates a circle and the caller''s owner membership atomically. Returns the new circle id.';

create or replace function core.create_invitation(
  p_circle_id uuid,
  p_email text,
  p_role core.member_role default 'caregiver'
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  v_bits bit(160) := ('x' || encode(extensions.gen_random_bytes(20), 'hex'))::bit(160);
  v_code text := '';
  v_email text := lower(btrim(coalesce(p_email, '')));
  v_display_code text;
begin
  if not core.can_circle(p_circle_id, 'manage') then
    raise exception 'only a circle owner can invite members' using errcode = '42501';
  end if;

  if p_role is null or p_role not in ('caregiver', 'viewer') then
    raise exception 'invitation role must be caregiver or viewer' using errcode = '22023';
  end if;

  if v_email !~ '^[^@[:space:]]+@[^@[:space:]]+$' or char_length(v_email) > 320 then
    raise exception 'invitation email is not a valid address' using errcode = '22023';
  end if;

  -- 160 random bits, Crockford base32 (no I, L, O, U), shown 8-8-8-8.
  for i in 0..31 loop
    v_code := v_code || substr(
      v_alphabet,
      (substring(v_bits from i * 5 + 1 for 5))::int + 1,
      1
    );
  end loop;

  v_display_code := format(
    '%s-%s-%s-%s',
    substr(v_code, 1, 8), substr(v_code, 9, 8),
    substr(v_code, 17, 8), substr(v_code, 25, 8)
  );

  insert into core.invitations (circle_id, email, role, token_hash, created_by)
  values (
    p_circle_id, v_email, p_role,
    extensions.digest(v_code, 'sha256'), (select auth.uid())
  );

  -- The plaintext code exists only in this return value: only its hash is stored.
  return v_display_code;
end;
$$;

comment on function core.create_invitation(uuid, text, core.member_role) is
  'Owner-only. Returns the invitation code once (Crockford base32, 160 bits); only sha256 of the normalised code is stored.';

create or replace function core.accept_invitation(p_code text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
  v_normalised text := upper(regexp_replace(coalesce(p_code, ''), '[[:space:]-]', '', 'g'));
  v_invitation core.invitations%rowtype;
  v_email text;
  v_email_confirmed timestamptz;
begin
  if v_user is null then
    raise exception 'not authenticated' using errcode = '42501';
  end if;

  select * into v_invitation
  from core.invitations i
  where i.token_hash = extensions.digest(v_normalised, 'sha256');

  -- One uniform error for unknown, expired, accepted and revoked codes, so a
  -- caller cannot use the message to probe which codes exist or existed.
  if not found
     or v_invitation.expires_at <= now()
     or v_invitation.accepted_at is not null
     or v_invitation.revoked_at is not null then
    raise exception 'invitation is not valid' using errcode = '22023';
  end if;

  select lower(u.email), u.email_confirmed_at
  into v_email, v_email_confirmed
  from auth.users u
  where u.id = v_user;

  if v_email_confirmed is null then
    raise exception 'confirm your email address before accepting an invitation'
      using errcode = '42501';
  end if;

  if v_email is distinct from v_invitation.email then
    raise exception 'invitation was issued for a different email address'
      using errcode = '42501';
  end if;

  -- Single use: claim the row first; a concurrent second call updates nothing.
  update core.invitations
  set accepted_at = now(), accepted_by = v_user
  where id = v_invitation.id and accepted_at is null;

  if not found then
    raise exception 'invitation is not valid' using errcode = '22023';
  end if;

  insert into core.memberships (circle_id, user_id, role)
  values (v_invitation.circle_id, v_user, v_invitation.role)
  on conflict (circle_id, user_id) do nothing;

  return v_invitation.circle_id;
end;
$$;

comment on function core.accept_invitation(text) is
  'Accepts a code (case/space/dash insensitive). Requires the caller''s confirmed email to match; single use; returns the circle id. Invalid/expired/used codes share one error.';

create or replace function core.revoke_invitation(p_invitation_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invitation core.invitations%rowtype;
begin
  select * into v_invitation from core.invitations i where i.id = p_invitation_id;

  if not found then
    raise exception 'invitation not found' using errcode = 'P0002';
  end if;

  if not core.can_circle(v_invitation.circle_id, 'manage') then
    raise exception 'only a circle owner can revoke an invitation' using errcode = '42501';
  end if;

  if v_invitation.accepted_at is not null then
    raise exception 'invitation was already accepted' using errcode = '22023';
  end if;

  update core.invitations
  set revoked_at = now()
  where id = p_invitation_id and revoked_at is null;
end;
$$;

comment on function core.revoke_invitation(uuid) is
  'Owner-only. Marks an unaccepted invitation revoked; idempotent.';

create or replace function core.remove_member(p_membership_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_membership core.memberships%rowtype;
  v_owner_count integer;
begin
  select * into v_membership from core.memberships m where m.id = p_membership_id;

  if not found then
    raise exception 'membership not found' using errcode = 'P0002';
  end if;

  if not core.can_circle(v_membership.circle_id, 'manage') then
    raise exception 'only a circle owner can remove members' using errcode = '42501';
  end if;

  -- Serialise owner changes in this circle so two callers cannot each remove
  -- "the other" last owner.
  perform 1 from core.memberships m where m.circle_id = v_membership.circle_id for update;

  select count(*) into v_owner_count
  from core.memberships m
  where m.circle_id = v_membership.circle_id and m.role = 'owner';

  if v_membership.role = 'owner' and v_owner_count <= 1 then
    raise exception 'cannot remove the last owner of a circle' using errcode = '23514';
  end if;

  delete from core.memberships m where m.id = p_membership_id;
end;
$$;

comment on function core.remove_member(uuid) is
  'Owner-only. Removes a membership; refuses to remove the circle''s last owner.';

create or replace function core.set_member_role(
  p_membership_id uuid,
  p_role core.member_role
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_membership core.memberships%rowtype;
  v_owner_count integer;
begin
  select * into v_membership from core.memberships m where m.id = p_membership_id;

  if not found then
    raise exception 'membership not found' using errcode = 'P0002';
  end if;

  if not core.can_circle(v_membership.circle_id, 'manage') then
    raise exception 'only a circle owner can change roles' using errcode = '42501';
  end if;

  if p_role is null then
    raise exception 'role is required' using errcode = '22023';
  end if;

  perform 1 from core.memberships m where m.circle_id = v_membership.circle_id for update;

  select count(*) into v_owner_count
  from core.memberships m
  where m.circle_id = v_membership.circle_id and m.role = 'owner';

  if v_membership.role = 'owner' and p_role <> 'owner' and v_owner_count <= 1 then
    raise exception 'cannot demote the last owner of a circle' using errcode = '23514';
  end if;

  update core.memberships m set role = p_role where m.id = p_membership_id;
end;
$$;

comment on function core.set_member_role(uuid, core.member_role) is
  'Owner-only. Changes a member''s role; refuses to demote the circle''s last owner.';

revoke all on function core.create_circle(text) from public;
revoke all on function core.create_circle(text) from anon;
grant execute on function core.create_circle(text) to authenticated;

revoke all on function core.create_invitation(uuid, text, core.member_role) from public;
revoke all on function core.create_invitation(uuid, text, core.member_role) from anon;
grant execute on function core.create_invitation(uuid, text, core.member_role) to authenticated;

revoke all on function core.accept_invitation(text) from public;
revoke all on function core.accept_invitation(text) from anon;
grant execute on function core.accept_invitation(text) to authenticated;

revoke all on function core.revoke_invitation(uuid) from public;
revoke all on function core.revoke_invitation(uuid) from anon;
grant execute on function core.revoke_invitation(uuid) to authenticated;

revoke all on function core.remove_member(uuid) from public;
revoke all on function core.remove_member(uuid) from anon;
grant execute on function core.remove_member(uuid) to authenticated;

revoke all on function core.set_member_role(uuid, core.member_role) from public;
revoke all on function core.set_member_role(uuid, core.member_role) from anon;
grant execute on function core.set_member_role(uuid, core.member_role) to authenticated;

-- --- Replication ------------------------------------------------------------
-- PowerSync replicates the four synced tables. invitations, audit_log and
-- patient_access_overrides stay server-side. Same idempotent form as M1.

do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'powersync') then
    create publication powersync;
  end if;
end;
$$;

do $$
declare
  v_table text;
begin
  foreach v_table in array array['care_circles', 'memberships', 'profiles', 'patients']
  loop
    if not exists (
      select 1
      from pg_publication_tables
      where pubname = 'powersync'
        and schemaname = 'core'
        and tablename = v_table
    ) then
      execute format('alter publication powersync add table core.%I', v_table);
    end if;
  end loop;
end;
$$;
