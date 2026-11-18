-- M3 medications tests (build-plan §M3, docs/domain/medications.md,
-- security-and-privacy §3 and §5, D6/D9/D13/D14).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- Same three kinds of assertion as core_care_circle_test.sql:
--
-- 1. RLS, the way PostgREST enforces it: `set local role authenticated` plus
--    request.jwt.claims. Viewers read, owner/caregiver write, only owners
--    delete a medication.
-- 2. The exact Sync Streams queries from backend/powersync/sync-config.yaml,
--    with auth.user_id() replaced by (select auth.uid()), run as each test user
--    with RLS bypassed (as the PowerSync replication role does).
-- 3. Constraints, triggers and audit: regimen versioning/overlap, times
--    validation, the append-only dose log and core.audit_log rows.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(135);

-- --- Fixtures ---------------------------------------------------------------
-- Alice owns circle A; Bob is a caregiver, Carol a viewer, Erin a caregiver who
-- is removed near the end. Dave owns the unrelated circle B; Frank has no
-- circle at all.

insert into auth.users
  (id, instance_id, aud, role, email, email_confirmed_at, raw_user_meta_data)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'carol@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'erin@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'dave@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'frank@example.test', now(), '{}'::jsonb);

insert into core.care_circles (id, name) values
  ('20000000-0000-4000-8000-00000000000a', 'Family A'),
  ('20000000-0000-4000-8000-00000000000b', 'Family B');

insert into core.memberships (id, circle_id, user_id, role) values
  ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000001', 'owner'),
  ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000002', 'caregiver'),
  ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000003', 'viewer'),
  ('30000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000004', 'caregiver'),
  ('30000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-00000000000b',
   '10000000-0000-4000-8000-000000000005', 'owner');

insert into core.patients (id, circle_id, display_name) values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Grandma'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Grandpa'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient');

-- --- Schema guarantees ------------------------------------------------------
-- Types have exactly the agreed values (the Kotlin domain maps them by name).

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'meds' and t.typname = 'medication_form'),
  array['tablet', 'capsule', 'drops', 'liquid', 'injection', 'inhaler', 'patch', 'cream', 'other'],
  'meds.medication_form has the agreed values');

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'meds' and t.typname = 'schedule_type'),
  array['daily', 'weekdays', 'every_n_days'],
  'meds.schedule_type has the agreed values');

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'meds' and t.typname = 'skip_reason'),
  array['refused', 'not_needed', 'unavailable', 'other'],
  'meds.skip_reason has the agreed values');

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'meds' and t.typname = 'dose_outcome'),
  array['taken', 'skipped'],
  'meds.dose_outcome has the agreed values');

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'meds'
     and c.relkind in ('r', 'p')
     and not (c.relrowsecurity and c.relforcerowsecurity)), 0,
  'every meds table has RLS enabled and forced');

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
   where n.nspname = 'meds'
     and c.relkind in ('r', 'p')
     and a.grantee = 0), 0,
  'no meds table grants anything to PUBLIC');

select ok(not has_schema_privilege('anon', 'meds', 'usage'),
          'anon has no usage on schema meds');

select is((
  select string_agg(schemaname || '.' || tablename, ',' order by tablename)
    from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'meds'),
  'meds.dose_log,meds.medications,meds.regimens',
  'the powersync publication covers exactly the three meds tables');

select ok(not has_table_privilege('authenticated', 'meds.dose_log', 'update'),
          'authenticated has no UPDATE privilege on the dose log');
select ok(not has_table_privilege('authenticated', 'meds.dose_log', 'delete'),
          'authenticated has no DELETE privilege on the dose log');
select ok(has_table_privilege('authenticated', 'meds.dose_log', 'insert'),
          'authenticated may insert dose log entries');
select ok(not has_table_privilege('authenticated', 'meds.regimens', 'delete'),
          'authenticated has no DELETE privilege on regimens (versioned)');

