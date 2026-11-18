-- M4 push devices, escalation ledger and webhook wiring
-- (build-plan §M4, D4/D8/D11/D14/D19/D22/D24,
-- docs/security-and-privacy.md "Push payloads").
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. `core.push_devices` is owner-only: a co-member (even an owner) sees no
--    token, and re-registering a token moves it to the caller instead of
--    duplicating it. The audit log records the change without the token.
-- 2. `meds.escalations` is server-only: no API role has any privilege.
-- 3. `meds.effective_reminder` coalesces the medication override over the
--    patient policy/primary, and hides stopped medications.
-- 4. The webhooks are no-ops when Vault is unconfigured, and queue exactly one
--    pg_net request (URL + secret header, row id body) when it is configured.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(60);

-- --- Fixtures ---------------------------------------------------------------
-- Circle A: Alice owner, Bob caregiver, Carol viewer. Circle B: Dave owner.
-- Carol is the "viewer never notified" role in the escalation selection test.

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
   'authenticated', 'authenticated', 'dave@example.test', now(), '{}'::jsonb);

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
  ('30000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-00000000000b',
   '10000000-0000-4000-8000-000000000004', 'owner');

insert into core.patients
  (id, circle_id, display_name, primary_caregiver_id, escalation_delay_minutes,
   missed_window_minutes)
values
  ('40000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-00000000000a',
   'Grandma', '10000000-0000-4000-8000-000000000001', 30, 120),
  ('40000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-00000000000a',
   'Grandpa', null, 45, 180),
  ('40000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-00000000000b',
   'Other patient', null, 30, 120);

insert into meds.medications
  (id, patient_id, name, form, stock_unit, reminder_policy, primary_caregiver_id,
   stopped_at)
values
  ('50000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000001',
   'Med one', 'tablet', 'tablet', null, null, null),
  ('50000000-0000-4000-8000-000000000002', '40000000-0000-4000-8000-000000000002',
   'Med two', 'tablet', 'tablet', 'all_caregivers',
   '10000000-0000-4000-8000-000000000002', null),
  ('50000000-0000-4000-8000-000000000003', '40000000-0000-4000-8000-000000000001',
   'Stopped med', 'tablet', 'tablet', null, null, now()),
  ('50000000-0000-4000-8000-000000000004', '40000000-0000-4000-8000-000000000001',
   'Med four', 'tablet', 'tablet', 'primary_with_escalation', null, null);

insert into meds.regimens
  (id, medication_id, schedule_type, start_date, time_zone, times)
values
  ('60000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001',
   'daily', '2026-11-01', 'Europe/Lisbon', '[{"time":"08:00","quantity":1}]'::jsonb),
  ('60000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000002',
   'daily', '2026-11-01', 'Europe/Lisbon', '[{"time":"09:00","quantity":1}]'::jsonb),
  ('60000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000003',
   'daily', '2026-11-01', 'Europe/Lisbon', '[{"time":"10:00","quantity":1}]'::jsonb),
  ('60000000-0000-4000-8000-000000000004', '50000000-0000-4000-8000-000000000004',
   'daily', '2026-11-01', 'Europe/Lisbon', '[{"time":"11:00","quantity":1}]'::jsonb);

-- --- 1. Schema and replication ---------------------------------------------
-- (7 assertions)

select ok(to_regclass('core.push_devices') is not null,
          'core.push_devices exists');

select ok(to_regclass('meds.escalations') is not null,
          'meds.escalations exists');

select ok(to_regclass('meds.effective_reminder') is not null,
          'meds.effective_reminder exists');

select ok((select relrowsecurity and relforcerowsecurity
             from pg_class where oid = 'core.push_devices'::regclass),
          'core.push_devices has RLS enabled and forced');

select ok((select relrowsecurity and relforcerowsecurity
             from pg_class where oid = 'meds.escalations'::regclass),
          'meds.escalations has RLS enabled and forced');

select is(
  (select string_agg(schemaname || '.' || tablename, ',' order by schemaname, tablename)
     from pg_publication_tables where pubname = 'powersync'),
  'core.care_circles,core.memberships,core.patients,core.profiles,'
  'meds.dose_log,meds.medications,meds.regimens,meds.stock_movements',
  'the powersync publication holds exactly the synced tables: no device token and no server ledgers');

select is(
  (select count(*)::int from pg_publication_tables
    where pubname = 'powersync'
      and (schemaname, tablename) in
          (('core', 'push_devices'), ('meds', 'escalations'))),
  0,
  'neither push_devices nor escalations is replicated');

-- --- 2. Privileges ---------------------------------------------------------
-- (6 assertions)

select ok(
  has_table_privilege('authenticated', 'core.push_devices',
                      'select,insert,update,delete'),
  'authenticated has full DML on its own push devices (RLS narrows the rows)');

select ok(
  not has_table_privilege('authenticated', 'meds.escalations',
                          'select,insert,update,delete'),
  'authenticated has no privilege on the escalation ledger');

