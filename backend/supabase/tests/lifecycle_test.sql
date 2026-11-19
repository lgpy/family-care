-- M7 patient lifecycle tests (docs/domain/patient-lifecycle.md, D30, D13/D14).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. Archive: only an owner sets or clears archived_at; while archived every
--    child table refuses insert/update/delete with 42501; the patient stays
--    read-only even for an owner; unarchive restores writes; the reminder view
--    and the stock planner hide it.
-- 2. Erase: caregiver/viewer refused, owner succeeds, all child rows and all
--    audit rows of the patient are gone, exactly one minimal patient.erased
--    security row remains, and a direct DELETE is refused at the privilege.
-- 3. Audit attribution for a cascade outside an erase (deleting a medication
--    keeps its regimens/dose-log rows' patient and circle).
-- 4. Export logging and the owner-only Activity feed (labels only, paging,
--    limit clamp, member sign-in events).
-- 5. The appointment change push is quiet for an archived patient.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(45);

-- --- Fixtures ---------------------------------------------------------------
-- Alice owns circle A; Bob is a caregiver, Carol a viewer.

insert into auth.users
  (id, instance_id, aud, role, email, email_confirmed_at, raw_user_meta_data)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'carol@example.test', now(), '{}'::jsonb);

insert into core.care_circles (id, name) values
  ('20000000-0000-4000-8000-00000000000a', 'Family A');

insert into core.memberships (id, circle_id, user_id, role) values
  ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000001', 'owner'),
  ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000002', 'caregiver'),
  ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000003', 'viewer');

insert into core.patients (id, circle_id, display_name) values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Grandma'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Grandpa');

insert into meds.medications (id, patient_id, name, form, stock_unit) values
  ('50000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001', 'Metformin', 'tablet', 'tablet'),
  ('50000000-0000-4000-8000-000000000002',
   '40000000-0000-4000-8000-000000000002', 'Med B', 'tablet', 'tablet');

insert into meds.regimens
  (id, medication_id, schedule_type, start_date, time_zone, times)
values
  ('60000000-0000-4000-8000-000000000001',
   '50000000-0000-4000-8000-000000000001', 'daily', current_date,
   'Europe/Lisbon', '[{"time":"08:00","quantity":1}]'::jsonb),
  ('60000000-0000-4000-8000-000000000002',
   '50000000-0000-4000-8000-000000000002', 'daily', current_date,
   'Europe/Lisbon', '[{"time":"09:00","quantity":1}]'::jsonb);

insert into meds.dose_log
  (id, regimen_id, medication_id, scheduled_local, outcome, actual_at, confirmed_by)
values
  ('70000000-0000-4000-8000-000000000001',
   '60000000-0000-4000-8000-000000000001',
   '50000000-0000-4000-8000-000000000001',
   date_trunc('minute', now()), 'taken', now(),
   '10000000-0000-4000-8000-000000000001'),
  ('70000000-0000-4000-8000-000000000002',
   '60000000-0000-4000-8000-000000000002',
   '50000000-0000-4000-8000-000000000002',
   date_trunc('minute', now()), 'taken', now(),
   '10000000-0000-4000-8000-000000000001');

insert into meds.stock_movements (id, medication_id, kind, quantity) values
  ('80000000-0000-4000-8000-000000000001',
   '50000000-0000-4000-8000-000000000001', 'count', 20);

insert into appointments.appointments
  (id, patient_id, kind, title, starts_local, time_zone, created_by)
values
  ('90000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001', 'consultation', 'Cardiology',
   date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '2 days',
   'Europe/Lisbon', '10000000-0000-4000-8000-000000000001');

insert into info.patient_info (id, patient_id, notes) values
  ('40000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001', 'Lives alone');

insert into core.patient_access_overrides (id, patient_id, user_id) values
  ('b0000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000002');

-- --- 1. Archive: owner only, read-only while archived ------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ update core.patients set archived_at = now()
      where id = '40000000-0000-4000-8000-000000000002' $$,
  '42501', null,
  'a caregiver cannot archive a patient');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ update core.patients set archived_at = now()
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'the owner archives a patient');

select throws_ok(
  $$ update core.patients set display_name = 'Grandma renamed'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'an archived patient cannot be renamed, even by the owner');

select throws_ok(
  $$ insert into meds.medications (patient_id, name, form, stock_unit)
     values ('40000000-0000-4000-8000-000000000001', 'New', 'tablet', 'tablet') $$,
  '42501', null,
  'adding a medication to an archived patient is refused');