-- --- Writes as Alice (owner of circle A) ------------------------------------
-- These are also the fixtures for the read/sync tests below.

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into meds.medications
       (id, patient_id, name, active_ingredient, strength, form, stock_unit,
        instructions, requires_prescription, prescription_valid_until)
     values
       ('50000000-0000-4000-8000-000000000001',
        '40000000-0000-4000-8000-000000000001', 'Metformin', 'metformin',
        '500 mg', 'tablet', 'tablet', 'Take with food', true, '2027-01-31') $$,
  'an owner can add a medication');

select lives_ok(
  $$ insert into meds.medications
       (id, patient_id, name, form, stock_unit)
     values
       ('50000000-0000-4000-8000-000000000002',
        '40000000-0000-4000-8000-000000000002', 'Ibuprofen', 'tablet', 'tablet') $$,
  'a second medication for another patient in the circle');

select lives_ok(
  $$ insert into meds.regimens
       (id, medication_id, schedule_type, start_date, time_zone, times)
     values
       ('60000000-0000-4000-8000-000000000001',
        '50000000-0000-4000-8000-000000000001', 'daily', '2026-11-01',
        'Europe/Lisbon',
        '[{"time":"08:00","quantity":1},{"time":"21:00","quantity":0.5}]'::jsonb) $$,
  'a daily regimen with a fractional evening dose is accepted');

select lives_ok(
  $$ insert into meds.regimens
       (id, medication_id, schedule_type, weekdays, start_date, time_zone, times)
     values
       ('60000000-0000-4000-8000-000000000002',
        '50000000-0000-4000-8000-000000000002', 'weekdays', 127, '2026-11-01',
        'Europe/Lisbon', '[{"time":"09:00","quantity":10}]'::jsonb) $$,
  'a weekdays regimen with the full 127 bitmask is accepted');

select lives_ok(
  $$ insert into meds.dose_log
       (id, regimen_id, medication_id, scheduled_local, outcome, actual_at,
        actual_quantity, given_by_user_id)
     values
       ('70000000-0000-4000-8000-000000000001',
        '60000000-0000-4000-8000-000000000001',
        '50000000-0000-4000-8000-000000000001',
        '2026-11-18 08:00', 'taken', now(), 1,
        '10000000-0000-4000-8000-000000000002') $$,
  'a taken dose is appended');

select lives_ok(
  $$ insert into meds.dose_log
       (id, regimen_id, medication_id, scheduled_local, outcome, skip_reason,
        actual_at)
     values
       ('70000000-0000-4000-8000-000000000002',
        '60000000-0000-4000-8000-000000000001',
        '50000000-0000-4000-8000-000000000001',
        '2026-11-18 21:00', 'skipped', 'refused', now()) $$,
  'a skipped dose with a reason is appended');

-- --- Sync Streams: the deployed queries, run as each user -------------------
-- Same form as core_care_circle_test.sql: migration role (RLS bypassed, like
-- the PowerSync replication role), only the JWT claims change. Each query must
-- appear here verbatim or powersync/check-sync-tests.sh fails.

reset role;

-- Alice: owner of circle A.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 2, 'stream medications: owner downloads the circle''s medications');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."regimens" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 2, 'stream regimens: owner downloads their regimens');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."dose_log" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 2, 'stream dose_log: owner downloads the dose log');

-- Carol: viewer in circle A. Streams do not filter by role, so she reads too.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 2, 'stream medications: viewer downloads the same rows');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."regimens" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 2, 'stream regimens: viewer downloads the same rows');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."dose_log" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 2, 'stream dose_log: viewer downloads the same rows');

-- Dave: owner of the unrelated circle B.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream medications: another circle downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."regimens" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream regimens: another circle downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."dose_log" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream dose_log: another circle downloads nothing');

-- Frank: no circle at all.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream medications: a user without a circle downloads nothing');

-- --- anon is denied entirely ------------------------------------------------

set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select throws_ok($$ select count(*) from meds.medications $$,
                 '42501', null, 'anon cannot read medications');
