-- M6 appointments tests (build-plan §M6, docs/domain/appointments.md,
-- security-and-privacy §3 and §5, D13/D14/D29).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. Schema and privileges: the enum values, RLS enabled *and* forced, nothing
--    granted to anon/PUBLIC, the four DML grants for authenticated, a read grant
--    for the service role, and the `powersync` publication membership.
-- 2. Constraints and triggers: text lengths, whole-minute starts_local, the
--    duration range, the IANA time zone, patient_id immutability, created_by
--    spoofing, the companion rule (owner/caregiver of the circle, not a viewer,
--    not an outsider), the follow-up rule (same patient, not itself), and
--    updated_at/updated_by on update.
-- 3. RLS per role: owner and caregiver write, viewer reads but cannot write,
--    another circle and a user without a circle see and write nothing, only an
--    owner deletes.
-- 4. The exact Sync Streams query from backend/powersync/sync-config.yaml, run
--    as each test user with RLS bypassed (like the PowerSync replication role).
-- 5. The audit trigger writes actor, derived patient/circle and before/after.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(54);

-- --- Fixtures ---------------------------------------------------------------
-- Circle A: Alice owner, Bob caregiver, Carol viewer. Circle B: Dave owner.
-- Frank has no circle at all.

insert into auth.users
  (id, instance_id, aud, role, email, email_confirmed_at, raw_user_meta_data)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'carol@example.test', now(), '{}'::jsonb),
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
  ('30000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-00000000000b',
   '10000000-0000-4000-8000-000000000005', 'owner');

insert into core.patients (id, circle_id, display_name) values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Grandma'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Grandpa'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient');

-- --- 1. Schema, replication and privileges ----------------------------------
-- (9 assertions)

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'appointments' and t.typname = 'appointment_kind'),
  array['consultation', 'blood_test', 'exam', 'checkup', 'other'],
  'appointments.appointment_kind has the agreed values');

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'appointments'
     and c.relkind in ('r', 'p')
     and not (c.relrowsecurity and c.relforcerowsecurity)), 0,
  'every appointments table has RLS enabled and forced');

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
   where n.nspname = 'appointments'
     and c.relkind in ('r', 'p')
     and a.grantee = 0), 0,
  'no appointments table grants anything to PUBLIC');

select ok(not has_schema_privilege('anon', 'appointments', 'usage'),
          'anon has no usage on schema appointments');

select ok(has_table_privilege('authenticated', 'appointments.appointments',
                              'select,insert,update,delete'),
          'authenticated may read, insert, update and delete appointments');

select ok(not has_table_privilege('anon', 'appointments.appointments',
                                  'select,insert,update,delete'),
          'anon has no privilege on appointments');

select ok(has_table_privilege('service_role', 'appointments.appointments', 'select'),
          'the service role may read appointments (the change push, task 2)');

select ok(has_schema_privilege('service_role', 'appointments', 'usage'),
          'the service role may use schema appointments (the change push, task 2)');

select is((
  select string_agg(schemaname || '.' || tablename, ',' order by tablename)
    from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'appointments'),
  'appointments.appointments',
  'the powersync publication covers exactly the appointments table');

-- --- 2. Writes as Alice (owner of circle A) ---------------------------------
-- These are also the fixtures for the read/sync tests below.

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, title, starts_local, time_zone, duration_minutes,
        place, doctor, preparation, notes)
     values
       ('80000000-0000-4000-8000-000000000001',
        '40000000-0000-4000-8000-000000000001', 'consultation', 'Cardiologia',
        '2026-12-01 09:30', 'Europe/Lisbon', 30, 'Hospital da Luz',
        'Dra. Silva', 'Fasting from 22:00', 'Bring previous results') $$,
  'an owner can add an appointment');

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, starts_local, time_zone)
     values
       ('80000000-0000-4000-8000-000000000002',
        '40000000-0000-4000-8000-000000000002', 'blood_test',
        '2026-12-02 08:00', 'Europe/Lisbon') $$,
  'a second appointment for another patient in the circle');

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, title, starts_local, time_zone, companion_user_id,
        follow_up_of)
     values
       ('80000000-0000-4000-8000-000000000003',
        '40000000-0000-4000-8000-000000000001', 'checkup', 'Follow-up',
        '2027-01-15 10:00', 'Europe/Lisbon',
        '10000000-0000-4000-8000-000000000002',
        '80000000-0000-4000-8000-000000000001') $$,
  'a follow-up of another appointment of the same patient, with a caregiver companion, is accepted');

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, starts_local, time_zone)
     values
       ('80000000-0000-4000-8000-000000000004',
        '40000000-0000-4000-8000-000000000001', 'other',
        '2026-12-20 15:00', 'Europe/Lisbon') $$,
  'a fourth appointment is added for the delete test');

