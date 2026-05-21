# Localization

Maintainer reference for the in-app language picker: user-chosen UI language (Auto / English / Russian), persisted across cold starts, applied to every Activity and the foreground service.

## Why this exists

Per-app language is required for users whose system locale doesn't match the language they want the app in (common for Russian-speaking users on devices configured in English by their carrier) and for staged rollout of human-reviewed translations (Russian shipped; Persian designed-for but deferred).

The naive AppCompat-based path *does not work in this app* and was the source of two distinct shipped-broken bugs before this design landed. See [The AppCompatDelegate trap](#the-appcompatdelegate-trap) below for the exact failure modes.

## State machine

```
[App start] ──XtlsApplication.attachBaseContext──> [Read tag from SharedPreferences]
                                                                │
                                                                ▼
                                                    [SupportedLanguage.applyInternal]
                                                                │
                                                                ├── API 33+ ──> LocaleManager.setApplicationLocales
                                                                └── API <33 ──> AppCompatDelegate.setApplicationLocales
                                                                │
                                                                ▼
                                          [Activity.attachBaseContext wraps with locale-overridden Configuration]

[User picks language] ──SupportedLanguage.apply──> [Write tag to SharedPreferences]
                                                                │
                                                                ▼
                                                        [applyInternal] (same as above)
                                                                │
                                                                ▼
                       [LocalizedComponentActivity.onResume detects mismatch, recreate() ]
                       (back-stack activities only; the foreground one is recreated by the system on API 33+)
```

## Components

The feature lives in [app/src/main/java/com/justme/xtls_core_proxy/i18n/](../../app/src/main/java/com/justme/xtls_core_proxy/i18n/) plus the [`XtlsApplication`](../../app/src/main/java/com/justme/xtls_core_proxy/XtlsApplication.kt) entry point.

| File | Responsibility |
|---|---|
| [`SupportedLanguage.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/i18n/SupportedLanguage.kt) | The locale engine. Enum of supported languages plus companion helpers: `apply(context, language)`, `applyFromStorage(context)`, `current(context)`, `readLocales(context)`, `localize(base)`. Owns the SharedPreferences-backed persistence. |
| [`LocalizedComponentActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/i18n/LocalizedComponentActivity.kt) | Base class every UI Activity extends. `attachBaseContext` wraps the base context via `SupportedLanguage.localize`; `onResume` self-recreates when the chosen locale no longer matches what `attachBaseContext` applied (handles back-stack activities). |
| [`LanguageSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/i18n/LanguageSettingsActivity.kt) | Compose UI: radio-row picker with Auto / English / Русский. Calls `SupportedLanguage.apply(this, language)` and `finish()`. |
| [`XtlsApplication.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/XtlsApplication.kt) | Application subclass. In `attachBaseContext` calls `SupportedLanguage.applyFromStorage(base)` so the chosen locale is re-applied before any Activity is constructed. Registered via `android:name=".XtlsApplication"` in the manifest. |

Strings live in [`app/src/main/res/values/strings.xml`](../../app/src/main/res/values/strings.xml) (English, source of truth) and [`app/src/main/res/values-ru/strings.xml`](../../app/src/main/res/values-ru/strings.xml) (Russian). Supported locales are declared in [`app/src/main/res/xml/locales_config.xml`](../../app/src/main/res/xml/locales_config.xml) and referenced from the manifest via `android:localeConfig` — **required by Android 14+** for `LocaleManager.setApplicationLocales` to accept the locale.

The foreground service [`XrayVpnService`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) uses a private `localizedString(@StringRes id, vararg args)` helper that routes every notification-bound `getString` through `SupportedLanguage.localize(this)`. Service contexts don't auto-refresh their `Configuration` on locale change on API <33, so this wrap is the only way notification text reflects a mid-session language switch.

Subscription refresh ([`SubscriptionRefreshCoordinator`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt)) calls `SupportedLanguage.localize(context)` once at refresh start and uses that context for every `getString` inside the IO coroutine. Callers ([`VpnViewModel.refreshSubscription`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) takers) must pass an Activity context, not `getApplication()` — see [`MainActivity.kt:178,332`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) and [`SubscriptionsActivity.kt:106`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionsActivity.kt).

## The AppCompatDelegate trap

`AppCompatDelegate.setApplicationLocales(...)` is the obvious API and what every tutorial recommends. It silently no-ops in this codebase on **both** Android tiers, for two different reasons:

**API 33+:** AppCompatDelegate's static path looks up `LocaleManager` via `sContextRef`, a `WeakReference<Context>` initialized only when an `AppCompatActivity` is instantiated. We extend `ComponentActivity` (via `LocalizedComponentActivity`) so `sContextRef` stays null and the entire setter is a silent no-op. Confirmed by logging `getApplicationLocales()` before/after `setApplicationLocales("ru")` and seeing both return empty.

**API 30–32:** AppCompat's autoStore persistence layer reads from a static field that's only populated when AppCompat's restore path runs — also gated on AppCompatActivity instantiation. So even if the user picked Russian in a previous session, on cold start the static field stays empty. Confirmed empirically on an API 30 device.

The fix in both tiers is to **bypass AppCompatDelegate**:

- API 33+: `context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ru")`. The system handles persistence.
- API <33: `AppCompatDelegate.setApplicationLocales(...)` does still work on this path (the static-field-only mode) — it just doesn't survive process death. We compensate with our own SharedPreferences-backed persistence: `apply()` writes the chosen tag; `XtlsApplication.attachBaseContext` reads it on every process start via `applyFromStorage(...)`.

This is why we have an `Application` subclass for what is otherwise a one-and-done preference: the prefs read has to happen *before* any Activity is constructed, and `Application.attachBaseContext` is the earliest hook available.

## Recreate-on-resume

`LocaleManager.setApplicationLocales` (and AppCompat's pre-T path) recreate the **foreground** activity, not back-stack activities. After the user picks Russian in `LanguageSettingsActivity` and `finish()` is called, `SettingsHubActivity` resumes — but with its original `Configuration` from before the locale change. `LocalizedComponentActivity.onResume` compares the locale that was applied in `attachBaseContext` against the current chosen locale; on mismatch, calls `recreate()`. Same pattern handles deep back-stacks: if the user navigates further back, every `LocalizedComponentActivity` on the stack picks up the new locale on its first resume.

`recreate()` after the new instance's `attachBaseContext` ran with the new locale is a no-op (mismatch is false), so there's no infinite loop and no double-recreate.

## How to add a new language

1. Translate strings to a new `values-<lang>/strings.xml`. **Every key in the source `values/strings.xml` must have a counterpart**, or `MissingTranslation` lint will fail the build.
2. Add the BCP-47 tag to [`res/xml/locales_config.xml`](../../app/src/main/res/xml/locales_config.xml). Without this entry, `LocaleManager.setApplicationLocales` silently rejects the call on Android 14+.
3. Add a new value to the `SupportedLanguage` enum with the matching `tag`.
4. Surface it in [`LanguageSettingsActivity.LanguageSettingsScreen`](../../app/src/main/java/com/justme/xtls_core_proxy/i18n/LanguageSettingsActivity.kt) — one more `LanguageRow` and a `when` branch in `SettingsHubScreen`'s `langLabel`.
5. If the translation is machine-only / not human-reviewed, render a disclaimer string below that option in the picker. The Persian `TODO(localization)` comment in `SupportedLanguage` is the marker for this.

No code changes elsewhere; the engine is locale-agnostic.

## Known limitations

**Notification channel name caching.** `NotificationChannel.name` and `description` are localized at channel-creation time and the system caches the value forever (until the channel is recreated with a new ID). A user who installs the app in English then switches to Russian will see the channel still listed in English in *Settings → Apps → Notifications*. Channels created fresh after the switch (i.e., on first launch after install in the new locale) are in the new language. Documented in the `localizedString` helper's KDoc; unfixable without rotating channel IDs, which would orphan user notification settings.

**The `applyFromStorage` path runs on every process start.** That's by design — it's the persistence-restore mechanism. It's a single `SharedPreferences` read and one `LocaleManager` setter call, sub-millisecond, but worth knowing if anyone profiles cold start.

**System-driven locale changes don't write to our prefs.** Theoretical: a user could set the per-app language via *Settings → System → App languages* (Android 13+'s OS-level picker) without going through our in-app picker. Our SharedPreferences would not reflect that choice, so on next process start `applyFromStorage` would overwrite the OS-set value with our last-known prefs value. In practice the in-app picker is the only documented way to change it and the system page is well-hidden; not worth the synchronization complexity until it bites someone.

## Testing

**Unit:** [`SupportedLanguageTagTest`](../../app/src/test/java/com/justme/xtls_core_proxy/i18n/SupportedLanguageTagTest.kt) covers the `fromTag` round-trip and enum tag mapping — JVM-only, no Android dependencies.

**Instrumented:** [`LanguagePickerTest`](../../app/src/androidTest/java/com/justme/xtls_core_proxy/i18n/LanguagePickerTest.kt) drives the picker through `SupportedLanguage.apply(context, language)` — the same production code path — and verifies:

- A freshly launched `SettingsHubActivity` renders in the chosen locale (proves `XtlsApplication.attachBaseContext` → `applyFromStorage` → `attachBaseContext` wrapping wiring).
- An activity in the back stack picks up a mid-session locale change on resume (proves `LocalizedComponentActivity.onResume` recreate logic).

Both tests *must* call `SupportedLanguage.apply`, not `AppCompatDelegate.setApplicationLocales` directly — the latter is the trap described above and would let the tests pass while production is broken. The previous iteration of this test made exactly that mistake and was caught in review.

**Lint:** `:app:lintDebug` enforces `MissingTranslation` (every English key has a Russian counterpart) and `HardcodedText` (no user-visible literal in Kotlin source). Both must stay green.

## Future work

- **Persian (`fa`)** translation. Picker entry is held back until a machine-translated baseline plus the "not human-reviewed" disclaimer string is in. Insertion points are marked with `TODO(localization)` comments in `SupportedLanguage.kt`.
- **RTL layout verification** lands with Persian.
- **System per-app-language sync** if it ever becomes a real complaint — observe the OS-level locale on resume and reconcile against our prefs.
