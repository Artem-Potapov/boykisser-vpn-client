# Boykisser Nag Screen

Maintainer reference for the Boykisser onboarding / conversion screen — internally the "nag screen" —
that walks an un-subscribed user through obtaining a VPN key. Reached from the home banner CTA and the
"VPN by Boykisser (Recommended)" row on the subscriptions screen; both surfaces only render while
`!PromotedSubscription.hasValidSubscription(...)` (see [`boykisser-vpn.md`](boykisser-vpn.md)).

## Why this exists

The promoted-subscription flow has two zero-friction entry points (custom-scheme deep link, App Link),
but both require the user to already be holding a `https://...` key URL on their clipboard. The
realistic path for a brand-new user is: *I tapped the banner / promo row, now what?*

The nag screen is that "now what" — a self-contained roadmap from "I have nothing" to "I have an
active subscription", with the Telegram bot, the bot reply format, and the paste-and-submit field all
inline. It is the *only* surface that bridges users who can't or won't tap the App Link button on the
website.

## Surface

Single Activity — [`subs/BoykisserInfoActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/BoykisserInfoActivity.kt),
declared `android:exported="false"`. Two launch sites, both internal:

| From | Trigger |
|---|---|
| `MainActivity` → `BoykisserBanner` | Tap "Add" on the dismissible magenta home banner. |
| `SubscriptionsActivity` → `BoykisserPromoRow` | Tap the "VPN by Boykisser (Recommended)" row. |

The activity finishes itself in two cases: back arrow tap (`onBack`), and on successful submission
(`onSubmitted`, after handing the validated URL off to `MainActivity` via
`EXTRA_ADD_BOYKISSER_SUB`).

## The roadmap

Four steps, alternating horizontal alignment to mimic a hand-drawn roadmap. Each step renders
[`RoadmapStep`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/BoykisserInfoActivity.kt) — a
36 dp magenta circle (`StepCircle`) with a bold white digit, plus a content column.

| Step | Side | Content |
|---|---|---|
| 1 | Start (0.18) | `boykisser_step1_intro` — "you need a VPN key" framing. |
| 2 | Start (0.18) | Label + full-width magenta button that opens `BoykisserInfoActivity.BOT_URL` (`https://t.me/boykisser_vpn_bot`). |
| 3 | End (0.82) | Intro line + `BotMessageMock` (Telegram-styled dark Surface with the canonical bot reply) + hint to tap the link in Telegram to copy. |
| 4 | Center (0.50) | Label + `PasteAndSubmit` (`OutlinedTextField` + "Let's go!" button). |

`HorizontalSide.{Start,Center,End}` are fractions of the parent width (`0.18 / 0.50 / 0.82`), used
by both the step's `Box.align` and the arrow endpoints — same source so they cannot drift.

### Step 4 grouping

Step 4 (label) and `PasteAndSubmit` are wrapped in a nested `Column` with `Arrangement.spacedBy(15.dp)`
so `Arrangement.SpaceBetween` on the outer column treats them as a single bottom-anchored unit.
Without the nest, `SpaceBetween` inserted a stretchy gap between the step label and the field that
read as a missing-arrow slot.

## Adaptive layout (the SpaceBetween / scroll dance)

Outer container is `BoxWithConstraints { Column(verticalScroll, heightIn(min = maxHeight),
verticalArrangement = Arrangement.SpaceBetween) }`. The `imePadding()` is applied to the
`BoxWithConstraints`, so `maxHeight` reflects the *post-keyboard* viewport.

Two regimes:

- **Content fits.** Column height equals viewport; `SpaceBetween` distributes slack between steps
  evenly. Nothing to scroll.
- **Content overflows** (small screen, max font scale, or keyboard raised). Column grows past
  viewport, `SpaceBetween` runs out of slack, scroll engages. `OutlinedTextField` brings itself
  into view on focus, so tapping the field auto-scrolls it above the IME.

This shape was deliberate — fix for `3f1fa4f` (`imePadding` ordering) and the scroll-on-overflow
rebuild that landed in `1173373`.

## Arrows (`ArrowConnector`)

Cubic bezier from step *N*'s bottom to step *N+1*'s top, drawn on a 40 dp tall `Canvas`. Endpoint
x-fractions come from `HorizontalSide`. Two tangent rules:

- **Start tangent** (cp1 at `(xFrom, midY)`): purely vertical. The arrow tails *out* of the previous
  step going straight down, matching the hand-drawn sketch.
- **End tangent** (cp2 at `end - 0.4 * chord`): aligned with the start-to-end chord. The arrow's
  natural end-tangent and the chord point the same way, so the chevron visually merges with the
  curve's final approach instead of looking detached.

The arrowhead spread, head length, and stroke width are all derived from `MaterialTheme.typography
.bodyLarge.fontSize` (head = 0.55, stroke = 0.14, spread = 0.4 × head). This is why the arrows
visually scale with font-scale changes — they track text, not screen density.

For same-side connectors (Step 1 → Step 2, both Start), `xFrom == xTo`, so cp1 and cp2 share x with
the endpoints and the curve collapses to a clean straight vertical line. The chevron derivation
still works because it uses `(end - cp2)` not a chord constant.

### Show / hide rule

```kotlin
val heightPx = LocalWindowInfo.current.containerSize.height
val showArrows = heightPx == 0 || heightPx.toDp() >= 800.dp
```

Arrows are hidden on short windows so the four steps don't fight the bezier connectors for the
already-cramped viewport. The `heightPx == 0` branch covers the first-frame case before the window
has measured — defaulting to "show" prevents a flash-of-no-arrows on tall screens; on a short screen
the measurement lands before the user can perceive anything. Fix landed in `f7594cf`.

## Submit path

[`PasteAndSubmit`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/BoykisserInfoActivity.kt)
owns the text state. Submit (button tap *or* IME `Done`) runs `BoykisserCallback.validate(text)`:

- `null` → set `showError = true`, render the
  `boykisser_error_invalid_domain` line via `OutlinedTextField`'s `supportingText`.
- non-null → call `onApproved(approved)`, which starts `MainActivity` with
  `EXTRA_ADD_BOYKISSER_SUB = approved` + `FLAG_ACTIVITY_CLEAR_TOP`, then calls `onSubmitted()`
  (which `finish()`es the nag screen).

The consent dialog and the actual `addSubscription` call live in `MainActivity` — see the
"Callback" section of [`boykisser-vpn.md`](boykisser-vpn.md). Keeping consent there means the
durable `viewModelScope` survives this Activity finishing, and the same dialog handles both the
deep-link callback and the nag-screen submission.

### Error supportingText shape

`supportingText` is conditionally `null` rather than always-present, so the slot reserves **no**
height when there's no error. Combined with `Arrangement.spacedBy(15.dp)` on the parent column, the
resting gap between the text field and "Let's go!" is exactly 15 dp. When an error is shown the gap
grows by the supporting-text row — accepted, since the user is actively reading the error.

## StepCircle line-height trim

`StepCircle` wraps the digit in `Text` with `LineHeightStyle(alignment = Center, trim = Both)`.
Without `trim = Both`, the line box's half-leading sits asymmetrically inside the 36 dp circle and
glyphs whose visual bounds aren't symmetric within the line box (notably "4") drift visibly
off-center. Fix landed in `251a566`.

## Strings

Source of truth in [`res/values/strings.xml`](../../app/src/main/res/values/strings.xml); Russian
translation in [`values-ru/strings.xml`](../../app/src/main/res/values-ru/strings.xml). Keys:
`boykisser_info_title`, `boykisser_step1_intro`, `boykisser_step2_label`, `boykisser_step2_button`,
`boykisser_step3_intro`, `boykisser_step3_hint`, `boykisser_step4_label`,
`boykisser_step4_field_hint`, `boykisser_step4_submit`, `boykisser_error_invalid_domain`,
`subs_cd_back`.

The bot reply in `BotMessageMock` is intentionally **not** localized — it's a screenshot-style
mock of what the (Russian-speaking) Telegram bot actually sends. Translating it would defeat the
"this is what you will see" purpose.

## Known limitations

- **No tests for the Compose layout.** Logic that matters under test (deep-link validation, promo
  visibility) lives in `BoykisserCallback` / `PromotedSubscription` and is covered by their unit
  tests. The roadmap is visual scaffolding around a paste field.
- **`BOT_URL` is hard-coded.** Changing the bot handle requires a code change, not a config push.
  Acceptable — it's the same lifetime as the approved-domain list in `PromotedSubscription`.
- **No "I already have a key" shortcut.** A user who already holds a key can paste it directly into
  the home-screen FAB → "Paste subscription URL" without ever opening the nag screen. The nag
  screen is for users who don't yet have one; adding a skip button would just be a worse path to
  the existing FAB.
