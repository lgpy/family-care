-- M4 push and escalation (build-plan §M4; docs/domain/care-circle.md
-- "Reminder routing"; D4, D8, D11, D14, D19, D22, D24;
-- docs/security-and-privacy.md "Push payloads").
--
-- Three pieces:
--
-- 1. `core.push_devices` - one row per app install with an FCM token. Device
--    tokens are personal data: RLS restricts every row to its owner, the token
--    is never written to the audit log, and nothing here is synced by PowerSync
--    (a token belongs to one phone, not to the shared record set).
-- 2. `meds.escalations` - the server's ledger of slots it has already
--    escalated, so the five-minute job is idempotent. Written only by the
--    service role; no client may read or write it.
-- 3. The HTTP plumbing: a Vault-configured URL + shared secret (`core.push_setting`
--    and `core.post_to_function`), an AFTER INSERT trigger on `meds.dose_log`
--    that pings `dose-confirmed`, and a pg_cron job that pings `escalate`
--    every five minutes. Both are no-ops when the Vault secrets are absent,
--    which is the local/CI default: nothing here needs the edge runtime or
--    network access to pass `db reset` and `test db`.
--
-- Notification content lives in the edge functions, not in SQL: payloads are
-- generic (no medication or patient names, D4) and localized in the recipient's
-- `core.profiles.preferred_language` (D8).

-- --- Push devices -----------------------------------------------------------

create table core.push_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users (id) on delete cascade,
  fcm_token text not null unique
    constraint push_devices_token_length check (char_length(fcm_token) between 1 and 4096),
  -- Android only today (iOS is not a target, D22); the column names the fact
  -- instead of a boolean so a second platform is a check-constraint change.
  platform text not null default 'android'
    constraint push_devices_platform_check check (platform = 'android'),
  -- Informational copy of the phone's language. Reminders are localized from
  -- core.profiles.preferred_language (the one place a user's choice is written);
  -- this is only useful when debugging a device whose profile is stale.
  app_language text
    constraint push_devices_app_language_check
    check (app_language is null or app_language in ('pt-PT', 'en')),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

comment on table core.push_devices is
  'FCM registration tokens, one row per app install. Personal data: RLS limits each row to its owner, and clients register/unregister through core.register_push_device() / core.unregister_push_device() so a token that moved between accounts is reassigned instead of duplicated. Not synced by PowerSync.';
comment on column core.push_devices.fcm_token is
  'FCM registration token. Unique: a token identifies an install, so re-registering it must reassign the row, never create a second one. Never written to core.audit_log.';
comment on column core.push_devices.last_seen_at is
  'Bumped by every register_push_device() call, so the escalation job could prune stale installs later. created_at stays at first registration.';

create index push_devices_user_id_idx on core.push_devices (user_id);

-- Device registration is a security event (it decides which phone can receive
-- pushes) but has no circle. It gets its own audit function instead of
-- core.audit_row_change() for one reason: the token must not be logged, and a
-- per-table redaction branch in the generic function would be easy to lose.
create or replace function core.audit_push_device()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old jsonb;
  v_new jsonb;
begin
  if tg_op = 'INSERT' then
    v_new := to_jsonb(new) - 'fcm_token';
  elsif tg_op = 'UPDATE' then
    v_old := to_jsonb(old) - 'fcm_token';
    v_new := to_jsonb(new) - 'fcm_token';
  else
    v_old := to_jsonb(old) - 'fcm_token';
  end if;

  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action,
    old_data, new_data, event_kind
  )
  values (
    (select auth.uid()), null, null, 'push_devices',
    coalesce((v_new ->> 'id')::uuid, (v_old ->> 'id')::uuid),
    lower(tg_op), v_old, v_new, 'security'
  );

  return null;
end;
$$;

comment on function core.audit_push_device() is
  'AFTER INSERT/UPDATE/DELETE on core.push_devices: one core.audit_log row, event_kind=security, no circle. fcm_token is removed from both JSON documents - the token is personal data and a bearer credential for that install, so only the device row id, user id, platform and language are recorded.';

create trigger push_devices_audit
  after insert or update or delete on core.push_devices
  for each row execute function core.audit_push_device();

