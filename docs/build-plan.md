# Build Plan

Status: **accepted**. No code yet. This plan turns the design into an order of work in which every milestone ends with something that runs on a real phone and has a clear "done" test.

Guiding rule: **build the riskiest parts first** — sign-in + sync + access control, then reminders. Features that are just forms and lists come later because they rarely surprise.

---

## 1. Repository structure

One repository (monorepo) for app, backend, operations and docs:

```
family-care/
├─ docs/                          design documents (this folder)
├─ android/                       native Android app (Kotlin, Jetpack Compose, Gradle)
│  ├─ app/                        entry point, navigation shell, wiring of modules
│  ├─ core/
│  │  ├─ domain/                  pure Kotlin: identity, care circle, access rules,
│  │  │                           extension-point contracts, shared types
│  │  ├─ data/                    PowerSync + Supabase setup, encrypted local DB, audit
│  │  ├─ notifications/           alarm scheduling, push handling, notification routing
│  │  └─ ui/                      design system, translations
│  └─ feature/
│     ├─ medications/
│     │  ├─ domain/               pure Kotlin: regimens, dose slots, statuses, stock maths
│     │  └─ android/              local tables, repositories, screens, module registration
│     ├─ appointments/  (domain/ + android/)
│     └─ patient-info/  (domain/ + android/)
├─ backend/
│  ├─ supabase/
│  │  ├─ migrations/              one schema per module: core, meds, appointments, info
│  │  ├─ functions/               server jobs: escalation, low stock, push sender
│  │  └─ tests/                   database tests: access isolation, audit, append-only
│  └─ powersync/                  sync rules (what each user's phone downloads)
├─ shared/test-vectors/           dose-schedule cases both app and server must pass
├─ ops/
│  ├─ backup/                     nightly backup job, restore checklist
│  └─ checklists/                 setup, release, security, device test matrix
└─ .github/workflows/             automated checks; nightly backup
```

### Why separate Gradle modules
Rule D5 says modules never depend on each other. With one Gradle module per feature, `medications` cannot use `appointments` code unless it is declared as a dependency, and an automated build check **fails if any feature module depends on another feature module**. A folder convention would rely on discipline.

### Why `domain/` is pure Kotlin
Each feature's `domain/` module contains only rules (no Android, database or UI code). It is:
- the easiest part to test, and the part where mistakes are most dangerous (dose times, stock);
- **ready for Kotlin Multiplatform**: the same code can later be compiled for iOS, or even for a web app, without rewriting it (D22).

The `android/` part of each feature holds local tables, repositories, Compose screens and a registration class that contributes dashboard cards, tasks, notification types, timeline entries and settings.

### One logic, two places: dose slots
Dose slots are computed on the phone (to schedule reminders) **and** on the server (to escalate unconfirmed doses). Two implementations of the same rules can drift apart. Mitigation: a shared file of **test vectors** (regimen + date range → expected slots, including daylight-saving changes). Both the Kotlin code and the server code must pass the same file.

---

## 2. Main libraries (candidates, confirmed during M1)

