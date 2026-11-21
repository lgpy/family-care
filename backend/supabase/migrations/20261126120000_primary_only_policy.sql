-- Third reminder policy: primary_only (docs/domain/care-circle.md "Reminder
-- routing").
--
-- Only the primary caregiver is reminded, with no escalation to the others and
-- low-stock pushes going to the primary alone. It exists for a self-managed
-- patient (D11) in a shared family circle: the patient takes their own
-- medicines, so the rest of the family is not pinged.
--
-- The self-managed default is the same as primary_with_escalation: a linked
-- patient with no primary chosen gets the linked user when they are an owner or
-- caregiver (a viewer stays null). The new enum value is compared as text
-- because a value added in this transaction cannot be used as a literal yet.

alter type core.reminder_policy add value if not exists 'primary_only';

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
     or new.reminder_policy::text not in ('primary_with_escalation', 'primary_only') then
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
  'BEFORE INSERT on core.patients (runs before patients_check, by name): D11 default - when a linked user is set, the primary is empty and the policy is primary_with_escalation or primary_only, set the primary to the linked user if they are an owner or caregiver. A viewer is left null.';

revoke all on function core.apply_primary_caregiver_default() from public;
