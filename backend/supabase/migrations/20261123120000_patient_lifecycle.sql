-- M7 patient lifecycle (build-plan §M7, docs/domain/patient-lifecycle.md,
-- docs/decisions.md D30, with D13/D14/D27).
--
-- Five things, all in this one migration because they share a transaction-
-- local flag and must land together:
--
-- 1. Archive is enforced read-only: core.patients.archived_at was added in M2
--    but nothing honoured it. Every child table gets a BEFORE INSERT/UPDATE/
--    DELETE trigger raising 42501 'patient is archived' through one function,
--    and core.patients itself only accepts an owner changing archived_at,
--    with nothing else editable while archived.
-- 2. Archived patients disappear from the server jobs: the effective_reminder
--    view, the stock-low function (its pure planner) and the appointment
--    change push.
-- 3. Erasure goes through core.erase_patient() only: direct DELETE on
--    core.patients is revoked from authenticated, the append-only audit guard
--    opens one tightly-scoped window, and every audit trigger skips the rows
--    of the patient being erased.
-- 4. Cascaded child deletes outside an erase keep their patient/circle
--    attribution: the parent's BEFORE DELETE trigger stashes the ids in
--    transaction-local settings, which the audit functions fall back to.
-- 5. Export logging (core.log_patient_export) and the owner Activity feed
--    (core.circle_activity), plus the two hosted security-advisor fixes.
--
-- Conventions are the M2-M6 ones: security definer + `set search_path = ''`
-- on every function, nothing granted to anon/PUBLIC, policies through
-- core.can()/core.can_circle(), audit rows in core.audit_log.

-- --- 1. Archive enforcement -------------------------------------------------

