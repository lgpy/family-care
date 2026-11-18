-- M3 medications (build-plan §M3, docs/domain/medications.md, D5/D6/D9/D13/D14).
--
-- Schema `meds` holds the medication, its versioned regimens and the
-- append-only dose log. Stock (ledger, low stock, shopping list) is M5 and is
-- deliberately absent: no stock table, no low-stock job.
--
-- Conventions copied from the M2 migration (20261118120000_core_care_circle.sql):
-- RLS enabled *and forced*, nothing granted to anon/PUBLIC, policies scoped
-- `to authenticated`, `(select auth.uid())`, `security definer` +
-- `set search_path = ''` on every function, one access function (`core.can`),
-- audit triggers writing `core.audit_log`, and the `powersync` publication for
-- replicated tables.
--
-- Module direction (D5, architecture "Rules"): `meds` references `core` by id
-- only and never the other way round. That is why the audit trigger for these
-- tables lives in `meds` (see "Audit" below) instead of extending
-- `core.audit_row_change()` with meds-specific branches.

create schema if not exists meds;

comment on schema meds is
  'Medications module: medications, versioned regimens and the append-only dose log. Stock is M5. See docs/domain/medications.md.';

revoke all on schema meds from public;
revoke all on schema meds from anon;
grant usage on schema meds to authenticated;

-- --- Types ------------------------------------------------------------------

create type meds.medication_form as enum
  ('tablet', 'capsule', 'drops', 'liquid', 'injection', 'inhaler', 'patch', 'cream', 'other');

create type meds.schedule_type as enum ('daily', 'weekdays', 'every_n_days');

create type meds.skip_reason as enum ('refused', 'not_needed', 'unavailable', 'other');

create type meds.dose_outcome as enum ('taken', 'skipped');

comment on type meds.medication_form is
  'How the medication is taken. The app shows this; stock is counted in the free-text stock_unit.';
comment on type meds.schedule_type is
  'MVP schedules: every day, ISO weekday bitmask, or every N days anchored on start_date. PRN/tapering are deferred.';
comment on type meds.skip_reason is
  'Why a dose was skipped. Required exactly when outcome = skipped.';
comment on type meds.dose_outcome is
  'What happened to a dose slot. "Missed" is derived, never stored (D9).';

-- Types are usable by PUBLIC by default, so revoke explicitly like tables.
revoke all on type meds.medication_form from public;
revoke all on type meds.medication_form from anon;
grant usage on type meds.medication_form to authenticated;

revoke all on type meds.schedule_type from public;
revoke all on type meds.schedule_type from anon;
grant usage on type meds.schedule_type to authenticated;

revoke all on type meds.skip_reason from public;
revoke all on type meds.skip_reason from anon;
grant usage on type meds.skip_reason to authenticated;

revoke all on type meds.dose_outcome from public;
revoke all on type meds.dose_outcome from anon;
grant usage on type meds.dose_outcome to authenticated;

-- --- Regimen times validation ----------------------------------------------
-- `times` is a jsonb array of {"time":"HH:MM","quantity":number}, 1..12
-- entries, strictly ascending and unique by time. Times are local wall-clock
-- strings so the app never has to reinterpret a timestamp on a DST change.
-- Implemented as an immutable function so the rule can be a CHECK constraint
-- and therefore cannot be bypassed by a trigger-free write path.

create or replace function meds.times_are_valid(p_times jsonb)
returns boolean
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_item jsonb;
  v_previous text;
  v_time text;
  v_quantity numeric;
  v_count integer := 0;
begin
  if p_times is null or jsonb_typeof(p_times) <> 'array' then
    return false;
  end if;

  if jsonb_array_length(p_times) not between 1 and 12 then
    return false;
  end if;

  for v_item in select value from jsonb_array_elements(p_times)
  loop
    v_count := v_count + 1;

    if jsonb_typeof(v_item) <> 'object' then
      return false;
    end if;

    if not (v_item ? 'time') or jsonb_typeof(v_item -> 'time') <> 'string' then
      return false;
    end if;

    v_time := v_item ->> 'time';
    if v_time !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then
      return false;
    end if;

    -- Strictly ascending also rejects duplicates: one slot per time per day.
    if v_previous is not null and v_time <= v_previous then
      return false;
    end if;
    v_previous := v_time;

    if not (v_item ? 'quantity') or jsonb_typeof(v_item -> 'quantity') <> 'number' then
      return false;
    end if;

    v_quantity := (v_item ->> 'quantity')::numeric;
    if v_quantity <= 0 or v_quantity > 100 or v_quantity <> round(v_quantity, 3) then
      return false;
    end if;
  end loop;

  -- jsonb_array_length ignores nothing, so this also catches unexpected holes.
  return v_count = jsonb_array_length(p_times);
