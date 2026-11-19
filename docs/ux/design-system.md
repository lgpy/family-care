# UX: Design System

The rules behind the screens: colour, type, sizing and the shared components in
`android/core/ui`. The visual reference is
[mockups/scaling.html](mockups/scaling.html) — read it for the exact values and
reflow behaviour; this page says why, and what the code exposes.

Implemented in `io.github.lgpy.familycare.core.ui.theme` and
`io.github.lgpy.familycare.core.ui.components`. Feature modules never copy a
component: it lives in `core/ui` (D5).

## 1. Colour

### Material scheme: dynamic by default

`FamilyCareTheme` uses **Material You dynamic colour** (`dynamicLightColorScheme` /
`dynamicDarkColorScheme`) always: minSdk 33 means those schemes exist on every
phone the app runs on. The app therefore takes the phone's wallpaper colours.
Brand green is not guaranteed (D32).

When `dynamicColor = false`, and always in previews
([`LocalInspectionMode`](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/LocalInspectionMode)),
the static **green preset** from the mockup (`GreenPreset` in `Color.kt`) is
used. More presets are added by adding another `FamilyCarePreset`; the theme
signature does not change.

### Fixed semantic colours (never follow the wallpaper)

`FamilyCareTheme.colors` (a `FamilyCareColors` provided by the theme) holds
these; they are literal values from the mockup, identical on every phone:

| Token | Light | Dark | Use |
|---|---|---|---|
| `attentionContainer` | `#FFDDB3` | `#5A3500` | attention card background |
| `onAttentionContainer` | `#2A1800` | `#FFDDB3` | text on it |
| `attentionAccent` | `#8A5100` | `#FFB95C` | icon, low-stock label, action button |
| `criticalContainer` | `#FFDAD6` | `#93000A` | allergy banner |
| `onCriticalContainer` | `#410002` | `#FFDAD6` | text on it |
| `criticalAccent` | `#BA1A1A` | `#FFB4AB` | allergy icon |

Rules:

- **Amber** means *something needs doing* — an unconfirmed dose group, low
  stock. It is the only coloured block on a screen.
- **Red** means *allergies*, nothing else. A missed dose is not red.
- **Done is neutral**: use `onSurfaceVariant` (times at 55% alpha), never
  green. Green would read as "good" next to amber "bad" and red "danger";
  the design has no third judgement.

### Patient identity colours

Eight fixed colours, light (tone 40) and dark (tone 80), exposed through
`LocalPatientColors`: `(PatientId) -> Color`.

| # | Light | Dark |
|---|---|---|
| 1 orange | `#9A4F00` | `#FFB77C` |
| 2 teal | `#00687A` | `#53D7F1` |
| 3 purple | `#6B4EA2` | `#D4BBFF` |
| 4 olive | `#5D6300` | `#C6CE6C` |
| 5 blue | `#3F5F91` | `#A8C8FF` |
| 6 green | `#3A6A4F` | `#A1D3B4` |
| 7 rose | `#8B4A62` | `#FFB0C9` |
| 8 sand | `#6F5B3E` | `#DCC3A0` |

The initials are drawn in `MaterialTheme.colorScheme.surface` on the colour.
Red and amber hues are absent so an avatar never reads as a warning; green is
allowed here because an identity is not a state.

Assignment: the default is **a stable hash of the patient id**, so the colour
is the same on every phone and after every restart without any wiring.
`orderedPatientColors(ids, palette)` / `rememberOrderedPatientColors(ids)`
assign the palette in id order instead, which guarantees that up to eight
patients in one circle never collide; the app shell does not provide it yet —
phase 2 can wrap the shell's patient list in
`CompositionLocalProvider(LocalPatientColors provides …)`.

## 2. Type

**Atkinson Hyperlegible Next** (SIL OFL; licence in
`android/core/ui/licenses/AtkinsonHyperlegibleNext-OFL.txt`), bundled as one
variable font, `android/core/ui/src/main/res/font/atkinson_hyperlegible_next.ttf`
(114,552 bytes, ≈ 112 KB). Weights 400/500/600/700 are variation settings on
that one file.

`MaterialTheme.typography` is the full M3 scale (Material's default sizes) in
this family, every style with `fontFeatureSettings = "tnum"` so times, doses
and stock counts align as digits change.

`FamilyCareTheme.type` adds the two styles M3 does not have:

| Style | Size | Use |
|---|---|---|
| `time` | 24 sp, bold, tnum, −0.02 em | time rail labels |
| `timeSmall` | 20 sp, bold, tnum, −0.02 em | appointment day numbers |

## 3. Sizing and reflow

- **Text is sp, everything else is dp.** There is no in-app density setting:
  the system **font size** (sp) and **display size** (dp) do the scaling.
- **Minimum touch target 48 dp** — M3's `minimumInteractiveComponentSize` is
  kept, and full-width buttons get `heightIn(min = 48.dp)`.
- **No fixed heights on anything containing text.** Cards, slots and rows grow
  with their text.
- **Never truncate** medicine names, allergies, times or patient names. No
  `maxLines` or ellipsis on those; they wrap and the container grows.
- **Avatars and icons are fixed dp** (avatar 32 dp, profile 56 dp, icons
  24 dp, check 18 dp). The initial in an avatar is sized from the circle, so a
  200% font cannot push it out.
- **Reflow instead of breakpoints.** Components decide by measuring:
  `ReflowRow` puts content and action on one line while
  `content + spacing + action <= available` and otherwise stacks them,
  action full width. No component looks at `fontScale`.

## 4. Components (`core/ui.components`)

All stateless, all with KDoc and `@ScalePreviews` previews using the mockup's
content (Maria, António, Mom; Metformin 850 mg; "Allergic to penicillin and
ibuprofen").

| Component | What it is |
|---|---|
| `ReflowRow` | content + action side by side while both fit, else action below, full width. Pure fit function `reflowFits` is unit-tested. |
| `PatientAvatar` | fixed-dp circle with the patient's initial in their identity colour. |
| `AttentionCard` / `AttentionItem` / `AttentionAction` | the amber "needs attention" card: icon + bold title + supporting text per item, 18% divider, optional full-width accent button. |
| `AllergyBanner` | the red allergy line: critical container, icon, bold text, never truncated. |
| `StockMeter` | 8 dp bar (primary, or attention accent when low) plus "N days left"; label moves under the bar when it does not fit. |
| `TimeRail` / `DoseTimeGroup` / `DoseSlot` / `DoseSlotUi` / `DoseSlotState` | doses today: time column beside slots while the widest *time* is ≤ 26% of the width (`TimeRailMaxTimeWidthFraction`), otherwise times as headings; countdowns wrap under the time. Dose slots are done (dashed outline, neutral), next (primary container) or upcoming (surface container). |

Icons come from `material-icons-core` (`Warning`, `Info`, `Check`); no
`icons-extended` dependency.

## 5. Checking 100%, 150% and 200%

- In Android Studio, open a component: each `@ScalePreviews` preview renders
  100%, 150% and 200% at 384 dp width, plus dark at 100%. Every component has
  them.
- On a phone: Settings → Display → **Font size** (100 → 150 → 200%) and
  **Display size** (default → largest), then open Today, a dose group and a
  patient profile (mockup `scaling.html` shows both extremes).
- What to verify: nothing is cut off, nothing overflows, buttons move below
  the content they belong to, ages and stock labels are still readable, and
  the 48 dp targets remain separate. The layout may reflow; it may never
  truncate.
