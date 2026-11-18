-- M2 care-circle tests (build-plan §M2, security-and-privacy §3 and §5, D13/D14).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- Three kinds of assertion live here:
--
-- 1. RLS (writes and reads as the `authenticated` role, the way PostgREST does
--    it): set local role authenticated + request.jwt.claims.
-- 2. Sync rules: the *exact* queries from backend/powersync/sync-config.yaml,
--    with `auth.user_id()` replaced by `(select auth.uid())`, executed as each
--    test user. They run as the migration role (which bypasses RLS, as the
--    PowerSync replication role does) so the row set is the stream's own
--    answer, not RLS's. backend/powersync/check-sync-tests.sh fails CI when a
--    query in the YAML is not found here, so the two halves cannot drift.
-- 3. Audit and append-only guarantees, partly as the table owner.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(135);

-- --- Fixtures ---------------------------------------------------------------
-- Eight users. Alice owns circle A; Bob is a caregiver, Carol a viewer and Erin
-- a caregiver who is removed later. Dave owns the unrelated circle B. Frank,
-- Gina and Henry only receive invitations.

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
  ('10000000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'frank@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000007', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'gina@example.test', now(), '{}'::jsonb),
  ('10000000-0000-4000-8000-000000000008', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'henry@example.test', now(), '{}'::jsonb);

-- The signup trigger must have created one profile per user, from
-- raw_user_meta_data full_name or, absent that, the email local part.
select is(
  (select display_name from core.profiles where id = '10000000-0000-4000-8000-000000000001'),
  'Alice Example',
  'signup trigger creates a profile from raw_user_meta_data.full_name'
);

select is(
  (select display_name from core.profiles where id = '10000000-0000-4000-8000-000000000002'),
  'bob',
  'signup trigger falls back to the email local part'
);

select is(
  (select count(*)::int from core.profiles),
  8,
  'one profile per auth user, no duplicates'
);

-- Circles and memberships (RLS bypassed here: this is fixture setup).
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
   'Grandma', '10000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000001'),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Grandpa', null, '10000000-0000-4000-8000-000000000002'),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient', null, '10000000-0000-4000-8000-000000000005');

-- Invitation plaintext codes are returned once, so the tests keep them here.
create temp table test_invitation_codes (
  label text primary key,
  code text not null,
  invitation_id uuid
);
grant all on test_invitation_codes to authenticated;

-- --- Sync Streams: the deployed queries, run as each user -------------------
-- These blocks run with the migration role (RLS bypassed, like the PowerSync
-- replication role) and only change the JWT claims. Any edit to a query here or
-- in sync-config.yaml must be made in both places or
-- powersync/check-sync-tests.sh fails.

-- Alice: owner of circle A.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."care_circles" WHERE id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 1, 'stream care_circles: owner downloads their circle');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 4, 'stream memberships: owner downloads every member of the circle');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."profiles" WHERE id IN (SELECT user_id FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 4, 'stream profiles: owner downloads co-members'' profiles');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 2, 'stream patients: owner downloads every patient in the circle');

-- Bob: caregiver in circle A. The stream is the same for every role.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."care_circles" WHERE id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 1, 'stream care_circles: caregiver downloads the circle');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 2, 'stream patients: caregiver downloads all patients in the circle');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 4, 'stream memberships: caregiver downloads all members');

-- Carol: viewer in circle A.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 2, 'stream patients: viewer downloads all patients too');

-- Erin: caregiver in circle A (removed later).
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000004","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 2, 'stream patients: the caregiver who will be removed sees the data first');

-- Dave: owner of the unrelated circle B.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."care_circles" WHERE id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 1, 'stream care_circles: another circle owner downloads only their own circle');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 1, 'stream patients: another circle owner sees none of circle A');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 1, 'stream memberships: another circle owner sees only their own membership');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."profiles" WHERE id IN (SELECT user_id FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 1, 'stream profiles: another circle owner sees only their own profile');

