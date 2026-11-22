-- Removing a member linked to archived patients
-- (20261127120000_remove_member_archived_patients.sql):
--   1. a client still cannot clear primary_caregiver_id / linked_user_id on an
--      archived patient with a direct UPDATE (caregiver or owner), nor write
--      the server-only marker table that lets core.remove_member() do it,
--   2. the last-owner guard still holds,
--   3. core.remove_member() succeeds for a member who is primary caregiver
--      and/or linked user of archived and active patients, clears every link
--      to them, leaves the archived patients otherwise unchanged and still
--      archived, audits the cleanup and leaves no marker row behind,
--   4. after the removal the direct UPDATE is still refused.
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- Everything happens in one transaction and is rolled back, so the tests need
-- no cleanup and never touch dev/prod data.

begin;

create extension if not exists pgtap with schema extensions;

select plan(25);

-- --- Fixtures ---------------------------------------------------------------
-- Alice owns circle A; Bob is a caregiver and Erin a caregiver who is removed.
-- Patients 1, 2, 4 and 5 are archived, 3 is active:
--   1: Erin primary, no link           (archived)
--   2: Erin linked, Alice primary      (archived)
--   3: Erin linked and primary         (active)
--   4: Erin linked and primary         (archived)
--   5: Bob primary, nothing of Erin's  (archived)

insert into auth.users
  (id, instance_id, aud, role, email, email_confirmed_at, raw_user_meta_data)
values
  ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'erin@example.test', now(), '{}'::jsonb);

insert into core.care_circles (id, name) values
  ('20000000-0000-4000-8000-00000000000a', 'Family A');

insert into core.memberships (id, circle_id, user_id, role) values
  ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000001', 'owner'),
  ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000002', 'caregiver'),
  ('30000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000a',
   '10000000-0000-4000-8000-000000000004', 'caregiver');

-- Inserted already archived: the archive trigger is BEFORE UPDATE only.
insert into core.patients
  (id, circle_id, display_name, linked_user_id, primary_caregiver_id,
   agreement_note, archived_at)
values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Archived, Erin primary', null,
   '10000000-0000-4000-8000-000000000004', 'note one',
   '2026-11-01 10:00:00+00'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Archived, Erin linked',
   '10000000-0000-4000-8000-000000000004',
   '10000000-0000-4000-8000-000000000001', 'note two',
   '2026-11-01 10:00:00+00'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000a',
   'Active, Erin both',
   '10000000-0000-4000-8000-000000000004',
   '10000000-0000-4000-8000-000000000004', null, null),
  ('40000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000a',
   'Archived, Erin both',
   '10000000-0000-4000-8000-000000000004',
   '10000000-0000-4000-8000-000000000004', null,
   '2026-11-02 10:00:00+00'),
  ('40000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-00000000000a',
   'Archived, Bob primary', null,
   '10000000-0000-4000-8000-000000000002', null,
   '2026-11-03 10:00:00+00');

-- Each patient as it was, minus the columns the cleanup may change.
create temp table archived_before as
select p.id,
       to_jsonb(p) - 'updated_at' - 'primary_caregiver_id' - 'linked_user_id' as data
from core.patients p;

-- --- 1. Clients still cannot clear the links directly -----------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ update core.patients set primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'a caregiver cannot clear the primary caregiver of an archived patient');

select throws_ok(
  $$ update core.patients set linked_user_id = null
      where id = '40000000-0000-4000-8000-000000000002' $$,
  '42501', null,
  'a caregiver cannot clear the linked user of an archived patient');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ update core.patients set primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'an owner cannot clear the primary caregiver of an archived patient');

select throws_ok(
  $$ update core.patients set linked_user_id = null, primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000004' $$,
  '42501', null,
  'an owner cannot clear both links of an archived patient');

select throws_ok(
  $$ insert into core.member_removal_unlinks (circle_id, user_id)
     values ('20000000-0000-4000-8000-00000000000a',
             '10000000-0000-4000-8000-000000000004') $$,
  '42501', null,
  'a client cannot write the remove_member marker table');

-- --- 2. Last-owner guard ----------------------------------------------------

select throws_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000001') $$,
  '23514', null,
  'the last owner still cannot be removed (and is primary of an archived patient)');

