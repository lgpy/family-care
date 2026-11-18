-- M6 appointments and patient info (build-plan §M6, docs/domain/appointments.md,
-- docs/domain/patient-info.md, D5/D13/D14/D27/D29).
--
-- Two independent feature modules in their own schemas, as D5 requires:
--
-- 1. `appointments.appointments` - one row per appointment, local wall-clock
--    `starts_local` + IANA `time_zone` (the regimens convention, so a DST change
--    or a trip never moves an appointment), optional companion and follow-up.
--    Edits happen in place; cancelling is a `cancelled_at` stamp, never a delete,
--    so the history survives (D29). The audit log keeps every change (D14).
-- 2. `info.patient_info` - one row per patient with `id = patient_id` (a
--    deterministic id, so two phones creating it offline converge on one row).
--    Text and small JSON lists; last write wins (D29).
--
-- The change-push trigger/function for appointments is a separate migration
-- (task 2); nothing here posts to pg_net.
--
-- Conventions are the M2-M5 ones: RLS enabled *and forced*, nothing granted to
-- anon/PUBLIC, policies scoped `to authenticated` through `core.can()`,
-- `security definer` + `set search_path = ''` on every function, one audit
-- trigger per schema writing `core.audit_log`, and the idempotent `powersync`
-- publication block. Module direction (D5): these schemas reference `core` by
-- id only and never each other, so their audit functions live with them instead
-- of extending `core.audit_row_change()`.

create schema if not exists appointments;

comment on schema appointments is
  'Appointments module: one row per appointment, cancelled by stamp, never deleted. See docs/domain/appointments.md.';

revoke all on schema appointments from public;
revoke all on schema appointments from anon;
grant usage on schema appointments to authenticated;

create schema if not exists info;

comment on schema info is
  'Patient info module: one row per patient (id = patient_id) with allergies, conditions, SNS number, doctor, health centre, emergency contacts and notes. See docs/domain/patient-info.md.';

revoke all on schema info from public;
revoke all on schema info from anon;
grant usage on schema info to authenticated;

-- --- Types ------------------------------------------------------------------

create type appointments.appointment_kind as enum
  ('consultation', 'blood_test', 'exam', 'checkup', 'other');

comment on type appointments.appointment_kind is
  'What the appointment is. The app shows this; `other` pairs with the free-text title.';

-- Types are usable by PUBLIC by default, so revoke explicitly like the M3 enums.
revoke all on type appointments.appointment_kind from public;
revoke all on type appointments.appointment_kind from anon;
grant usage on type appointments.appointment_kind to authenticated;

-- --- Appointments table -----------------------------------------------------

create table appointments.appointments (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references core.patients (id) on delete cascade,
  kind appointments.appointment_kind not null,
  title text
    constraint appointments_title_length
    check (title is null or char_length(title) <= 200),
  starts_local timestamp without time zone not null
    constraint appointments_starts_local_whole_minute
    check (starts_local = date_trunc('minute', starts_local)),
  time_zone text not null,
  duration_minutes integer
    constraint appointments_duration_range
    check (duration_minutes is null or duration_minutes between 1 and 1440),
  place text
    constraint appointments_place_length
    check (place is null or char_length(place) <= 200),
  doctor text
    constraint appointments_doctor_length
    check (doctor is null or char_length(doctor) <= 200),
  preparation text
    constraint appointments_preparation_length
    check (preparation is null or char_length(preparation) <= 1000),
  notes text
    constraint appointments_notes_length
    check (notes is null or char_length(notes) <= 2000),
  companion_user_id uuid references auth.users (id) on delete set null,
  follow_up_of uuid references appointments.appointments (id) on delete set null,
  cancelled_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid references auth.users (id) on delete set null default auth.uid(),
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users (id) on delete set null
);

comment on table appointments.appointments is
  'One appointment for one patient. Any member reads, owner/caregiver writes, only an owner deletes; cancelling is cancelled_at, not a delete (D29). patient_id never changes (trigger).';
