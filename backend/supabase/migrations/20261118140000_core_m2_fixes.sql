-- M2 follow-up fixes (build-plan §M2): removed-member cleanup, the D11
-- self-managed reminder default, security events for sign-ins/sessions, audit
-- retention, and the schema those need.
--
-- Why a second migration instead of editing 20261118120000_core_care_circle.sql:
-- that migration is already deployed. Everything here is `create or replace` or
-- additive, so applying it to an existing project is safe and re-running the
-- file against the same schema is a no-op apart from the cron job upsert.
--
-- References: docs/security-and-privacy.md §2 (authentication), §5 (audit) and
-- §7 (retention); docs/decisions.md D11 (self-managed reminders) and D14
-- (audit); docs/domain/care-circle.md.

-- --- 1. Removing a member clears their patient links ------------------------
--
-- Decided behaviour: "clear and require a choice". A removed member must not
-- keep a patient pointing at a non-member (check_patient would reject any later
-- write to that row, and reminders would target a user with no access), so the
-- same transaction that deletes the membership also clears:
--
--   * primary_caregiver_id where it is the removed user, and
--   * linked_user_id where it is the removed user,
--
-- for the patients of that circle only. No default replacement is chosen: the
-- app asks the owner to pick a new primary caregiver ("needs a primary
-- caregiver", D11), which is why the column is nullable.
--
-- These are ordinary UPDATEs on core.patients: they go through the table, are
-- written to core.audit_log by patients_audit, and pass core.check_patient()
-- because null is allowed for both columns.

create or replace function core.remove_member(p_membership_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_membership core.memberships%rowtype;
  v_owner_count integer;
begin
  select * into v_membership from core.memberships m where m.id = p_membership_id;

  if not found then
    raise exception 'membership not found' using errcode = 'P0002';
  end if;

  if not core.can_circle(v_membership.circle_id, 'manage') then
    raise exception 'only a circle owner can remove members' using errcode = '42501';
  end if;

  -- Serialise owner changes in this circle so two callers cannot each remove
  -- "the other" last owner.
  perform 1 from core.memberships m where m.circle_id = v_membership.circle_id for update;

  select count(*) into v_owner_count
  from core.memberships m
  where m.circle_id = v_membership.circle_id and m.role = 'owner';

  if v_membership.role = 'owner' and v_owner_count <= 1 then
    raise exception 'cannot remove the last owner of a circle' using errcode = '23514';
  end if;

  -- Clear both links in one statement per patient, so one audit row records
  -- the whole cleanup. Only this circle's patients are touched.
  update core.patients p
     set primary_caregiver_id = case
           when p.primary_caregiver_id = v_membership.user_id then null
           else p.primary_caregiver_id
         end,
         linked_user_id = case
           when p.linked_user_id = v_membership.user_id then null
           else p.linked_user_id
         end
   where p.circle_id = v_membership.circle_id
     and (p.primary_caregiver_id = v_membership.user_id
          or p.linked_user_id = v_membership.user_id);

  delete from core.memberships m where m.id = p_membership_id;
end;
$$;

comment on function core.remove_member(uuid) is
  'Owner-only. Removes a membership, refusing to remove the circle''s last owner. In the same transaction it clears primary_caregiver_id and linked_user_id on this circle''s patients where they point at the removed user (audited normal updates; the app then requires a new primary caregiver).';

-- --- 2. D11: self-managed patients default to their own account -------------
--
-- Defaults when creating a patient (docs/domain/care-circle.md, D11): patient
-- with a linked_user -> primary_with_escalation, primary = the linked user.
-- The primary is only filled when the linked user is an owner or caregiver;
-- a viewer stays read-only, so the column is left null for check_patient to
-- accept.
--
-- Trigger order matters: Postgres fires same-timing BEFORE triggers in name
-- order, and this name ("patients_apply_primary_default") sorts before
-- "patients_check", so the default is set *before* core.check_patient()
-- validates the role. Renaming either trigger can silently break that.

create or replace function core.apply_primary_caregiver_default()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_role core.member_role;
begin
  -- Only the D11 case: a linked patient, no primary chosen, self-managed.
  if new.linked_user_id is null
     or new.primary_caregiver_id is not null
     or new.reminder_policy <> 'primary_with_escalation' then
    return new;
  end if;

  select m.role into v_role
  from core.memberships m
  where m.circle_id = new.circle_id and m.user_id = new.linked_user_id;

  if v_role in ('owner', 'caregiver') then
    new.primary_caregiver_id := new.linked_user_id;
  end if;

  return new;
end;
$$;

comment on function core.apply_primary_caregiver_default() is
  'BEFORE INSERT on core.patients (runs before patients_check, by name): D11 default - when a linked user is set, the primary is empty and the policy is primary_with_escalation, set the primary to the linked user if they are an owner or caregiver. A viewer is left null.';

create trigger patients_apply_primary_default
  before insert on core.patients
  for each row execute function core.apply_primary_caregiver_default();

revoke all on function core.apply_primary_caregiver_default() from public;

-- --- 3. Security events -----------------------------------------------------
--
-- docs/security-and-privacy.md §5: logins, failed logins, MFA changes,
-- invitations, membership/role changes, data exports, deletions and session
-- revocations.
--
--   * Invitations and membership/role changes are already written by the row
--     triggers from 20261118120000; they are classified as `security` below so
--     the 1-year retention applies to them.
--   * Sign-ins and session endings are captured from auth.sessions: on the
--     local stack GoTrue inserts one auth.sessions row on sign-in and deletes
--     it on sign-out (verified by probing the local Auth API; refresh-token
--     rotation only updates the row). A trigger there is server-side and needs
--     no app change, unlike a client-side "I signed in" call.
--
-- What we deliberately do NOT store: access/refresh tokens, the session id,
-- user agent or IP address. Only user, event, time. The spec asks for logins
-- and revocations, not session forensics.
--
-- Failed logins and MFA changes happen inside Google or GoTrue, before our
-- database is involved (Google is the only sign-in provider, D12), so they
-- are covered by Supabase Auth logs, not by this table. Locally a wrong
-- password writes no auth.audit_log_entries row and no auth.sessions row, so
-- there is nothing in Postgres to trigger on and we do not fake it. Supabase's
-- auth audit logs have a short, provider-controlled retention (and on hosted
-- projects writing them to auth.audit_log_entries is a dashboard toggle, often
-- off), so they answer "what happened recently", while successful sign-ins are
-- kept here for the 1-year audit retention.
--
-- Hosted Supabase: triggers on auth tables are allowed. Supabase's "Restricting
-- access on auth, storage and realtime schemas" changelog (21 April 2025)
-- lists auth.sessions among the tables where creating database triggers is
-- still permitted (alongside auth.users, auth.refresh_tokens and
-- auth.audit_log_entries); the restriction removed DDL on other objects, not
-- triggers. The local stack confirms the migration role holds TRIGGER on
-- auth.sessions (`has_table_privilege('postgres','auth.sessions','trigger')`).
-- Sanity-check the same query on the hosted project before pushing.

alter table core.audit_log
  add column event_kind text not null default 'change'
    constraint audit_log_event_kind_check
    check (event_kind in ('change', 'security'));

comment on column core.audit_log.event_kind is
  'change = health-data history (retained 6 years); security = authentication/membership/invitation events (retained 1 year). See core.purge_audit_log().';

create index audit_log_event_kind_occurred_at_idx
  on core.audit_log (event_kind, occurred_at);

-- Classification for the row-trigger tables. Invitations and memberships are
-- listed as security events in §5, so their rows live for 1 year; every other
-- audited table is change history (6 years).
create or replace function core.audit_row_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old jsonb;
  v_new jsonb;
  v_record_id uuid;
  v_circle_id uuid;
  v_patient_id uuid;
  v_event_kind text;
begin
  if tg_op = 'INSERT' then
    v_new := to_jsonb(new);
    v_record_id := new.id;
  elsif tg_op = 'UPDATE' then
    v_old := to_jsonb(old);
    v_new := to_jsonb(new);
    v_record_id := new.id;
  else
    v_old := to_jsonb(old);
    v_record_id := old.id;
  end if;

  -- Never log the invitation secret (only its hash is stored, but it is still
  -- a bearer credential for a future invitation lookup).
  if tg_table_name = 'invitations' then
    v_old := v_old - 'token_hash';
    v_new := v_new - 'token_hash';
  end if;

  -- Derive the circle the change belongs to.
  if tg_table_name = 'care_circles' then
    v_circle_id := v_record_id;
  elsif tg_table_name = 'patient_access_overrides' then
    v_patient_id := coalesce((v_new ->> 'patient_id')::uuid, (v_old ->> 'patient_id')::uuid);
    select p.circle_id into v_circle_id from core.patients p where p.id = v_patient_id;
  else
    v_circle_id := coalesce((v_new ->> 'circle_id')::uuid, (v_old ->> 'circle_id')::uuid);
  end if;

  if tg_table_name = 'patients' then
    v_patient_id := v_record_id;
  end if;

  v_event_kind := case
    when tg_table_name in ('invitations', 'memberships') then 'security'
    else 'change'
  end;

  insert into core.audit_log (
    actor_id, circle_id, patient_id, table_name, record_id, action,
    old_data, new_data, event_kind
  )
  values (
    (select auth.uid()), v_circle_id, v_patient_id, tg_table_name, v_record_id,
    lower(tg_op), v_old, v_new, v_event_kind
  );

  return null;
end;
$$;

comment on function core.audit_row_change() is
  'Generic AFTER INSERT/UPDATE/DELETE trigger writing one core.audit_log row with actor (auth.uid()), circle, patient, before/after JSON. token_hash is removed from invitation rows. Invitation and membership rows are event_kind=security (1-year retention); every other table is change history (6 years).';

-- Sign-in / session-ended events from GoTrue's own table. SECURITY DEFINER so
-- the insert works while GoTrue runs as supabase_auth_admin, and defensive: an
-- audit failure must never block authentication, so exceptions are swallowed.
create or replace function core.audit_session_event()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid;
  v_action text;
  v_occurred_at timestamptz;
begin
  if tg_op = 'INSERT' then
    v_user_id := new.user_id;
    v_action := 'auth.sign_in';
    v_occurred_at := coalesce(new.created_at, now());
  else
    v_user_id := old.user_id;
    v_action := 'auth.session_ended';
    v_occurred_at := now();
  end if;

  begin
    insert into core.audit_log (
      occurred_at, actor_id, circle_id, patient_id, table_name, record_id,
      action, old_data, new_data, event_kind
    )
    values (
      v_occurred_at, v_user_id, null, null, 'auth.sessions', null,
      v_action, null, null, 'security'
    );
  exception when others then
    -- Never let auditing break sign-in or sign-out.
    null;
  end;

  return null;
end;
$$;

comment on function core.audit_session_event() is
  'AFTER INSERT/DELETE on auth.sessions: one event_kind=security audit row, action auth.sign_in or auth.session_ended, actor the user, time from the session. No session id, tokens, user agent or IP. Catches sign-in, sign-out and admin/remote session revocation; exceptions are swallowed so auth never fails because of the log.';

create trigger auth_sessions_audit
  after insert or delete on auth.sessions
  for each row execute function core.audit_session_event();

revoke all on function core.audit_session_event() from public;

comment on table core.audit_log is
  'Append-only change log (security-and-privacy §5, D14). Written by core.audit_row_change() triggers and, for sign-ins/session endings, core.audit_session_event(). No inserts/updates/deletes for clients; a guard trigger refuses UPDATE/DELETE/TRUNCATE for every role, including the table owner, except core.purge_audit_log(). event_kind separates 6-year change history from 1-year security events. Owners of a circle may read its rows; security events with no circle (auth.sessions) are therefore not visible to any app user yet (the Activity screen is M7). Not synced by PowerSync.';

-- --- 4. Retention -----------------------------------------------------------
--
-- docs/security-and-privacy.md §7: change history 6 years, security events
-- 1 year. The purge is a security-definer function owned by the migration role
-- and not executable by any API role; pg_cron calls it daily.

create or replace function core.audit_log_is_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- One controlled exception: core.purge_audit_log() sets this transaction-
  -- local flag and runs as the table owner. A client that sets the flag itself
  -- still has no DELETE privilege on the table, so the privilege check fires
  -- first; the flag alone does nothing. RETURN OLD is required: a BEFORE
  -- DELETE trigger returning NULL would silently skip the row.
  if tg_op = 'DELETE'
     and coalesce(current_setting('core.audit_log_purge', true), '') = 'on'
     and current_user = (
       select pg_get_userbyid(c.relowner)
       from pg_class c
       where c.oid = 'core.audit_log'::regclass
     ) then
    return old;
  end if;

  raise exception 'core.audit_log is append-only: % is not allowed', tg_op
    using errcode = '42501';
end;
$$;

comment on function core.audit_log_is_append_only() is
  'BEFORE UPDATE/DELETE/TRUNCATE on core.audit_log: raises for every role, including the table owner. The only allowed DELETE is inside core.purge_audit_log() (transaction-local core.audit_log_purge flag plus table-owner role).';

create or replace function core.purge_audit_log()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_change integer;
  v_security integer;
begin
  perform set_config('core.audit_log_purge', 'on', true);

  delete from core.audit_log
   where event_kind = 'change'
     and occurred_at < now() - interval '6 years';
  get diagnostics v_change = row_count;

  delete from core.audit_log
   where event_kind = 'security'
     and occurred_at < now() - interval '1 year';
  get diagnostics v_security = row_count;

  perform set_config('core.audit_log_purge', 'off', true);

  return v_change + v_security;
end;
$$;

comment on function core.purge_audit_log() is
  'Retention job (security-and-privacy §7): deletes change history older than 6 years and security events older than 1 year, returning the number of rows removed. Security definer and not granted to any API role; scheduled daily by pg_cron.';

-- Owner-only: no API role may call or replace it. The revoke from PUBLIC also
-- removes service_role's implicit execute.
revoke all on function core.purge_audit_log() from public;
revoke all on function core.purge_audit_log() from anon;
revoke all on function core.purge_audit_log() from authenticated;

-- pg_cron is available in the local Supabase Postgres image (checked with
-- pg_available_extensions; it is also in shared_preload_libraries) and on
-- hosted Supabase, where the extension is normally already installed. A daily
-- 03:23 UTC run. cron.schedule with a job name upserts, so re-applying this
-- migration does not duplicate the job.
create extension if not exists pg_cron;

do $$
begin
  execute $schedule$select cron.schedule(
    'core_purge_audit_log',
    '23 3 * * *',
    'select core.purge_audit_log()'
  )$schedule$;
end;
$$;