-- --- 3. Removing Erin -------------------------------------------------------

select lives_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000004') $$,
  'the owner removes a member linked to archived patients');

reset role;

select is(
  (select count(*)::int from core.memberships
    where id = '30000000-0000-4000-8000-000000000004'),
  0,
  'the membership is gone');

select is(
  (select count(*)::int from core.patients
    where circle_id = '20000000-0000-4000-8000-00000000000a'
      and ('10000000-0000-4000-8000-000000000004' in (primary_caregiver_id, linked_user_id))),
  0,
  'no patient of the circle, archived or not, still points at the removed member');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000001'),
  null::uuid,
  'archived patient: the primary caregiver they held is cleared');

select is(
  (select (linked_user_id, primary_caregiver_id)::text from core.patients
    where id = '40000000-0000-4000-8000-000000000002'),
  ((null::uuid, '10000000-0000-4000-8000-000000000001'::uuid))::text,
  'archived patient: the link to them is cleared and the other primary is kept');

select is(
  (select (linked_user_id, primary_caregiver_id)::text from core.patients
    where id = '40000000-0000-4000-8000-000000000003'),
  ((null::uuid, null::uuid))::text,
  'active patient: both links are cleared');

select is(
  (select (linked_user_id, primary_caregiver_id)::text from core.patients
    where id = '40000000-0000-4000-8000-000000000004'),
  ((null::uuid, null::uuid))::text,
  'archived patient: both links are cleared');

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000005'),
  '10000000-0000-4000-8000-000000000002'::uuid,
  'an archived patient without a link to them is not changed');

select is(
  (select array_agg(id::text order by id) from core.patients
    where archived_at is not null),
  array['40000000-0000-4000-8000-000000000001',
        '40000000-0000-4000-8000-000000000002',
        '40000000-0000-4000-8000-000000000004',
        '40000000-0000-4000-8000-000000000005'],
  'the archived patients are still archived and the active one is not');

select is(
  (select count(*)::int
     from core.patients p
     join archived_before b on b.id = p.id
    where (to_jsonb(p) - 'updated_at' - 'primary_caregiver_id' - 'linked_user_id')
          is distinct from b.data),
  0,
  'apart from the two links, every patient is unchanged (archived_at, name, note, policy, ...)');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'update'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'primary_caregiver_id' = '10000000-0000-4000-8000-000000000004'
     and old_data ->> 'linked_user_id' = '10000000-0000-4000-8000-000000000004'
     and new_data ->> 'primary_caregiver_id' is null
     and new_data ->> 'linked_user_id' is null
     and new_data ->> 'archived_at' is not null
     and event_kind = 'change'), 1,
  'the cleanup of an archived patient is audited like any patient change');

select is(
  (select count(*)::int from core.member_removal_unlinks),
  0,
  'remove_member leaves no marker row behind');

select ok(
  not has_table_privilege('authenticated', 'core.member_removal_unlinks',
                          'select,insert,update,delete,truncate'),
  'authenticated has no privilege on the marker table');

select ok(
  not has_table_privilege('service_role', 'core.member_removal_unlinks',
                          'select,insert,update,delete,truncate'),
  'service_role has no privilege on the marker table');

-- --- 4. The direct UPDATE is still refused afterwards -----------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ update core.patients set primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000002' $$,
  '42501', null,
  'after a removal the owner still cannot clear a link on an archived patient');

select throws_ok(
  $$ update core.patients set primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000005' $$,
  '42501', null,
  'after a removal the owner cannot clear another member''s link on an archived patient');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ update core.patients set primary_caregiver_id = null
      where id = '40000000-0000-4000-8000-000000000002' $$,
  '42501', null,
  'after a removal a caregiver still cannot clear a link on an archived patient');

select throws_ok(
  $$ update core.patients set display_name = 'Renamed'
      where id = '40000000-0000-4000-8000-000000000004' $$,
  '42501', null,
  'an archived patient whose links were cleared is still read-only');

reset role;

select is(
  (select primary_caregiver_id from core.patients
    where id = '40000000-0000-4000-8000-000000000002'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'the refused updates changed nothing');

select * from finish();

rollback;