end;
$$;

comment on function meds.times_are_valid(jsonb) is
  'True when times is a 1..12 element ascending, unique array of {"time":"HH:MM","quantity":>0..100 with <=3 decimals}. Used by the regimens CHECK constraint.';

revoke all on function meds.times_are_valid(jsonb) from public;
revoke all on function meds.times_are_valid(jsonb) from anon;
-- CHECK constraints execute the function as the inserting role, so
-- authenticated needs EXECUTE even though it never calls it directly.
grant execute on function meds.times_are_valid(jsonb) to authenticated;

-- --- Tables -----------------------------------------------------------------

create table meds.medications (
  id uuid primary key default gen_random_uuid(),
  patient_id uuid not null references core.patients (id) on delete cascade,
  name text not null
    constraint medications_name_length check (char_length(name) between 1 and 200),
  active_ingredient text
    constraint medications_active_ingredient_length
    check (active_ingredient is null or char_length(active_ingredient) <= 200),
  strength text
    constraint medications_strength_length
    check (strength is null or char_length(strength) <= 100),
  form meds.medication_form not null,
  stock_unit text not null
    constraint medications_stock_unit_length check (char_length(stock_unit) between 1 and 50),
  instructions text
    constraint medications_instructions_length
    check (instructions is null or char_length(instructions) <= 1000),
  requires_prescription boolean not null default false,
  prescription_valid_until date,
  low_stock_threshold_days integer not null default 7
    constraint medications_low_stock_threshold_range
    check (low_stock_threshold_days between 0 and 365),
  reminder_policy core.reminder_policy,
  primary_caregiver_id uuid references auth.users (id) on delete set null,
  stopped_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  created_by uuid references auth.users (id) on delete set null default auth.uid()
);

comment on table meds.medications is
  'A medication for one patient. Direct DML through PostgREST with RLS: any member reads, owner/caregiver writes, only an owner deletes. patient_id never changes (trigger).';
comment on column meds.medications.stock_unit is
  'What stock will be counted in (M5): "tablet", "ml", "pen". Free text for now.';
comment on column meds.medications.reminder_policy is
  'Optional override of the patient''s reminder policy; null means inherit. primary_caregiver_id is only allowed together with an override.';
comment on column meds.medications.primary_caregiver_id is
  'Who is reminded first for this medication; must be an owner or caregiver of the patient''s circle. Null inherits the patient''s primary caregiver.';
comment on column meds.medications.stopped_at is
  'Set when the medication is stopped. History (regimens, dose log) is kept.';
comment on column meds.medications.low_stock_threshold_days is
  'Low-stock threshold in days (M5 uses it). Stored now so the app can edit it with the medication.';

create index medications_patient_id_idx on meds.medications (patient_id);
create index medications_primary_caregiver_id_idx on meds.medications (primary_caregiver_id);
create index medications_created_by_idx on meds.medications (created_by);

create table meds.regimens (
  id uuid primary key default gen_random_uuid(),
  medication_id uuid not null references meds.medications (id) on delete cascade,
  schedule_type meds.schedule_type not null,
  weekdays smallint
    constraint regimens_weekdays_range check (weekdays between 1 and 127),
  interval_days smallint
    constraint regimens_interval_days_range check (interval_days between 2 and 365),
  start_date date not null,
  end_date date,
  time_zone text not null,
  times jsonb not null
    constraint regimens_times_valid check (meds.times_are_valid(times)),
  created_at timestamptz not null default now(),
  created_by uuid references auth.users (id) on delete set null default auth.uid(),

  constraint regimens_end_not_before_start
    check (end_date is null or end_date >= start_date),

  -- Exactly one schedule shape is valid: daily has neither weekdays nor an
  -- interval; weekdays has a non-zero ISO bitmask; every_n_days has 2..365
  -- days anchored on start_date.
  constraint regimens_schedule_consistency check (
    (schedule_type = 'daily' and weekdays is null and interval_days is null)
    or (schedule_type = 'weekdays' and weekdays is not null and interval_days is null)
    or (schedule_type = 'every_n_days' and interval_days is not null and weekdays is null)
  )
);