-- --- 3. Sync Streams: the deployed query, run as each user -------------------
-- Migration role (RLS bypassed, like the PowerSync replication role), only the
-- JWT claims change. The query must appear here verbatim or
-- powersync/check-sync-tests.sh fails.

reset role;

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "appointments"."appointments" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 4, 'stream appointments: owner downloads the circle''s appointments');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "appointments"."appointments" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 4, 'stream appointments: viewer downloads the same rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "appointments"."appointments" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream appointments: another circle downloads nothing');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "appointments"."appointments" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream appointments: a user without a circle downloads nothing');

-- --- 4. anon is denied entirely ---------------------------------------------

set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select throws_ok($$ select count(*) from appointments.appointments $$,
                 '42501', null, 'anon cannot read appointments');
select throws_ok($$ insert into appointments.appointments
                     (patient_id, kind, starts_local, time_zone)
                   values ('40000000-0000-4000-8000-000000000001', 'other',
                           '2026-12-01 09:00', 'Europe/Lisbon') $$,
                 '42501', null, 'anon cannot write appointments');

-- --- 5. RLS per role --------------------------------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select count(*)::int from appointments.appointments), 4,
          'owner reads the circle''s appointments');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from appointments.appointments), 4,
          'caregiver reads the circle''s appointments');
select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, starts_local, time_zone)
     values
       ('80000000-0000-4000-8000-000000000005',
        '40000000-0000-4000-8000-000000000002', 'exam',
        '2026-12-10 11:00', 'Europe/Lisbon') $$,
  'caregiver can add an appointment');
select is_empty(
  $$ delete from appointments.appointments
      where id = '80000000-0000-4000-8000-000000000002'
      returning id $$,
  'caregiver cannot delete an appointment (manage)');
select is(
  (select updated_by from appointments.appointments
    where id = '80000000-0000-4000-8000-000000000001'),
  null::uuid,
  'created_by/updated_by stay as written until an update');
select lives_ok(
  $$ update appointments.appointments
        set place = 'Hospital da Luz, piso 2'
      where id = '80000000-0000-4000-8000-000000000001' $$,
  'caregiver can edit an appointment');
select is(
  (select updated_by from appointments.appointments
    where id = '80000000-0000-4000-8000-000000000001'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'the update trigger sets updated_by to the caller');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((select count(*)::int from appointments.appointments), 5,
          'viewer reads every appointment in the circle');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-05 09:00', 'Europe/Lisbon') $$,
  '42501', null, 'viewer cannot insert an appointment');

select is_empty(
  $$ update appointments.appointments set place = 'viewer edit'
      where id = '80000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer updates zero appointment rows');

select is_empty(
  $$ delete from appointments.appointments
      where id = '80000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer deletes zero appointment rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((select count(*)::int from appointments.appointments), 0,
          'another circle user reads no appointments');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-05 09:00', 'Europe/Lisbon') $$,
  '42501', null, 'another circle user cannot insert an appointment');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((select count(*)::int from appointments.appointments), 0,
          'a user without a circle reads no appointments');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-05 09:00', 'Europe/Lisbon') $$,
  '42501', null, 'a user without a circle cannot insert an appointment');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ delete from appointments.appointments
      where id = '80000000-0000-4000-8000-000000000004' $$,
  'an owner can delete an appointment');