revoke all on function core.audit_push_device() from public;

-- Registration RPCs. Tokens are per install, not per account: if a token is
-- already present (a reinstall that kept the token, or a shared phone where the
-- user signed in with another account), the row moves to the caller. That is
-- why direct INSERT is not enough and the RPC is SECURITY DEFINER.
create or replace function core.register_push_device(p_token text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := (select auth.uid());
  v_id uuid;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_token is null or char_length(p_token) not between 1 and 4096 then
    raise exception 'invalid push token' using errcode = '22023';
  end if;

  insert into core.push_devices (user_id, fcm_token)
  values (v_user_id, p_token)
  on conflict (fcm_token) do update
    set user_id = excluded.user_id,
        last_seen_at = now()
  returning id into v_id;

  return v_id;
end;
$$;

comment on function core.register_push_device(text) is
  'Upsert the caller''s FCM token and return the device row id. Re-registering an existing token updates last_seen_at and reassigns the row to the caller (tokens belong to installs, not accounts). Audited without the token.';

create or replace function core.unregister_push_device(p_token text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select auth.uid()) is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;

  delete from core.push_devices
   where fcm_token = p_token
     and user_id = (select auth.uid());

  return found;
end;
$$;

comment on function core.unregister_push_device(text) is
  'Delete the caller''s own device row for this token. Returns false when the caller does not own it (silently: another account''s registration is not theirs to remove).';

revoke all on function core.register_push_device(text) from public;
revoke all on function core.register_push_device(text) from anon;
grant execute on function core.register_push_device(text) to authenticated;

revoke all on function core.unregister_push_device(text) from public;
revoke all on function core.unregister_push_device(text) from anon;
grant execute on function core.unregister_push_device(text) to authenticated;

-- --- Escalation ledger ------------------------------------------------------

create table meds.escalations (
  id uuid primary key default gen_random_uuid(),
  regimen_id uuid not null references meds.regimens (id) on delete cascade,
  patient_id uuid not null references core.patients (id) on delete cascade,
  scheduled_local timestamp without time zone not null,
  escalated_at timestamptz not null default now(),
  recipients integer not null default 0
    constraint escalations_recipients_non_negative check (recipients >= 0),
  constraint escalations_slot_key unique (regimen_id, scheduled_local)
);

comment on table meds.escalations is
  'One row per dose slot the server has escalated (D4 "server escalation"). The unique (regimen_id, scheduled_local) key is what makes the five-minute job idempotent. Written only by the escalate edge function through the service role; no API role has a privilege on it and it is not synced.';
comment on column meds.escalations.recipients is
  'How many users were notified for this slot (devices may be zero). Recorded even when zero so a slot is not retried every five minutes.';
comment on column meds.escalations.patient_id is
  'Denormalized for the API query and cascade; a trigger requires it to match the regimen''s medication.';

create index escalations_patient_id_idx on meds.escalations (patient_id);

create or replace function meds.check_escalation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_patient_id uuid;
begin
  select m.patient_id
    into v_patient_id
    from meds.regimens r
    join meds.medications m on m.id = r.medication_id
   where r.id = new.regimen_id;

  if v_patient_id is null then
    raise exception 'escalation regimen does not exist' using errcode = '23503';
  end if;
  if new.patient_id <> v_patient_id then
    raise exception 'escalation patient does not match the regimen'
      using errcode = '23514';
  end if;
  if date_trunc('minute', new.scheduled_local) <> new.scheduled_local then
    raise exception 'escalation slot must be a whole minute' using errcode = '23514';
  end if;
  return new;
end;
$$;

comment on function meds.check_escalation() is
  'BEFORE INSERT/UPDATE on meds.escalations: patient matches the regimen''s medication and scheduled_local is a whole minute (slot times are HH:MM).';

create trigger escalations_check
  before insert or update on meds.escalations
  for each row execute function meds.check_escalation();

revoke all on function meds.check_escalation() from public;

-- --- Effective reminder settings --------------------------------------------
-- Policy and primary caregiver are per patient, optionally overridden per
-- medication (docs/domain/care-circle.md "Reminder routing"). One view keeps
-- the coalesce in one place instead of every caller re-deriving it. The delays
-- have no medication override: they are patient settings.

create view meds.effective_reminder
with (security_invoker = true)
as
select
  m.id as medication_id,
  m.patient_id,
  p.circle_id,
  coalesce(m.reminder_policy, p.reminder_policy) as reminder_policy,
  coalesce(m.primary_caregiver_id, p.primary_caregiver_id) as primary_caregiver_id,
  p.escalation_delay_minutes,
  p.missed_window_minutes
from meds.medications m
join core.patients p on p.id = m.patient_id
where m.stopped_at is null;

comment on view meds.effective_reminder is
  'Active medications with their effective reminder settings: medication override else patient policy, medication primary else patient primary, plus the patient''s escalation delay and missed window. security_invoker so RLS still decides who reads what; server jobs use the service role.';

revoke all on meds.effective_reminder from public;
revoke all on meds.effective_reminder from anon;
revoke all on meds.effective_reminder from authenticated;
grant select on meds.effective_reminder to service_role;

-- --- Vault-configured HTTP plumbing -----------------------------------------
-- A trigger and a cron job both need "POST this JSON to that function URL with
-- this shared secret". Supabase's supported store for such values is Vault
-- (`vault.secrets`, read through `vault.decrypted_secrets`); `app.settings`
-- would need a database restart and is not editable from SQL on hosted
-- Supabase. The secret names are documented in backend/README.md:
--
--   escalate_url, dose_confirmed_url, push_cron_secret
--
-- Absent secrets, an error reading Vault, or a missing pg_net all make the
-- callers no-ops: the local stack and CI have none of them configured, and a
-- dose insert must never fail because a webhook is not set up.

create or replace function core.push_setting(p_name text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_value text;
begin
  select s.decrypted_secret
    into v_value
    from vault.decrypted_secrets s
   where s.name = p_name;

  return v_value;
exception
  when others then
    -- Vault may be absent (a bare Postgres) or the caller may not have the
    -- privilege even through this definer. Either way: unconfigured.
    return null;
end;
$$;

comment on function core.push_setting(text) is
  'Read one Vault secret by name, or null when Vault/secrets are unavailable. Security definer and executable by no API role; only the internal webhook helpers call it.';

create or replace function core.post_to_function(p_url_secret_name text, p_body jsonb)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_url text;
  v_secret text;
  v_request_id bigint;
begin
  v_url := core.push_setting(p_url_secret_name);
  v_secret := core.push_setting('push_cron_secret');

  if v_url is null or v_secret is null then
    return null;
  end if;

  -- pg_net is enabled by this migration when the image has it; on hosted
  -- Supabase a human enables it in the dashboard (see README).
  if to_regprocedure('net.http_post(text,jsonb,jsonb,jsonb,integer)') is null then
    return null;
  end if;

  select net.http_post(
    url := v_url,
    body := p_body,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'X-Cron-Secret', v_secret
    ),
    timeout_milliseconds := 5000
  )
  into v_request_id;

  return v_request_id;
exception
  when others then
    -- Never let a missing/broken webhook surface in the caller's transaction.
    return null;
end;
$$;

comment on function core.post_to_function(text, jsonb) is
  'Queue an asynchronous POST (pg_net) to the URL stored in Vault under p_url_secret_name, with the X-Cron-Secret header from the push_cron_secret Vault secret. Returns the pg_net request id, or null when unconfigured or pg_net is missing. No API role may execute it: it is a server-side SSRF-shaped helper.';

revoke all on function core.push_setting(text) from public;
revoke all on function core.push_setting(text) from anon;
revoke all on function core.push_setting(text) from authenticated;

revoke all on function core.post_to_function(text, jsonb) from public;
revoke all on function core.post_to_function(text, jsonb) from anon;
revoke all on function core.post_to_function(text, jsonb) from authenticated;

create extension if not exists pgcrypto;

-- `pg_net` installs its functions in the `net` schema on every Supabase image
-- (the `schema` option is ignored). A failure here - for example hosted
-- Postgres without the extension available - is caught so the migration still
-- applies and the webhook silently no-ops.
do $$
begin
  create extension if not exists pg_net;
exception
  when others then
    raise notice 'pg_net is not available (%); push webhooks stay disabled', sqlerrm;
end;
$$;

-- --- dose-confirmed trigger -------------------------------------------------
-- AFTER INSERT on the append-only dose log. Only the new row id crosses the
-- boundary; the function re-reads the row with the service role and decides who
-- to ping. A correction insert therefore clears the slot again, which is what
-- the phones need.

create or replace function meds.notify_dose_confirmed()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform core.post_to_function(
    'dose_confirmed_url',
    jsonb_build_object('dose_log_id', new.id)
  );
  return null;
exception
  when others then
    -- A webhook must never fail the dose entry: the insert is the record (D6),
    -- the push is only a convenience for the other phones.
    return null;
end;
$$;

comment on function meds.notify_dose_confirmed() is
  'AFTER INSERT on meds.dose_log: asks the dose-confirmed edge function to broadcast a data-only clear for the slot. No-op unless the Vault secrets are configured, and never raises.';

create trigger dose_log_notify_confirmed
  after insert on meds.dose_log
  for each row execute function meds.notify_dose_confirmed();

revoke all on function meds.notify_dose_confirmed() from public;

-- --- five-minute escalation tick --------------------------------------------

create or replace function core.enqueue_escalation_push()
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
begin
  return core.post_to_function(
    'escalate_url',
    jsonb_build_object('trigger', 'cron')
  );
end;
$$;

comment on function core.enqueue_escalation_push() is
  'Queues the five-minute POST to the escalate edge function. Called by pg_cron as the database owner; no API role may execute it. Returns null when unconfigured.';

revoke all on function core.enqueue_escalation_push() from public;
revoke all on function core.enqueue_escalation_push() from anon;
revoke all on function core.enqueue_escalation_push() from authenticated;

do $$
begin
  execute $schedule$select cron.schedule(
    'push_escalation_tick',
    '*/5 * * * *',
    'select core.enqueue_escalation_push()'
  )$schedule$;
exception
  when others then
    raise notice 'pg_cron is not available (%); the escalation tick is not scheduled', sqlerrm;
end;
$$;

-- --- RLS and privileges -----------------------------------------------------

alter table core.push_devices enable row level security;
alter table core.push_devices force row level security;
alter table meds.escalations enable row level security;
alter table meds.escalations force row level security;

-- push_devices: the owner, and only the owner. A co-member must never learn
-- another person's device token (security-and-privacy: device tokens are
-- personal data).
revoke all on core.push_devices from public;
revoke all on core.push_devices from anon;
grant select, insert, update, delete on core.push_devices to authenticated;

