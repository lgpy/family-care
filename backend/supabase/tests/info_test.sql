-- M6 patient info tests (build-plan §M6, docs/domain/patient-info.md,
-- security-and-privacy §3 and §5, D13/D14/D29).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. Schema and privileges: RLS enabled *and* forced, nothing granted to
--    anon/PUBLIC, the four DML grants for authenticated, and the `powersync`
--    publication membership.
-- 2. The row key: id = patient_id, one row per patient (a duplicate is refused),
--    both immutable.
-- 3. The jsonb validators: allergies/conditions length and item limits, the SNS
--    number, the phone charset, and the emergency contacts (count, name,
--    relationship, phone).
-- 4. updated_at/updated_by on update (updated_by is the caller).
-- 5. RLS per role: owner and caregiver write, viewer reads but cannot write,
--    another circle and a user without a circle see and write nothing, only an
--    owner deletes.
-- 6. The exact Sync Streams query from backend/powersync/sync-config.yaml, run
--    as each test user with RLS bypassed (like the PowerSync replication role).
-- 7. The audit trigger writes actor, derived patient/circle and before/after.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(55);

-- --- Fixtures ---------------------------------------------------------------
-- Circle A: Alice owner, Bob caregiver, Carol viewer. Circle B: Dave owner.
-- Frank has no circle. Three patients in circle A; the third is filled by Bob.

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
  ('40000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000a',
   'Great-aunt'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient');

-- --- 1. Schema, replication and privileges ----------------------------------
-- (6 assertions)

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'info'
     and c.relkind in ('r', 'p')
     and not (c.relrowsecurity and c.relforcerowsecurity)), 0,
  'every info table has RLS enabled and forced');

select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    cross join lateral aclexplode(coalesce(c.relacl, acldefault('r', c.relowner))) a
   where n.nspname = 'info'
     and c.relkind in ('r', 'p')
     and a.grantee = 0), 0,
  'no info table grants anything to PUBLIC');

select ok(not has_schema_privilege('anon', 'info', 'usage'),
          'anon has no usage on schema info');

select ok(has_table_privilege('authenticated', 'info.patient_info',
                              'select,insert,update,delete'),
          'authenticated may read, insert, update and delete patient info');

select ok(not has_table_privilege('anon', 'info.patient_info',
                                  'select,insert,update,delete'),
          'anon has no privilege on patient info');

select is((
  select string_agg(schemaname || '.' || tablename, ',' order by tablename)
    from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'info'),
  'info.patient_info',
  'the powersync publication covers exactly the patient info table');

-- --- 2. Writes as Alice (owner of circle A) ---------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into info.patient_info
       (id, patient_id, allergies, conditions, sns_number, family_doctor,
        health_centre, health_centre_phone, emergency_contacts, notes)
     values
       ('40000000-0000-4000-8000-000000000001',
        '40000000-0000-4000-8000-000000000001',
        '["Penicillin", "Pollen"]'::jsonb, '["Hypertension", "Diabetes"]'::jsonb,
        '123456789', 'Dra. Costa', 'Centro de Saúde de Alvalade',
        '+351 210 000 000',
        '[{"name":"Ana","relationship":"Daughter","phone":"+351 912 345 678"}]'::jsonb,
        'Prefers morning appointments') $$,
  'an owner can add a patient info row');

select lives_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000002',
             '40000000-0000-4000-8000-000000000002') $$,
  'a patient info row can be created with defaults only');

select is(
  (select allergies = '[]'::jsonb
      and conditions = '[]'::jsonb
      and emergency_contacts = '[]'::jsonb
     from info.patient_info
    where id = '40000000-0000-4000-8000-000000000002'),
  true,
  'allergies, conditions and emergency contacts default to an empty array');

-- --- 3. Sync Streams: the deployed query, run as each user -------------------
-- Migration role (RLS bypassed, like the PowerSync replication role), only the
-- JWT claims change. The query must appear here verbatim or
-- powersync/check-sync-tests.sh fails.

reset role;

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "info"."patient_info" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 2, 'stream patient_info: owner downloads the circle''s info rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "info"."patient_info" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 2, 'stream patient_info: viewer downloads the same rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "info"."patient_info" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream patient_info: another circle downloads nothing');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "info"."patient_info" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream patient_info: a user without a circle downloads nothing');

-- --- 4. anon is denied entirely ---------------------------------------------

set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select throws_ok($$ select count(*) from info.patient_info $$,
                 '42501', null, 'anon cannot read patient info');
select throws_ok($$ insert into info.patient_info (id, patient_id)
                   values ('40000000-0000-4000-8000-000000000003',
                           '40000000-0000-4000-8000-000000000003') $$,
                 '42501', null, 'anon cannot write patient info');

-- --- 5. RLS per role --------------------------------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select count(*)::int from info.patient_info), 2,
          'owner reads the circle''s info rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from info.patient_info), 2,
          'caregiver reads the circle''s info rows');
select lives_ok(
  $$ insert into info.patient_info (id, patient_id, allergies)
     values ('40000000-0000-4000-8000-000000000004',
             '40000000-0000-4000-8000-000000000004', '["Aspirin"]'::jsonb) $$,
  'caregiver can add a patient info row');
select is_empty(
  $$ delete from info.patient_info
      where id = '40000000-0000-4000-8000-000000000002'
      returning id $$,
  'caregiver cannot delete patient info (manage)');
select lives_ok(
  $$ update info.patient_info set notes = 'Edited by Bob'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'caregiver can edit patient info');
select is(
  (select updated_by from info.patient_info
    where id = '40000000-0000-4000-8000-000000000001'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'the update trigger sets updated_by to the caller');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((select count(*)::int from info.patient_info), 3,
          'viewer reads every info row in the circle');

select throws_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000003',
             '40000000-0000-4000-8000-000000000003') $$,
  '42501', null, 'viewer cannot insert patient info');