-- --- 6. Constraints and triggers (as the owner) -----------------------------

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, title, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other', repeat('x', 201),
             '2026-12-03 09:00', 'Europe/Lisbon') $$,
  '23514', null, 'a title longer than 200 characters is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, place)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', repeat('x', 201)) $$,
  '23514', null, 'a place longer than 200 characters is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, doctor)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', repeat('x', 201)) $$,
  '23514', null, 'a doctor longer than 200 characters is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, preparation)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', repeat('x', 1001)) $$,
  '23514', null, 'a preparation longer than 1000 characters is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, notes)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', repeat('x', 2001)) $$,
  '23514', null, 'notes longer than 2000 characters are refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00:30', 'Europe/Lisbon') $$,
  '23514', null, 'a starts_local with seconds is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, duration_minutes)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', 0) $$,
  '23514', null, 'a duration of zero minutes is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, duration_minutes)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon', 1441) $$,
  '23514', null, 'a duration above 1440 minutes is refused');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Mars/Olympus') $$,
  '23514', 'time_zone is not a known IANA time zone: Mars/Olympus',
  'an unknown time zone is refused');

select throws_ok(
  $$ update appointments.appointments
        set patient_id = '40000000-0000-4000-8000-000000000002'
      where id = '80000000-0000-4000-8000-000000000001' $$,
  '23514', 'an appointment cannot move to another patient',
  'an appointment cannot move to another patient');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, created_by)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon',
             '10000000-0000-4000-8000-000000000002') $$,
  '42501', 'created_by must be the signed-in user',
  'a client cannot create an appointment as another user');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, companion_user_id)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon',
             '10000000-0000-4000-8000-000000000003') $$,
  '23514', 'companion_user_id must be an owner or caregiver of the patient''s care circle',
  'a viewer cannot be the appointment''s companion');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, companion_user_id)
     values ('40000000-0000-4000-8000-000000000001', 'other',
             '2026-12-03 09:00', 'Europe/Lisbon',
             '10000000-0000-4000-8000-000000000005') $$,
  '23514', 'companion_user_id must be an owner or caregiver of the patient''s care circle',
  'an outsider cannot be the appointment''s companion');

select throws_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, starts_local, time_zone, follow_up_of)
     values ('80000000-0000-4000-8000-000000000010',
             '40000000-0000-4000-8000-000000000001', 'checkup',
             '2027-01-20 10:00', 'Europe/Lisbon',
             '80000000-0000-4000-8000-000000000010') $$,
  '23514', 'an appointment cannot follow up itself',
  'an appointment cannot follow up itself');

select throws_ok(
  $$ insert into appointments.appointments
       (patient_id, kind, starts_local, time_zone, follow_up_of)
     values ('40000000-0000-4000-8000-000000000001', 'checkup',
             '2027-01-20 10:00', 'Europe/Lisbon',
             '80000000-0000-4000-8000-000000000002') $$,
  '23514', 'follow_up_of must be another appointment of the same patient',
  'a follow-up must reference an appointment of the same patient');

select lives_ok(
  $$ update appointments.appointments
        set cancelled_at = now()
      where id = '80000000-0000-4000-8000-000000000002' $$,
  'cancelling is an update, not a delete');

-- --- 7. Audit ---------------------------------------------------------------
-- Trigger-written rows, read as the migration role so the owner-only SELECT
-- policy on core.audit_log does not limit the assertions.

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'appointments'
     and record_id = '80000000-0000-4000-8000-000000000001'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and patient_id = '40000000-0000-4000-8000-000000000001'
     and new_data ->> 'kind' = 'consultation'), 1,
  'an appointment insert writes one audit row with actor, circle, patient and data');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'appointments'
     and record_id = '80000000-0000-4000-8000-000000000001'
     and action = 'update'
     and actor_id = '10000000-0000-4000-8000-000000000002'
     and old_data ->> 'place' = 'Hospital da Luz'
     and new_data ->> 'place' = 'Hospital da Luz, piso 2'), 1,
  'an appointment update is audited with before and after values');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'appointments'
     and record_id = '80000000-0000-4000-8000-000000000004'
     and action = 'delete'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'kind' = 'other'), 1,
  'deleting an appointment writes an audit row with the old values');

select * from finish();

rollback;