create or replace function core.assert_patient_writable(p_patient_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_archived_at timestamptz;
begin
  if p_patient_id is null then
    return;
  end if;

  select p.archived_at into v_archived_at
  from core.patients p
  where p.id = p_patient_id;

  -- The erase RPC deletes the patient and cascades; that path is the one
  -- exception. A client cannot usefully set the flag itself: it has no DELETE
  -- on core.patients and direct deletes are refused.
  if v_archived_at is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         <> p_patient_id::text then
    raise exception 'patient is archived' using errcode = '42501';
  end if;
end;
$$;

comment on function core.assert_patient_writable(uuid) is
  'Raises 42501 ''patient is archived'' when the patient is archived and is not being erased. Called by the per-table archive triggers; no API role may execute it.';

-- Rows that carry patient_id themselves (meds.medications, appointments,
-- info.patient_info, core.patient_access_overrides).
create or replace function core.assert_patient_writable_row()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'DELETE' then
    perform core.assert_patient_writable(old.patient_id);
    return old;
  end if;

  perform core.assert_patient_writable(new.patient_id);
  return new;
end;
$$;

comment on function core.assert_patient_writable_row() is
  'BEFORE INSERT/UPDATE/DELETE trigger helper for tables with a patient_id column. Returns the row so the DML is not cancelled.';

-- Rows two joins away from the patient (regimens, dose_log, stock_movements):
-- the patient comes from the medication. When a medication cascade has
-- already removed the parent, the lookup is null and there is nothing to
-- check - the erase path sets the flag anyway.
create or replace function core.assert_patient_writable_medication()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_medication_id uuid;
  v_patient_id uuid;
begin
  if tg_op = 'DELETE' then
    v_medication_id := old.medication_id;
  else
    v_medication_id := new.medication_id;
  end if;

  select m.patient_id into v_patient_id
  from meds.medications m
  where m.id = v_medication_id;

  perform core.assert_patient_writable(v_patient_id);

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

comment on function core.assert_patient_writable_medication() is
  'BEFORE INSERT/UPDATE/DELETE trigger helper for meds tables that reach the patient through medication_id. Returns the row so the DML is not cancelled.';

create trigger medications_archive_check
  before insert or update or delete on meds.medications
  for each row execute function core.assert_patient_writable_row();

create trigger regimens_archive_check
  before insert or update or delete on meds.regimens
  for each row execute function core.assert_patient_writable_medication();

create trigger dose_log_archive_check
  before insert or update or delete on meds.dose_log
  for each row execute function core.assert_patient_writable_medication();

create trigger stock_movements_archive_check
  before insert or update or delete on meds.stock_movements
  for each row execute function core.assert_patient_writable_medication();

create trigger appointments_archive_check
  before insert or update or delete on appointments.appointments
  for each row execute function core.assert_patient_writable_row();

create trigger patient_info_archive_check
  before insert or update or delete on info.patient_info
  for each row execute function core.assert_patient_writable_row();

create trigger patient_access_overrides_archive_check
  before insert or update or delete on core.patient_access_overrides
  for each row execute function core.assert_patient_writable_row();

-- On core.patients itself: only an owner may set or clear archived_at, and
-- while archived the only accepted update is clearing it. Applies to server
-- jobs too (auth.uid() is then null, so circle_role() is null).
create or replace function core.check_patient_archive()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.archived_at is distinct from old.archived_at
     and core.circle_role(new.circle_id) is distinct from 'owner' then
    raise exception 'only an owner can archive or unarchive a patient'
      using errcode = '42501';
  end if;

  if old.archived_at is not null then
    if new.archived_at is not null then
      -- Still archived: nothing at all may change (updated_at is set by the
      -- later trigger and is not a client field).
      if (to_jsonb(new) - 'updated_at') is distinct from (to_jsonb(old) - 'updated_at') then
        raise exception 'patient is archived' using errcode = '42501';
      end if;
    else
      -- The one allowed update: clear archived_at, and change nothing else.
      if (to_jsonb(new) - 'archived_at' - 'updated_at')
         is distinct from (to_jsonb(old) - 'archived_at' - 'updated_at') then
        raise exception 'patient is archived' using errcode = '42501';
      end if;
    end if;
  end if;

  return new;
end;
$$;

comment on function core.check_patient_archive() is
  'BEFORE UPDATE on core.patients: only an owner changes archived_at; while archived the only permitted update is clearing archived_at.';

create trigger patients_archive_check
  before update on core.patients
  for each row execute function core.check_patient_archive();

revoke all on function core.assert_patient_writable(uuid) from public;
revoke all on function core.assert_patient_writable_row() from public;
revoke all on function core.assert_patient_writable_medication() from public;
revoke all on function core.check_patient_archive() from public;

-- --- 2. Hide archived patients from server jobs -----------------------------

-- Same columns and grants as M4; one extra predicate. The escalate function
-- reads this view, so no reminder/escalation is scheduled for archived rows.
create or replace view meds.effective_reminder
with (security_invoker = true)
as
select
  m.id as medication_id,
  m.patient_id,
  p.circle_id,
  coalesce(m.reminder_policy, p.reminder_policy) as reminder_policy,
  coalesce(m.primary_caregiver_id, p.primary_caregiver_id) as primary_caregiver_id,
  p.escalation_delay_minutes,
  p.missed_window_minutes
from meds.medications m
join core.patients p on p.id = m.patient_id
where m.stopped_at is null
  and p.archived_at is null;

comment on view meds.effective_reminder is
  'Active medications of non-archived patients with their effective reminder settings: medication override else patient policy, medication primary else patient primary, plus the patient''s escalation delay and missed window. security_invoker so RLS still decides who reads what; server jobs use the service role.';

revoke all on meds.effective_reminder from public;
revoke all on meds.effective_reminder from anon;
revoke all on meds.effective_reminder from authenticated;
grant select on meds.effective_reminder to service_role;

-- The appointment change push is skipped for archived patients. The archive
-- trigger already refuses client writes; this is the defensive half for the
-- erase path and for any future server caller.
create or replace function appointments.notify_appointment_changed()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid;
  v_archived_at timestamptz;
begin
  if tg_op = 'UPDATE' then
    if new.starts_local is not distinct from old.starts_local
       and new.time_zone is not distinct from old.time_zone
       and new.place is not distinct from old.place
       and new.companion_user_id is not distinct from old.companion_user_id
       and new.cancelled_at is not distinct from old.cancelled_at then
      return null;
    end if;
  end if;

  -- Archived patients are hidden from every push (D30).
  select p.archived_at into v_archived_at
  from core.patients p
  where p.id = new.patient_id;

  if v_archived_at is not null then
    return null;
  end if;

  -- The wall-clock start is stored without a zone; "one day in the past" must be
  -- read in the appointment's zone, not the server's.
  if new.starts_local < ((now() at time zone new.time_zone) - interval '1 day') then
    return null;
  end if;

  v_actor := case
    when tg_op = 'INSERT' then new.created_by
    else new.updated_by
  end;

  perform core.post_to_function(
    'appointment_changed_url',
    jsonb_build_object('appointment_id', new.id, 'actor', v_actor)
  );

  return null;
exception
  when others then
    -- A webhook must never fail the row change: the row is the record, the push
    -- is only a courtesy for the other phones.
    return null;
end;
$$;

comment on function appointments.notify_appointment_changed() is
  'AFTER INSERT/UPDATE on appointments.appointments: asks the appointment-changed edge function to notify the circle''s owners and caregivers (except the actor). Skips cosmetic updates, archived patients and appointments over a day old in their own zone. No-op unless the Vault secrets are configured, and never raises.';

revoke all on function appointments.notify_appointment_changed() from public;

-- --- 3. Erase ---------------------------------------------------------------

-- The append-only guard gains the one erase window: DELETE is allowed only
-- for a row whose patient_id is exactly the patient named by the
-- transaction-local flag. There is still no DELETE grant on the table for any
-- API role, so a client cannot open that window by setting the GUC itself.
create or replace function core.audit_log_is_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- Controlled exception 1: core.purge_audit_log() (retention).
  if tg_op = 'DELETE'
     and coalesce(current_setting('core.audit_log_purge', true), '') = 'on'
     and current_user = (
       select pg_get_userbyid(c.relowner)
       from pg_class c
       where c.oid = 'core.audit_log'::regclass
     ) then
    return old;
  end if;

  -- Controlled exception 2: core.erase_patient() removing that patient's own
  -- history. It names one patient, and only rows of that patient pass.
  if tg_op = 'DELETE'
     and old.patient_id is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         = old.patient_id::text then
    return old;
  end if;

  raise exception 'core.audit_log is append-only: % is not allowed', tg_op
    using errcode = '42501';
end;
$$;

comment on function core.audit_log_is_append_only() is
  'BEFORE UPDATE/DELETE/TRUNCATE on core.audit_log: raises for every role, including the table owner. Allowed DELETEs: core.purge_audit_log() (transaction-local core.audit_log_purge flag plus table-owner role) and core.erase_patient() for rows of the patient named by familycare.erasing_patient.';

create or replace function core.erase_patient(p_patient_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_circle_id uuid;
begin
  if p_patient_id is null then
    raise exception 'patient is required' using errcode = '22023';
  end if;

  select p.circle_id into v_circle_id
  from core.patients p
  where p.id = p_patient_id;

  if not found then
    raise exception 'patient not found' using errcode = 'P0002';
  end if;

  if not core.can(p_patient_id, 'manage') then
    raise exception 'only a circle owner can erase a patient' using errcode = '42501';
  end if;

  -- 1. Every audit trigger skips rows of this patient (the patient row itself
  --    included), and the append-only guard opens for exactly these rows.
  perform set_config('familycare.erasing_patient', p_patient_id::text, true);

  -- 2. The patient's history goes, so no health data outlives the erasure.
  delete from core.audit_log a where a.patient_id = p_patient_id;

  -- 3. One minimal security event, with no old/new data and no patient_id: the
  --    id survives only in record_id (D30, security-and-privacy §7).
  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action,
    old_data, new_data, event_kind
  )
  values (
    (select auth.uid()), v_circle_id, null, 'patients', p_patient_id,
    'patient.erased', null, null, 'security'
  );

  -- 4. Cascades remove everything else (medications -> regimens/dose_log,
  --    stock_movements/alerts/escalations, appointments, patient_info,
  --    patient_access_overrides, push devices are per user and stay).
  delete from core.patients p where p.id = p_patient_id;

  perform set_config('familycare.erasing_patient', '', true);
end;
$$;

comment on function core.erase_patient(uuid) is
  'Owner-only GDPR erasure (D30): deletes the patient''s audit rows and then the patient (cascades remove all health data) and writes one minimal event_kind=security row action=patient.erased with null old/new data. The only path to delete a patient from M7 on.';

revoke all on function core.erase_patient(uuid) from public;
revoke all on function core.erase_patient(uuid) from anon;
grant execute on function core.erase_patient(uuid) to authenticated;

-- The RPC is the only delete path: no DELETE privilege for API roles, and the
-- old owner delete policy goes with it.
revoke delete on core.patients from authenticated;
drop policy if exists patients_delete_can_manage on core.patients;

-- --- 4. Cascade attribution outside an erase --------------------------------

-- The parent row is gone by the time the cascaded child's AFTER DELETE audit
-- trigger runs, so the join in the audit function finds nothing. The parent's
-- BEFORE DELETE trigger stashes the ids first.
create or replace function core.stash_medication_for_cascade()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_circle_id uuid;
begin
  select p.circle_id into v_circle_id
  from core.patients p
  where p.id = old.patient_id;

  perform set_config(
    'familycare.medication_patient_' || replace(old.id::text, '-', '_'),
    old.patient_id::text, true
  );

  if v_circle_id is not null then
    perform set_config(
      'familycare.medication_circle_' || replace(old.id::text, '-', '_'),
      v_circle_id::text, true
    );
  end if;

  return old;
end;
$$;

comment on function core.stash_medication_for_cascade() is
  'BEFORE DELETE on meds.medications: stores the patient/circle in transaction-local settings so cascaded regimen, dose-log and stock-movement audit rows keep their attribution.';

create trigger medications_stash_for_cascade
  before delete on meds.medications
  for each row execute function core.stash_medication_for_cascade();

create or replace function core.stash_patient_for_cascade()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform set_config(
    'familycare.deleted_patient_circle_' || replace(old.id::text, '-', '_'),
    old.circle_id::text, true
  );
  return old;
end;
$$;

comment on function core.stash_patient_for_cascade() is
  'BEFORE DELETE on core.patients: stores the circle so cascaded appointments/patient-info/override audit rows keep their circle after the patient row is gone.';

create trigger patients_stash_for_cascade
  before delete on core.patients
  for each row execute function core.stash_patient_for_cascade();

revoke all on function core.stash_medication_for_cascade() from public;
revoke all on function core.stash_patient_for_cascade() from public;

-- The audit trigger functions gain two things: a fallback to the stashed ids
-- (so a cascade keeps patient_id/circle_id after the parent row is gone) and
-- the erase flag check (so no row of the patient being erased is audited,
-- including the patient's own delete).

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
  v_event_kind text;
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

  if tg_table_name = 'invitations' then
    v_old := v_old - 'token_hash';
    v_new := v_new - 'token_hash';
  end if;

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

  -- A patient cascade removes the parent before the child audit trigger runs.
  if v_patient_id is not null and v_circle_id is null then
    v_circle_id := nullif(current_setting(
      'familycare.deleted_patient_circle_' || replace(v_patient_id::text, '-', '_'),
      true
    ), '')::uuid;
  end if;

  if v_patient_id is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         = v_patient_id::text then
    return null;
  end if;

  v_event_kind := case
    when tg_table_name in ('invitations', 'memberships') then 'security'
    else 'change'
  end;

  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action,
    old_data, new_data, event_kind
  )
  values (
    (select auth.uid()), v_circle_id, v_patient_id, tg_table_name, v_record_id,
    lower(tg_op), v_old, v_new, v_event_kind
  );

  return null;
end;
$$;

comment on function core.audit_row_change() is
  'Generic AFTER INSERT/UPDATE/DELETE trigger writing one core.audit_log row with actor (auth.uid()), circle, patient, before/after JSON. token_hash is removed from invitation rows. Invitation and membership rows are event_kind=security; every other table is change history. Falls back to the BEFORE DELETE stashes for cascaded rows, and skips the patient being erased.';

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
  v_key text;
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

    -- Medication gone (cascade): the parent stashed the ids before deleting.
    if v_patient_id is null and v_medication_id is not null then
      v_key := replace(v_medication_id::text, '-', '_');
      v_patient_id := nullif(current_setting(
        'familycare.medication_patient_' || v_key, true), '')::uuid;
      v_circle_id := nullif(current_setting(
        'familycare.medication_circle_' || v_key, true), '')::uuid;
    end if;
  end if;

  if v_patient_id is not null and v_circle_id is null then
    select p.circle_id into v_circle_id from core.patients p where p.id = v_patient_id;
  end if;

  if v_patient_id is not null and v_circle_id is null then
    v_circle_id := nullif(current_setting(
      'familycare.deleted_patient_circle_' || replace(v_patient_id::text, '-', '_'),
      true
    ), '')::uuid;
  end if;

  if v_patient_id is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         = v_patient_id::text then
    return null;
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
  'AFTER INSERT/UPDATE/DELETE on the meds tables: one core.audit_log row with actor, patient, circle and before/after JSON. Falls back to the medication/patient BEFORE DELETE stashes for cascaded rows, and skips the patient being erased.';

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

  if v_patient_id is not null and v_circle_id is null then
    v_circle_id := nullif(current_setting(
      'familycare.deleted_patient_circle_' || replace(v_patient_id::text, '-', '_'),
      true
    ), '')::uuid;
  end if;

  if v_patient_id is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         = v_patient_id::text then
    return null;
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
  'AFTER INSERT/UPDATE/DELETE on the appointments table: one core.audit_log row with actor, patient, circle and before/after JSON. Falls back to the patient BEFORE DELETE stash and skips the patient being erased.';

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

  if v_patient_id is not null and v_circle_id is null then
    v_circle_id := nullif(current_setting(
      'familycare.deleted_patient_circle_' || replace(v_patient_id::text, '-', '_'),
      true
    ), '')::uuid;
  end if;

  if v_patient_id is not null
     and coalesce(current_setting('familycare.erasing_patient', true), '')
         = v_patient_id::text then
    return null;
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
  'AFTER INSERT/UPDATE/DELETE on info.patient_info: one core.audit_log row with actor, patient, circle and before/after JSON. Falls back to the patient BEFORE DELETE stash and skips the patient being erased.';

-- --- 5. Export logging and Activity -----------------------------------------

create or replace function core.log_patient_export(
  p_patient_id uuid,
  p_format text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_circle_id uuid;
begin
  if p_format is null or p_format not in ('json', 'pdf') then
    raise exception 'export format must be json or pdf' using errcode = '22023';
  end if;

  select p.circle_id into v_circle_id
  from core.patients p
  where p.id = p_patient_id;

  if not found then
    raise exception 'patient not found' using errcode = 'P0002';
  end if;

  -- Owners and caregivers; viewers cannot export. Archived patients may be
  -- exported (the data is still theirs to take).
  if not core.can(p_patient_id, 'write') then
    raise exception 'only an owner or caregiver can export patient data'
      using errcode = '42501';
  end if;

  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action,
    old_data, new_data, event_kind
  )
  values (
    (select auth.uid()), v_circle_id, p_patient_id, 'patients', p_patient_id,
    'patient.exported', null, jsonb_build_object('format', p_format), 'security'
  );
end;
$$;

comment on function core.log_patient_export(uuid, text) is
  'Owner/caregiver only. Writes one event_kind=security audit row action=patient.exported with new_data {"format": "json"|"pdf"} and nothing else. Allowed for archived patients.';

revoke all on function core.log_patient_export(uuid, text) from public;
revoke all on function core.log_patient_export(uuid, text) from anon;
grant execute on function core.log_patient_export(uuid, text) to authenticated;

create or replace function core.circle_activity(
  p_circle_id uuid,
  p_before timestamptz default null,
  p_limit int default 50
)
returns table (
  id bigint,
  occurred_at timestamptz,
  event_kind text,
  action text,
  table_name text,
  record_id uuid,
  actor_id uuid,
  actor_name text,
  patient_id uuid,
  patient_name text,
  label text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not core.can_circle(p_circle_id, 'manage') then
    raise exception 'only a circle owner can read its activity'
      using errcode = '42501';
  end if;

  return query
  with visible as (
    select
      a.id,
      a.occurred_at,
      a.event_kind,
      a.action,
      a.table_name,
      a.record_id,
      a.actor_id,
      a.patient_id,
      coalesce(a.new_data, a.old_data) as data
    from core.audit_log a
    where (
        a.circle_id = p_circle_id
        or (
          a.circle_id is null
          and a.table_name = 'auth.sessions'
          and a.actor_id in (
            select m.user_id
            from core.memberships m
            where m.circle_id = p_circle_id
          )
        )
      )
      and (p_before is null or a.occurred_at < p_before)
    order by a.occurred_at desc, a.id desc
    limit least(greatest(coalesce(p_limit, 50), 1), 200)
  )
  select
    v.id,
    v.occurred_at,
    v.event_kind,
    v.action,
    v.table_name,
    v.record_id,
    v.actor_id,
    (select pr.display_name from core.profiles pr where pr.id = v.actor_id),
    v.patient_id,
    (select p.display_name from core.patients p where p.id = v.patient_id),
    case v.table_name
      when 'medications' then v.data ->> 'name'
      when 'appointments' then coalesce(v.data ->> 'title', v.data ->> 'kind')
      when 'patients' then v.data ->> 'display_name'
      when 'memberships' then v.data ->> 'role'
      when 'invitations' then v.data ->> 'role'
      when 'regimens' then (
        select m.name from meds.medications m
        where m.id = nullif(v.data ->> 'medication_id', '')::uuid
      )
      when 'dose_log' then (
        select m.name from meds.medications m
        where m.id = nullif(v.data ->> 'medication_id', '')::uuid
      )
      when 'stock_movements' then (
        select m.name from meds.medications m
        where m.id = nullif(v.data ->> 'medication_id', '')::uuid
      )
      when 'patient_info' then (
        select p.display_name from core.patients p where p.id = v.patient_id
      )
      else null
    end
  from visible v
  order by v.occurred_at desc, v.id desc;
end;
$$;

comment on function core.circle_activity(uuid, timestamptz, int) is
  'Owner-only Activity feed (42501 otherwise): the circle''s audit rows plus its current members'' auth.sign_in / auth.session_ended events, newest first, paged with p_before and clamped to 200. Returns short labels only, never old_data/new_data.';

revoke all on function core.circle_activity(uuid, timestamptz, int) from public;
revoke all on function core.circle_activity(uuid, timestamptz, int) from anon;
grant execute on function core.circle_activity(uuid, timestamptz, int) to authenticated;

-- --- 6. Security advisor fixes ----------------------------------------------

-- pg_net was installed in `public` by M4 on hosted projects (the local image
-- did not have it). Its functions live in the `net` schema either way - the
-- extension's schema option only places the extension objects - so
-- core.post_to_function is unaffected. Recreate it in `extensions` where the
-- extension is available; a bare Postgres catches the error and stays as it
-- was.
do $$
begin
  drop extension if exists pg_net;
  create extension if not exists pg_net with schema extensions;
exception
  when others then
    raise notice 'pg_net could not be moved to extensions (%)', sqlerrm;
end;
$$;

-- Hosted-only helper created by Supabase's default event trigger. It is not
-- ours and has no reason to be callable by API roles. Absent locally.
do $$
begin
  if exists (
    select 1
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.proname = 'rls_auto_enable'
  ) then
    execute 'revoke execute on function public.rls_auto_enable() from anon, authenticated, public';
  end if;
end;
$$;
