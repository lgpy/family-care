-- M1 isolation tests for core.sync_probe (build-plan §4 "Database tests",
-- security-and-privacy §3/D13): deny by default, owner-only rows, anon denied.
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- Everything happens in one transaction and is rolled back, so the tests need no
-- cleanup and never touch dev/prod data. Two users are created in auth.users and
-- each statement block impersonates one of them the way PostgREST does:
--   set local role authenticated;
--   set local request.jwt.claims = '{"sub":"<user id>","role":"authenticated"}';
-- `reset role` returns to the migration (postgres) role, which bypasses RLS --
-- so every assertion that is about visibility runs while impersonating a user.

begin;

create extension if not exists pgtap with schema extensions;

select plan(30);

-- --- Fixtures ---------------------------------------------------------------
-- Two unrelated users: there is no care circle yet (that is M2), so in M1
-- "owns their own rows, sees nobody else's" is the whole access rule.
insert into auth.users (id, instance_id, aud, role, email, email_confirmed_at)
values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'alice@example.test', now()),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '00000000-0000-0000-0000-000000000000',
   'authenticated', 'authenticated', 'bob@example.test', now());

-- --- Schema-wide guarantees -------------------------------------------------
-- These iterate pg_class rather than naming tables, so every table added to
-- `core` later is covered automatically (the "future tables" trap in T3).
select cmp_ok(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'core' and c.relkind in ('r', 'p')),
  '>', 0::bigint,
  'schema core contains at least one table (so the RLS check below is not vacuous)'
);

select is(
  (select count(*) from pg_class c
     join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'core'
      and c.relkind in ('r', 'p')
      and not (c.relrowsecurity and c.relforcerowsecurity)),
  0::bigint,
  'every table in schema core has RLS both enabled and forced'
);

select ok(
  has_schema_privilege('authenticated', 'core', 'usage'),
  'authenticated can use schema core'
);

select ok(
  not has_schema_privilege('anon', 'core', 'usage'),
  'anon has no usage on schema core'
);

select ok(has_table_privilege('authenticated', 'core.sync_probe', 'select'),
          'authenticated may select core.sync_probe (rows still filtered by RLS)');
select ok(has_table_privilege('authenticated', 'core.sync_probe', 'insert'),
          'authenticated may insert into core.sync_probe');
select ok(has_table_privilege('authenticated', 'core.sync_probe', 'update'),
          'authenticated may update core.sync_probe');
select ok(has_table_privilege('authenticated', 'core.sync_probe', 'delete'),
          'authenticated may delete from core.sync_probe');

select ok(
  not has_table_privilege('anon', 'core.sync_probe', 'select'),
  'anon has no select on core.sync_probe'
);

-- The publication is what PowerSync replicates from, so it must cover the table.
select is(
  (select count(*)::int from pg_publication where pubname = 'powersync'),
  1,
  'publication powersync exists'
);

select is(
  (select count(*)::int from pg_publication_tables
    where pubname = 'powersync' and schemaname = 'core' and tablename = 'sync_probe'),
  1,
  'publication powersync contains core.sync_probe'
);

-- --- anon: denied entirely --------------------------------------------------
set local role anon;
set local request.jwt.claims = '{"role":"anon"}';

select throws_ok(
  $$ select count(*) from core.sync_probe $$,
  '42501', null,
  'anon cannot read core.sync_probe'
);

select throws_ok(
  $$ insert into core.sync_probe (note) values ('anon write') $$,
  '42501', null,
  'anon cannot write to core.sync_probe'
);

-- --- owner (Alice) ----------------------------------------------------------
set local role authenticated;
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","role":"authenticated"}';

select lives_ok(
  $$ insert into core.sync_probe (id, note)
     values ('11111111-1111-4111-8111-111111111111', 'first note') $$,
  'owner can insert a row without naming an owner'
);

select is(
  (select owner_id from core.sync_probe where id = '11111111-1111-4111-8111-111111111111'),
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
  'owner_id defaults to the caller (auth.uid())'
);

select is(
  (select count(*)::int from core.sync_probe),
  1,
  'owner sees their own row'
);

select results_eq(
  $$ update core.sync_probe set note = 'second note'
      where id = '11111111-1111-4111-8111-111111111111'
      returning id $$,
  $$ values ('11111111-1111-4111-8111-111111111111'::uuid) $$,
  'owner can update their own row'
);

select is(
  (select note from core.sync_probe where id = '11111111-1111-4111-8111-111111111111'),
  'second note',
  'the update actually landed'
);

-- The UPDATE policy's WITH CHECK stops a row being handed to somebody else.
select throws_ok(
  $$ update core.sync_probe
        set owner_id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid
      where id = '11111111-1111-4111-8111-111111111111' $$,
  '42501', null,
  'owner cannot reassign their row to another user'
);

select is(
  (select owner_id from core.sync_probe where id = '11111111-1111-4111-8111-111111111111'),
  'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
  'the rejected update left the row with its owner'
);

-- --- other user (Bob) -------------------------------------------------------
set local request.jwt.claims = '{"sub":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","role":"authenticated"}';

select is(
  (select count(*)::int from core.sync_probe),
  0,
  'another user sees none of the owner''s rows'
);

select is_empty(
  $$ update core.sync_probe set note = 'hijacked'
      where id = '11111111-1111-4111-8111-111111111111'
      returning id $$,
  'another user updates zero rows'
);

select is_empty(
  $$ delete from core.sync_probe
      where id = '11111111-1111-4111-8111-111111111111'
      returning id $$,
  'another user deletes zero rows'
);

select throws_ok(
  $$ insert into core.sync_probe (id, owner_id, note)
     values ('33333333-3333-4333-8333-333333333333',
             'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
             'forged owner') $$,
  '42501', null,
  'another user cannot insert a row owned by someone else'
);

select lives_ok(
  $$ insert into core.sync_probe (id, note)
     values ('22222222-2222-4222-8222-222222222222', 'bob note') $$,
  'another user can insert their own row'
);

select is(
  (select count(*)::int from core.sync_probe),
  1,
  'another user sees only their own row'
);

select is(
  (select owner_id from core.sync_probe where id = '22222222-2222-4222-8222-222222222222'),
  'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
  'the other user''s row belongs to them'
);

-- --- owner again: still isolated, and can delete ----------------------------
set local request.jwt.claims = '{"sub":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","role":"authenticated"}';

select is(
  (select count(*)::int from core.sync_probe),
  1,
  'owner still sees only their own row after the other user inserted'
);

select results_eq(
  $$ delete from core.sync_probe
      where id = '11111111-1111-4111-8111-111111111111'
      returning id $$,
  $$ values ('11111111-1111-4111-8111-111111111111'::uuid) $$,
  'owner can delete their own row'
);

select is(
  (select count(*)::int from core.sync_probe where id = '11111111-1111-4111-8111-111111111111'),
  0,
  'the deleted row is gone'
);

reset role;

select * from finish();

rollback;
