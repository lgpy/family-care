-- M2 follow-up tests (20261118140000_core_m2_fixes.sql):
--   1. removing a member clears that person's patient links (and only theirs),
--   2. the D11 self-managed default for primary caregiver,
--   3. security events for sign-in / session end and the invitation/membership
--      classification,
--   4. audit retention (6 years change history, 1 year security events),
--   5. the isolation cases the M2 suite did not cover:
--      circle rename by non-owners, cross-circle patient insert,
--      patient_access_overrides being owner-only, invitation role/email rules.
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- Everything happens in one transaction and is rolled back, so the tests need
-- no cleanup and never touch dev/prod data. The fixtures are independent from
-- core_care_circle_test.sql on purpose: these tests mutate patient links and
-- the audit log, which the M2 suite does not.

begin;

create extension if not exists pgtap with schema extensions;

select plan(45);

-- --- Fixtures ---------------------------------------------------------------
-- Alice owns circle A; Bob is a caregiver, Carol a viewer and Erin a caregiver
-- who is removed later. Dave owns the unrelated circle B. Gina has an
-- unconfirmed email and receives the invitation (Frank is not needed here).

insert into auth.users
  (id, instance_id, aud, role, email, email_confirmed_at, raw_user_meta_data)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now(),
   '{"full_name":"Alice Example"}'::jsonb),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'carol@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'erin@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'dave@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000007', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'gina@example.test', null, '{}'::jsonb);

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

insert into core.patients
  (id, circle_id, display_name, linked_user_id, primary_caregiver_id)
values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Erin linked and primary',
   '10000000-0000-4000-8000-000000000004',
   '10000000-0000-4000-8000-000000000004'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Erin primary only', null,
   '10000000-0000-4000-8000-000000000004'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000a',
   'Alice linked and primary',
   '10000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000001'),
  ('40000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000b',
   'Dave primary only', null,
   '10000000-0000-4000-8000-000000000005');

create temp table m2_invitation_codes (
  label text primary key,
  code text not null
);
grant all on m2_invitation_codes to authenticated;

-- --- D11: a linked caregiver becomes the primary on insert -------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into core.patients (id, circle_id, display_name, linked_user_id)
     values ('40000000-0000-4000-8000-000000000005',
             '20000000-0000-4000-8000-00000000000a', 'Self-managed Bob',
             '10000000-0000-4000-8000-000000000002') $$,
  'D11: a patient can be created with a linked caregiver and no primary');

select lives_ok(
  $$ insert into core.patients (id, circle_id, display_name, linked_user_id)
     values ('40000000-0000-4000-8000-000000000006',
             '20000000-0000-4000-8000-00000000000a', 'Viewer linked',
             '10000000-0000-4000-8000-000000000003') $$,
  'D11: a patient can be linked to a viewer (primary stays empty)');

select lives_ok(
  $$ insert into core.patients
       (id, circle_id, display_name, linked_user_id, reminder_policy)
     values ('40000000-0000-4000-8000-000000000007',
             '20000000-0000-4000-8000-00000000000a', 'All caregivers',
             '10000000-0000-4000-8000-000000000002', 'all_caregivers') $$,
  'a linked patient may use another reminder policy');

select lives_ok(
  $$ insert into core.patients
       (id, circle_id, display_name, linked_user_id, reminder_policy)
     values ('40000000-0000-4000-8000-000000000009',
             '20000000-0000-4000-8000-00000000000a', 'Only primary',
             '10000000-0000-4000-8000-000000000002', 'primary_only') $$,
  'a linked patient may use the primary_only policy');

select lives_ok(
  $$ insert into core.patients
       (id, circle_id, display_name, linked_user_id, primary_caregiver_id)
     values ('40000000-0000-4000-8000-000000000008',
             '20000000-0000-4000-8000-00000000000a', 'Explicit primary',
             '10000000-0000-4000-8000-000000000002',
             '10000000-0000-4000-8000-000000000001') $$,
  'a linked patient may have another primary chosen explicitly');

