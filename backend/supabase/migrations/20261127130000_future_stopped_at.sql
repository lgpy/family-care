-- meds.medications.stopped_at may be in the future.
--
-- The app used to write stopped_at = now() when a medication was stopped, even
-- with a later last day, which ended its remaining reminders early. From now
-- on stopped_at is the instant the stop takes effect: a medication is stopped
-- only when stopped_at is not null and stopped_at <= now(), and stays active
-- (reminders, escalation) until then.
--
-- Server objects that test stopped_at: only meds.effective_reminder (final
-- definition in 20261123120000_patient_lifecycle.sql). No check constraint or
-- trigger on meds.medications looks at stopped_at, so a future value is
-- already accepted. The stock-low edge function filters stopped_at with its
-- own query and is changed separately.

create or replace view meds.effective_reminder
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
where (m.stopped_at is null or m.stopped_at > now())
  and p.archived_at is null;

comment on view meds.effective_reminder is
  'Active medications (stopped_at null or still in the future) of non-archived patients with their effective reminder settings: medication override else patient policy, medication primary else patient primary, plus the patient''s escalation delay and missed window. security_invoker so RLS still decides who reads what; server jobs use the service role.';

revoke all on meds.effective_reminder from public;
revoke all on meds.effective_reminder from anon;
revoke all on meds.effective_reminder from authenticated;
grant select on meds.effective_reminder to service_role;

comment on column meds.medications.stopped_at is
  'When the stop takes effect; may be in the future (the last day of treatment). Null or a future instant means active; stopped only once stopped_at <= now(). History (regimens, dose log) is kept.';