| Need | Candidate |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Screen state | AndroidX ViewModel + Kotlin Flow (unidirectional data flow) |
| Navigation | Navigation Compose (type-safe routes) |
| Dependency injection | Koin (works with Kotlin Multiplatform) or Hilt — chosen in M1 |
| Local database + sync | PowerSync Kotlin SDK + its Supabase connector; local encryption via its SQLite3MultipleCiphers package ([docs](https://docs.powersync.com/client-sdks/advanced/data-encryption)) |
| Supabase client | supabase-kt (Auth, Postgrest, Functions) |
| Google sign-in | Android Credential Manager → Supabase sign-in with the Google ID token |
| Encryption key storage | Android Keystore |
| Local reminders | AlarmManager **alarm-clock alarms** directly (D19) + receivers for reboot / time change; WorkManager for periodic rescheduling |
| Push | Firebase Cloud Messaging (no other Firebase services) |
| Translations | Android string resources (`values/` English, `values-pt-rPT/` Portuguese) |
| Tests | JUnit, kotlinx-coroutines-test, Turbine, Compose UI tests |
| Server jobs | Supabase Edge Functions triggered by pg_cron |
| Database tests | pgTAP |

---
## 3. Milestones

Size: **S** small · **M** medium · **L** large (relative effort, not calendar time).

### M0 — Accounts and setup (S)
- Check Google 2-Step Verification on both caregivers' Google accounts.
- Create: GitHub private repo, Supabase **dev** and **prod** projects (both free tier, EU), PowerSync instance (free, EU), Firebase project (messaging only), backup storage at a second EU provider with object lock.
- Create the **release signing key** and store it in two safe places (§7).
- MFA on every one of these admin accounts.
- Sign DPAs where offered.
- **Done when:** the setup checklist in `ops/checklists/` is fully ticked.

### M1 — Walking skeleton (M) — *highest risk first*
- Kotlin/Compose app with Gradle module structure, navigation shell, pt-PT/en switching.
- Sign in with Google → Supabase session.
- One test table synced through PowerSync, encrypted locally, protected by Row-Level Security.
- Automated checks on every push (analysis, tests).
- Secret protection: ignore rules for keystores/config files, and the free scanner **gitleaks** run before each commit and in the automated checks (GitHub's built-in secret scanning isn't available for free private repos).
- **Done when:** on two phones, a change made in airplane mode appears on the other phone after reconnecting; a third test account sees nothing; the first isolation test passes automatically.

### M2 — Care circle (M)
- Circles, memberships, roles, patients (with linked user), reminder policy settings, optional agreement note.
- **Invitations by one-time code** (sent as text via WhatsApp or any app, D23). Accepting requires signing in with the invited email's Google account. No email provider needed. The app is sent separately (manual APK, D18).
- The core access function; database policies and sync rules for all core tables.
- Audit triggers and the append-only audit table.
- **Done when:** you invite Mom; four patients exist; the isolation test suite covers the scenarios in [security-and-privacy §3](security-and-privacy.md); edits appear in the audit log.

### M3 — Medications without reminders (L)
- Medication and regimen screens (4-step add flow), versioned regimen changes.
- Dose-slot calculation + shared test vectors (incl. DST weeks).
- Dose log: confirm a whole time slot, exceptions with reasons, "given by someone else", double-confirmation warning.
- "Doses today" card on Today (first real use of the dashboard extension point).
- **Done when:** all test vectors pass; you can track a day's doses for all four patients manually.

### M4 — Reminders and escalation (L) — *second highest risk*
- Local reminders with "Given" and "Snooze" actions from the notification.
- Rescheduling after reboot, time-zone change, app update, and when synced changes arrive.
- Reminders scheduled as **alarm-clock alarms** (D19): the most reliable kind of Android alarm, exempt from Doze.
- Android permissions: notifications (Android 13+), exact alarms (Android 14+ requires explicit user permission), battery-optimisation exemption.
- **Guided "make reminders reliable" setup per phone brand** (Honor/MagicOS and Samsung/One UI — see §8), opening the right settings screens where possible.
- **Reminder health check:** on every app start, verify permissions are still granted and future alarms are scheduled; warn on Today if not.
- Server escalation job (every few minutes) + FCM push with generic text; clearing notifications on other phones when a dose is confirmed.
- Derived "missed" status; "Needs attention" card.
- **Done when:** the device test matrix passes on **the Honor 200 and the Samsung Galaxy A**: screen off for hours (Doze), app swiped away, phone rebooted, airplane mode, battery saver on, clock moved across a DST change, escalation reaching the other phone.

> **After M4: start using it for real**, alongside the current system (paper/WhatsApp) for 1–2 weeks. Real use will change the plan more than any document.
>
> **Gate before the first real health data enters prod:** the nightly independent backup job is running and one restore has been tested. On the free tier this is the *only* backup (D20), so it moves forward from M7.

### M5 — Stock and shopping (M)
- Stock ledger, days remaining, low-stock threshold in days, `StockLow` → dashboard + push.
- Shopping list with "needs prescription first" / "ready to buy"; purchase creates a refill.
- **Done when:** a week of real doses produces correct stock numbers, and a low-stock alert reaches both phones.

### M6 — Appointments and patient info (M)
- Appointments: create/edit, types, preparation notes, reminders (evening before, 1 hour before), agenda list.
- Patient info: allergies banner, conditions, emergency contacts, SNS number, family doctor.
- Patient history timeline (fed by medications and appointments).
- **Done when:** real upcoming appointments for all four are in the app and reminders arrive.

### M7 — Hardening and release (M)
- Per-patient export (JSON + PDF), delete, archive. Activity screen for the owner.
- Backup job already running since real use began (gate after M4); repeat the restore test and record it.
- Decide on upgrading Supabase to Pro (D20).
- Security checklist: provider security advisor clean, no secrets in repo, auto-backup disabled, generic notifications, admin MFA verified.
- Release as a **signed APK installed by hand** on both phones (D18).
- **Done when:** both phones run the signed release build, the restore test is recorded as passed, the old system can be retired.

After M7: MVP complete. First v2 candidates: documents, measurements (validates the extension points), calendar view.

---

## 4. Testing strategy

| Level | What | When |
|---|---|---|
| Domain unit tests | Dose slots, statuses, days remaining, regimen versioning | Every push |
| Shared test vectors | Same schedule cases for Kotlin and server | Every push |
| Database tests | Access isolation per role, audit written, audit cannot be changed, dose log append-only | Every push touching `backend/` |
| Sync-rule tests | Test users download exactly what they should | Every change to sync rules |
| Widget / integration tests | Sign-in, add medication, confirm doses | Every push (emulator) |
| Device test matrix | Reminder reliability on real phones | Before each release |
| Restore test | Backup → scratch project → app works | Quarterly, and before M7 release |

---

## 5. Environments and release

- **dev:** Supabase free project, invented test patients only. **Never real health data.**
- **prod:** Supabase project (EU), real data. Free tier at first; upgraded to Pro when we rely on it (D20).
- Database changes only through versioned migrations, applied to dev first, then tested against a restored copy of prod before production.
- Distribution: **signed APK installed by hand** (D18), see §7.

---

## 6. Accounts and running costs (D20)

Everything starts **free**. Paying is a decision taken later, when the family relies on the app.

| Service | Purpose | Free tier | Main free-tier limitation | Paid option |
|---|---|---|---|---|
| Supabase (dev + prod, EU) | Database, auth, storage, jobs | 2 projects, 500 MB database, 1 GB files | **Paused after 1 week of inactivity; no automatic backups** | Pro ~$25/month: never paused, daily backups (7 days) |
| PowerSync (EU) | Sync | 2 GB synced/month, 50 devices | **Deactivated after 1 week of inactivity** | Pro from ~$49/month, or self-host the free Open Edition on a ~€5/month server |
| Firebase | Push only (FCM) | Free | — | — |
| Google sign-in | Login | Free | — | — |
| Backblaze B2 (EU) | Independent backups | First 10 GB free (our backups are a few MB) | — | ~$7/TB/month |
| GitHub | Repository, automated checks, nightly backup job | Free private repos, 2,000 job minutes/month | — | — |

**Why free is workable here:** daily use and the nightly backup job keep both Supabase and PowerSync active, so pausing should not happen in normal life. If it ever does, the app keeps working offline (reminders included) until the project is resumed, and our own nightly backup (D15) covers the missing provider backups.

**When to pay:** at M7 release, decide on Supabase Pro (~$25/month). Recommended once real health data lives only there, because it removes pausing and adds a second, independent backup layer. PowerSync stays free unless deactivation actually becomes a problem.

Estimated cost: **€0/month while building**; **~€25/month** after upgrading Supabase.

---
## 7. Manual installation (D18)

- Release builds are **APK files signed with our own release key**, installed by allowing "Install unknown apps" for the app used to open them (Files / Drive).
- **The signing key is critical.** Android only installs an update if it is signed with the same key as the installed app. If the key is lost, the app must be uninstalled and reinstalled — local data is lost (server data is not). Store the key and its password in the password manager **and** an offline backup. Never in the repository.
- Delivery: APK placed in a private shared Google Drive folder; installed by opening it on the phone. Each release has a version number visible in *More → About*.
- Updates are manual: the app shows its version; I tell Mom when there is a new one (or, optionally later, the app checks a version file and shows "update available").
- Google Play Protect may show a warning for an unknown app; expected — choose "Install anyway".
- Later switch to Google Play remains possible without reinstalling, as long as we keep the same signing key.
- Optional middle ground if manual updates get tedious: Firebase App Distribution (free, already using Firebase, sends update notifications).

---

## 8. Phones and reminder reliability

| Phone | System | Known behaviour | Setup the app must guide |
|---|---|---|---|
| Honor 200 (me) | MagicOS (Android) | Among the most aggressive at closing background apps; can block alarms and delay push for apps it "optimises" | *App launch* → set the app to **Manage manually** with auto-launch, secondary launch and run in background **on**; battery optimisation → **don't optimise**; notifications and lock-screen notifications allowed |
| Samsung Galaxy A (Mom) | One UI (Android) | Puts unused apps into "sleeping" / "deep sleeping"; then alarms and push can be delayed | Battery → app set to **Unrestricted**; add to **Never sleeping apps**; turn off "Put unused apps to sleep" for it; notifications allowed |

Safety nets that don't depend on these settings:
- Alarm-clock alarms (D19) are the type phone makers are least willing to block.
- The reminder health check warns when something is off.
- Server escalation still alerts the other caregiver if a dose stays unconfirmed.

Both phones are in the M4 device test matrix. Settings names vary by system version; the guide is verified on the actual phones during M4 (reference: dontkillmyapp.com).

---

## Open questions (build)

- ~~**B1 — Phone models.**~~ Resolved: Honor 200 (MagicOS) and Samsung Galaxy A (One UI). See §8.
- ~~**B2 — Distribution.**~~ Resolved: manual APK installation for now (D18). See §7.