reset role;

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000005'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'D11: the linked caregiver becomes the primary caregiver');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000006'),
  null::uuid,
  'D11: a linked viewer is not made the primary caregiver');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000007'),
  null::uuid,
  'D11: an all_caregivers patient is not made their own primary');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000009'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'D11: the self-managed default also applies to primary_only');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000008'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'D11: an explicitly chosen primary is not overwritten');

-- --- Removing a member clears their links ----------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000004') $$,
  'the owner removes Erin');

reset role;

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000001'),
  null::uuid,
  'removing a member clears the primary caregiver they held');

select is(
  (select linked_user_id from core.patients
    where id = '40000000-0000-4000-8000-000000000001'),
  null::uuid,
  'removing a member clears the patient link to them');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000002'),
  null::uuid,
  'a patient who only had them as primary is cleared too');

select is(
  (select (linked_user_id, primary_caregiver_id)::text from core.patients
    where id = '40000000-0000-4000-8000-000000000003'),
  (('10000000-0000-4000-8000-000000000001'::uuid,
    '10000000-0000-4000-8000-000000000001'::uuid))::text,
  'patients of the same circle are not changed unless they point at the member');

select is(
  (select (linked_user_id, primary_caregiver_id)::text from core.patients
    where id = '40000000-0000-4000-8000-000000000004'),
  ((null::uuid, '10000000-0000-4000-8000-000000000005'::uuid))::text,
  'patients in another circle are not changed at all');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000001'
     and action = 'update'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'primary_caregiver_id' = '10000000-0000-4000-8000-000000000004'
     and old_data ->> 'linked_user_id' = '10000000-0000-4000-8000-000000000004'
     and new_data ->> 'primary_caregiver_id' is null
     and new_data ->> 'linked_user_id' is null
     and event_kind = 'change'), 1,
  'the cleanup is audited as a normal patient change with before/after');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'update'), 0,
  'no audit row is written for another circle''s patient');

-- --- Security events: sign-in and session ended -----------------------------

insert into auth.sessions (id, user_id, created_at, updated_at)
values ('50000000-0000-4000-8000-000000000001',
        '10000000-0000-4000-8000-000000000001',
        now() - interval '5 minutes', now());

select is((
  select count(*)::int from core.audit_log
   where table_name = 'auth.sessions'
     and action = 'auth.sign_in'
     and event_kind = 'security'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id is null
     and patient_id is null
     and record_id is null
     and old_data is null
     and new_data is null), 1,
  'a sign-in writes one minimal security event (user, action, time; no session id)');

delete from auth.sessions where id = '50000000-0000-4000-8000-000000000001';

select is((
  select count(*)::int from core.audit_log
   where table_name = 'auth.sessions'
     and action = 'auth.session_ended'
     and event_kind = 'security'
     and actor_id = '10000000-0000-4000-8000-000000000001'), 1,
  'deleting a session (sign-out, revocation) writes a session-ended event');

select is(
  (select event_kind from core.audit_log
    where table_name = 'memberships'
      and record_id = '30000000-0000-4000-8000-000000000004'
      and action = 'delete'),
  'security',
  'membership changes are classified as security events (1-year retention)');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from core.audit_log where table_name = 'auth.sessions'), 0,
  'sign-in events have no circle, so the owner-only policy hides them (Activity is M7)');

reset role;

-- --- Retention --------------------------------------------------------------
-- Rows are inserted directly as the migration role: these stand in for "the
-- clock passed the retention limit".

insert into core.audit_log (occurred_at, table_name, action, event_kind) values
  (now() - interval '7 years', 'retention_probe_change_old', 'insert', 'change'),
  (now() - interval '1 year', 'retention_probe_change_recent', 'insert', 'change'),
  (now() - interval '2 years', 'retention_probe_security_old', 'auth.sign_in', 'security'),
  (now() - interval '1 month', 'retention_probe_security_recent', 'auth.sign_in', 'security');

select is(core.purge_audit_log(), 2,
          'purge removes change history older than 6 years and security events older than 1 year');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'retention_probe_change_old'), 0,
  'change history older than 6 years is purged');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'retention_probe_security_old'), 0,
  'security events older than 1 year are purged');

