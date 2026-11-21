# UX audit: first use (v0.7.0)

Date: 2026-11-20. Build: debug 0.7.0 (versionCode 9) on an Honor phone (MagicOS, 1200×2664, font scale 1.0, dark theme).

Method: I walked through every tab, form and detail screen on the phone with the circle's existing test data and looked at it as someone opening the app for the first time. The sign-in and circle-setup screens were reviewed from code (`AuthGate`, `SignInScreen`, `CircleGateScreen`, strings) because reaching them would have meant wiping the app's data. No doses, patients or medicines were saved.

Findings are ordered by impact. **Bug** means the app behaves wrongly. **UX** means it works as coded, but a new user is likely to get it wrong or get stuck.

## Summary

| # | Finding | Type | Severity |
|---|---|---|---|
| 1 | Medicine search never finds anything on a real phone | Bug | High |
| 2 | Dose sheet: following its own hint records every pill as *Refused* | UX | High |
| 3 | First run gives no path to "add your first patient / medicine" | UX | High |
| 4 | Notification permission is never asked for unless the user finds it | UX | High |
| 5 | Allergies cannot be entered when adding a patient | UX | Medium |
| 6 | "Who gets reminded" opens the wrong screen | Bug | Medium |
| 7 | Leaving a form discards typed input without asking | UX | Medium |
| 8 | Overdue doses look calmer than upcoming ones | UX | Medium |
| 9 | Placeholder About text shipped | Bug | Low |
| 10–22 | Smaller copy, consistency and layout issues | UX | Low |

---

## High

### 1. Medicine search never finds anything (bug)

Typing "Parac" in **New medicine** shows no suggestions, only the button **Add Parac**. The bundled INFARMED catalogue has 7,916 products, paracetamol included.

Cause: the build stores the asset uncompressed, under a different name. The installed APK contains `assets/meds_catalog.tsv` (1,068,849 bytes), not `meds_catalog.tsv.gz`. `MedicationsModule.kt` opens `CatalogTsv.ASSET_NAME` = `"meds_catalog.tsv.gz"`, which throws `FileNotFoundException`. `MedicationFormViewModel.requestSuggestions` wraps the call in `runCatching { … }.getOrDefault(emptyList())`, so the error is silent. `CatalogTsvTest` reads the source file directly, so the tests pass.

Fix: either open the name the APK actually contains (and drop the gunzip), or rename the asset to something AAPT won't touch (e.g. `.bin`) and keep the gzip stream. Add an instrumented or APK-level check that the asset opens, and log rather than swallow the failure.

This also breaks the D34 promise in `screens.md` §4 ("pick the medicine from the catalogue"): name, strength, form, pack size and prescription flag must all be typed by hand.

### 2. The dose sheet invites the wrong tap

**Today → tap a dose row** opens "test · 08:00". Under the patient name it says *"Check each pill as you hand it over"*, but every pill's check circle is already filled. A first user who follows the hint taps the check, which **un**-gives the pill: the name is struck through, it shows **Skipped · Refused**, and the button changes to **Give 1 · skip 1**. If they tap through all the pills and press the button, the app records every dose as refused.

Suggestions:
- Make the hint match the default ("All checked. Untick any that weren't taken"), or start unchecked so the hint is literally true and the button counts up ("Give 2").
- Don't default the skip reason to *Refused*. Ask for it, or use a neutral "Not given".
- The accessibility label "Mark Metformin 850 mg" doesn't say whether the pill is currently given or skipped.

Also on this sheet: the patient name appears twice (title "test · 08:00" and again beside the avatar).

### 3. No first-run guidance after the circle exists

After **Create circle**, a new user lands on **Today**, which shows just "No doses are due today." (`meds_doses_today_empty`) and possibly the amber reminders card. Nothing says the next step is to add a patient, then a medicine. The only entry point is the **Add patient** button on the Patients tab. Empty states on other tabs are passive too ("No appointments yet.", "Nothing to buy").

Suggestions: when the circle has no patients, Today should show one clear call to action ("Add the first person you care for"), then "Add their first medicine" on the new profile. The empty states should link to the action that fills them.

### 4. Notification permission is requested in only one place

`POST_NOTIFICATIONS` is requested only by the **Fix** button in **More → Make reminders reliable** (`RemindersScreen.kt:138`). On this phone, *Notifications are allowed* is still ✗ while 8 of 8 reminders are scheduled, so they would fire with no visible notification. Reliable reminders are the product's first principle, but the user only learns this from an amber card that reads "Check: notifications and battery exemption."

Suggestion: ask for notification permission (and offer the battery exemption) right after the first medicine is saved, with one sentence explaining why. Keep the reliability screen for repair.

---

## Medium

### 5. Allergies aren't part of adding a patient

**Add patient** asks for name, year of birth, linked app user, reminder policy, primary caregiver, escalation delay, missed window and an agreement note. It does not ask for allergies or conditions, the one thing the design says is "always visible". They are entered later via profile → **Info** → **Edit info**, and the Info empty state ("No other information recorded yet.") doesn't mention them.