comment on table meds.regimens is
  'Versioned schedule (D9): never edited in place. A dose change ends the old regimen and inserts a new one. Only end_date may ever change, and only downwards. One medication must not have overlapping date ranges.';
comment on column meds.regimens.weekdays is
  'ISO weekday bitmask: Monday=1, Tuesday=2, Wednesday=4, Thursday=8, Friday=16, Saturday=32, Sunday=64. Non-zero 1..127, only for schedule_type = weekdays.';
comment on column meds.regimens.interval_days is
  'For schedule_type = every_n_days: a dose every N days counted from start_date. Null otherwise.';
comment on column meds.regimens.time_zone is
  'IANA zone the wall-clock times are written in (e.g. Europe/Lisbon). Validated against pg_timezone_names by a trigger.';
comment on column meds.regimens.times is
  'jsonb array of {"time":"HH:MM","quantity":number}, ascending and unique, 1..12 entries, quantities >0..100 with at most 3 decimals.';
comment on column meds.regimens.end_date is
  'Inclusive last day. Null means ongoing. May only be set or moved earlier, never cleared once set.';

create index regimens_medication_id_idx on meds.regimens (medication_id);
create index regimens_created_by_idx on meds.regimens (created_by);

create table meds.dose_log (
  id uuid primary key default gen_random_uuid(),
  regimen_id uuid not null references meds.regimens (id) on delete cascade,
  medication_id uuid not null references meds.medications (id) on delete cascade,
  scheduled_local timestamp without time zone not null,
  outcome meds.dose_outcome not null,
  skip_reason meds.skip_reason,
  note text
    constraint dose_log_note_length check (note is null or char_length(note) <= 500),
  actual_at timestamptz not null,
  actual_quantity numeric(7, 3)
    constraint dose_log_actual_quantity_positive
    check (actual_quantity is null or actual_quantity > 0),
  confirmed_by uuid not null default auth.uid(),
  given_by_user_id uuid,
  given_by_other boolean not null default false,
  given_by_name text
    constraint dose_log_given_by_name_length
    check (given_by_name is null or char_length(given_by_name) <= 100),
  corrects_entry_id uuid references meds.dose_log (id) on delete cascade,
  created_at timestamptz not null default now(),

  constraint dose_log_skip_reason_consistency check (
    (outcome = 'taken' and skip_reason is null)
    or (outcome = 'skipped' and skip_reason is not null)
  ),
  constraint dose_log_actual_quantity_only_when_taken
    check (actual_quantity is null or outcome = 'taken'),
  constraint dose_log_given_by_exclusive
    check (not (given_by_user_id is not null and given_by_other)),
  constraint dose_log_given_by_name_only_when_other
    check (given_by_name is null or given_by_other)
);

comment on table meds.dose_log is
  'Append-only record of what happened to a dose slot (D6). No UPDATE/DELETE grant; a BEFORE UPDATE trigger refuses updates for every role (cascades still delete). Corrections are new rows with corrects_entry_id; nothing is edited or voided in place.';
comment on column meds.dose_log.scheduled_local is
  'The slot''s local wall-clock time in the regimen''s zone. Slot identity is (regimen_id, scheduled_local); duplicate rows for one slot are allowed on purpose (double confirmation) and shown as one dose confirmed twice.';
comment on column meds.dose_log.actual_at is
  'Server/user recorded instant the dose was actually given or skipped.';
comment on column meds.dose_log.confirmed_by is
  'The signed-in user who recorded the entry; a trigger requires it to equal auth.uid() when the caller is authenticated. Deliberately no foreign key: history survives account deletion.';