comment on column appointments.appointments.starts_local is
  'Local wall-clock start in time_zone, whole minute. Stored without a zone so a DST change or a trip never moves the appointment; "past" is derived in its zone.';
comment on column appointments.appointments.time_zone is
  'IANA zone starts_local is written in (e.g. Europe/Lisbon). Validated against pg_timezone_names by a trigger, like meds.regimens.';
comment on column appointments.appointments.companion_user_id is
  'The owner/caregiver who takes the patient; decides whose phone gets the reminders. Must be an owner or caregiver of the patient''s circle (trigger); null means every owner and caregiver is reminded.';
comment on column appointments.appointments.follow_up_of is
  'The earlier appointment this one follows up. Must be another appointment of the same patient (trigger).';
comment on column appointments.appointments.cancelled_at is
  'Set when the appointment is cancelled. The row and its history are kept; a cancelled appointment has no reminders.';
comment on column appointments.appointments.created_by is
  'The signed-in user who created the row; a trigger requires it to equal auth.uid() when the caller is authenticated. No cascade on account deletion beyond set null: the appointment survives.';

create index appointments_patient_starts_idx
  on appointments.appointments (patient_id, starts_local);
create index appointments_companion_user_id_idx
  on appointments.appointments (companion_user_id);
create index appointments_follow_up_of_idx
  on appointments.appointments (follow_up_of);
create index appointments_created_by_idx
  on appointments.appointments (created_by);

-- --- Appointment integrity --------------------------------------------------
-- The rules that need a lookup (the time zone list, the circle membership of
-- the companion, the patient of the follow-up) live in one BEFORE trigger.
-- updated_at/updated_by are set here too: updated_by is the caller and must not
-- be spoofable, which one trigger keeps in one place.

create or replace function appointments.check_appointment()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
  v_circle_id uuid;
  v_role core.member_role;
begin
  if not exists (select 1 from pg_timezone_names z where z.name = new.time_zone) then
    raise exception 'time_zone is not a known IANA time zone: %', new.time_zone
      using errcode = '23514';
  end if;

  if tg_op = 'INSERT' then
    -- Authenticated clients create rows as themselves; a server job (no JWT,
    -- hence null auth.uid()) may set another creator.
    if v_user is not null and new.created_by is distinct from v_user then
      raise exception 'created_by must be the signed-in user'
        using errcode = '42501';
    end if;
  else
    if new.patient_id is distinct from old.patient_id then
      raise exception 'an appointment cannot move to another patient'
        using errcode = '23514';
    end if;

    new.updated_at := now();
    if v_user is not null then
      new.updated_by := v_user;
    end if;
  end if;

  if new.companion_user_id is not null then
    select p.circle_id into v_circle_id
    from core.patients p
    where p.id = new.patient_id;

    if v_circle_id is not null then
      select m.role into v_role
      from core.memberships m
      where m.circle_id = v_circle_id and m.user_id = new.companion_user_id;

      if v_role is null or v_role not in ('owner', 'caregiver') then
        raise exception 'companion_user_id must be an owner or caregiver of the patient''s care circle'
          using errcode = '23514';
      end if;
    end if;
  end if;

  if new.follow_up_of is not null then
    if new.follow_up_of = new.id then
      raise exception 'an appointment cannot follow up itself'
        using errcode = '23514';
    end if;

    if not exists (
      select 1
      from appointments.appointments a
      where a.id = new.follow_up_of
        and a.patient_id = new.patient_id
    ) then
      raise exception 'follow_up_of must be another appointment of the same patient'
        using errcode = '23514';
    end if;
  end if;

  return new;
end;
$$;

comment on function appointments.check_appointment() is
  'BEFORE INSERT/UPDATE on appointments.appointments: valid IANA time_zone; created_by is the caller on insert; patient never changes and updated_at/updated_by are set on update; companion_user_id is an owner/caregiver of the patient''s circle; follow_up_of is another appointment of the same patient.';

