-- M5 stock and shopping (build-plan §M5, docs/domain/medications.md "Stock" and
-- "Refills and shopping list", D6/D9/D13/D14/D22/D26/D27/D28).
--
-- Two tables and one daily job:
--
-- 1. `meds.stock_movements` - the append-only stock ledger: `count` (a
--    stocktake, "there are N now", also the opening balance), `refill` (+N) and
--    `discard` (-N, with a reason). There is deliberately no "dose" movement
--    (D28): the dose log already is an append-only ledger (D6), so current stock
--    = the latest count + refills - discards after it - the effective `taken`
--    quantities in `meds.dose_log` after it. The exact rules are written out in
--    shared/test-vectors/README.md and pinned by shared/test-vectors/stock.json;
--    they are implemented in Kotlin (app) and TypeScript (edge functions).
-- 2. `meds.stock_alerts` - the server's "this medication is already alerted"
--    ledger, one row per medication while it is low. Written only by the
--    stock-low edge function through the service role, like `meds.escalations`
--    (D27/D28); the row is removed when the medication is no longer low, which
--    re-arms the alert.
-- 3. The daily push plumbing: the function URL and the shared cron secret live
--    in Vault (`stock_low_url`, `push_cron_secret`), `core.enqueue_stock_low_push()`
--    posts through `core.post_to_function` and pg_cron runs it every morning.
--    Everything is a no-op when the secrets are absent, which is the local/CI
--    default: `db reset` and `test db` need neither the edge runtime nor network.
--
-- Conventions are the M3/M4 ones: RLS enabled *and forced*, nothing granted to
-- anon/PUBLIC, policies scoped `to authenticated` through
-- meds.patient_of_medication() + core.can(), `security definer` +
-- `set search_path = ''` on every function, audit rows in core.audit_log, and
-- the idempotent `powersync` publication block.

-- --- Types ------------------------------------------------------------------

create type meds.stock_movement_kind as enum ('count', 'refill', 'discard');

comment on type meds.stock_movement_kind is
  'Stock ledger kinds (D28): count = stocktake/baseline ("there are N now", may be 0), refill = +N, discard = -N with a reason. A dose is not a movement; taken doses come from meds.dose_log.';

-- Types are usable by PUBLIC by default, so revoke explicitly like the M3 enums.
revoke all on type meds.stock_movement_kind from public;
revoke all on type meds.stock_movement_kind from anon;
grant usage on type meds.stock_movement_kind to authenticated;

-- --- Tables -----------------------------------------------------------------

create table meds.stock_movements (
  id uuid primary key default gen_random_uuid(),
  medication_id uuid not null references meds.medications (id) on delete cascade,
  kind meds.stock_movement_kind not null,
  -- The stock unit is free text on the medication ("tablet", "ml", "pen"), so
  -- the ledger keeps three decimals like the regimen quantities.
  quantity numeric(9, 3) not null
    constraint stock_movements_quantity_range check (quantity between 0 and 100000),
  reason text
    constraint stock_movements_reason_length
    check (reason is null or char_length(reason) <= 500),
  occurred_at timestamptz not null default now(),
  created_by uuid default auth.uid() references auth.users (id) on delete set null,
  created_at timestamptz not null default now(),

  -- A count may be zero ("the box is empty"); a refill or discard of nothing is
  -- a mistake, not a record.
  constraint stock_movements_quantity_by_kind check (
    (kind = 'count' and quantity >= 0)
    or (kind in ('refill', 'discard') and quantity > 0)
  ),

  -- Discarding stock always says why (expired, lost, spilled).
  constraint stock_movements_discard_reason check (
    kind <> 'discard' or (reason is not null and btrim(reason) <> '')
  )
);

comment on table meds.stock_movements is
  'Append-only stock ledger (D6/D28): counts, refills and discards. No UPDATE/DELETE grant; a BEFORE UPDATE trigger refuses updates for every role (cascades still delete). Current stock is derived, never stored.';
comment on column meds.stock_movements.kind is
  'count = stocktake and baseline, refill = stock came in, discard = stock went out.';
comment on column meds.stock_movements.quantity is
  'In the medication''s stock_unit. A count may be 0; refill and discard must be > 0. At most 100000.';
comment on column meds.stock_movements.reason is
  'Why stock left (discard) or an optional note. Required and non-blank for discard, optional otherwise; at most 500 characters.';
comment on column meds.stock_movements.occurred_at is
  'When the movement happened, as recorded by the phone. The latest count''s instant is the baseline: movements at or before it are already inside the count.';
comment on column meds.stock_movements.created_by is
  'The signed-in user who recorded the movement; a trigger requires it to equal auth.uid() when the caller is authenticated. No foreign-key cascade surprises: the row survives account deletion.';

create index stock_movements_medication_occurred_idx
  on meds.stock_movements (medication_id, occurred_at);

-- One row per medication while a low-stock alert has been sent. It is what
-- makes "one push per low episode" idempotent (D28).
create table meds.stock_alerts (
  medication_id uuid primary key references meds.medications (id) on delete cascade,
  alerted_at timestamptz not null default now(),
  recipients integer not null default 0
    constraint stock_alerts_recipients_non_negative check (recipients >= 0)
);