-- Frank: no circle at all.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "core"."care_circles" WHERE id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream care_circles: a user with no circle downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream patients: a user with no circle downloads no patients');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream memberships: a user with no circle downloads no memberships');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."profiles" WHERE id IN (SELECT user_id FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream profiles: a user with no circle downloads no profiles');

-- --- anon is denied entirely ------------------------------------------------

set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select throws_ok(
  $$ select count(*) from core.patients $$,
  '42501', null,
  'anon cannot read core.patients');

select throws_ok(
  $$ insert into core.patients (circle_id, display_name)
     values ('20000000-0000-4000-8000-00000000000a', 'anon patient') $$,
  '42501', null,
  'anon cannot write core.patients');

select throws_ok(
  $$ select core.can('40000000-0000-4000-8000-000000000001', 'read') $$,
  '42501', null,
  'anon cannot execute the access function');

select throws_ok(
  $$ select core.create_circle('anon circle') $$,
  '42501', null,
  'anon cannot execute core.create_circle');

-- --- owner (Alice) ----------------------------------------------------------

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select count(*)::int from core.care_circles), 1,
          'owner sees their circle');
select is((select count(*)::int from core.patients), 2,
          'owner sees both patients in the circle');
select is((select count(*)::int from core.memberships), 4,
          'owner sees all four memberships');
select is((select count(*)::int from core.profiles), 4,
          'owner sees the four co-members'' profiles, not outsiders');

select is(core.circle_role('20000000-0000-4000-8000-00000000000a')::text,
          'owner', 'circle_role returns the caller''s role');
select is(core.can('40000000-0000-4000-8000-000000000001', 'read'),
          true, 'owner can read a patient (core.can)');
select is(core.can('40000000-0000-4000-8000-000000000001', 'write'),
          true, 'owner can write a patient (core.can)');
select is(core.can('40000000-0000-4000-8000-000000000001', 'manage'),
          true, 'owner can manage a patient (core.can)');
select is(core.can('40000000-0000-4000-8000-000000000001', 'not-an-action'),
          false, 'unknown actions are denied (core.can)');
select is(core.can('40000000-0000-4000-8000-000000000003', 'read'),
          false, 'a patient in another circle is not readable (core.can)');

select lives_ok(
  $$ update core.care_circles set name = 'Family A renamed'
      where id = '20000000-0000-4000-8000-00000000000a' $$,
  'owner can rename the circle');

select throws_ok(
  $$ insert into core.care_circles (name) values ('direct insert') $$,
  '42501', null,
  'circles cannot be inserted directly (create_circle only)');

select throws_ok(
  $$ insert into core.memberships (circle_id, user_id, role)
     values ('20000000-0000-4000-8000-00000000000a',
             '10000000-0000-4000-8000-000000000006', 'caregiver') $$,
  '42501', null,
  'memberships cannot be inserted directly (RPC only)');

select throws_ok(
  $$ insert into core.invitations (circle_id, email, role, token_hash, created_by)
     values ('20000000-0000-4000-8000-00000000000a', 'x@example.test', 'viewer',
             extensions.digest('x', 'sha256'),
             '10000000-0000-4000-8000-000000000001') $$,
  '42501', null,
  'invitations cannot be inserted directly (create_invitation only)');

-- --- caregiver (Bob) --------------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from core.patients), 2,
          'caregiver sees all patients in the circle (security §3)');
select is(core.can('40000000-0000-4000-8000-000000000001', 'read'),
          true, 'caregiver can read a patient');
select is(core.can('40000000-0000-4000-8000-000000000001', 'write'),
          true, 'caregiver can write a patient');
select is(core.can('40000000-0000-4000-8000-000000000001', 'manage'),
          false, 'caregiver cannot manage patients');

select lives_ok(
  $$ insert into core.patients (id, circle_id, display_name)
     values ('40000000-0000-4000-8000-000000000004',
             '20000000-0000-4000-8000-00000000000a', 'Bob added') $$,
  'caregiver can add a patient');

select results_eq(
  $$ update core.patients set display_name = 'Bob renamed'
      where id = '40000000-0000-4000-8000-000000000004'
      returning display_name $$,
  $$ values ('Bob renamed') $$,
  'caregiver can update a patient');

select is_empty(
  $$ delete from core.patients
      where id = '40000000-0000-4000-8000-000000000004'
      returning id $$,
  'caregiver cannot delete a patient (manage)');

select throws_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000003') $$,
  '42501', null,
  'caregiver cannot remove members');

select throws_ok(
  $$ select core.create_invitation('20000000-0000-4000-8000-00000000000a',
                                   'someone@example.test', 'caregiver') $$,
  '42501', null,
  'caregiver cannot create invitations');