select throws_ok($$ insert into meds.medications (patient_id, name, form, stock_unit)
                   values ('40000000-0000-4000-8000-000000000001', 'x', 'tablet', 'tablet') $$,
                 '42501', null, 'anon cannot write medications');
select throws_ok($$ select count(*) from meds.dose_log $$,
                 '42501', null, 'anon cannot read the dose log');
select throws_ok($$ select meds.patient_of_medication('50000000-0000-4000-8000-000000000001') $$,
                 '42501', null, 'anon cannot execute the access helper');

-- --- isolation: owner (Alice) and caregiver (Bob) ---------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select count(*)::int from meds.medications), 2,
          'owner reads the circle''s medications');
select is((select count(*)::int from meds.regimens), 2,
          'owner reads the circle''s regimens');
select is((select count(*)::int from meds.dose_log), 2,
          'owner reads the circle''s dose log');
select is(meds.patient_of_medication('50000000-0000-4000-8000-000000000001'),
          '40000000-0000-4000-8000-000000000001'::uuid,
          'patient_of_medication returns the owning patient');
select is(meds.patient_of_medication('90000000-0000-4000-8000-000000000000'),
          null::uuid,
          'patient_of_medication returns null for an unknown medication');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from meds.medications), 2,
          'caregiver reads the circle''s medications');
select is((select count(*)::int from meds.dose_log), 2,
          'caregiver reads the circle''s dose log');
select lives_ok(
  $$ insert into meds.medications (id, patient_id, name, form, stock_unit)
     values ('50000000-0000-4000-8000-000000000003',
             '40000000-0000-4000-8000-000000000002', 'Paracetamol', 'tablet', 'tablet') $$,
  'caregiver can add a medication');
select is_empty(
  $$ delete from meds.medications
      where id = '50000000-0000-4000-8000-000000000003'
      returning id $$,
  'caregiver cannot delete a medication (manage)');

-- --- viewer (Carol) reads but cannot write ----------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((select count(*)::int from meds.medications), 3,
          'viewer reads every medication in the circle');
select is((select count(*)::int from meds.regimens), 2,
          'viewer reads every regimen in the circle');
select is((select count(*)::int from meds.dose_log), 2,
          'viewer reads the dose log');

select throws_ok(
  $$ insert into meds.medications (patient_id, name, form, stock_unit)
     values ('40000000-0000-4000-8000-000000000001', 'viewer med', 'tablet', 'tablet') $$,
  '42501', null, 'viewer cannot insert a medication');

select is_empty(
  $$ update meds.medications set name = 'viewer edit'
      where id = '50000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer updates zero medication rows');

select is_empty(
  $$ delete from meds.medications
      where id = '50000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer deletes zero medication rows');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, start_date, end_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000001', 'daily', '2026-01-01',
             '2026-01-31', 'Europe/Lisbon', '[{"time":"10:00","quantity":1}]') $$,
  '42501', null, 'viewer cannot insert a regimen');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-18 08:00', 'taken', now()) $$,
  '42501', null, 'viewer cannot append a dose log entry');

-- --- another circle (Dave) --------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((select count(*)::int from meds.medications), 0,
          'another circle user reads no medications');
select is((select count(*)::int from meds.regimens), 0,
          'another circle user reads no regimens');
select is((select count(*)::int from meds.dose_log), 0,
          'another circle user reads no dose log rows');

select throws_ok(
  $$ insert into meds.medications (patient_id, name, form, stock_unit)
     values ('40000000-0000-4000-8000-000000000001', 'intruder', 'tablet', 'tablet') $$,
  '42501', null, 'another circle user cannot insert a medication');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-19 08:00', 'taken', now()) $$,
  '42501', null, 'another circle user cannot append a dose log entry');

select is_empty(
  $$ update meds.medications set name = 'stolen'
      where id = '50000000-0000-4000-8000-000000000001'
      returning id $$,
  'another circle user updates zero medication rows');

-- --- medication checks (as the owner) ---------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ insert into meds.medications (patient_id, name, form, stock_unit)
     values ('40000000-0000-4000-8000-000000000001',
             repeat('x', 201), 'tablet', 'tablet') $$,
  '23514', null, 'a medication name longer than 200 characters is refused');