comment on column meds.dose_log.given_by_user_id is
  'Circle member who physically gave the dose, when known. Mutually exclusive with given_by_other. No foreign key: history survives account deletion.';
comment on column meds.dose_log.given_by_name is
  'Free-text name when the dose was given by someone outside the circle (given_by_other = true).';
comment on column meds.dose_log.corrects_entry_id is
  'The entry this one corrects. Must share regimen_id and scheduled_local; the app derives that the old entry is void. Nothing is deleted.';

create index dose_log_regimen_slot_idx on meds.dose_log (regimen_id, scheduled_local);
create index dose_log_medication_id_idx on meds.dose_log (medication_id);
create index dose_log_corrects_entry_id_idx on meds.dose_log (corrects_entry_id);

-- --- updated_at -------------------------------------------------------------
-- core.set_updated_at() is defined by the M1 migration and reused unchanged.

create trigger medications_set_updated_at
  before update on meds.medications
  for each row execute function core.set_updated_at();

-- --- Access helper ----------------------------------------------------------
-- Policies on the child tables need the patient of a medication. One security
-- definer helper keeps every policy to the same `core.can(patient, action)`
-- question and avoids a policy on `medications` inside a policy on `regimens`.

create or replace function meds.patient_of_medication(p_medication_id uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select m.patient_id from meds.medications m where m.id = p_medication_id;
$$;

comment on function meds.patient_of_medication(uuid) is
  'Patient that owns a medication, for policies on regimens and dose_log. Security definer so RLS does not recurse.';

revoke all on function meds.patient_of_medication(uuid) from public;
revoke all on function meds.patient_of_medication(uuid) from anon;
grant execute on function meds.patient_of_medication(uuid) to authenticated;

-- --- Medication integrity ---------------------------------------------------

create or replace function meds.check_medication()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_circle_id uuid;
  v_role core.member_role;
begin
  if tg_op = 'UPDATE' and new.patient_id is distinct from old.patient_id then
    raise exception 'a medication cannot move to another patient'
      using errcode = '23514';
  end if;

  if new.primary_caregiver_id is not null then
    -- An override is only meaningful with a policy override: otherwise it
    -- would silently disagree with the patient's inherited reminder routing.
    if new.reminder_policy is null then
      raise exception 'primary_caregiver_id requires a reminder_policy override'
        using errcode = '23514';
    end if;

    select p.circle_id into v_circle_id from core.patients p where p.id = new.patient_id;

    if v_circle_id is not null then
      select m.role into v_role
      from core.memberships m
      where m.circle_id = v_circle_id and m.user_id = new.primary_caregiver_id;

      if v_role is null or v_role not in ('owner', 'caregiver') then
        raise exception 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle'
          using errcode = '23514';
      end if;
    end if;
  end if;

  return new;
end;
$$;

comment on function meds.check_medication() is
  'BEFORE INSERT/UPDATE on meds.medications: patient never changes; primary_caregiver_id needs a reminder_policy override and must be an owner/caregiver of the patient''s circle.';

create trigger medications_check
  before insert or update on meds.medications
  for each row execute function meds.check_medication();

-- --- Regimen integrity (versioning + overlap) -------------------------------

create or replace function meds.check_regimen()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not exists (select 1 from pg_timezone_names z where z.name = new.time_zone) then
    raise exception 'time_zone is not a known IANA time zone: %', new.time_zone
      using errcode = '23514';
  end if;

  if tg_op = 'UPDATE' then
    -- D9: a regimen is versioned. Only end_date may ever change.
    if (to_jsonb(new) - 'end_date') is distinct from (to_jsonb(old) - 'end_date') then
      raise exception 'a regimen is versioned: only end_date may change'
        using errcode = '23514';
    end if;

    if old.end_date is not null and new.end_date is distinct from old.end_date then
      if new.end_date is null then
        raise exception 'end_date cannot be cleared once set' using errcode = '23514';
      end if;

      if new.end_date > old.end_date then
        raise exception 'end_date can only be moved earlier' using errcode = '23514';
      end if;
    end if;
  end if;

  if new.end_date is not null and new.end_date < new.start_date then
    raise exception 'end_date cannot be before start_date' using errcode = '23514';
  end if;

  -- Inclusive ranges; null end_date is unbounded. Serialisation is left to the
  -- single-writer app flow for now: a concurrent pair of inserts is not
  -- prevented by this check (an exclusion constraint would need btree_gist and
  -- is unnecessary at this scale).
  if exists (
    select 1
    from meds.regimens r
    where r.medication_id = new.medication_id
      and r.id <> new.id
      and daterange(r.start_date, r.end_date, '[]')
          && daterange(new.start_date, new.end_date, '[]')
  ) then
    raise exception 'regimens for one medication must not overlap'
      using errcode = '23514';
  end if;

  return new;
end;
$$;

comment on function meds.check_regimen() is
  'BEFORE INSERT/UPDATE on meds.regimens: valid IANA time_zone; only end_date may change and only downwards; end_date never before start_date; no overlapping date range for the medication.';

create trigger regimens_check
  before insert or update on meds.regimens
  for each row execute function meds.check_regimen();

-- --- Dose log integrity (append-only, slot identity, confirmations) ---------

create or replace function meds.check_dose_log()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
  v_regimen_medication uuid;
  v_circle_id uuid;
begin
  if new.confirmed_by is null then
    raise exception 'confirmed_by is required' using errcode = '23514';
  end if;

  -- Authenticated clients cannot confirm on someone else's behalf. Server jobs
  -- (no JWT, hence null auth.uid()) may insert a row for a known user.
  if v_user is not null and new.confirmed_by <> v_user then
    raise exception 'confirmed_by must be the signed-in user' using errcode = '42501';
  end if;

  select r.medication_id into v_regimen_medication
  from meds.regimens r
  where r.id = new.regimen_id;

  if v_regimen_medication is not null
     and v_regimen_medication <> new.medication_id then
    raise exception 'medication_id must match the regimen''s medication'
      using errcode = '23514';
  end if;

  if new.given_by_user_id is not null then
    select p.circle_id into v_circle_id
    from meds.medications m
    join core.patients p on p.id = m.patient_id
    where m.id = new.medication_id;

    if v_circle_id is not null and not exists (
      select 1 from core.memberships m
      where m.circle_id = v_circle_id and m.user_id = new.given_by_user_id
    ) then
      raise exception 'given_by_user_id must be a member of the patient''s care circle'
        using errcode = '23514';
    end if;
  end if;

  if new.corrects_entry_id is not null then
    if not exists (
      select 1
      from meds.dose_log d
      where d.id = new.corrects_entry_id
        and d.id <> new.id
        and d.regimen_id = new.regimen_id
        and d.scheduled_local = new.scheduled_local
    ) then
      raise exception 'a correction must reference an entry for the same regimen and slot'
        using errcode = '23514';
    end if;
  end if;

  return new;
end;
$$;

comment on function meds.check_dose_log() is
  'BEFORE INSERT on meds.dose_log: confirmed_by is the caller (when authenticated); medication matches the regimen; given_by_user_id is a circle member; a correction references the same regimen and slot.';

create trigger dose_log_check
  before insert on meds.dose_log
  for each row execute function meds.check_dose_log();

create or replace function meds.dose_log_is_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'meds.dose_log is append-only: % is not allowed', tg_op
    using errcode = '42501';
end;
$$;

comment on function meds.dose_log_is_append_only() is
  'BEFORE UPDATE on meds.dose_log: always raises. DELETE is refused by the missing grant for authenticated; cascades from medication/regimen deletion (and GDPR erasure) still work.';

create trigger dose_log_no_update
  before update on meds.dose_log
  for each row execute function meds.dose_log_is_append_only();

revoke all on function meds.check_medication() from public;
revoke all on function meds.check_regimen() from public;
revoke all on function meds.check_dose_log() from public;
revoke all on function meds.dose_log_is_append_only() from public;

-- --- Audit ------------------------------------------------------------------
-- core.audit_row_change() knows the core tables only: it derives the circle
-- from a `circle_id` column and the patient from `core.patients`. These tables
-- have neither (the patient is one or two joins away), so the meds module gets
-- its own trigger function with the same output shape. Keeping it here (rather
-- than adding meds branches to the core function) preserves the module rule
-- "modules depend on the core, never on each other".

create or replace function meds.audit_row_change()
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
  v_medication_id uuid;
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

  if tg_table_name = 'medications' then
    v_patient_id := coalesce((v_new ->> 'patient_id')::uuid, (v_old ->> 'patient_id')::uuid);
  else
    v_medication_id := coalesce((v_new ->> 'medication_id')::uuid, (v_old ->> 'medication_id')::uuid);
    select m.patient_id into v_patient_id
    from meds.medications m
    where m.id = v_medication_id;
  end if;

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

comment on function meds.audit_row_change() is
  'AFTER INSERT/UPDATE/DELETE on the meds tables: writes one core.audit_log row with actor, patient, circle (derived by join) and before/after JSON.';

create trigger medications_audit
  after insert or update or delete on meds.medications
  for each row execute function meds.audit_row_change();

create trigger regimens_audit
  after insert or update or delete on meds.regimens
  for each row execute function meds.audit_row_change();

create trigger dose_log_audit
  after insert or update or delete on meds.dose_log
  for each row execute function meds.audit_row_change();

revoke all on function meds.audit_row_change() from public;

-- --- RLS and privileges: deny by default -------------------------------------

alter table meds.medications enable row level security;
alter table meds.medications force row level security;
alter table meds.regimens enable row level security;
alter table meds.regimens force row level security;
alter table meds.dose_log enable row level security;
alter table meds.dose_log force row level security;

-- medications: readable by any circle member, written by owner/caregiver,
-- deleted only by an owner (`manage`).
revoke all on meds.medications from public;
revoke all on meds.medications from anon;
grant select, insert, update, delete on meds.medications to authenticated;

-- regimens: versioned, so clients may not DELETE them (a stopped treatment is
-- an end_date, not a missing row). Removal happens only by medication cascade.
revoke all on meds.regimens from public;
revoke all on meds.regimens from anon;
grant select, insert, update on meds.regimens to authenticated;

-- dose_log: append-only. SELECT + INSERT only; no UPDATE/DELETE grant at all.
revoke all on meds.dose_log from public;
revoke all on meds.dose_log from anon;
grant select, insert on meds.dose_log to authenticated;

create policy medications_select_can_read
  on meds.medications
  for select
  to authenticated
  using (core.can(patient_id, 'read'));

create policy medications_insert_can_write
  on meds.medications
  for insert
  to authenticated
  with check (core.can(patient_id, 'write'));

create policy medications_update_can_write
  on meds.medications
  for update
  to authenticated
  using (core.can(patient_id, 'write'))
  with check (core.can(patient_id, 'write'));

create policy medications_delete_can_manage
  on meds.medications
  for delete
  to authenticated
  using (core.can(patient_id, 'manage'));

create policy regimens_select_can_read
  on meds.regimens
  for select
  to authenticated
  using (core.can(meds.patient_of_medication(medication_id), 'read'));

create policy regimens_insert_can_write
  on meds.regimens
  for insert
  to authenticated
  with check (core.can(meds.patient_of_medication(medication_id), 'write'));

-- The trigger makes this policy effectively end_date-only.
create policy regimens_update_can_write
  on meds.regimens
  for update
  to authenticated
  using (core.can(meds.patient_of_medication(medication_id), 'write'))
  with check (core.can(meds.patient_of_medication(medication_id), 'write'));

create policy dose_log_select_can_read
  on meds.dose_log
  for select
  to authenticated
  using (core.can(meds.patient_of_medication(medication_id), 'read'));

create policy dose_log_insert_can_write
  on meds.dose_log
  for insert
  to authenticated
  with check (core.can(meds.patient_of_medication(medication_id), 'write'));

-- --- Replication ------------------------------------------------------------
-- PowerSync replicates only these three meds tables. Same idempotent form as
-- M1/M2 so a re-run or a partially migrated database is safe.

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
  foreach v_table in array array['medications', 'regimens', 'dose_log']
  loop
    if not exists (
      select 1
      from pg_publication_tables
      where pubname = 'powersync'
        and schemaname = 'meds'
        and tablename = v_table
    ) then
      execute format('alter publication powersync add table meds.%I', v_table);
    end if;
  end loop;
end;
$$;