select ok(
  not has_table_privilege('anon', 'core.push_devices',
                          'select,insert,update,delete'),
  'anon has no privilege on push devices');

select ok(
  has_function_privilege('authenticated', 'core.register_push_device(text)', 'execute'),
  'authenticated may register a push device');

select ok(
  not has_function_privilege('anon', 'core.register_push_device(text)', 'execute'),
  'anon may not register a push device');

select ok(
  not has_function_privilege('authenticated', 'core.post_to_function(text, jsonb)', 'execute'),
  'no API role may call the Vault-backed POST helper');

-- --- 3. push_devices RLS: owner only ---------------------------------------
-- (7 assertions)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into core.push_devices (id, user_id, fcm_token)
     values ('d0000000-0000-4000-8000-000000000001',
             '10000000-0000-4000-8000-000000000001', 'token-alice-direct') $$,
  'Alice can insert her own device row directly');

select is((select count(*)::int from core.push_devices), 1,
          'Alice sees exactly her own device');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is((select count(*)::int from core.push_devices), 0,
          'Bob (co-member) sees none of Alice''s devices');

select throws_ok(
  $$ insert into core.push_devices (user_id, fcm_token)
     values ('10000000-0000-4000-8000-000000000001', 'token-stolen') $$,
  '42501', null,
  'Bob cannot insert a device row for another user');

select is_empty(
  $$ update core.push_devices set last_seen_at = now()
      where user_id = '10000000-0000-4000-8000-000000000001'
      returning id $$,
  'Bob cannot update another user''s device');

select is_empty(
  $$ delete from core.push_devices
      where user_id = '10000000-0000-4000-8000-000000000001'
      returning id $$,
  'Bob cannot delete another user''s device');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ update core.push_devices set last_seen_at = now()
      where fcm_token = 'token-alice-direct' $$,
  'Alice can update her own device');

-- --- 4. Registration RPCs --------------------------------------------------
-- (9 assertions)

select ok(core.register_push_device('token-alice-1') is not null,
          'register_push_device returns the new device id');

select is(
  core.register_push_device('token-alice-1'),
  (select id from core.push_devices where fcm_token = 'token-alice-1'),
  'registering an existing token returns the same row instead of a new one');

select is(
  (select count(*)::int from core.push_devices where fcm_token = 'token-alice-1'),
  1,
  'a token identifies one install: no duplicate row after re-registration');

select throws_ok(
  $$ select core.register_push_device('') $$,
  '22023', null,
  'an empty token is rejected');

reset role;

-- Bob registers a token that Alice will then claim.
set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select ok(core.register_push_device('token-shared') is not null,
          'Bob can register a token');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select ok(core.register_push_device('token-shared') is not null,
          'Alice can register the same token after signing in on that phone');

reset role;

select is(
  (select count(*)::int from core.push_devices where fcm_token = 'token-shared'),
  1,
  'the moved token still has exactly one row');

select is(
  (select user_id from core.push_devices where fcm_token = 'token-shared'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'the moved token is reassigned to the caller');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select is(core.unregister_push_device('token-shared'), false,
          'Bob cannot unregister a token that now belongs to Alice');

select is(core.unregister_push_device('token-alice-1'), false,
          'Bob cannot unregister Alice''s other token');

set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select is(core.unregister_push_device('token-alice-1'), true,
          'the owner can unregister their token');

select is(
  (select count(*)::int from core.push_devices where fcm_token = 'token-alice-1'),
  0,
  'the unregistered token row is gone');

reset role;

-- --- 5. Audit: the token never reaches the log -----------------------------
-- (4 assertions)

select is(
  (select count(*)::int from core.audit_log
    where table_name = 'push_devices' and action = 'insert'),
  3,
  'every device insert (direct and RPC) has an audit row');

select is(
  (select count(*)::int from core.audit_log
    where table_name = 'push_devices' and action = 'delete'),
  1,
  'unregistering a device is audited');

select is(
  (select count(*)::int from core.audit_log
    where table_name = 'push_devices' and event_kind <> 'security'),
  0,
  'device registrations are classified as security events');

select ok(
  not exists (
    select 1 from core.audit_log
     where table_name = 'push_devices'
       and (old_data ? 'fcm_token' or new_data ? 'fcm_token')
  ),
  'the fcm_token key is redacted from audit row data');

select ok(
  not exists (
    select 1 from core.audit_log
     where table_name = 'push_devices'
       and (coalesce(old_data ->> 'fcm_token', '') <> ''
            or coalesce(new_data ->> 'fcm_token', '') <> ''
            or coalesce(old_data::text, '') like '%token-%'
            or coalesce(new_data::text, '') like '%token-%'
            or coalesce(old_data::text, '') like '%token-shared%'
            or coalesce(new_data::text, '') like '%token-shared%')
  ),
  'no token value appears anywhere in the device audit rows');

-- --- 6. Escalation ledger: server-only and slot-unique ---------------------
-- (5 assertions)

select lives_ok(
  $$ insert into meds.escalations (regimen_id, patient_id, scheduled_local, recipients)
     values ('60000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001',
             '2026-11-16 08:00', 1) $$,
  'the server can record an escalated slot');

select throws_ok(
  $$ insert into meds.escalations (regimen_id, patient_id, scheduled_local, recipients)
     values ('60000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001',
             '2026-11-16 08:00', 1) $$,
  '23505', null,
  'one escalation per (regimen_id, scheduled_local)');

select throws_ok(
  $$ insert into meds.escalations (regimen_id, patient_id, scheduled_local)
     values ('60000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000002',
             '2026-11-16 09:00') $$,
  '23514', null,
  'the escalation patient must match the regimen''s medication');

select throws_ok(
  $$ insert into meds.escalations (regimen_id, patient_id, scheduled_local)
     values ('60000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001',
             '2026-11-16 10:00:30') $$,
  '23514', null,
  'escalation slots are whole minutes');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ select count(*) from meds.escalations $$,
  '42501', null,
  'even a circle owner cannot read the escalation ledger');