select is_empty(
  $$ update info.patient_info set notes = 'viewer edit'
      where id = '40000000-0000-4000-8000-000000000002'
      returning id $$,
  'viewer updates zero info rows');

select is_empty(
  $$ delete from info.patient_info
      where id = '40000000-0000-4000-8000-000000000002'
      returning id $$,
  'viewer deletes zero info rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((select count(*)::int from info.patient_info), 0,
          'another circle user reads no info rows');

select throws_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001') $$,
  '42501', null, 'another circle user cannot insert patient info');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((select count(*)::int from info.patient_info), 0,
          'a user without a circle reads no info rows');

select throws_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001') $$,
  '42501', null, 'a user without a circle cannot insert patient info');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ delete from info.patient_info
      where id = '40000000-0000-4000-8000-000000000004' $$,
  'an owner can delete a patient info row');

-- --- 6. The row key and the validators (as the owner) -----------------------

select throws_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000099',
             '40000000-0000-4000-8000-000000000002') $$,
  '23514', null, 'id must equal patient_id');

select throws_ok(
  $$ insert into info.patient_info (id, patient_id)
     values ('40000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001') $$,
  '23505', null, 'a second info row for the same patient is refused');

select throws_ok(
  $$ update info.patient_info
        set patient_id = '40000000-0000-4000-8000-000000000002'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', 'patient_info cannot move to another patient',
  'patient info cannot move to another patient');

select throws_ok(
  $$ update info.patient_info
        set id = '40000000-0000-4000-8000-000000000002'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', 'patient_info.id cannot change',
  'patient info id cannot change');

select throws_ok(
  $$ update info.patient_info
        set allergies = (select jsonb_agg('allergy ' || n)
                           from generate_series(1, 31) n)
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'more than 30 allergies are refused');

select throws_ok(
  $$ update info.patient_info
        set allergies = jsonb_build_array(repeat('x', 101))
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an allergy longer than 100 characters is refused');

select throws_ok(
  $$ update info.patient_info set allergies = '[""]'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an empty allergy entry is refused');

select throws_ok(
  $$ update info.patient_info set allergies = '{"a":"b"}'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'allergies that are not an array are refused');

select throws_ok(
  $$ update info.patient_info
        set conditions = jsonb_build_array(repeat('x', 201))
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'a condition longer than 200 characters is refused');

select throws_ok(
  $$ update info.patient_info set sns_number = '12345678A'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an SNS number with letters is refused');

select throws_ok(
  $$ update info.patient_info set sns_number = '12345678'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an SNS number shorter than 9 digits is refused');

select lives_ok(
  $$ update info.patient_info set sns_number = '987654321'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'a 9-digit SNS number is accepted');

select throws_ok(
  $$ update info.patient_info set health_centre_phone = 'call me'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'a health centre phone with letters is refused');

select lives_ok(
  $$ update info.patient_info set health_centre_phone = '+351 217 000 000'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'a phone with digits, spaces, +, - and brackets is accepted');

select throws_ok(
  $$ update info.patient_info
        set emergency_contacts = (select jsonb_agg(jsonb_build_object(
                                    'name', 'Contact ' || n,
                                    'phone', '912345678'))
                                    from generate_series(1, 6) n)
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'more than 5 emergency contacts are refused');

select throws_ok(
  $$ update info.patient_info
        set emergency_contacts = '[{"phone":"912345678"}]'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an emergency contact without a name is refused');

select throws_ok(
  $$ update info.patient_info
        set emergency_contacts = '[{"name":"Ana"}]'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an emergency contact without a phone is refused');

select throws_ok(
  $$ update info.patient_info
        set emergency_contacts = '[{"name":"Ana","phone":"ana@example.test"}]'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'an emergency contact phone with letters is refused');

select throws_ok(
  $$ update info.patient_info
        set emergency_contacts = (select jsonb_build_array(jsonb_build_object(
                                    'name', 'Ana',
                                    'relationship', repeat('x', 51),
                                    'phone', '912345678')))
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'a relationship longer than 50 characters is refused');

select lives_ok(
  $$ update info.patient_info
        set emergency_contacts = '[{"name":"Ana","relationship":"Daughter","phone":"+351 912 345 678"},{"name":"Rui","phone":"210 000 000"}]'::jsonb
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'valid emergency contacts are accepted');

select throws_ok(
  $$ update info.patient_info set notes = repeat('x', 2001)
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'notes longer than 2000 characters are refused');

select throws_ok(
  $$ update info.patient_info set family_doctor = repeat('x', 201)
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', null, 'a family doctor longer than 200 characters is refused');

-- --- 7. Audit ---------------------------------------------------------------
-- Trigger-written rows, read as the migration role so the owner-only SELECT
-- policy on core.audit_log does not limit the assertions.

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patient_info'
     and record_id = '40000000-0000-4000-8000-000000000001'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and patient_id = '40000000-0000-4000-8000-000000000001'
     and new_data ->> 'sns_number' = '123456789'), 1,
  'a patient info insert writes one audit row with actor, circle, patient and data');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patient_info'
     and record_id = '40000000-0000-4000-8000-000000000001'
     and action = 'update'
     and actor_id = '10000000-0000-4000-8000-000000000002'
     and old_data ->> 'notes' = 'Prefers morning appointments'
     and new_data is not null), 1,
  'a patient info update is audited with the actor and before/after data');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patient_info'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'delete'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'allergies' = '["Aspirin"]'), 1,
  'deleting patient info writes an audit row with the old values');

select * from finish();

rollback;