create trigger appointments_check
  before insert or update on appointments.appointments
  for each row execute function appointments.check_appointment();

revoke all on function appointments.check_appointment() from public;

-- --- Audit ------------------------------------------------------------------
-- Same output shape as core.audit_row_change() / meds.audit_row_change(). This
-- table carries patient_id directly, so the circle is one lookup away.

create or replace function appointments.audit_row_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old jsonb;
  v_new jsonb;
  v_record_id uuid;
  v_patient_id uuid;
  v_circle_id uuid;
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

  v_patient_id := coalesce((v_new ->> 'patient_id')::uuid, (v_old ->> 'patient_id')::uuid);

  if v_patient_id is not null then
    select p.circle_id into v_circle_id from core.patients p where p.id = v_patient_id;
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

comment on function appointments.audit_row_change() is
  'AFTER INSERT/UPDATE/DELETE on the appointments table: writes one core.audit_log row with actor, patient, circle (derived from patient_id) and before/after JSON.';

create trigger appointments_audit
  after insert or update or delete on appointments.appointments
  for each row execute function appointments.audit_row_change();

revoke all on function appointments.audit_row_change() from public;

-- --- RLS and privileges: deny by default -------------------------------------

alter table appointments.appointments enable row level security;
alter table appointments.appointments force row level security;

revoke all on appointments.appointments from public;
revoke all on appointments.appointments from anon;
grant select, insert, update, delete on appointments.appointments to authenticated;

-- The appointment-changed edge function (task 2) reads the circle's owners and
-- caregivers and the appointment it is about; a read grant now keeps the
-- function migration down to the trigger and the function itself. Like the M4
-- functions it reaches the schema through PostgREST, so it needs USAGE too.
grant usage on schema appointments to service_role;
grant select on appointments.appointments to service_role;

create policy appointments_select_can_read
  on appointments.appointments
  for select
  to authenticated
  using (core.can(patient_id, 'read'));

create policy appointments_insert_can_write
  on appointments.appointments
  for insert
  to authenticated
  with check (core.can(patient_id, 'write'));

create policy appointments_update_can_write
  on appointments.appointments
  for update
  to authenticated
  using (core.can(patient_id, 'write'))
  with check (core.can(patient_id, 'write'));

create policy appointments_delete_can_manage
  on appointments.appointments
  for delete
  to authenticated
  using (core.can(patient_id, 'manage'));

-- --- Patient info validators ------------------------------------------------
-- jsonb lists are validated by immutable functions so the rules are CHECK
-- constraints and cannot be bypassed by a trigger-free write path, like
-- meds.times_are_valid().