-- --- viewer (Carol) ---------------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((select count(*)::int from core.patients), 3,
          'viewer sees all patients in the circle, including one added by a caregiver');

select is(core.can('40000000-0000-4000-8000-000000000001', 'read'),
          true, 'viewer can read a patient');
select is(core.can('40000000-0000-4000-8000-000000000001', 'write'),
          false, 'viewer cannot write patients');
select is(core.can('40000000-0000-4000-8000-000000000001', 'manage'),
          false, 'viewer cannot manage patients');

select throws_ok(
  $$ insert into core.patients (circle_id, display_name)
     values ('20000000-0000-4000-8000-00000000000a', 'viewer patient') $$,
  '42501', null,
  'viewer cannot insert a patient');

select is_empty(
  $$ update core.patients set display_name = 'viewer edit'
      where id = '40000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer updates zero patient rows');

select is_empty(
  $$ delete from core.patients
      where id = '40000000-0000-4000-8000-000000000001'
      returning id $$,
  'viewer deletes zero patient rows');

select throws_ok(
  $$ select core.create_invitation('20000000-0000-4000-8000-00000000000a',
                                   'someone@example.test', 'viewer') $$,
  '42501', null,
  'viewer cannot create invitations');

-- --- another circle (Dave) --------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((select count(*)::int from core.care_circles), 1,
          'another circle user sees only their own circle');
select is((select count(*)::int from core.patients), 1,
          'another circle user sees none of circle A''s patients');
select is((select count(*)::int from core.memberships), 1,
          'another circle user sees only their own membership');
select is((select count(*)::int from core.profiles), 1,
          'another circle user sees only their own profile');
select is_empty(
  $$ select id from core.profiles
      where id = '10000000-0000-4000-8000-000000000001' $$,
  'another circle user cannot read a circle A profile');
select is((select count(*)::int from core.invitations), 0,
          'another circle user sees no invitations');
select is_empty(
  $$ select id from core.audit_log
      where circle_id = '20000000-0000-4000-8000-00000000000a' $$,
  'another circle user sees no audit rows of circle A');
select is_empty(
  $$ select id from core.patients
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'another circle user reads no circle A patient row');

select is(core.can('40000000-0000-4000-8000-000000000001', 'read'), false,
          'another circle user gets false from core.can');

-- --- patient integrity: linked user and primary caregiver -------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ insert into core.patients (circle_id, display_name, linked_user_id)
     values ('20000000-0000-4000-8000-00000000000a', 'Bad link',
             '10000000-0000-4000-8000-000000000005') $$,
  '23514', 'linked_user_id must be a member of the patient''s care circle',
  'linked_user_id must be a member of the circle');

select throws_ok(
  $$ insert into core.patients (circle_id, display_name, primary_caregiver_id)
     values ('20000000-0000-4000-8000-00000000000a', 'Bad primary',
             '10000000-0000-4000-8000-000000000003') $$,
  '23514', 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle',
  'primary_caregiver_id may not be a viewer');

select throws_ok(
  $$ insert into core.patients (circle_id, display_name, primary_caregiver_id)
     values ('20000000-0000-4000-8000-00000000000a', 'Bad primary',
             '10000000-0000-4000-8000-000000000005') $$,
  '23514', 'primary_caregiver_id must be an owner or caregiver of the patient''s care circle',
  'primary_caregiver_id must be a member of the circle');

select lives_ok(
  $$ insert into core.patients (id, circle_id, display_name, linked_user_id, primary_caregiver_id)
     values ('40000000-0000-4000-8000-000000000005',
             '20000000-0000-4000-8000-00000000000a', 'Linked patient',
             '10000000-0000-4000-8000-000000000002',
             '10000000-0000-4000-8000-000000000002') $$,
  'a member may be linked and be the primary caregiver');

select throws_ok(
  $$ update core.patients
        set circle_id = '20000000-0000-4000-8000-00000000000b'
      where id = '40000000-0000-4000-8000-000000000001' $$,
  '23514', 'a patient cannot move to another care circle',
  'a patient cannot move to another circle');

-- --- invitations ------------------------------------------------------------

select is(
  (select count(*)::int from core.invitations),
  0,
  'no invitations exist before one is created');

insert into test_invitation_codes (label, code)
values ('frank', core.create_invitation(
  '20000000-0000-4000-8000-00000000000a', '  Frank@Example.Test  ', 'caregiver'));

