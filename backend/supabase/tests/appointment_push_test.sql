-- M6 appointment change push (build-plan §M6, docs/domain/appointments.md
-- "Change push", D4/D27/D29).
--
-- Run with: npx supabase@latest test db   (from backend/)
--
-- What is asserted here:
--
-- 1. Without Vault the trigger is a no-op: the insert succeeds and nothing is
--    queued (a webhook must never fail the row change).
-- 2. With the function URL and shared secret in Vault, an INSERT queues exactly
--    one pg_net request for the appointment-changed function, with the Vault
--    secret in `X-Cron-Secret`, `appointment_id` and `actor` (= `created_by`).
-- 3. An UPDATE of `notes` alone queues nothing; an UPDATE of `starts_local` or
--    `cancelled_at` queues one, with `actor` = `updated_by`.
-- 4. An appointment that started more than a day ago (in its own zone) queues
--    nothing.
--
-- Everything happens in one transaction and is rolled back.

begin;

create extension if not exists pgtap with schema extensions;

select plan(19);

-- --- Fixtures ---------------------------------------------------------------
-- Circle A: Alice owner, Bob caregiver, Carol viewer.

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
   'Grandma');

-- --- 1. No Vault: no-op -----------------------------------------------------
-- (3 assertions)

select is(core.push_setting('appointment_changed_url'), null::text,
          'no Vault secret means no configured appointment-changed URL');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, title, starts_local, time_zone)
     values ('70000000-0000-4000-8000-000000000001',
             '40000000-0000-4000-8000-000000000001', 'consultation', 'Cardiology',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '2 days',
             'Europe/Lisbon') $$,
  'an appointment insert still succeeds with the webhook unconfigured');

reset role;

select is((select count(*)::int from net.http_request_queue), 0,
          'the appointment trigger queues nothing when unconfigured');

-- --- 2. Configured: INSERT queues one request -------------------------------
-- (7 assertions)

select lives_ok(
  $$ select vault.create_secret('http://127.0.0.1:54321/functions/v1/appointment-changed',
                                'appointment_changed_url'),
            vault.create_secret('test-secret', 'push_cron_secret') $$,
  'the function URL and shared secret can be stored in Vault');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, title, starts_local, time_zone)
     values ('70000000-0000-4000-8000-000000000002',
             '40000000-0000-4000-8000-000000000001', 'blood_test', 'Blood test',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) + interval '3 days',
             'Europe/Lisbon') $$,
  'an appointment insert succeeds with the webhook configured');

reset role;

select is((select count(*)::int from net.http_request_queue), 1,
          'the insert queues exactly one request');

select is((select url from net.http_request_queue),
          'http://127.0.0.1:54321/functions/v1/appointment-changed',
          'the queued request goes to the appointment-changed URL');

select is((select headers ->> 'X-Cron-Secret' from net.http_request_queue),
          'test-secret',
          'the queued request carries the Vault shared secret');

select is((select convert_from(body, 'UTF8')::jsonb ->> 'appointment_id' from net.http_request_queue),
          '70000000-0000-4000-8000-000000000002',
          'the queued body names the appointment id');

select is((select convert_from(body, 'UTF8')::jsonb ->> 'actor' from net.http_request_queue),
          '10000000-0000-4000-8000-000000000001',
          'the actor of an insert is created_by');

-- --- 3. UPDATE: only the interesting columns queue a request ----------------
-- (5 assertions)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select lives_ok(
  $$ update appointments.appointments
        set notes = 'Bring the referral letter'
      where id = '70000000-0000-4000-8000-000000000002' $$,
  'an update of notes alone succeeds');

reset role;

select is((select count(*)::int from net.http_request_queue), 1,
          'a notes-only update queues nothing');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select lives_ok(
  $$ update appointments.appointments
        set starts_local = date_trunc('minute', (now() at time zone 'Europe/Lisbon'))
                           + interval '4 days'
      where id = '70000000-0000-4000-8000-000000000002' $$,
  'an update of starts_local succeeds');

reset role;

select is((select count(*)::int from net.http_request_queue), 2,
          'a starts_local update queues one more request');

select is((select convert_from(body, 'UTF8')::jsonb ->> 'actor'
            from net.http_request_queue order by id desc limit 1),
          '10000000-0000-4000-8000-000000000002',
          'the actor of an update is updated_by');

-- --- 4. Past appointments and cancelling ------------------------------------
-- (4 assertions)

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000001","role":"authenticated"}';

select lives_ok(
  $$ insert into appointments.appointments
       (id, patient_id, kind, title, starts_local, time_zone)
     values ('70000000-0000-4000-8000-000000000003',
             '40000000-0000-4000-8000-000000000001', 'checkup', 'Old checkup',
             date_trunc('minute', (now() at time zone 'Europe/Lisbon')) - interval '2 days',
             'Europe/Lisbon') $$,
  'an appointment from two days ago inserts without error');

reset role;

select is((select count(*)::int from net.http_request_queue), 2,
          'an appointment over a day in the past queues nothing');

set local role authenticated;
set local request.jwt.claims = '{"sub":"10000000-0000-4000-8000-000000000002","role":"authenticated"}';

select lives_ok(
  $$ update appointments.appointments
        set cancelled_at = now()
      where id = '70000000-0000-4000-8000-000000000002' $$,
  'cancelling an appointment succeeds');

reset role;

select is((select count(*)::int from net.http_request_queue), 3,
          'cancelling an upcoming appointment queues one more request');

select * from finish();
rollback;
