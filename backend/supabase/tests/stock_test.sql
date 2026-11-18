-- M5 stock ledger, low-stock alert ledger and the daily push wiring
-- (build-plan §M5, docs/domain/medications.md "Stock", D6/D9/D13/D14/D22/D28).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. Schema and privileges: both tables exist, RLS is forced, the ledger is
--    append-only for every role, and `meds.stock_alerts` is invisible to the API
--    roles. The ledger is in the `powersync` publication; the ledger of alerts
--    is not.
-- 2. Constraints: a count may be 0, a refill/discard must be > 0, a discard
--    needs a non-blank reason, quantities stay within the stock range.
-- 3. created_by cannot be spoofed by an authenticated client.
-- 4. RLS per role: owner and caregiver read and append, a viewer reads but
--    cannot append, another circle and a user without a circle see and write
--    nothing.
-- 5. The exact Sync Streams query from backend/powersync/sync-config.yaml, run
--    as each user with RLS bypassed (like the PowerSync replication role).
-- 6. The audit trigger writes the derived patient and circle.
-- 7. The daily job is a no-op without Vault and queues exactly one pg_net
--    request with the Vault secret when it is configured; pg_cron has the job.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(64);

-- --- Fixtures ---------------------------------------------------------------
-- Circle A: Alice owner, Bob caregiver, Carol viewer. Circle B: Dave owner.
-- Frank has no circle. Two medications in circle A: one active, one stopped.

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
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient');

insert into meds.medications
  (id, patient_id, name, form, stock_unit, stopped_at)
values
  ('50000000-0000-4000-8000-000000000001',
   '40000000-0000-4000-8000-000000000001', 'Metformin', 'tablet', 'tablet', null),
  ('50000000-0000-4000-8000-000000000002',
   '40000000-0000-4000-8000-000000000001', 'Stopped med', 'tablet', 'tablet', now());

insert into meds.regimens
  (id, medication_id, schedule_type, start_date, time_zone, times)
values
  ('60000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001',
   'daily', '2026-11-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]'::jsonb);

-- --- 1. Schema, replication and privileges ----------------------------------
-- (17 assertions)

select ok(to_regclass('meds.stock_movements') is not null,
          'meds.stock_movements exists');

select ok(to_regclass('meds.stock_alerts') is not null,
          'meds.stock_alerts exists');

select is(
  (select array_agg(e.enumlabel::text order by e.enumsortorder)
     from pg_enum e
     join pg_type t on t.oid = e.enumtypid
     join pg_namespace n on n.oid = t.typnamespace
    where n.nspname = 'meds' and t.typname = 'stock_movement_kind'),
  array['count', 'refill', 'discard'],
  'meds.stock_movement_kind has the agreed values');

select ok((select relrowsecurity and relforcerowsecurity
             from pg_class where oid = 'meds.stock_movements'::regclass),
          'meds.stock_movements has RLS enabled and forced');

select ok((select relrowsecurity and relforcerowsecurity
             from pg_class where oid = 'meds.stock_alerts'::regclass),
          'meds.stock_alerts has RLS enabled and forced');

select ok(has_table_privilege('authenticated', 'meds.stock_movements', 'select'),
          'authenticated may read the stock ledger');

select ok(has_table_privilege('authenticated', 'meds.stock_movements', 'insert'),
          'authenticated may append to the stock ledger');

select ok(not has_table_privilege('authenticated', 'meds.stock_movements', 'update'),
          'authenticated has no UPDATE privilege on the stock ledger');

select ok(not has_table_privilege('authenticated', 'meds.stock_movements', 'delete'),
          'authenticated has no DELETE privilege on the stock ledger');

select ok(not has_table_privilege('anon', 'meds.stock_movements', 'select,insert'),
          'anon has no privilege on the stock ledger');

select ok(
  not has_table_privilege('authenticated', 'meds.stock_alerts',
                          'select,insert,update,delete'),
  'authenticated has no privilege on the alert ledger');

select ok(
  not has_table_privilege('anon', 'meds.stock_alerts',
                          'select,insert,update,delete'),
  'anon has no privilege on the alert ledger');

select ok(has_table_privilege('service_role', 'meds.stock_movements', 'select'),
          'the service role may read the stock ledger');

select ok(has_table_privilege('service_role', 'meds.stock_alerts',
                              'select,insert,delete'),
          'the service role may read, write and clear alert rows');

select ok(
  not has_function_privilege('authenticated',
                             'core.enqueue_stock_low_push()', 'execute'),
  'no API role may execute the stock-low cron entry point');

