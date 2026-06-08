# Sideloading Warning

Warns users that Google's developer-verification mandate (announced Aug 2025,
phased rollout from 2026) will block installs/updates of apps from unverified
developers on certified Android devices — which threatens how this app is
distributed (F-Droid, IzzyOnDroid, Obtainium, direct APK).

## Surfaces
- **Launch dialog** — shown once per app version from `MainActivity`. Keyed on
  `BuildConfig.VERSION_CODE` via `SideloadWarningRepository`; dismissing (either
  button) calls `markShown`.
- **Settings entry** — `SettingsHubActivity` row re-opens the same dialog on
  demand. Does NOT call `markShown`.

## Components
- `sideload/SideloadWarningRepository.kt` — SharedPreferences (`xray_prefs`,
  key `sideload_warning_last_version`); `shouldShow` / `markShown`.
- `sideload/SideloadWarningDialog.kt` — themed Material3 `AlertDialog`;
  "Learn more" opens `KEEP_ANDROID_OPEN_URL` (https://keepandroidopen.org/).

## Strings
`sideload_warn_*` and `settings_sideload_*` in `values/strings.xml` and
`values-ru/strings.xml`. The campaign URL is a code constant, not a string
resource.

## Tests
`app/src/test/.../sideload/SideloadWarningRepositoryTest.kt` covers the
once-per-version decision and persistence.

## Out of scope
Countdown timer, repeated re-prompts, remote-fetched copy.