Suggestion: put **Allergies** (with "None known" as an explicit option) on the add form. Move escalation delay, missed window and agreement note under **More options** with their defaults, as the medicine form already does.

### 6. "Who gets reminded" opens the reliability checklist (bug)

In `MoreScreen.kt`, both the **Make reminders reliable** and **Who gets reminded** rows use `onClick = onOpenReminders`. The second row should open per-patient reminder policy, which today lives only in each patient's edit form. Likewise **Circle members** and **Invite someone** open the same screen; that's acceptable, but **Invite someone** should at least scroll to or focus the email field.

### 7. Closing a form throws away typed input

On **New medicine**, typing a name and tapping ✕ closes the form immediately with no "Discard changes?" confirmation. The same is likely true of **Add patient** and the dose sheet note. That's painful on long forms.

### 8. Urgency hierarchy on Today is inverted

In **Doses today**, the overdue 08:00 slots use the muted surface and button, while the upcoming 16:00 slots ("in 43 min") use the strong primary container and bright **Give** buttons. The overdue slots are repeated in the amber attention card above, but in the timeline the eye goes to the wrong row. Mark the overdue rows (e.g. "2 h late" under the time) and don't make future rows the boldest thing on the screen.

---

## Low

9. **Placeholder About text.** `about_body`: "Family Care is being built one step at a time. This screen exists to prove navigation works."
10. **Sign-in copy assumes an invite.** `sign_in_body` says "Sign in with the Google account that was invited to your care circle". The first user, who creates the circle, wasn't invited. Use something like "Sign in with Google to create or join your family circle."
11. **Inconsistent action labels for the same thing.** "Give it" / "Give all 2" (attention card), "Give" (timeline), "Give all 2" / "Give 1 · skip 1" (sheet). "Add medication" (profile) vs "New medicine for …" / "Add medicine" (form). Pick one noun (*medicine*, the everyday word) and one verb pattern.
12. **Empty-name validation message is about length.** Saving with no name shows "Enter a name of up to 200 characters." It should say the name is required.
13. **Reminders card layout.** On Today, the **Reminders need attention** row puts its chevron alone on a new line at the bottom-left, unlike the other rows where it sits to the right.
14. **Reliability checklist.** ✓ and ✗ share the same accent colour, so failures don't stand out. The checks read as statements even when false ("✗ Notifications are allowed"), and the **Fix** links are small text targets. The screen has no back arrow or top bar, unlike its siblings.
15. **Unlabelled box icon.** In the profile's medicine card, a box icon next to "About 20 days left" has no visible meaning (it just opens the medicine). The low-stock medicine shows a "!" in that spot instead.
16. **Medicine cards omit the amount.** The profile list shows times (08:00, 16:00) but not "1 tablet". The amount only appears in the detail screen or the dose sheet.
17. **Timeline row amounts are inconsistent.** One Today row shows "2 pills" with drawings; others show only drawings and no count.
18. **Form icons.** On **New medicine**, *Drops* uses the same pill icon as Tablet/Capsule, and Liquid/Injection/Inhaler/Patch/Cream all share the generic medicine-box icon.
19. **History includes the future.** Profile → **History** lists the 23 Nov blood test (3 days ahead) above "Yesterday" events.
20. **Cryptic policy summary.** The profile header's "You first, then everyone" means *Primary with escalation*. The form calls the same thing "Primary with escalation". Use one wording and include the delay ("You first; everyone after 30 min").
21. **"Send a code by WhatsApp".** The More row promises WhatsApp, but the screen starts with an email field and a generic share.
22. **Medicine detail.** "Count" (with a ③ icon) doesn't say it means *recount stock*. The 08:00 chip uses a sun icon while 16:00 uses a clock.

## What works well

- The allergy banner is unmissable and appears where it matters: profile and dose sheet.
- Pill drawings plus descriptions ("Large white long tablet with a score line") make the dose sheet easy to check against the physical box.
- Today really is sorted by urgency, and the attention card is the only coloured block, as the design system intends.
- Stock in days ("About 3 days left") plus the prescription-first grouping in Shopping is clear and actionable.
- Large type, generous touch targets and the Atkinson Hyperlegible font suit the intended older caregivers.
- The reminders reliability screen with vendor-specific steps (Honor/MagicOS) is a strong idea; it needs to be shown at the right moment (finding 4).

## Suggested order

1. Fix the catalogue asset (1); it's a one-line cause with a big effect.
2. Fix the dose-sheet default and hint (2).
3. First-run path: empty-state CTAs, plus the notification permission ask after the first medicine (3, 4).
4. Allergies on the add-patient form; advanced fields under More options (5).
5. Wire "Who gets reminded", add discard confirmation, fix the About text (6, 7, 9).
6. Copy and consistency pass (10–22).