select is((
  select string_agg(schemaname || '.' || tablename, ',' order by tablename)
    from pg_publication_tables
   where pubname = 'powersync' and schemaname = 'meds'),
  'meds.dose_log,meds.medications,meds.regimens,meds.stock_movements',
  'the powersync publication covers exactly the four meds tables');

select is((
  select count(*)::int from pg_publication_tables
   where pubname = 'powersync'
     and (schemaname, tablename) in
         (('meds', 'stock_alerts'))), 0,
  'the alert ledger is server state and is not replicated');

-- --- 2. Quantity and reason constraints ------------------------------------
-- (11 assertions; Alice is the owner of circle A)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity, occurred_at)
     values ('a0000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001', 'count', 30,
             '2026-11-18T07:00:00Z') $$,
  'an owner records the opening count');

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity)
     values ('a0000000-0000-4000-8000-000000000002',
             '50000000-0000-4000-8000-000000000001', 'count', 0) $$,
  'a count may be zero (the box is empty)');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'refill', 0) $$,
  '23514', null,
  'a refill of nothing is rejected');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'count', -1) $$,
  '23514', null,
  'a negative count is rejected');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity, reason)
     values ('50000000-0000-4000-8000-000000000001', 'discard', 0, 'spilled') $$,
  '23514', null,
  'a discard of nothing is rejected');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'discard', 2) $$,
  '23514', null,
  'a discard without a reason is rejected');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity, reason)
     values ('50000000-0000-4000-8000-000000000001', 'discard', 2, '   ') $$,
  '23514', null,
  'a discard with a blank reason is rejected');

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity, reason)
     values ('a0000000-0000-4000-8000-000000000003',
             '50000000-0000-4000-8000-000000000001', 'discard', 2, 'Expired') $$,
  'a discard with a reason is accepted');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'count', 100001) $$,
  '23514', null,
  'a quantity above the stock range is rejected');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity, reason)
     values ('50000000-0000-4000-8000-000000000001', 'refill', 1, repeat('x', 501)) $$,
  '23514', null,
  'a reason longer than 500 characters is rejected');

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity)
     values ('a0000000-0000-4000-8000-000000000005',
             '50000000-0000-4000-8000-000000000002', 'refill', 10) $$,
  'a movement on a stopped medication is still allowed (history is kept)');

-- --- 3. created_by cannot be spoofed ---------------------------------------
-- (3 assertions)

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity, created_by)
     values ('50000000-0000-4000-8000-000000000001', 'count', 5,
             '10000000-0000-4000-8000-000000000002') $$,
  '42501', null,
  'an authenticated user cannot record a movement in someone else''s name');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity, created_by)
     values ('50000000-0000-4000-8000-000000000001', 'count', 5, null) $$,
  '42501', null,
  'an authenticated user cannot leave created_by blank');

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity, created_by)
     values ('a0000000-0000-4000-8000-000000000004',
             '50000000-0000-4000-8000-000000000001', 'refill', 10,
             '10000000-0000-4000-8000-000000000001') $$,
  'an owner may name themselves as the creator');

-- --- 4. Append-only for every role -----------------------------------------
-- (4 assertions)

select throws_ok(
  $$ update meds.stock_movements set quantity = 1
      where id = 'a0000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'an authenticated user cannot update a movement (no grant)');

select throws_ok(
  $$ delete from meds.stock_movements
      where id = 'a0000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'an authenticated user cannot delete a movement (no grant)');

reset role;

select throws_ok(
  $$ update meds.stock_movements set quantity = 1
      where id = 'a0000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'the table owner is refused by the append-only trigger too');

set local role service_role;

select throws_ok(
  $$ update meds.stock_movements set quantity = 1
      where id = 'a0000000-0000-4000-8000-000000000001' $$,
  '42501', null,
  'the service role cannot update a movement either');

reset role;

-- --- 5. RLS per role -------------------------------------------------------
-- (11 assertions)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((select count(*)::int from meds.stock_movements), 5,
          'an owner reads the circle''s stock ledger');

select throws_ok($$ select count(*) from meds.stock_alerts $$,
                 '42501', null,
                 'even a circle owner cannot read the alert ledger');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from meds.stock_movements), 5,
          'a caregiver reads the circle''s stock ledger');

select lives_ok(
  $$ insert into meds.stock_movements (id, medication_id, kind, quantity)
     values ('a0000000-0000-4000-8000-000000000006',
             '50000000-0000-4000-8000-000000000001', 'refill', 30) $$,
  'a caregiver can append a refill');

select is((select count(*)::int from meds.stock_movements), 6,
          'the caregiver''s refill is visible');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((select count(*)::int from meds.stock_movements), 6,
          'a viewer reads the circle''s stock ledger');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'refill', 1) $$,
  '42501', null,
  'a viewer cannot append to the stock ledger');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((select count(*)::int from meds.stock_movements), 0,
          'another circle reads no stock movements');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'count', 1) $$,
  '42501', null,
  'another circle cannot append to the stock ledger');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((select count(*)::int from meds.stock_movements), 0,
          'a user without a circle reads no stock movements');