comment on table meds.stock_alerts is
  'Server-only ledger of medications already alerted as low (D28). One row per medication: the daily job inserts it after the push and deletes it once the medication is no longer low (or is stopped/deleted, which re-arms the alert). Written only by the stock-low edge function through the service role; no API role has a privilege and it is not synced.';
comment on column meds.stock_alerts.recipients is
  'How many users were notified for this low episode (devices may be zero). Recorded even when zero so the medication is not retried every day.';

-- --- Movement integrity (append-only, caller identity) ----------------------

create or replace function meds.check_stock_movement()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := (select auth.uid());
begin
  -- Authenticated clients record their own movements. Server jobs (no JWT,
  -- hence null auth.uid()) may insert a row with any creator.
  if v_user is not null and new.created_by is distinct from v_user then
    raise exception 'created_by must be the signed-in user'
      using errcode = '42501';
  end if;

  return new;
end;
$$;

comment on function meds.check_stock_movement() is
  'BEFORE INSERT on meds.stock_movements: created_by is the caller when the caller is authenticated (no spoofing), free for server jobs.';

create trigger stock_movements_check
  before insert on meds.stock_movements
  for each row execute function meds.check_stock_movement();

create or replace function meds.stock_movements_is_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'meds.stock_movements is append-only: % is not allowed', tg_op
    using errcode = '42501';
end;
$$;

comment on function meds.stock_movements_is_append_only() is
  'BEFORE UPDATE on meds.stock_movements: always raises, for every role. DELETE is refused by the missing grant for authenticated; cascades from medication deletion (and GDPR erasure) still work. A wrong movement is corrected by a new count, not an edit.';

create trigger stock_movements_no_update
  before update on meds.stock_movements
  for each row execute function meds.stock_movements_is_append_only();

revoke all on function meds.check_stock_movement() from public;
revoke all on function meds.stock_movements_is_append_only() from public;

-- --- Audit ------------------------------------------------------------------
-- meds.audit_row_change() already derives the patient (and circle) from a
-- medication_id column, so the stock ledger reuses it unchanged (D14).

create trigger stock_movements_audit
  after insert or update or delete on meds.stock_movements
  for each row execute function meds.audit_row_change();

-- --- RLS and privileges: deny by default -------------------------------------

alter table meds.stock_movements enable row level security;
alter table meds.stock_movements force row level security;
alter table meds.stock_alerts enable row level security;
alter table meds.stock_alerts force row level security;

-- stock_movements: append-only. SELECT + INSERT only; no UPDATE/DELETE grant at
-- all, mirroring the dose log.
revoke all on meds.stock_movements from public;
revoke all on meds.stock_movements from anon;
grant select, insert on meds.stock_movements to authenticated;

create policy stock_movements_select_can_read
  on meds.stock_movements
  for select
  to authenticated
  using (core.can(meds.patient_of_medication(medication_id), 'read'));

create policy stock_movements_insert_can_write
  on meds.stock_movements
  for insert
  to authenticated
  with check (core.can(meds.patient_of_medication(medication_id), 'write'));

-- stock_alerts: server-only. No grant to any API role and no policies, so even
-- a future accidental grant would still see nothing without BYPASSRLS.
revoke all on meds.stock_alerts from public;
revoke all on meds.stock_alerts from anon;
revoke all on meds.stock_alerts from authenticated;

-- The service role runs the stock-low function. It needs the movement ledger
-- (read) and the alert ledger (read/insert/delete); everything else it reads was
-- already granted by the M4 migration.
grant select on meds.stock_movements to service_role;
grant select, insert, delete on meds.stock_alerts to service_role;

-- --- Replication ------------------------------------------------------------
-- The phone derives stock locally, so the ledger syncs with the medication.
-- Same idempotent form as M1-M3 so a re-run is safe. `stock_alerts` stays
-- server-side.

do $$
begin
  if not exists (
    select 1
    from pg_publication_tables
    where pubname = 'powersync'
      and schemaname = 'meds'
      and tablename = 'stock_movements'
  ) then
    alter publication powersync add table meds.stock_movements;
  end if;
end;
$$;

-- --- Daily low-stock push ----------------------------------------------------
-- Same Vault/pg_net/pg_cron shape as the M4 escalation tick (D27). The URL is
-- `stock_low_url`; the secret is the shared `push_cron_secret`. Absent secrets,
-- an error reading Vault, or a missing pg_net/pg_cron make this a no-op.

create or replace function core.enqueue_stock_low_push()
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
begin
  return core.post_to_function(
    'stock_low_url',
    jsonb_build_object('trigger', 'cron')
  );
end;
$$;

comment on function core.enqueue_stock_low_push() is
  'Queues the daily POST to the stock-low edge function. Called by pg_cron as the database owner; no API role may execute it. Returns null when unconfigured.';

revoke all on function core.enqueue_stock_low_push() from public;
revoke all on function core.enqueue_stock_low_push() from anon;
revoke all on function core.enqueue_stock_low_push() from authenticated;

do $$
begin
  execute $schedule$select cron.schedule(
    'stock_low_daily',
    '0 9 * * *',
    'select core.enqueue_stock_low_push()'
  )$schedule$;
exception
  when others then
    raise notice 'pg_cron is not available (%); the stock-low tick is not scheduled', sqlerrm;
end;
$$;