select throws_ok(
  $$ insert into meds.medications (patient_id, name, form, stock_unit)
     values ('40000000-0000-4000-8000-000000000001', 'Empty unit', 'tablet', '') $$,
  '23514', null, 'an empty stock_unit is refused');

select throws_ok(
  $$ insert into meds.medications
       (patient_id, name, form, stock_unit, low_stock_threshold_days)
     values ('40000000-0000-4000-8000-000000000001', 'Bad threshold', 'tablet',
             'tablet', 366) $$,
  '23514', null, 'a low-stock threshold above 365 days is refused');

select throws_ok(
  $$ update meds.medications
        set patient_id = '40000000-0000-4000-8000-000000000002'
      where id = '50000000-0000-4000-8000-000000000001' $$,
  '23514', 'a medication cannot move to another patient',
  'a medication cannot move to another patient');

select throws_ok(
  $$ insert into meds.medications
       (patient_id, name, form, stock_unit, reminder_policy, primary_caregiver_id)
     values ('40000000-0000-4000-8000-000000000001', 'Viewer primary', 'tablet',
             'tablet', 'all_caregivers', '10000000-0000-4000-8000-000000000003') $$,
  '23514', 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle',
  'a viewer cannot be the medication''s primary caregiver');

select throws_ok(
  $$ insert into meds.medications
       (patient_id, name, form, stock_unit, reminder_policy, primary_caregiver_id)
     values ('40000000-0000-4000-8000-000000000001', 'Outsider primary', 'tablet',
             'tablet', 'all_caregivers', '10000000-0000-4000-8000-000000000005') $$,
  '23514', 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle',
  'an outsider cannot be the medication''s primary caregiver');

select throws_ok(
  $$ insert into meds.medications
       (patient_id, name, form, stock_unit, primary_caregiver_id)
     values ('40000000-0000-4000-8000-000000000001', 'No policy', 'tablet',
             'tablet', '10000000-0000-4000-8000-000000000002') $$,
  '23514', 'primary_caregiver_id requires a reminder_policy override',
  'a primary caregiver override needs a reminder-policy override');

select lives_ok(
  $$ update meds.medications
        set reminder_policy = 'all_caregivers',
            primary_caregiver_id = '10000000-0000-4000-8000-000000000002'
      where id = '50000000-0000-4000-8000-000000000001' $$,
  'a caregiver of the circle may be the primary caregiver with an override');

select lives_ok(
  $$ update meds.medications
        set reminder_policy = null, primary_caregiver_id = null
      where id = '50000000-0000-4000-8000-000000000001' $$,
  'the override can be cleared again');

-- --- regimens: shape, times and time zone ----------------------------------

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, weekdays, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', 1, '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'weekdays are refused for a daily regimen');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'weekdays', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'a weekdays regimen needs a non-null bitmask');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, weekdays, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'weekdays', 0, '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'a zero weekday bitmask is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, weekdays, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'weekdays', 128, '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'a weekday bitmask above 127 is refused');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, weekdays, interval_days, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'weekdays', 1, 3, '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'a weekdays regimen cannot carry an interval');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, interval_days, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'every_n_days', 1,
             '2030-01-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'an interval of one day is refused');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, interval_days, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'every_n_days', 366,
             '2030-01-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'an interval above 365 days is refused');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, weekdays, interval_days, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'every_n_days', 1, 3,
             '2030-01-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'an every_n_days regimen cannot carry weekdays');