select throws_ok(
  $$ insert into meds.stock_movements (medication_id, kind, quantity)
     values ('50000000-0000-4000-8000-000000000001', 'count', 1) $$,
  '42501', null,
  'a user without a circle cannot append to the stock ledger');

-- --- 6. Sync Streams: the deployed query, run as each user ------------------
-- (5 assertions)
-- Same form as meds_test.sql: migration role (RLS bypassed, like the PowerSync
-- replication role), only the JWT claims change. The query must appear here
-- verbatim or powersync/check-sync-tests.sh fails.

reset role;

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."stock_movements" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 6, 'stream stock_movements: owner downloads the circle''s ledger');

select is((
  select array_agg(id::text order by id) from (
    SELECT * FROM "meds"."stock_movements" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s),
  array['a0000000-0000-4000-8000-000000000001',
        'a0000000-0000-4000-8000-000000000002',
        'a0000000-0000-4000-8000-000000000003',
        'a0000000-0000-4000-8000-000000000004',
        'a0000000-0000-4000-8000-000000000005',
        'a0000000-0000-4000-8000-000000000006'],
  'stream stock_movements: owner downloads exactly those rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000003","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."stock_movements" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 6, 'stream stock_movements: viewer downloads the same rows');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000005","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."stock_movements" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream stock_movements: another circle downloads nothing');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000006","role":"authenticated"}';

select is((
  select count(*)::int from (
    SELECT * FROM "meds"."stock_movements" WHERE medication_id IN (SELECT id FROM "meds"."medications" WHERE patient_id IN (SELECT id FROM "core"."patients" WHERE circle_id IN (SELECT circle_id FROM "core"."memberships" WHERE user_id = (select auth.uid()))))
  ) s), 0, 'stream stock_movements: a user without a circle downloads nothing');

-- --- 7. Audit: patient and circle are derived ------------------------------
-- (4 assertions)

select is(
  (select count(*)::int from core.audit_log
    where table_name = 'stock_movements' and action = 'insert'),
  6,
  'every movement insert has an audit row');

select is(
  (select patient_id from core.audit_log
    where table_name = 'stock_movements'
      and record_id = 'a0000000-0000-4000-8000-000000000001'),
  '40000000-0000-4000-8000-000000000001'::uuid,
  'the audit row carries the medication''s patient');

select is(
  (select circle_id from core.audit_log
    where table_name = 'stock_movements'
      and record_id = 'a0000000-0000-4000-8000-000000000001'),
  '20000000-0000-4000-8000-00000000000a'::uuid,
  'the audit row carries the patient''s circle');

select is(
  (select actor_id from core.audit_log
    where table_name = 'stock_movements'
      and record_id = 'a0000000-0000-4000-8000-000000000001'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'the audit row carries the actor');

-- --- 8. The daily job: no-op without Vault, one queued request with it -----
-- (7 assertions)

select is(core.enqueue_stock_low_push(), null::bigint,
          'no Vault secret means no stock-low call');

select is((select count(*)::int from net.http_request_queue), 0,
          'nothing is queued when unconfigured');

select lives_ok(
  $$ select vault.create_secret('http://127.0.0.1:54321/functions/v1/stock-low',
                                'stock_low_url'),
            vault.create_secret('test-secret', 'push_cron_secret') $$,
  'the function URL and shared secret can be stored in Vault');

select ok(core.enqueue_stock_low_push() is not null,
          'the daily tick queues the stock-low request when configured');

select is((select count(*)::int from net.http_request_queue), 1,
          'the cron entry point queues exactly one request');

select is((select url from net.http_request_queue),
          'http://127.0.0.1:54321/functions/v1/stock-low',
          'the queued request goes to the stock-low URL');

select is((select headers ->> 'X-Cron-Secret' from net.http_request_queue),
          'test-secret',
          'the queued request carries the Vault shared secret');

-- --- 9. Cron ---------------------------------------------------------------
-- (2 assertions)

select is(
  (select schedule from cron.job where jobname = 'stock_low_daily'),
  '0 9 * * *',
  'pg_cron runs the stock-low job every morning');

select is(
  (select command from cron.job where jobname = 'stock_low_daily'),
  'select core.enqueue_stock_low_push()',
  'the cron job calls the Vault-aware entry point');

select * from finish();

rollback;
