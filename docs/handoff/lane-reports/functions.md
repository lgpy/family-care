# Edge functions lane: report

Branch `fix/edge-functions-push-bugs`, head `23342bf`. Five commits on top of `main` (`ce54165`), one per fix. Nothing pushed, deployed, or called remotely.

## Final checks (run from `backend/supabase/functions`, on head 23342bf)
- `npx --no-install deno test --allow-read` → `ok | 109 passed | 0 failed` (was 91 before my work: 8 delivery, 7 paging, 6 stockAlerts, 5 vector cases; the one escalation test gained assertions)
- `npx --no-install deno check escalate/index.ts stock-low/index.ts appointment-changed/index.ts dose-confirmed/index.ts` → all four `Check` lines, no errors
- `npx --no-install deno lint` → `Checked 24 files`, no problems

## Fix 1: ledger row only when delivered (commit 82b00ca)
- Confirmed by reading. Step 7 of `escalate/index.ts` upserted all of `plan.escalations` whatever `sender.send` returned; `stock-low/index.ts` did the same with `stock_alerts`.
- New `_shared/delivery.ts` (`rowsToRecord`, `SendAttempt`): a slot/episode is recorded if at least one send was `sent`, or none of its sends was `retry`/`failed` (no recipients, no devices, only dead tokens). Dead plus retryable still retries.
- `_shared/escalation.ts`: notifications now carry `regimen_id` (not part of the push `data`) and `escalationSlotKey()` joins notification and ledger row for the same slot.
- `escalate/index.ts` step 6 collects attempts, step 7 upserts `rowsToRecord(...)`. `stock-low/index.ts` same, keyed by `medication_id`. Both responses gain `to_retry`; `escalations`/`alerts` now count the rows written.
- Tests: `_shared/delivery_test.ts` (8 cases). Written first; failed on the missing module. Also the escalation test asserts `regimen_id`/`escalationSlotKey` (failed type-check before).
- The entrypoints themselves are still untested (no harness); their logic is only the collection of attempts and the call to `rowsToRecord`.

## Fix 2: paging and bounded ledger read (commit 2b87323)
- Confirmed by reading: the `escalations` read had no bound and no paging; `config.toml` has `max_rows = 1000`.
- New `_shared/paging.ts`: `fetchAllPages(fetchPage, pageSize = 1000)` loops `.range()` until a short page, throws the first error. Callers supply a stable unique `.order()`.
- `escalate/index.ts`: ledger read now has `.gte('scheduled_local', lowerBound)`; effective_reminder (order `medication_id`), regimens (`id`), dose_log (`id`), escalations (`regimen_id`, `scheduled_local`), memberships (`circle_id`, `user_id`), profiles (`id`), push_devices (`fcm_token`) are all paged.
- `stock-low/index.ts`: its private `readEntries` loop now uses the helper; medications, stock_alerts, patients, regimens, stock_movements, memberships, profiles, push_devices are paged too (the first three weren't in your list but can also exceed 1000).
- Tests: `_shared/paging_test.ts` (7 cases: short page, empty, 2500 rows, exact multiple, custom size, null page, error mid-way). I wrote the test and the helper together, then proved the test bites by temporarily making the helper stop after one page: 4 of 7 failed; restored.
- Not done: PostgREST `.in(...)` lists with very many ids can exceed URL length limits; I did not chunk them.

## Fix 3: future `stopped_at` (commit 092af45)
- `stock-low/index.ts`: `.is('stopped_at', null)` replaced by `.or('stopped_at.is.null,stopped_at.gt.<now>')`, and `stopped_at` is now selected.
- `_shared/stockAlerts.ts`: `ActiveStockMedication.stopped_at?`, and `planStockAlerts` also skips a medication with `stopped_at <= now` (so it falls into `cleared`). Comment at the old line 63 (`StockAlertInput.medications`) and the header rules updated.
- `escalate` has no stopped check of its own; it relies on the `meds.effective_reminder` view, which still reads `where m.stopped_at is null` in `20261120120000_push_and_escalation.sql:245`. **Outside my files:** the SQL lane must change that to `stopped_at is null or stopped_at > now()`, or a medication with a future stop will not be escalated before its last day.
- Tests (failed first on type-check): future `stopped_at` stays active; `stopped_at` equal to or before `now` is skipped and its row cleared; null is active. The `.or()` filter itself is only covered by type-check, not a test.

## Fix 4: untracked medication (commit 277e062)
- Confirmed by reading: a medication with a regimen and no movements got baseline 0, `daysRemaining` 0, `low` true. The app's `StockOverview.tracked` is `movements.isNotEmpty()`.
- `_shared/stockAlerts.ts` `planStockAlerts`: skips a medication with no movement of any kind (count, refill or discard); it still reaches `cleared` if it had a row. `stockSummary` and the vector `no-count-goes-negative-and-is-low` untouched.
- Tests: never counted → not low, row cleared (failed before); a refill or discard alone is tracked (passed before, pins the boundary); another medication's movements don't count (failed before). No existing test pinned the old alert.

## Fix 5: exact days remaining (commit 23342bf)
- Reproduced: the four given cases plus the weekday case all failed before (got 2, 6, 1, 2, 6 against 3, 7, 2, 3, 7).
- `_shared/stock.ts`: quantities are converted to whole thousandths (`Math.round(q * 1000)`; schema allows at most 3 decimals: regimen quantity `<= 3 decimals` check, `dose_log.actual_quantity numeric(7,3)`, `stock_movements.quantity numeric(9,3)`). `currentStock` sums thousandths (removes `round3` and the float sum); `dailyConsumption` sums thousandths; `daysRemaining` is a `BigInt` floor division `stock_milli * P / (Q_milli * K)`, capped at `Int.MAX_VALUE` like Kotlin.
- `shared/test-vectors/stock.json`: appended five cases (existing ones unchanged): `decimal-quantities-0.3-over-0.1-is-3-days` (3), `decimal-stock-sum-1-minus-0.3-over-0.1-is-7-days` (stock 0.7 from count 1 minus a taken 0.3; 7), `decimal-quantities-0.6-over-0.1-plus-0.2-is-2-days` (2), `decimal-quantities-1.2-over-0.4-is-3-days` (3), `weekdays-mask-mon-wed-fri-decimal-quantity-is-7-days` (mask 21, qty 0.1, stock 0.3, 7; daily_consumption 0.042857142857). `stock_vectors_test.ts` loads them unchanged; all 5 failed before and pass now.
- Not verified: I did not run the Kotlin suite against the new vectors (no Android build in scope). Expected values are computed by exact arithmetic and match the `BigDecimal`/`RoundingMode.FLOOR` code I read; the Kotlin lane/CI should confirm. `shared/test-vectors/README.md` was not touched (outside my files); it does not need a change unless you want to list the new cases.

## Other notes
- Source files were rewritten with LF; the repo is `text=auto`, so commits are LF-normalised (git printed CRLF warnings only).
- `deno lint` flagged `require-await` once after my stock-low refactor (`readEntries`); fixed by dropping `async`.
