-- Removing a member who is linked to an archived patient
-- (docs/domain/care-circle.md D24, docs/domain/patient-lifecycle.md D30).
--
-- core.remove_member (20261118140000_core_m2_fixes.sql) clears the removed
-- user's primary_caregiver_id / linked_user_id on the circle's patients before
-- deleting the membership. core.check_patient_archive
-- (20261123120000_patient_lifecycle.sql) refuses every change to an archived
-- patient except clearing archived_at, so when the removed member was linked
-- to an archived patient the whole RPC rolled back and the member kept their
-- access. The owner's only way out was unarchive, remove, re-archive.
--
-- The fix lets exactly that cleanup through, and nothing else:
--
--   * core.member_removal_unlinks is a server-only table. remove_member writes
--     one row (current transaction id, circle, removed user) before its patient
--     update and deletes it right after, so the row exists only inside that
--     one RPC call. No API role has any privilege on it and it has no policy,
--     so a client cannot create the row; rows of an aborted transaction vanish
--     with it, and xid8 transaction ids are never reused.
--   * check_patient_archive accepts an update of an archived patient whose only
--     changes are primary_caregiver_id and/or linked_user_id going from a user
--     to null, and only when that row names the current transaction, this
--     patient's circle and that user. Any other column, a non-null new value or
--     a missing row still raises 42501 'patient is archived'.
--
-- Why not a session setting or current_user: set_config/current_setting values
-- can be set by anyone with SQL access (the audit finding on
-- familycare.erasing_patient), and every trigger function on core.patients is
-- SECURITY DEFINER, so current_user inside them is always the function owner,
-- whoever called. A table row only this migration role can write is neither.
--
-- The other triggers on core.patients are unaffected: patients_check
-- (core.check_patient) accepts null links; patients_set_updated_at bumps
-- updated_at, which the archive check ignores as before; patients_audit writes
-- the usual audit row, so the cleanup of an archived patient is audited like
-- that of an active one.

-- --- 1. Server-only marker table --------------------------------------------

create table core.member_removal_unlinks (
  txid xid8 not null default pg_current_xact_id(),
  circle_id uuid not null,
  user_id uuid not null,
  primary key (txid, circle_id, user_id)
);

comment on table core.member_removal_unlinks is
  'Server-only, transient: core.remove_member() inserts (current transaction, circle, removed user) before clearing that user''s patient links and deletes it right after. core.check_patient_archive() reads it to let only that cleanup through on archived patients. No API role has a privilege and there is no policy; normally empty.';

alter table core.member_removal_unlinks enable row level security;
alter table core.member_removal_unlinks force row level security;

revoke all on core.member_removal_unlinks from public;
revoke all on core.member_removal_unlinks from anon;
revoke all on core.member_removal_unlinks from authenticated;
revoke all on core.member_removal_unlinks from service_role;

-- --- 2. Archive check: let the removal cleanup through ----------------------
-- Final definition before this: 20261123120000_patient_lifecycle.sql. Only the
-- "still archived" branch changes.

create or replace function core.check_patient_archive()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.archived_at is distinct from old.archived_at
     and core.circle_role(new.circle_id) is distinct from 'owner' then
    raise exception 'only an owner can archive or unarchive a patient'
      using errcode = '42501';
  end if;

  if old.archived_at is not null then
    if new.archived_at is not null then
      -- Still archived: nothing may change (updated_at is set by the later
      -- trigger and is not a client field), except core.remove_member()
      -- clearing the removed user's links, which it marks in
      -- core.member_removal_unlinks for its own transaction only.
      if (to_jsonb(new) - 'updated_at' - 'primary_caregiver_id' - 'linked_user_id')
           is distinct from
         (to_jsonb(old) - 'updated_at' - 'primary_caregiver_id' - 'linked_user_id')
         or (new.primary_caregiver_id is distinct from old.primary_caregiver_id
             and not (new.primary_caregiver_id is null
                      and exists (
                        select 1 from core.member_removal_unlinks u
                        where u.txid = pg_current_xact_id()
                          and u.circle_id = old.circle_id
                          and u.user_id = old.primary_caregiver_id)))
         or (new.linked_user_id is distinct from old.linked_user_id
             and not (new.linked_user_id is null
                      and exists (
                        select 1 from core.member_removal_unlinks u
                        where u.txid = pg_current_xact_id()
                          and u.circle_id = old.circle_id
                          and u.user_id = old.linked_user_id))) then
        raise exception 'patient is archived' using errcode = '42501';
      end if;
    else
      -- The one allowed client update: clear archived_at, and change nothing
      -- else.
      if (to_jsonb(new) - 'archived_at' - 'updated_at')
         is distinct from (to_jsonb(old) - 'archived_at' - 'updated_at') then
        raise exception 'patient is archived' using errcode = '42501';
      end if;
    end if;
  end if;

  return new;
end;
$$;

comment on function core.check_patient_archive() is
  'BEFORE UPDATE on core.patients: only an owner changes archived_at; while archived the only permitted client update is clearing archived_at. core.remove_member() may also clear the removed user''s primary_caregiver_id/linked_user_id, proven by its own row in core.member_removal_unlinks.';

revoke all on function core.check_patient_archive() from public;

-- --- 3. remove_member marks its cleanup -------------------------------------
-- Final definition before this: 20261118140000_core_m2_fixes.sql. Unchanged
-- apart from the marker row around the patient update.

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

  -- Lets core.check_patient_archive() accept clearing this user's links on
  -- archived patients of this circle, for this transaction only.
  insert into core.member_removal_unlinks (circle_id, user_id)
  values (v_membership.circle_id, v_membership.user_id)
  on conflict do nothing;

  -- Clear both links in one statement per patient, so one audit row records
  -- the whole cleanup. Only this circle's patients are touched, archived or
  -- not.
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

  delete from core.member_removal_unlinks u
   where u.txid = pg_current_xact_id()
     and u.circle_id = v_membership.circle_id
     and u.user_id = v_membership.user_id;

  delete from core.memberships m where m.id = p_membership_id;
end;
$$;

comment on function core.remove_member(uuid) is
  'Owner-only. Removes a membership, refusing to remove the circle''s last owner. In the same transaction it clears primary_caregiver_id and linked_user_id on this circle''s patients, archived ones included, where they point at the removed user (audited normal updates; the app then requires a new primary caregiver).';

revoke all on function core.remove_member(uuid) from public;
revoke all on function core.remove_member(uuid) from anon;
grant execute on function core.remove_member(uuid) to authenticated;