select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, start_date, end_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-10',
             '2030-01-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', 'end_date cannot be before start_date',
  'an end date before the start date is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Mars/Olympus', '[{"time":"08:00","quantity":1}]') $$,
  '23514', 'time_zone is not a known IANA time zone: Mars/Olympus',
  'an unknown time zone is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[]'::jsonb) $$,
  '23514', null, 'an empty times array is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon',
             (select jsonb_agg(jsonb_build_object('time', to_char(t, 'HH24:MI'), 'quantity', 1))
                from generate_series(0, 12) t)) $$,
  '23514', null, 'more than twelve times a day are refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon',
             '[{"time":"21:00","quantity":1},{"time":"08:00","quantity":1}]') $$,
  '23514', null, 'times must be sorted ascending');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon',
             '[{"time":"08:00","quantity":1},{"time":"08:00","quantity":2}]') $$,
  '23514', null, 'duplicate times are refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"8:00","quantity":1}]') $$,
  '23514', null, 'a non-24h time string is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"24:00","quantity":1}]') $$,
  '23514', null, 'a 24:00 time is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":0}]') $$,
  '23514', null, 'a zero quantity is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":100.001}]') $$,
  '23514', null, 'a quantity with more than three decimals is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":101}]') $$,
  '23514', null, 'a quantity above 100 is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00","quantity":"1"}]') $$,
  '23514', null, 'a string quantity is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '[{"time":"08:00"}]') $$,
  '23514', null, 'a time entry without a quantity is refused');

select throws_ok(
  $$ insert into meds.regimens (medication_id, schedule_type, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'daily', '2030-01-01',
             'Europe/Lisbon', '["08:00"]') $$,
  '23514', null, 'an entry that is not an object is refused');

select lives_ok(
  $$ insert into meds.regimens
       (id, medication_id, schedule_type, weekdays, start_date, end_date, time_zone, times)
     values ('60000000-0000-4000-8000-000000000003',
             '50000000-0000-4000-8000-000000000002', 'weekdays', 5,
             '2026-10-01', '2026-10-31', 'Europe/Lisbon',
             '[{"time":"08:00","quantity":0.125},{"time":"20:00","quantity":100}]') $$,
  'a weekday regimen with 3-decimal fractions and quantity 100 is accepted');

-- --- regimens: versioning and overlap ---------------------------------------

select throws_ok(
  $$ update meds.regimens
        set times = '[{"time":"08:00","quantity":2}]'::jsonb
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'a regimen is versioned: only end_date may change',
  'a regimen''s times cannot be edited in place');

select throws_ok(
  $$ update meds.regimens
        set start_date = '2026-10-01'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'a regimen is versioned: only end_date may change',
  'a regimen''s start date cannot be edited in place');

select throws_ok(
  $$ update meds.regimens
        set time_zone = 'Europe/Madrid'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'a regimen is versioned: only end_date may change',
  'a regimen''s time zone cannot be edited in place');

select throws_ok(
  $$ update meds.regimens
        set medication_id = '50000000-0000-4000-8000-000000000002'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'a regimen is versioned: only end_date may change',
  'a regimen cannot be moved to another medication');

select lives_ok(
  $$ update meds.regimens set end_date = '2026-11-15'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  'end_date can be set (the dose change ends the regimen)');

select throws_ok(
  $$ update meds.regimens set end_date = '2026-11-20'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'end_date can only be moved earlier',
  'end_date cannot be moved later');

select throws_ok(
  $$ update meds.regimens set end_date = null
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'end_date cannot be cleared once set',
  'end_date cannot be cleared once set');

select lives_ok(
  $$ update meds.regimens set end_date = '2026-11-10'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  'end_date can be moved earlier');

select throws_ok(
  $$ update meds.regimens set end_date = '2026-10-01'
      where id = '60000000-0000-4000-8000-000000000001' $$,
  '23514', 'end_date cannot be before start_date',
  'end_date cannot be moved before start_date');

-- r1 is now [2026-11-01, 2026-11-10].
select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, start_date, end_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000001', 'daily', '2026-11-05',
             '2026-11-20', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', 'regimens for one medication must not overlap',
  'an overlapping regimen is refused on insert');

select lives_ok(
  $$ insert into meds.regimens
       (id, medication_id, schedule_type, start_date, end_date, time_zone, times)
     values ('60000000-0000-4000-8000-000000000004',
             '50000000-0000-4000-8000-000000000001', 'daily', '2026-11-11',
             '2026-11-20', 'Europe/Lisbon', '[{"time":"08:00","quantity":2}]') $$,
  'a regimen starting the day after the previous end date is accepted');