select ok(
  (select code ~ '^[0-9A-HJKMNP-TV-Z]{8}(-[0-9A-HJKMNP-TV-Z]{8}){3}$'
   from test_invitation_codes where label = 'frank'),
  'invitation code is grouped, upper-case Crockford base32');

select is(
  (select email from core.invitations limit 1),
  'frank@example.test',
  'invitation email is stored trimmed and lower-case');

-- The hash is only visible to the migration role: read the stored bytes there.
reset role;

select is(
  (select octet_length(token_hash) from core.invitations
    where email = 'frank@example.test'),
  32,
  'only a 32-byte sha256 hash of the code is stored');

select is(
  (select encode(i.token_hash, 'hex') from core.invitations i
    where i.email = 'frank@example.test'),
  (select encode(extensions.digest(replace(c.code, '-', ''), 'sha256'), 'hex')
     from test_invitation_codes c where c.label = 'frank'),
  'the stored hash is sha256 of the normalised code');

select ok(
  (select encode(i.token_hash, 'hex') <> replace(c.code, '-', '')
     from core.invitations i, test_invitation_codes c
    where i.email = 'frank@example.test' and c.label = 'frank'),
  'the plaintext code itself is not stored');

set local role authenticated;

select ok(
  not has_column_privilege('authenticated', 'core.invitations', 'token_hash', 'select'),
  'authenticated has no SELECT privilege on invitations.token_hash');

select ok(
  has_column_privilege('authenticated', 'core.invitations', 'email', 'select'),
  'authenticated may select the non-secret invitation columns');

select throws_ok(
  $$ select token_hash from core.invitations $$,
  '42501', null,
  'an owner cannot read token_hash through the API either');

select is((select count(*)::int from core.invitations), 1,
          'the circle owner sees the invitation');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ select core.create_invitation('20000000-0000-4000-8000-00000000000a',
                                   'x@example.test', 'caregiver') $$,
  '42501', null,
  'only an owner can create an invitation');

select is((select count(*)::int from core.invitations), 0,
          'a caregiver cannot see invitations');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select throws_ok(
  $$ select core.accept_invitation((select code from test_invitation_codes where label = 'frank')) $$,
  '42501', 'invitation was issued for a different email address',
  'the wrong account cannot accept an invitation');

-- The invited account accepts.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is(
  core.accept_invitation((select code from test_invitation_codes where label = 'frank')),
  '20000000-0000-4000-8000-00000000000a'::uuid,
  'the invited user accepts and gets the circle id');

select is(
  (select m.role::text from core.memberships m
    where m.circle_id = '20000000-0000-4000-8000-00000000000a'
      and m.user_id = '10000000-0000-4000-8000-000000000006'),
  'caregiver',
  'accepting creates the membership with the invited role');

select throws_ok(
  $$ select core.accept_invitation((select code from test_invitation_codes where label = 'frank')) $$,
  '22023', 'invitation is not valid',
  'an invitation cannot be used twice');

-- Expired invitation: created by the owner, then aged past its expiry.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

insert into test_invitation_codes (label, code)
values ('gina', core.create_invitation(
  '20000000-0000-4000-8000-00000000000a', 'gina@example.test', 'viewer'));

-- Expiry is server-side metadata: clients have no UPDATE privilege, so age the
-- row as the migration role (this stands in for "the clock passed the expiry").
reset role;

select lives_ok(
  $$ update core.invitations set expires_at = now() - interval '1 minute'
      where email = 'gina@example.test' $$,
  'test setup: an invitation can be made expired');

set local role authenticated;

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000007","role":"authenticated"}';

select throws_ok(
  $$ select core.accept_invitation((select code from test_invitation_codes where label = 'gina')) $$,
  '22023', 'invitation is not valid',
  'an expired invitation is rejected with the uniform error');

-- Revoked invitation.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

insert into test_invitation_codes (label, code)
values ('henry', core.create_invitation(
  '20000000-0000-4000-8000-00000000000a', 'henry@example.test', 'viewer'));

-- Record the id as the migration role: a caregiver cannot read invitations,
-- but a caregiver can still *guess* an id and call the RPC, so the test needs
-- the real id to prove the owner check (not merely "not found").
reset role;