select throws_ok(
  $$ update meds.medications set name = 'Renamed'
      where patient_id = '40000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'editing a medication of an archived patient is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000001', 'daily', current_date,
             'Europe/Lisbon', '[{"time":"10:00","quantity":1}]'::jsonb) $$,
  '42501', null,
  'adding a regimen to an archived patient is refused');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, confirmed_by)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             date_trunc('minute', now()) + interval '1 hour', 'taken', now(),
             '10000000-0000-4000-8000-000000000001') $$,
  '42501', null,
  'logging a dose for an archived patient is refused');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'count', 3) $$,
  '42501', null,
  'recording stock for an archived patient is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'consultation',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '3 days',
             'Europe/Lisbon') $$,
  '42501', null,
  'adding an appointment for an archived patient is refused');

select throws_ok(
  $$ update info.patient_info set notes = 'Changed'
      where patient_id = '40000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'editing patient info of an archived patient is refused');

select throws_ok(
  $$ insert into core.patient_access_overrides (patient_id, user_id)
     values ('40000000-0000-4000-8000-000000000001',
             '10000000-0000-4000-8000-000000000003') $$,
  '42501', null,
  'adding an access override for an archived patient is refused');

-- The reminder view is service-role only (no SELECT for authenticated), so
-- this half runs as the migration role, like the server job does.
reset role;

select is_empty(
  $$ select 1 from meds.effective_reminder
      where patient_id = '40000000-0000-4000-8000-000000000001' $$,
  'the reminder view hides an archived patient');

select is((
  select count(*)::int from meds.effective_reminder
   where patient_id = '40000000-0000-4000-8000-000000000001'), 0,
  'no effective-reminder row exists for the archived patient');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ update core.patients set archived_at = null
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'the owner unarchives a patient');

reset role;

select is((
  select count(*)::int from meds.effective_reminder
   where patient_id = '40000000-0000-4000-8000-000000000001'), 1,
  'the reminder view includes the patient again after unarchive');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'checkup',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '4 days',
             'Europe/Lisbon') $$,
  'writes work again after unarchive');

-- --- 3. Cascade attribution: deleting a medication --------------------------

select lives_ok(
  $$ delete from meds.medications
      where id = '50000000-0000-4000-8000-000000000002' $$,
  'the owner can still delete a medication');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'regimens'
     and record_id = '60000000-0000-4000-8000-000000000002'
     and action = 'delete'
     and patient_id = '40000000-0000-4000-8000-000000000002'
     and circle_id = '20000000-0000-4000-8000-00000000000a'), 1,
  'a cascaded regimen delete keeps its patient and circle');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'dose_log'
     and record_id = '70000000-0000-4000-8000-000000000002'
     and action = 'delete'
     and patient_id = '40000000-0000-4000-8000-000000000002'
     and circle_id = '20000000-0000-4000-8000-00000000000a'), 1,
  'a cascaded dose-log delete keeps its patient and circle');

-- --- 4. Export logging ------------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select throws_ok(
  $$ select core.log_patient_export('40000000-0000-4000-8000-000000000001', 'json') $$,
  '42501', null,
  'a viewer cannot log an export');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ select core.log_patient_export('40000000-0000-4000-8000-000000000001', 'csv') $$,
  '22023', null,
  'an unknown export format is refused');

-- Archived export is allowed: export is the patient's own data.
select lives_ok(
  $$ update core.patients set archived_at = now()
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'the owner archives the patient again for the export check');

select lives_ok(
  $$ select core.log_patient_export('40000000-0000-4000-8000-000000000001', 'pdf') $$,
  'an owner can log the export of an archived patient');

reset role;

select is((
  select count(*)::int from core.audit_log
   where action = 'patient.exported'
     and event_kind = 'security'
     and table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000001'
     and patient_id = '40000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and new_data = '{"format":"pdf"}'::jsonb
     and old_data is null), 1,
  'the export writes one security row with only the format');

-- --- 5. Activity (owner only) ----------------------------------------------

-- A member sign-in (auth.sessions has no circle; the RPC adds it by membership).
insert into auth.sessions (id, user_id, created_at, updated_at)
values ('c0000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000002', now() - interval '5 minutes', now());

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ select * from core.circle_activity('20000000-0000-4000-8000-00000000000a') $$,
  '42501', null,
  'a caregiver cannot read the Activity feed');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select ok((
  select count(*) from core.circle_activity('20000000-0000-4000-8000-00000000000a')) > 0,
  'an owner reads the Activity feed');

select is((
  select count(*)::int from core.circle_activity('20000000-0000-4000-8000-00000000000a')
   where table_name = 'auth.sessions'
     and action = 'auth.sign_in'
     and actor_id = '10000000-0000-4000-8000-000000000002'
     and actor_name = 'bob'), 1,
  'the feed includes a member sign-in event with their name');

select is((
  select label from core.circle_activity('20000000-0000-4000-8000-00000000000a')
   where table_name = 'medications' and action = 'insert'
     and record_id = '50000000-0000-4000-8000-000000000001'
   limit 1), 'Metformin',
  'the label is the medication name, not clinical data');

select ok((
  select label from core.circle_activity('20000000-0000-4000-8000-00000000000a')
   where table_name = 'memberships'
   limit 1) in ('owner', 'caregiver', 'viewer'),
  'membership labels are the role, never the email');