select is((
  select count(*)::int from core.audit_log
   where table_name like 'retention_probe_%'), 2,
  'recent change history and recent security events are kept');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ select core.purge_audit_log() $$,
  '42501', null,
  'authenticated cannot run the retention purge');

select ok(
  not has_table_privilege('authenticated', 'core.audit_log', 'delete'),
  'authenticated still has no delete privilege on the audit log');

select throws_ok(
  $$ delete from core.audit_log where table_name = 'retention_probe_change_recent' $$,
  '42501', null,
  'authenticated cannot delete audit rows directly, purge function or not');

-- --- patient_access_overrides: owner only -----------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into core.patient_access_overrides (patient_id, user_id, access_level)
     values ('40000000-0000-4000-8000-000000000003',
             '10000000-0000-4000-8000-000000000002', 'read') $$,
  'the circle owner can create a patient access override');

select is((select count(*)::int from core.patient_access_overrides), 1,
          'the circle owner can read the override');

select results_eq(
  $$ update core.patient_access_overrides set access_level = 'write'
      where patient_id = '40000000-0000-4000-8000-000000000003'
        and user_id = '10000000-0000-4000-8000-000000000002'
      returning access_level $$,
  $$ values ('write') $$,
  'the circle owner can update the override');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from core.patient_access_overrides), 0,
          'a caregiver cannot read the override');

select throws_ok(
  $$ insert into core.patient_access_overrides (patient_id, user_id, access_level)
     values ('40000000-0000-4000-8000-000000000003',
             '10000000-0000-4000-8000-000000000003', 'read') $$,
  '42501', null,
  'a caregiver cannot create an override');

select is_empty(
  $$ update core.patient_access_overrides set access_level = 'read'
      returning id $$,
  'a caregiver updates zero override rows');

select is_empty(
  $$ delete from core.patient_access_overrides returning id $$,
  'a caregiver deletes zero override rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ delete from core.patient_access_overrides
      where patient_id = '40000000-0000-4000-8000-000000000003' $$,
  'the circle owner can delete the override');

-- --- Circle and patient isolation -------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is_empty(
  $$ update core.care_circles set name = 'hijacked'
      where id = '20000000-0000-4000-8000-00000000000a' returning id $$,
  'a caregiver cannot rename the circle');

select throws_ok(
  $$ insert into core.patients (circle_id, display_name)
     values ('20000000-0000-4000-8000-00000000000b', 'Bob in circle B') $$,
  '42501', null,
  'a caregiver cannot insert a patient into another circle');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is_empty(
  $$ update core.care_circles set name = 'hijacked'
      where id = '20000000-0000-4000-8000-00000000000a' returning id $$,
  'a viewer cannot rename the circle');

-- --- Invitations: role and email rules --------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ select core.create_invitation(
       '20000000-0000-4000-8000-00000000000a', 'x@example.test', 'owner') $$,
  '22023', 'invitation role must be caregiver or viewer',
  'create_invitation refuses the owner role');

select lives_ok(
  $$ insert into m2_invitation_codes (label, code)
     values ('gina', core.create_invitation(
       '20000000-0000-4000-8000-00000000000a', 'gina@example.test', 'viewer')) $$,
  'the owner can invite a viewer');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'invitations'
     and action = 'insert'
     and event_kind = 'security'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and circle_id = '20000000-0000-4000-8000-00000000000a'), 1,
  'creating an invitation is audited as a security event');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000007","role":"authenticated"}';

select throws_ok(
  $$ select core.accept_invitation(
       (select code from m2_invitation_codes where label = 'gina')) $$,
  '42501', 'confirm your email address before accepting an invitation',
  'an unconfirmed email cannot accept an invitation');

-- --- pg_cron schedules the retention job ------------------------------------

reset role;

select is((
  select count(*)::int from cron.job where jobname = 'core_purge_audit_log'), 1,
  'pg_cron has one daily job for core.purge_audit_log()');

select * from finish();

rollback;