create policy push_devices_select_own
  on core.push_devices
  for select
  to authenticated
  using (user_id = (select auth.uid()));

create policy push_devices_insert_own
  on core.push_devices
  for insert
  to authenticated
  with check (user_id = (select auth.uid()));

create policy push_devices_update_own
  on core.push_devices
  for update
  to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

create policy push_devices_delete_own
  on core.push_devices
  for delete
  to authenticated
  using (user_id = (select auth.uid()));

-- escalations: server-only. No grant to any API role and no policies, so even
-- a future accidental grant would still see nothing without BYPASSRLS.
revoke all on meds.escalations from public;
revoke all on meds.escalations from anon;
revoke all on meds.escalations from authenticated;

-- The service role runs the two edge functions. It needs schema usage and
-- table privileges on exactly what they read (and the ledger they write);
-- nothing else in this migration widens its reach.
grant usage on schema core to service_role;
grant usage on schema meds to service_role;

grant select on core.push_devices to service_role;
grant select on core.memberships to service_role;
grant select on core.profiles to service_role;
grant select on core.patients to service_role;
grant select on meds.medications to service_role;
grant select on meds.regimens to service_role;
grant select on meds.dose_log to service_role;
grant select, insert, update on meds.escalations to service_role;

-- No new table joins the `powersync` publication: push_devices is per install
-- and the escalation ledger is server state (D3/D13 - the sync rules and the
-- publication are tested to be exactly the tables the app expects).