create or replace function info.text_list_is_valid(
  p_value jsonb,
  p_max_items integer,
  p_max_len integer
)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_item jsonb;
begin
  if p_value is null or jsonb_typeof(p_value) <> 'array' then
    return false;
  end if;

  if jsonb_array_length(p_value) > p_max_items then
    return false;
  end if;

  for v_item in select value from jsonb_array_elements(p_value)
  loop
    if jsonb_typeof(v_item) <> 'string' then
      return false;
    end if;

    if char_length(v_item #>> '{}') not between 1 and p_max_len then
      return false;
    end if;
  end loop;

  return true;
end;
$$;

comment on function info.text_list_is_valid(jsonb, integer, integer) is
  'True when the value is a 0..p_max_items element jsonb array of strings, each 1..p_max_len characters. Used by the allergies/conditions CHECK constraints.';

create or replace function info.contacts_are_valid(p_contacts jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_item jsonb;
  v_relationship jsonb;
begin
  if p_contacts is null or jsonb_typeof(p_contacts) <> 'array' then
    return false;
  end if;

  if jsonb_array_length(p_contacts) > 5 then
    return false;
  end if;

  for v_item in select value from jsonb_array_elements(p_contacts)
  loop
    if jsonb_typeof(v_item) <> 'object' then
      return false;
    end if;

    if not (v_item ? 'name') or jsonb_typeof(v_item -> 'name') <> 'string' then
      return false;
    end if;

    if char_length(v_item ->> 'name') not between 1 and 100 then
      return false;
    end if;

    if not (v_item ? 'phone') or jsonb_typeof(v_item -> 'phone') <> 'string' then
      return false;
    end if;

    if (v_item ->> 'phone') !~ '^[0-9 +()-]{1,30}$' then
      return false;
    end if;

    v_relationship := v_item -> 'relationship';
    if v_relationship is not null and jsonb_typeof(v_relationship) <> 'null' then
      if jsonb_typeof(v_relationship) <> 'string' then
        return false;
      end if;

      if char_length(v_relationship #>> '{}') > 50 then
        return false;
      end if;
    end if;
  end loop;

  return true;
end;
$$;

comment on function info.contacts_are_valid(jsonb) is
  'True when the value is a 0..5 element jsonb array of {name 1..100 required, relationship <=50 optional, phone 1..30 required with the phone charset}. Used by the emergency_contacts CHECK constraint.';

revoke all on function info.text_list_is_valid(jsonb, integer, integer) from public;
revoke all on function info.text_list_is_valid(jsonb, integer, integer) from anon;
-- CHECK constraints execute the function as the inserting role.
grant execute on function info.text_list_is_valid(jsonb, integer, integer) to authenticated;

revoke all on function info.contacts_are_valid(jsonb) from public;
revoke all on function info.contacts_are_valid(jsonb) from anon;
grant execute on function info.contacts_are_valid(jsonb) to authenticated;

-- --- Patient info table -----------------------------------------------------

create table info.patient_info (
  -- Deterministic id: the row for a patient is the same row on every phone, so
  -- two offline creates converge instead of duplicating (D29).
  id uuid primary key references core.patients (id) on delete cascade,
  patient_id uuid not null unique references core.patients (id) on delete cascade,
  allergies jsonb not null default '[]'::jsonb
    constraint patient_info_allergies_valid
    check (info.text_list_is_valid(allergies, 30, 100)),
  conditions jsonb not null default '[]'::jsonb
    constraint patient_info_conditions_valid
    check (info.text_list_is_valid(conditions, 30, 200)),
  sns_number text
    constraint patient_info_sns_number_format
    check (sns_number is null or sns_number ~ '^[0-9]{9}$'),
  family_doctor text
    constraint patient_info_family_doctor_length
    check (family_doctor is null or char_length(family_doctor) <= 200),
  health_centre text
    constraint patient_info_health_centre_length
    check (health_centre is null or char_length(health_centre) <= 200),
  health_centre_phone text
    constraint patient_info_health_centre_phone_format
    check (health_centre_phone is null or health_centre_phone ~ '^[0-9 +()-]{1,30}$'),
  emergency_contacts jsonb not null default '[]'::jsonb
    constraint patient_info_emergency_contacts_valid
    check (info.contacts_are_valid(emergency_contacts)),
  notes text
    constraint patient_info_notes_length
    check (notes is null or char_length(notes) <= 2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  updated_by uuid references auth.users (id) on delete set null,

  -- One row per patient, keyed by the patient itself.
  constraint patient_info_id_is_patient check (id = patient_id)
);

comment on table info.patient_info is
  'One row per patient (id = patient_id), edited in place, last write wins (D29). Any member reads, owner/caregiver writes, only an owner deletes; every change is audited.';
comment on column info.patient_info.id is
  'Equals patient_id: the deterministic row key, so two phones creating the row offline converge on one row. Immutable (trigger).';
comment on column info.patient_info.patient_id is
  'The patient the info belongs to. Immutable (trigger); cascades with the patient.';
comment on column info.patient_info.allergies is
  'jsonb array of 0..30 short strings, each 1..100 characters. Always shown as a banner when non-empty.';
comment on column info.patient_info.conditions is
  'jsonb array of 0..30 short strings, each 1..200 characters.';
comment on column info.patient_info.sns_number is
  'Portuguese national health service user number, exactly 9 digits. Null when unknown.';
comment on column info.patient_info.health_centre_phone is
  'Up to 30 characters of digits, spaces, +, -, ( and ). Null when unknown.';
comment on column info.patient_info.emergency_contacts is
  'jsonb array of 0..5 {name, relationship, phone}: name 1..100 required, relationship <=50 optional, phone 1..30 required with the phone charset.';
comment on column info.patient_info.updated_by is
  'Set by a trigger to auth.uid() on every update; never supplied by the client.';

-- --- Patient info integrity -------------------------------------------------

create or replace function info.check_patient_info()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
begin
  if tg_op = 'UPDATE' then
    if new.patient_id is distinct from old.patient_id then
      raise exception 'patient_info cannot move to another patient'
        using errcode = '23514';
    end if;

    if new.id is distinct from old.id then
      raise exception 'patient_info.id cannot change'
        using errcode = '23514';
    end if;

    new.updated_at := now();
    if v_user is not null then
      new.updated_by := v_user;
    end if;
  end if;

  return new;
end;
$$;

comment on function info.check_patient_info() is
  'BEFORE INSERT/UPDATE on info.patient_info: patient_id and id never change; updated_at/updated_by are set on update (updated_by = the caller when authenticated).';

create trigger patient_info_check
  before insert or update on info.patient_info
  for each row execute function info.check_patient_info();

revoke all on function info.check_patient_info() from public;

-- --- Audit ------------------------------------------------------------------

create or replace function info.audit_row_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old jsonb;
  v_new jsonb;
  v_record_id uuid;
  v_patient_id uuid;
  v_circle_id uuid;
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

  v_patient_id := coalesce((v_new ->> 'patient_id')::uuid, (v_old ->> 'patient_id')::uuid);

  if v_patient_id is not null then
    select p.circle_id into v_circle_id from core.patients p where p.id = v_patient_id;
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

comment on function info.audit_row_change() is
  'AFTER INSERT/UPDATE/DELETE on info.patient_info: writes one core.audit_log row with actor, patient, circle (derived from patient_id) and before/after JSON.';

create trigger patient_info_audit
  after insert or update or delete on info.patient_info
  for each row execute function info.audit_row_change();

revoke all on function info.audit_row_change() from public;

-- --- RLS and privileges: deny by default -------------------------------------

alter table info.patient_info enable row level security;
alter table info.patient_info force row level security;

revoke all on info.patient_info from public;
revoke all on info.patient_info from anon;
grant select, insert, update, delete on info.patient_info to authenticated;

create policy patient_info_select_can_read
  on info.patient_info
  for select
  to authenticated
  using (core.can(patient_id, 'read'));

create policy patient_info_insert_can_write
  on info.patient_info
  for insert
  to authenticated
  with check (core.can(patient_id, 'write'));

create policy patient_info_update_can_write
  on info.patient_info
  for update
  to authenticated
  using (core.can(patient_id, 'write'))
  with check (core.can(patient_id, 'write'));

create policy patient_info_delete_can_manage
  on info.patient_info
  for delete
  to authenticated
  using (core.can(patient_id, 'manage'));

-- --- Replication ------------------------------------------------------------
-- PowerSync replicates both tables. Same idempotent form as M1-M5 so a re-run
-- or a partially migrated database is safe.

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
      and schemaname = 'appointments'
      and tablename = 'appointments'
  ) then
    alter publication powersync add table appointments.appointments;
  end if;

  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'powersync'
      and schemaname = 'info'
      and tablename = 'patient_info'
  ) then
    alter publication powersync add table info.patient_info;
  end if;
end;
$$;
