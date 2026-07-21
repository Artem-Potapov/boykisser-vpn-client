# Debug Tools: Unrestricted Profile Adder

Maintainer reference for `DebugUnrestrictedAddProfileActivity` — a DEBUG-only Settings-hub screen that
inserts a profile config **verbatim**, bypassing every ingest gate the normal Add flow enforces.

## Why this exists

The production add path (`VpnViewModel.addProfile`) always produces a *buildable* stored profile: it
runs the input through `ConfigBuilder.toProfileStorageConfig`, diagnoses DNS via
`ConfigBuilder.dnsDiagnosis` (routing to the `DnsWarning` fix-up flow on `DIRTY`), and only inserts
after `ConfigBuilder.makeSecureDns` succeeds. That is correct fail-closed behavior for real users, but
it also means a maintainer cannot reach the app's *failure* branches through the UI — there is no
legitimate way to get a malformed/unbuildable config into Room to exercise, for example, the
Config-Sanitization failure path (`ConfigSanitizer` / `ConfigSanitizationActivity`'s failure state; see
[`config-sanitization.md`](config-sanitization.md)). NEW-M2/M2-a — the Russian localized
Config-Sanitization failure message must be a self-contained sentence with no interpolated English
tail — is exactly this kind of otherwise-unreachable branch. `DebugUnrestrictedAddProfileActivity`
exists solely to reach that class of fail-closed UI branch on demand, in debug builds only.

This is a maintainer diagnostic tool, not a feature surface. It has no place in a release build and is
gated three separate ways (below) so that it cannot appear or run in one.

## The three release guards

1. **Composed only under `BuildConfig.DEBUG`.** The Settings-hub row that opens this screen
   (`settings/SettingsHubActivity.kt`, About section, right after the About row) is wrapped in
   `if (BuildConfig.DEBUG) SettingsRow(...)` — in a release build the row does not exist, so there is no
   UI entry point at all.
2. **`onCreate` self-checks and bails.** `DebugUnrestrictedAddProfileActivity.onCreate` starts with
   `if (!BuildConfig.DEBUG) { finish(); return }` before doing anything else — belt-and-suspenders: even
   if something managed to start the Activity by class name in a release build, it immediately
   finishes with no UI ever composed.
3. **`android:exported="false"`.** The manifest entry
   (`app/src/main/AndroidManifest.xml`) declares the Activity non-exported, so no other app can launch
   it via an explicit `Intent` regardless of build type — the same declaration pattern as every other
   internal settings screen (`ConfigSanitizationActivity`, `PingTestSettingsActivity`, etc.).

Any one of the three would suffice for release safety; all three are present because this screen's
entire purpose is to defeat validation, so it gets the same defense-in-depth posture as the app's other
fail-closed chokepoints.

## `VpnViewModel.addRawProfile`

```kotlin
fun addRawProfile(name: String, raw: String): Job = viewModelScope.launch {
    val id = dao.insert(Profile(name = name, config = raw, sanitizedDns = false))
    ActiveProfileRepository.setActiveProfileId(getApplication(), id)
}
```

Two properties matter:

- **Verbatim storage, no `ConfigBuilder`.** Unlike `addProfile`, this calls `dao.insert(...)` directly
  on the raw string — no `toProfileStorageConfig`, no `dnsDiagnosis`, no `makeSecureDns`. Whatever is
  typed into the config field is byte-for-byte what lands in `Profile.config`, including strings that
  are not valid JSON at all.
- **Activation via the sanctioned writer.** After insert, it calls
  `ActiveProfileRepository.setActiveProfileId(getApplication(), id)` — the same writer the QS tile,
  `XrayVpnService`, and the Connect flow use — so the new row becomes the profile that Config
  Sanitization, the QS tile, and Connect all resolve as "active," rather than requiring a separate
  manual activation step.

`addRawProfile` returns the `Job` it launches (not `Unit`), and that Job is the mechanism the caller
uses to know insert+activate have both completed — see below.

## The awaited-`Job` activation detail

`DebugAddScreen`'s Add button does not fire-and-forget:

```kotlin
scope.launch {
    onAdd(name, config).join()
    Toast.makeText(context, doneMsg, Toast.LENGTH_SHORT).show()
    onBack()
}
```

`onAdd` is `viewModel::addRawProfile`, so `onAdd(name, config)` returns the `Job` from
`viewModelScope.launch`, and the screen's own coroutine `.join()`s it before showing the confirmation
Toast and calling `onBack()`. This guarantees the Room insert **and** the
`ActiveProfileRepository.setActiveProfileId` write are both complete before the screen navigates away
— without the `join()`, a fast back-navigation could race the insert/activate coroutine, and a
subsequently opened Config Sanitization screen could still resolve the *previous* active profile
instead of the one just added.

## Manual recipe: NEW-M2 / M2-a (Russian failure-message regression)

This is the concrete, on-device way to exercise the branch this tool exists for:

1. Settings → About section → **Debug: unrestricted add** (debug builds only).
2. Name: anything (e.g. leave the default `DEBUG raw`). Config: `not json` (any string that is not
   valid, buildable Xray JSON).
3. Tap **Add raw + activate** — waits for insert + activation, then returns to the hub.
4. Set the app language to Russian (Settings → Language).
5. Open **Config sanitization** (Advanced section).
6. Expect the failure text to be **fully Russian** —
   `"Конфиг этого профиля не удалось обработать."` — with **no** trailing/interpolated English
   fragment (the pre-fix bug produced an English tail via `%1$s` string-format interpolation; the
   fixed string is a single self-contained sentence with no placeholder in either locale — English
   reads `"This profile's config couldn't be processed."`).

This is automated as `SanitizationFailureLocalizationTest`
(`app/src/androidTest/java/com/justme/xtls_core_proxy/settings/SanitizationFailureLocalizationTest.kt`)
via direct locale-set + `getString`, and the manual recipe above is the on-device equivalent using the
real UI path (see [`settings-hub.md`](settings-hub.md) for the hub's section layout and row
conventions, and [`config-sanitization.md`](config-sanitization.md) for the failure state this
reaches).

## Components

- `settings/DebugUnrestrictedAddProfileActivity.kt` — the DEBUG-only screen: name field, multi-line
  config field, Add button that awaits `addRawProfile(...).join()` before toasting and finishing.
- `state/VpnViewModel.kt` (`addRawProfile`) — verbatim insert + activation, documented above.
- `settings/SettingsHubActivity.kt` — the `BuildConfig.DEBUG`-gated hub row (About section) that is
  this screen's only in-app entry point.
- `AndroidManifest.xml` — `android:exported="false"` declaration.

## Testing

- `VpnViewModelRawProfileTest` (instrumented) — `addRawProfile` stores the config byte-for-byte (no
  `ConfigBuilder` mutation) and the inserted row becomes the active profile.
- `SanitizationFailureLocalizationTest` (instrumented) — the NEW-M2/M2-a regression: the Russian and
  English Config-Sanitization failure strings are each a self-contained sentence with no `%` format
  placeholder and no English tail in the Russian string.
- No dedicated Compose UI test for `DebugAddScreen` itself — the screen is a thin, debug-only shell
  around `addRawProfile`; the ViewModel method and the downstream failure-message behavior it unlocks
  are what are under test.