select lives_ok(
  $$ update test_invitation_codes c
        set invitation_id = (select id from core.invitations
                              where email = 'henry@example.test')
      where c.label = 'henry' $$,
  'test setup: capture the invitation id');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ select core.revoke_invitation(
       (select invitation_id from test_invitation_codes where label = 'henry')) $$,
  'the owner can revoke an invitation');

select ok(
  (select revoked_at is not null from core.invitations
    where email = 'henry@example.test'),
  'revoking stamps revoked_at');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ select core.revoke_invitation(
       (select invitation_id from test_invitation_codes where label = 'henry')) $$,
  '42501', null,
  'a caregiver cannot revoke an invitation');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000008","role":"authenticated"}';

select throws_ok(
  $$ select core.accept_invitation((select code from test_invitation_codes where label = 'henry')) $$,
  '22023', 'invitation is not valid',
  'a revoked invitation is rejected with the uniform error');

-- --- last owner -------------------------------------------------------------

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000001') $$,
  '23514', 'cannot remove the last owner of a circle',
  'the last owner cannot remove themselves');

select throws_ok(
  $$ select core.set_member_role('30000000-0000-4000-8000-000000000001', 'caregiver') $$,
  '23514', 'cannot demote the last owner of a circle',
  'the last owner cannot demote themselves');

select lives_ok(
  $$ select core.set_member_role('30000000-0000-4000-8000-000000000003', 'viewer') $$,
  'the owner can change a role (viewer stays viewer)');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select throws_ok(
  $$ select core.set_member_role('30000000-0000-4000-8000-000000000003', 'caregiver') $$,
  '42501', null,
  'a non-owner cannot change roles');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

-- --- removed caregiver loses access immediately ------------------------------

select lives_ok(
  $$ select core.remove_member('30000000-0000-4000-8000-000000000004') $$,
  'the owner removes Erin');

select is_empty(
  $$ select id from core.memberships
      where id = '30000000-0000-4000-8000-000000000004' $$,
  'the removed membership is gone');

-- Erin now sees nothing through RLS, and her stream queries return nothing.
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000004","role":"authenticated"}';

select is((select count(*)::int from core.care_circles), 0,
          'a removed caregiver sees no circles');
select is((select count(*)::int from core.memberships), 0,
          'a removed caregiver sees no memberships');
select is((select count(*)::int from core.patients), 0,
          'a removed caregiver sees no patients');
select is_empty(
  $$ select id from core.patients
      where id = '40000000-0000-4000-8000-000000000001' $$,
  'a removed caregiver reads no patient row');

reset role;

select is((
  select count(*)::int from (
    SELECT * FROM "core"."care_circles" WHERE id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream care_circles: a removed caregiver downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream memberships: a removed caregiver downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))
  ) s), 0, 'stream patients: a removed caregiver downloads nothing');

select is((
  select count(*)::int from (
    SELECT * FROM "core"."profiles" WHERE id IN (SELECT user_id FROM "core"."memberships" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid())))
  ) s), 0, 'stream profiles: a removed caregiver downloads nothing');

-- --- audit ------------------------------------------------------------------
-- Rows are written by triggers; these run as the migration role so the
-- assertions are not limited by the owner-only SELECT policy.

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'insert'
     and actor_id = '10000000-0000-4000-8000-000000000002'
     and circle_id = '20000000-0000-4000-8000-00000000000a'
     and patient_id = '40000000-0000-4000-8000-000000000004'
     and old_data is null
     and new_data ->> 'display_name' = 'Bob added'), 1,
  'a patient insert writes one audit row with actor, circle, patient and new data');

select is((
  select old_data ->> 'display_name' from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'update'), 'Bob added',
  'a patient update keeps the previous value');

select is((
  select new_data ->> 'display_name' from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'update'), 'Bob renamed',
  'a patient update stores the new value');

-- The owner deletes that patient: the log keeps the row even though the
-- patient is gone (no foreign keys on audit_log, by design).
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ delete from core.patients
      where id = '40000000-0000-4000-8000-000000000004' $$,
  'the owner can delete a patient');

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'patients'
     and record_id = '40000000-0000-4000-8000-000000000004'
     and action = 'delete'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'display_name' = 'Bob renamed'
     and new_data is null), 1,
  'a patient delete writes an audit row with the old values and the actor');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'memberships'
     and record_id = '30000000-0000-4000-8000-000000000004'
     and action = 'delete'
     and actor_id = '10000000-0000-4000-8000-000000000001'
     and old_data ->> 'role' = 'caregiver'), 1,
  'removing a member writes an audit row with the old role and actor');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'care_circles'
     and record_id = '20000000-0000-4000-8000-00000000000a'
     and action = 'update'
     and old_data ->> 'name' = 'Family A'
     and new_data ->> 'name' = 'Family A renamed'), 1,
  'renaming a circle writes an audit row with before/after');