reset role;

-- --- 7. Effective reminder settings ---------------------------------------
-- (5 assertions)

select is(
  (select reminder_policy::text from meds.effective_reminder
    where medication_id = '50000000-0000-4000-8000-000000000001'),
  'primary_with_escalation',
  'a medication without an override inherits the patient policy');

select is(
  (select primary_caregiver_id from meds.effective_reminder
    where medication_id = '50000000-0000-4000-8000-000000000001'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'a medication without an override inherits the patient primary caregiver');

select is(
  (select reminder_policy::text from meds.effective_reminder
    where medication_id = '50000000-0000-4000-8000-000000000002'),
  'all_caregivers',
  'the medication policy override wins');

select is(
  (select primary_caregiver_id from meds.effective_reminder
    where medication_id = '50000000-0000-4000-8000-000000000004'),
  '10000000-0000-4000-8000-000000000001'::uuid,
  'an override policy with no medication primary still inherits the patient primary');

select is(
  (select array_agg(medication_id::text order by medication_id)
     from meds.effective_reminder),
  array['50000000-0000-4000-8000-000000000001',
        '50000000-0000-4000-8000-000000000002',
        '50000000-0000-4000-8000-000000000004'],
  'stopped medications are not in the effective view');

-- --- 8. Webhooks: no-op without Vault, one queued request with it ---------
-- (8 assertions)

select is(core.push_setting('dose_confirmed_url'), null::text,
          'no Vault secret means no configured dose-confirmed URL');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-16 08:00', 'taken', now()) $$,
  'a dose confirmation still succeeds with the webhook unconfigured');

reset role;

select is((select count(*)::int from net.http_request_queue), 0,
          'the dose-confirmed trigger queues nothing when unconfigured');

select lives_ok(
  $$ select vault.create_secret('http://127.0.0.1:54321/functions/v1/dose-confirmed',
                                'dose_confirmed_url'),
            vault.create_secret('http://127.0.0.1:54321/functions/v1/escalate',
                                'escalate_url'),
            vault.create_secret('test-secret', 'push_cron_secret') $$,
  'the function URL and shared secret can be stored in Vault');

select lives_ok(
  $$ insert into meds.dose_log
       (regimen_id, medication_id, scheduled_local, outcome, actual_at)
     values ('60000000-0000-4000-8000-000000000001',
             '50000000-0000-4000-8000-000000000001',
             '2026-11-17 08:00', 'taken', now()) $$,
  'a dose confirmation succeeds with the webhook configured');

select is((select count(*)::int from net.http_request_queue), 1,
          'the trigger queues exactly one request');

select is((select url from net.http_request_queue), 
          'http://127.0.0.1:54321/functions/v1/dose-confirmed',
          'the queued request goes to the dose-confirmed URL');

select is((select headers ->> 'X-Cron-Secret' from net.http_request_queue),
          'test-secret',
          'the queued request carries the Vault shared secret');

select ok(core.enqueue_escalation_push() is not null,
          'the five-minute tick queues the escalate request when configured');

select is((select count(*)::int from net.http_request_queue), 2,
          'the cron entry point queues exactly one more request');

-- --- 9. Cron ---------------------------------------------------------------
-- (2 assertions)

select is(
  (select schedule from cron.job where jobname = 'push_escalation_tick'),
  '*/5 * * * *',
  'pg_cron runs the escalation tick every five minutes');

select is(
  (select command from cron.job where jobname = 'push_escalation_tick'),
  'select core.enqueue_escalation_push()',
  'the cron job calls the Vault-aware entry point');

-- --- 10. The escalation ledger stays server-only on write ------------------
-- (1 assertion, after the configured secrets section so Vault is exercised)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select throws_ok(
  $$ insert into meds.escalations (regimen_id, patient_id, scheduled_local)
     values ('60000000-0000-4000-8000-000000000002',
             '40000000-0000-4000-8000-000000000002',
             '2026-11-16 09:00') $$,
  '42501', null,
  'no client can write the escalation ledger');

reset role;

select * from finish();

rollback;
