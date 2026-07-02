# Sideloading Warning

Warns users that Google's developer-verification mandate (announced Aug 2025)
will block installs/updates of apps from unverified developers on certified
Android devices — which threatens every distribution path for this app outside
the Play Store.

> **Status: the launch popup is DORMANT.** It is gated off by
> `MainActivity.SIDELOAD_WARNING_LAUNCH_ENABLED = false` (companion object in
> `MainActivity.kt`, marked `// TEMP`). The dialog composable, repository,
> strings, tests, and the Settings-hub entry all remain intact — flip the flag
> back to `true` to restore the once-per-version launch prompt. Do not "clean
> up" the pieces the disabled path leaves unused.

## Surfaces

- **Launch dialog — dormant.** `MainActivity.onCreate` computes
  `showSideloadWarning = SIDELOAD_WARNING_LAUNCH_ENABLED &&
  SideloadWarningRepository.shouldShow(this, BuildConfig.VERSION_CODE)`; with
  the flag `false` the `&&` short-circuits, so `shouldShow` is never queried
  and no user currently sees this dialog at launch. When enabled, it shows
  once per app version (keyed on `BuildConfig.VERSION_CODE`), and **any**
  dismissal calls `markShown` — either button, back press, or outside tap
  (the dialog routes `onDismissRequest` to the same `onDismiss` callback).
- **Settings entry — live.** `SettingsHubActivity` has an always-available row
  (independent of the launch flag) that re-opens the same dialog on demand.
  Dismissing there only hides the dialog; it does NOT call `markShown`, so the
  once-per-version launch bookkeeping is untouched by Settings re-opens.

## Components

- `sideload/SideloadWarningRepository.kt` — SharedPreferences (`xray_prefs`,
  key `sideload_warning_last_version`); `shouldShow(context, versionCode)`
  is true while the stored version is < the current one, `markShown` records
  the current version. Deliberately no StateFlow (unlike
  `KillSwitchRepository`): this is a one-shot launch check, not a
  live-observed preference.
- `sideload/SideloadWarningDialog.kt` — themed Material3 `AlertDialog`.
  Dismissal semantics live in the caller: the composable routes both buttons
  and `onDismissRequest` through the single `onDismiss` parameter, and
  MainActivity vs the Settings hub decide whether that means `markShown`.
  The confirm button ("Take action") opens the campaign URL from
  `R.string.sideload_warn_url` via `ACTION_VIEW` before dismissing, falling
  back to a Toast showing the URL when no browser handles the intent.

## Strings

`sideload_warn_*` and `settings_sideload_*` in `values/strings.xml` and
`values-ru/strings.xml` (Russian fully localized):

- Title: "You could lose access to your VPN".
- Body: urgent campaign copy — verification "starts THIS SEPTEMBER" and will
  block updates of this app; ends with a call to action (learn how to stop
  it, sign the petition). It names no specific stores/channels.
- Buttons: confirm is **"Take action"** / «Действовать»
  (`sideload_warn_learn_more` — the resource name is a legacy "Learn more"
  label; the visible text is not), dismiss is **"Dismiss"** / «Закрыть»
  (`sideload_warn_dismiss`).
- Settings row: `settings_sideload_title` "Don't let Google block your
  updates", `settings_sideload_subtitle` "Why Google's verification rules
  will block updates".
- Campaign URL is a locale-aware string resource (`sideload_warn_url`):
  `values/` → `https://keepandroidopen.org/`, `values-ru/` →
  `https://keepandroidopen.org/ru/`.

## Tests

`app/src/test/.../sideload/SideloadWarningRepositoryTest.kt` (JVM unit,
mocked SharedPreferences) covers the once-per-version decision (`shouldShow`
true below the current version, false at/above it) and `markShown`
persistence. The dialog and the launch/Settings wiring have no automated
coverage — and the dormant launch path cannot be exercised without flipping
the flag.

## Related

`docs/features/name-theft-warning.md` lists this dormancy under "Related
temporary changes"; `AGENTS.md` tracks it in "Dormant / Temporarily-Disabled
Features".

## Out of scope

Countdown timer, repeated re-prompts, remote-fetched copy.