select is((
  select count(*)::int from core.audit_log
   where table_name = 'invitations'
     and action = 'insert'
     and (old_data ? 'token_hash' or new_data ? 'token_hash')), 0,
  'audit rows never contain the invitation token hash');

-- Profiles are logged too. Bob edits his own name.
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select lives_ok(
  $$ update core.profiles set display_name = 'Bob B.'
      where id = '10000000-0000-4000-8000-000000000002' $$,
  'a user can rename their own profile');

reset role;

select is((
  select count(*)::int from core.audit_log
   where table_name = 'profiles'
     and record_id = '10000000-0000-4000-8000-000000000002'
     and action = 'update'
     and actor_id = '10000000-0000-4000-8000-000000000002'
     and new_data ->> 'display_name' = 'Bob B.'), 1,
  'a profile update writes an audit row');

-- Only circle owners read the log.
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';
select is((select count(*)::int from core.audit_log), 0,
          'a caregiver cannot read the audit log');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';
select cmp_ok((select count(*)::int from core.audit_log), '>', 0,
              'the owner reads the circle''s audit log');

-- Append-only: authenticated has no write privilege, and the guard trigger
-- refuses even the table owner.
select throws_ok(
  $$ insert into core.audit_log (table_name, action) values ('x', 'insert') $$,
  '42501', null,
  'authenticated cannot insert into the audit log');

select throws_ok(
  $$ update core.audit_log set action = 'tampered' $$,
  '42501', null,
  'authenticated cannot update the audit log');

select throws_ok(
  $$ delete from core.audit_log $$,
  '42501', null,
  'authenticated cannot delete from the audit log');

reset role;

select throws_ok(
  $$ update core.audit_log set action = 'tampered' $$,
  '42501', null,
  'even the table owner cannot update the audit log');

select throws_ok(
  $$ delete from core.audit_log $$,
  '42501', null,
  'even the table owner cannot delete from the audit log');

select throws_ok(
  $$ truncate core.audit_log $$,
  '42501', null,
  'even the table owner cannot truncate the audit log');

-- --- privileges and replication ---------------------------------------------

select ok(not has_table_privilege('authenticated', 'core.memberships', 'insert'),
          'authenticated has no insert on memberships');
select ok(not has_table_privilege('authenticated', 'core.memberships', 'update'),
          'authenticated has no update on memberships');
select ok(not has_table_privilege('authenticated', 'core.memberships', 'delete'),
          'authenticated has no delete on memberships');
select ok(not has_table_privilege('authenticated', 'core.invitations', 'insert'),
          'authenticated has no insert on invitations');
select ok(not has_table_privilege('authenticated', 'core.audit_log', 'update'),
          'authenticated has no update on the audit log');
select ok(has_function_privilege('authenticated', 'core.create_circle(text)', 'execute'),
          'authenticated may execute core.create_circle');
select ok(has_function_privilege('authenticated', 'core.accept_invitation(text)', 'execute'),
          'authenticated may execute core.accept_invitation');
select ok(not has_schema_privilege('anon', 'core', 'usage'),
          'anon still has no usage on schema core');

select is((
  select count(*)::int from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'core'
     and tablename in ('care_circles', 'memberships', 'profiles', 'patients')), 4,
  'the powersync publication covers the four synced tables');

select is((
  select count(*)::int from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'core'
     and tablename in ('invitations', 'patient_access_overrides', 'audit_log')), 0,
  'invitations, overrides and the audit log are not replicated');

-- --- schema-wide guarantee --------------------------------------------------
-- The M1 test also checks this over pg_class; keep an explicit assertion here
-- so a new table cannot be added without RLS in this milestone either.
select is((
  select count(*)::int from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'core'
     and c.relkind in ('r', 'p')
     and not (c.relrowsecurity and c.relforcerowsecurity)), 0,
  'every core table (including the new ones) has RLS enabled and forced');

select * from finish();

rollback;