-- r2 is open-ended from 2026-11-01, so anything later overlaps it.
select throws_ok(
  $$ insert into meds.regimens
       (medication_id, schedule_type, interval_days, start_date, time_zone, times)
     values ('50000000-0000-4000-8000-000000000002', 'every_n_days', 3,
             '2030-01-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]') $$,
  '23514', 'regimens for one medication must not overlap',
  'an open-ended regimen overlaps every later start');

-- --- dose log: outcome, skip reason, quantity -------------------------------

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'skipped', now()) $$,
  '23514', null, 'a skipped dose without a reason is refused');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, skip_reason, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'taken', 'refused', now()) $$,
  '23514', null, 'a taken dose with a skip reason is refused');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, skip_reason, actual_at, actual_quantity)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'skipped', 'refused', now(), 1) $$,
  '23514', null, 'a skipped dose cannot carry an actual quantity');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, actual_quantity)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'taken', now(), 0) $$,
  '23514', null, 'an actual quantity of zero is refused');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, note)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'taken', now(), repeat('x', 501)) $$,
  '23514', null, 'a note longer than 500 characters is refused');

select lives_ok(
  $$ insert into meds.dose_log
       (id, regimen_id, medication_id, scheduled_local, outcome, actual_at, actual_quantity)
     values ('70000000-0000-4000-8000-000000000003',
             '60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'taken', now(), 1.25) $$,
  'an actual quantity with fractions is accepted for a taken dose');

-- --- dose log: confirmation provenance --------------------------------------

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, confirmed_by)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 09:00', 'taken', now(),
             '10000000-0000-4000-8000-000000000002') $$,
  '42501', 'confirmed_by must be the signed-in user',
  'a client cannot confirm a dose as another user');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000002',
             '2026-11-17 09:00', 'taken', now()) $$,
  '23514', 'medication_id must match the regimen''s medication',
  'the medication must match the regimen''s medication');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at,
        given_by_user_id, given_by_other)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 09:00', 'taken', now(),
             '10000000-0000-4000-8000-000000000002', true) $$,
  '23514', null, 'given_by_user_id and given_by_other are mutually exclusive');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, given_by_name)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 09:00', 'taken', now(), 'Neighbour') $$,
  '23514', null, 'given_by_name is refused unless given_by_other is set');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, given_by_user_id)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 09:00', 'taken', now(),
             '10000000-0000-4000-8000-000000000005') $$,
  '23514', 'given_by_user_id must be a member of the patient''s care circle',
  'given_by_user_id must be a circle member');

select lives_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at,
        given_by_user_id)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 09:00', 'taken', now(),
             '10000000-0000-4000-8000-000000000003') $$,
  'a viewer is a circle member and may be recorded as the giver');

select lives_ok(
  $$ insert into meds.dose_log
       (id, regimen_id, medication_id, scheduled_local, outcome, actual_at,
        given_by_other, given_by_name)
     values ('70000000-0000-4000-8000-000000000004',
             '60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 10:00', 'taken', now(), true, 'Neighbour') $$,
  'a dose given by someone else can carry a name');

-- --- dose log: corrections and append-only ----------------------------------

-- r2 is a different regimen: the slot cannot belong to both.
select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, corrects_entry_id)
     values ('60000000-0000-4000-8000-000000000002',
             '50000000-0000-4000-8000-000000000002',
             '2026-11-18 08:00', 'taken', now(),
             '70000000-0000-4000-8000-000000000001') $$,
  '23514', 'a correction must reference an entry for the same regimen and slot',
  'a correction cannot reference another regimen''s entry');

select throws_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at, corrects_entry_id)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-18 09:00', 'taken', now(),
             '70000000-0000-4000-8000-000000000001') $$,
  '23514', 'a correction must reference an entry for the same regimen and slot',
  'a correction cannot reference another slot');

