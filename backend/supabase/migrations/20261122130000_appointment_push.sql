-- M6 appointment change push (docs/domain/appointments.md "Change push",
-- docs/decisions.md D27/D29).
--
-- One AFTER INSERT OR UPDATE trigger on `appointments.appointments` posts
-- `{appointment_id, actor}` to the `appointment-changed` edge function through
-- the M4 plumbing: the URL comes from Vault (`appointment_changed_url`), the
-- shared secret from each request header (`X-Cron-Secret`), and the post is
-- best effort exactly like `meds.notify_dose_confirmed`:
--
-- - only the appointment id and the actor cross the boundary; the function
--   re-reads the row with the service role and decides who to ping (D4/D27);
-- - an update is only interesting when `starts_local`, `time_zone`, `place`,
--   `companion_user_id` or `cancelled_at` changed (a notes or title edit must
--   not buzz every phone);
-- - an appointment that started more than a day ago is history, not news, and
--   queues nothing (evaluated in the appointment's own zone, like `starts_local`
--   itself);
-- - without Vault, pg_net or the secret the helper returns null and the DML
--   still succeeds - a webhook must never fail the change.
--
-- D5: the trigger lives in `appointments` (module-owned), but all of its
-- helpers are the existing `core` ones, so it adds nothing to the boundary.

create or replace function appointments.notify_appointment_changed()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid;
begin
  if tg_op = 'UPDATE' then
    if new.starts_local is not distinct from old.starts_local
       and new.time_zone is not distinct from old.time_zone
       and new.place is not distinct from old.place
       and new.companion_user_id is not distinct from old.companion_user_id
       and new.cancelled_at is not distinct from old.cancelled_at then
      return null;
    end if;
  end if;

  -- The wall-clock start is stored without a zone; "one day in the past" must be
  -- read in the appointment's zone, not the server's.
  if new.starts_local < ((now() at time zone new.time_zone) - interval '1 day') then
    return null;
  end if;

  v_actor := case
    when tg_op = 'INSERT' then new.created_by
    else new.updated_by
  end;

  perform core.post_to_function(
    'appointment_changed_url',
    jsonb_build_object('appointment_id', new.id, 'actor', v_actor)
  );

  return null;
exception
  when others then
    -- A webhook must never fail the row change: the row is the record, the push
    -- is only a courtesy for the other phones.
    return null;
end;
$$;

comment on function appointments.notify_appointment_changed() is
  'AFTER INSERT/UPDATE on appointments.appointments: asks the appointment-changed edge function to notify the circle''s owners and caregivers (except the actor). Skips cosmetic updates and appointments over a day old in their own zone. No-op unless the Vault secrets are configured, and never raises.';

create trigger appointments_notify_changed
  after insert or update on appointments.appointments
  for each row execute function appointments.notify_appointment_changed();

revoke all on function appointments.notify_appointment_changed() from public;

-- The edge function reads `appointments.appointments` (granted with the table
-- in 20261122120000_appointments_and_info.sql) and, through the service role,
-- `core.memberships`, `core.profiles` and `core.push_devices` (granted in
-- 20261120120000_push_and_escalation.sql). No new privilege is needed here; the
-- trigger runs as its definer and only calls `core.post_to_function`, which no
-- API role may execute.