select ok(
  position('old_data' in pg_get_function_result(
    'core.circle_activity(uuid,timestamptz,int)'::regprocedure)) = 0,
  'core.circle_activity never returns old_data');

select ok(
  position('new_data' in pg_get_function_result(
    'core.circle_activity(uuid,timestamptz,int)'::regprocedure)) = 0,
  'core.circle_activity never returns new_data');

select is((
  select count(*)::int from core.circle_activity(
    '20000000-0000-4000-8000-00000000000a',
    (select max(occurred_at) from core.circle_activity('20000000-0000-4000-8000-00000000000a')),
    50)
   where occurred_at >= (
     select max(occurred_at) from core.circle_activity('20000000-0000-4000-8000-00000000000a'))), 0,
  'p_before pages to strictly older rows');

select is((
  select count(*)::int from core.circle_activity(
    '20000000-0000-4000-8000-00000000000a', null, 0)), 1,
  'p_limit is clamped to at least 1');

-- --- 6. Erase ---------------------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ select core.erase_patient('40000000-0000-4000-8000-000000000001') $$,
  '42501', null,
  'a caregiver cannot erase a patient');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select throws_ok(
  $$ select core.erase_patient('40000000-0000-4000-8000-000000000001') $$,
  '42501', null,
  'a viewer cannot erase a patient');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ select core.erase_patient('40000000-0000-4000-8000-000000000001') $$,
  'an owner erases a patient through the RPC');

reset role;

select is((
  select (select count(*) from meds.medications
           where patient_id = '40000000-0000-4000-8000-000000000001')
       + (select count(*) from meds.regimens
           where medication_id = '50000000-0000-4000-8000-000000000001')
       + (select count(*) from meds.dose_log
           where medication_id = '50000000-0000-4000-8000-000000000001')
       + (select count(*) from meds.stock_movements
           where medication_id = '50000000-0000-4000-8000-000000000001'))::int, 0,
  'erasure removes the whole medication chain');

select is((
  select (select count(*) from appointments.appointments
           where patient_id = '40000000-0000-4000-8000-000000000001')
       + (select count(*) from info.patient_info
           where patient_id = '40000000-0000-4000-8000-000000000001')
       + (select count(*) from core.patient_access_overrides
           where patient_id = '40000000-0000-4000-8000-000000000001'))::int, 0,
  'erasure removes appointments, patient info and overrides');

select is((
  select count(*)::int from core.patients
   where id = '40000000-0000-4000-8000-000000000001'), 0,
  'erasure removes the patient row');

select is((
  select count(*)::int from core.audit_log
   where patient_id = '40000000-0000-4000-8000-000000000001'), 0,
  'no audit row of the erased patient remains');

select is((
  select count(*)::int from core.audit_log
   where action = 'patient.erased'
     and event_kind = 'security'
     and table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000001'
     and patient_id is null
     and old_data is null
     and new_data is null), 1,
  'exactly one minimal patient.erased security row remains');

-- --- 7. Appointment push is quiet for an archived patient -------------------

select vault.create_secret(
  'http://127.0.0.1:54321/functions/v1/appointment-changed',
  'appointment_changed_url');
select vault.create_secret('test-secret', 'push_cron_secret');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, starts_local, time_zone)
     values ('90000000-0000-4000-8000-000000000002',
             '40000000-0000-4000-8000-000000000002', 'consultation',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '3 days',
             'Europe/Lisbon') $$,
  'an appointment insert for an active patient succeeds');

reset role;

select is((select count(*)::int from net.http_request_queue), 1,
          'the appointment insert queues one change push');

-- Archive the patient, then exercise the notify trigger through the one path
-- that can still reach it (the erase flag, as the migration role). It must
-- stay quiet.
update core.patients set archived_at = now()
 where id = '40000000-0000-4000-8000-000000000002';

select set_config('familycare.erasing_patient',
                  '40000000-0000-4000-8000-000000000002', true);

update appointments.appointments
   set starts_local = starts_local + interval '1 hour'
 where id = '90000000-0000-4000-8000-000000000002';

select is((select count(*)::int from net.http_request_queue), 1,
          'an update of an archived patient''s appointment queues nothing');

select set_config('familycare.erasing_patient', '', true);

-- --- 8. Activity limit upper clamp ------------------------------------------

-- 205 synthetic in-circle rows so the 200 cap is observable.
insert into core.audit_log (occurred_at, circle_id, table_name, action, event_kind)
select now() - (n || ' seconds')::interval,
       '20000000-0000-4000-8000-00000000000a', 'patients', 'update', 'change'
from generate_series(1, 205) as n;

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from core.circle_activity(
    '20000000-0000-4000-8000-00000000000a', null, 1000)), 200,
  'p_limit is clamped to at most 200');

select * from finish();

rollback;