select lives_ok(
  $$ insert into meds.dose_log
       (id, regimen_id, medication_id, scheduled_local, outcome, actual_at,
        actual_quantity, corrects_entry_id)
     values ('70000000-0000-4000-8000-000000000005',
             '60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-18 08:00', 'taken', now(), 0.5,
             '70000000-0000-4000-8000-000000000001') $$,
  'a correction for the same regimen and slot is accepted');

select throws_ok(
  $$ update meds.dose_log set note = 'tampered'
      where id = '70000000-0000-4000-8000-000000000001' $$,
  '42501', null, 'authenticated cannot update the dose log');

select throws_ok(
  $$ delete from meds.dose_log
      where id = '70000000-0000-4000-8000-000000000001' $$,
  '42501', null, 'authenticated cannot delete dose log rows');

-- Even the table owner is stopped from updating (the grant is the second wall).
reset role;

select throws_ok(
  $$ update meds.dose_log set note = 'tampered'
      where id = '70000000-0000-4000-8000-000000000001' $$,
  '42501', 'meds.dose_log is append-only: UPDATE is not allowed',
  'even the table owner cannot update the dose log');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select lives_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-18 12:00', 'taken', now()) $$,
  'a caregiver can append a dose log entry');

-- --- audit ------------------------------------------------------------------
-- Trigger-written rows, read as the migration role so the owner-only SELECT
-- policy does not limit the assertions.

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'medications'
     and record_id = '50000000-0000-4000-8000-000000000001'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and patient_id = '40000000-0000-4000-8000-000000000001'
     and new_data ->> 'name' = 'Metformin'), 1,
  'a medication insert writes one audit row with actor, circle, patient and data');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'regimens'
     and record_id = '60000000-0000-4000-8000-000000000001'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and patient_id = '40000000-0000-4000-8000-000000000001'), 1,
  'a regimen insert writes an audit row with the derived patient and circle');

select is((
  select old_data -> 'times' from core.audit_log
   where table_name = 'regimens'
     and record_id = '60000000-0000-4000-8000-000000000001'
     and action = 'update'
   order by occurred_at desc
   limit 1),
  '[{"time":"08:00","quantity":1},{"time":"21:00","quantity":0.5}]'::jsonb,
  'a regimen end_date update keeps the previous times');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'dose_log'
     and record_id = '70000000-0000-4000-8000-000000000001'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and patient_id = '40000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and new_data ->> 'outcome' = 'taken'), 1,
  'a dose log insert is audited with actor, patient and circle');

-- The owner sees the meds rows through the core audit policy; a caregiver does
-- not read the log.
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from core.audit_log), 0,
          'a caregiver cannot read the audit log');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select cmp_ok((
  select count(*)::int from core.audit_log
   where table_name in ('medications', 'regimens', 'dose_log')), '>', 0,
  'the owner reads the medication audit rows');

-- Deleting a medication is an audited manage action.
select lives_ok(
  $$ delete from meds.medications
      where id = '50000000-0000-4000-8000-000000000003' $$,
  'an owner can delete a medication');

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'medications'
     and record_id = '50000000-0000-4000-8000-000000000003'
     and action = 'delete'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'name' = 'Paracetamol'), 1,
  'deleting a medication writes an audit row with the old values');

-- --- removed caregiver loses access immediately ------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000004') $$,
  'the owner removes Erin');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000004","role":"authenticated"}';

select is((select count(*)::int from meds.medications), 0,
          'a removed caregiver sees no medications');
select is((select count(*)::int from meds.regimens), 0,
          'a removed caregiver sees no regimens');
select is((select count(*)::int from meds.dose_log), 0,
          'a removed caregiver sees no dose log rows');
select is_empty(
  $$ select id from meds.medications
      where id = '50000000-0000-4000-8000-000000000001' $$,
  'a removed caregiver reads no medication row');

reset role;

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream medications: a removed caregiver downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."regimens" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream regimens: a removed caregiver downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."dose_log" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream dose_log: a removed caregiver downloads nothing');

select * from finish();

rollback;
