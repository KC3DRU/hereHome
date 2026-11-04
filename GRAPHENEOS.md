# GRAPHENEOS — de-Googled / GrapheneOS compatibility review

Scope: every source file in this repo, evaluated against a device running **stock Android**
or **GrapheneOS with no Google Play Services and no microG**. This is a review document; no
code was changed by it. Recommended fixes are listed but not applied.

## Verdict

**The app works on GrapheneOS today, by design — nothing in it will break for lack of Google
Play Services.** Location uses the bare framework `LocationManager`, SMS uses the framework
`SmsManager`, dependencies are pure AndroidX/Maven-Central, and every `PendingIntent` already
carries `FLAG_IMMUTABLE` (Android 14 requirement satisfied); widget-tap → activity starts are
user-initiated from the launcher, which remains allowed under Android 14+ background-activity
restrictions. What remains are **degradations and cosmetics**: the location provider fallback
loop is needlessly gated behind API 30 (dead code path on Android 8/9 devices, which is most of
what GrapheneOS runs), no last-known-location fast path means a cold-fix wait on every tap,
"success" in the UI means handed-to-framework rather than delivered, and
`android.enableJetifier=true` is dead build overhead.

## Findings

| ID | Severity | Where | Issue | Recommended fix |
|----|----------|-------|-------|-----------------|
| G-1 | **Degrades** (old devices) | `app/src/main/java/com/example/here/LocationHelper.kt`, `getBestProvider()` | Provider fallback iterates `locationManager.getProviders(true)` only under `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)`. On a GrapheneOS/stock device below API 30 where neither `GPS_PROVIDER` nor `NETWORK_PROVIDER` is registered but another enabled provider exists, the function returns `null` and the app aborts with `"No location provider available. Enable GPS in Settings."` even though a provider exists. (`getProviders(boolean)` has existed since API 1 — the version gate is unnecessary.) | Drop the `SDK_INT >= R` gate and always fall through to the enabled-providers loop. |
| G-2 | **Degrades** (UX, both targets) | `LocationHelper.kt` | `getLastKnownLocation()` is never consulted — every widget tap requests a fresh fix and can burn the full 30 s window, most visibly on a cold GPS start indoors. There is no fast path at all. | Before `requestSingleUpdate`, check `getLastKnownLocation(provider)` for each enabled provider and accept a fix younger than ~60 s; fall back to the live request otherwise. (Privacy trade-off is acceptable: the cached fix is discarded unless the user taps a widget.) |
| G-3 | Info — works today | `LocationHelper.kt` | `locationManager.requestSingleUpdate(...)` is deprecated since API 30 but fully functional on AOSP/GrapheneOS. The 30 s `withTimeout` + `removeUpdates(listener)` path is correct: if a provider never calls back (disabled mid-request, sky-blocked GPS), the deferred times out, the listener is unregistered, and `LocationUnavailableException` surfaces to the UI — no listener leak. | If ever modernised, switch to `requestLocationUpdates(provider, 0, 0f, listener)` + `removeUpdates`; no urgency. |
| G-4 | **Cosmetic** | `widget_arrived.xml:20`, `widget_heading_home.xml:20`, `widget_moving_on.xml:20` | Button labels embed emoji (`📍`, `🏠`, `🚗`). Stock Android and GrapheneOS ship Noto Color Emoji, so these render; only a font-restricted custom ROM would show tofu boxes. Text remains readable either way. | None needed; optional vector-drawable icons if ever targeting font-stripped builds. |
| G-5 | Info — works | `widget_arrived.xml:13`, `widget_heading_home.xml:13`, `widget_moving_on.xml:13` | Icons are framework resources `@android:drawable/ic_menu_mylocation`, `ic_dialog_map`, `ic_menu_send`. All three are AOSP framework drawables (deprecated at API 21 in favour of material icons but still present in the framework resource table on AOSP and therefore GrapheneOS). | None; optionally bundle own vectors to be immune to future framework cleanup. |
| G-6 | Info — degraded condition handled | `LocationHelper.kt`, `getBestProvider()` | On GrapheneOS there may be no `NETWORK_PROVIDER` unless the user has enabled the bundled network-location add-on (Ichnusa/Moloka) or a third one. The GPS-first order plus the enabled-providers loop means the app still finds `gps`; it simply loses the faster assisted fallback, so indoor fixes depend on whatever network location the user configured. | Document in README that enabling the OS network-location provider improves fix times; see also G-1/G-2. |
| G-7 | **Degrades** (misleading success) | `app/src/main/java/com/example/here/SmsSender.kt` | `sendSms()` returns `Result.success(parts)` as soon as `smsManager.sendTextMessage(...)`/`sendMultipartTextMessage(...)` hand off — delivery is never confirmed. The sent `PendingIntent` (`Intent("sms_sent")`) has no registered receiver, so even the framework's sent-callback is discarded. On a SIM-less or out-of-coverage device the UI can show "✅ All sent" while nothing left the radio. (No-SIM is partly pre-guarded: `MainActivity.requestSmsPermission()` checks `tm.phoneType == TelephonyManager.PHONE_TYPE_NONE` and explains the device can't SMS.) Multi-part splitting itself is the standard `divideMessage` + `sendMultipartTextMessage` path and works on GrapheneOS's Telephony stack. | Register a sent-receiver for the per-part `PendingIntent` (or at least `observe` via `ACTION_SMS_SENT`), or relabel UI text from "sent" to "queued". |
| G-8 | Info — works, verified | `ArrivedWidget.kt` / `HeadingHomeWidget.kt` / `MovingOnWidget.kt` + `MainActivity.kt` | Widgets launch `MainActivity` via `PendingIntent.getActivity(context, appWidgetId, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`. Android 14+ background-activity-start restrictions do **not** block this: the tap is user-initiated input on the launcher (a system-owned surface). `FLAG_IMMUTABLE` is the correct, targetSdk-31-mandatory choice, and using `appWidgetId` as the request code keeps one distinct `PendingIntent` per widget instance. `MainActivity` is `launchMode="singleTop"` in the manifest and overrides `onNewIntent` → `handleIntent`, so repeat taps while the activity is alive are delivered instead of lost. | None. |
| G-9 | Info — sufficient | `MainActivity.kt`, `requestPermissionLauncher` | `SEND_SMS` and `ACCESS_FINE_LOCATION` are requested via `ActivityResultContracts.RequestPermission()` — the standard AOSP permission dialog. GrapheneOS uses the stock permission UI (with per-app permission controls), so this is sufficient; no vendor-specific grant flow exists or is needed. | None. |
| G-10 | **Cosmetic** (build) | `gradle.properties` | `android.enableJetifier=true` — every dependency is AndroidX or Maven-Normal (see Verified clean); there is no legacy `android.support.*` artifact for Jetifier to rewrite, so the flag only slows the build. | Remove the line. |
| G-11 | **Cosmetic** | `app/src/main/AndroidManifest.xml:2` | `xmlns:tools` is declared but no `tools:` attribute is used anywhere in the manifest. | Remove the namespace or use `tools:ignore` where intended. |

## Verified clean

Checked by reading every tracked source file plus `git grep` over `app/`:

1. **No Play Services / Firebase / microG anywhere.** No `com.google.android.gms`,
   `firebase`, or `microg` string in any `.kt`, `.kts`, or `.xml` under `app/` (the only former
   GMS mention was two dead `-keep` rules in `app/proguard-rules.pro`, already deleted per
   `SECURITY-REVIEW.md` SR-5). `app/build.gradle.kts` dependencies are exclusively
   `androidx.*`, `com.google.android.material` (standalone UI library, not Play Services), and
   `org.jetbrains.kotlinx:kotlinx-coroutines-android`. `settings.gradle.kts` resolves from
   `google()` + `mavenCentral()` only — no vendor repos that could smuggle GMS transitively.
2. **Location stack is de-Googled by construction.** `LocationHelper` uses
   `android.location.LocationManager` (`GPS_PROVIDER`, `NETWORK_PROVIDER`, then any enabled
   provider) — no `FusedLocationProviderClient`, no location SDK. GrapheneOS's Ichnusa network
   provider (when enabled) appears as an ordinary enabled provider and is picked up by the
   generic loop on API 30+.
3. **SMS stack is framework-only.** `context.getSystemService(android.telephony.SmsManager::class.java)`
   is the modern, non-deprecated accessor (`SmsManager.getDefault()` is not used; it was
   deprecated at API 31). Multi-part send uses the standard framework split path. No third-party
   SMS gateway.
4. **Android 14 / targetSdk 34 posture.** All four `PendingIntent` constructions carry an explicit
   `FLAG_IMMUTABLE`; both `<receiver>` widget providers are `exported="true"` (required for
   `APPWIDGET_UPDATE`); `MainActivity` is `exported="true"` with a LAUNCHER filter only — no
   implicit deep links. No foreground services, no exact alarms, no trampoline broadcasts.
5. **Widget plumbing.** `android:taskAffinity=""` on `<application>` cleanly isolates the
   widget-launched task from the launcher; every widget layout keeps the shared
   `@+id/widgetButton` that all three providers bind click handlers to.
   All three `<receiver>` widget providers are declared and verified present in the built
   APK manifest: `ArrivedWidget`, `HeadingHomeWidget`, `MovingOnWidget`.
6. **No network code at all.** No socket/HTTP/URL-connection usage; the Maps URL is plain text —
   consistent with the removed `INTERNET` permission (SR-4) and the README's claims.
7. **No device-signal assumptions beyond telephony.** `TelephonyManager` is used only for the
   `PHONE_TYPE_NONE` capability check, which correctly degrades on Wi-Fi-only devices instead of
   crashing.

## Build verification

Run from the repo root on the tree containing the SR-1..SR-6 fixes, JDK 21, Android SDK 34,
Gradle 8.9. No source change was needed to make either build pass.

| Command | Result |
|---|---|
| `bash build.sh` (→ `./gradlew assembleDebug --no-daemon`) | **PASS** — `BUILD SUCCESSFUL in 54s`, 37 tasks |
| `./gradlew assembleRelease --no-daemon` | **PASS** — `BUILD SUCCESSFUL in 1m 18s`, 46 tasks, R8 minification and `lintVitalRelease` clean |

One initial `bash build.sh` attempt failed in Gradle *configuration* with
`Could not resolve all artifacts for configuration ':classpath'` — every failing artifact
was a `repo.maven.apache.org` download and the bare cause string was the hostname
(UnknownHostException signature). Verified transient: plain `curl` and a direct
`java.net.HttpURLConnection` probe to the very same URL returned HTTP 200, and the immediate
re-run of the identical command succeeded. No `build.gradle.kts` or repository change was
made.

Artifacts (`ls -l`, repo-relative):

```
-rw-rw-r-- 1 <user> <user> 11474808 app/build/outputs/apk/debug/app-debug.apk
-rw-rw-r-- 1 <user> <user>  2129573 app/build/outputs/apk/release/app-release-unsigned.apk
-rw-rw-r-- 1 <user> <user> 11474808 app/build/outputs/hereHome.apk   (debug copy made by build.sh)
```

**Release signing:** expected and not a defect — no keystore exists in the repo (and
`SECURITY-REVIEW.md` SR-6 keeps it that way), so `assembleRelease` produced
`app-release-unsigned.apk`. The unsigned APK is **not installable** as-is (Android rejects
un-signed packages; `adb install` fails with `INSTALL_PARSE_FAILED_NO_CERTIFICATES` /
`adb: Failed to parse APK ... no signature`). It is correct R8-processed packaging and becomes
installable once signed with a release key (`apksigner sign`, or a `signingConfig` wired into
the `release` build type). The debug-signed `app-debug.apk` (also copied to
`app/build/outputs/hereHome.apk`) is directly installable on any device, including GrapheneOS
with no Play Services.

## Applied in v1.1

Findings from this review implemented in versionCode 2 / versionName 1.1 (both builds re-run
green afterwards; `aapt dump badging` confirms `versionCode='2' versionName='1.1'` and all
three widget receivers in the packaged manifest):

| ID | Status | What changed |
|----|--------|--------------|
| G-1 | **Fixed** | `getBestProvider()` and its `Build.VERSION_CODES.R` gate deleted from `LocationHelper.kt`. The enabled set now comes straight from `locationManager.getProviders(true)`; empty → `LocationUnavailableException("No location provider available. Enable GPS in Settings.")`. |
| G-2 | **Fixed** | `getCurrentLocation()` now has a fast path: `getLastKnownLocation(p)` for every enabled provider, and the **most accurate fix younger than 60 s** (`MAX_CACHE_AGE_MS`) is returned immediately with no waiting. Without a fresh cache it **races every enabled provider** — `requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())` per provider, each registration individually guarded (`SecurityException`/`IllegalArgumentException` skip only that provider), shared `LocationListener` completing on the first fix with `time > 0`. The 30 s timeout is kept; a `finally` block calls `removeUpdates(listener)` on **every** exit path (no-op after the success-path removal), so the listener can never outlive the call, including partial-registration cases. KDoc updated. |
| G-3 | Not changed | As recommended — `requestSingleUpdate` deprecation left alone; the rewritten code no longer uses it at all, superseding the concern with `requestLocationUpdates` + shared-listener racing. |
| G-4, G-5 | Not changed | Cosmetic, per review. |
| G-6 | Improved | Coarse/approximate grants now work end-to-end (see G-12), so a GrapheneOS device without the GPS lock but with any enabled network provider still produces a fix instead of a hard failure. |
| G-7 | **Fixed — full implementation, no fallback taken** | `SmsSender` gained `ACTION_SMS_SENT = "io.github.kc3dru.here.SMS_SENT"` and `EXTRA_RECIPIENT`; a private per-call `PendingIntent` (`Intent(ACTION_SMS_SENT).setPackage(context.packageName)` carrying the recipient, monotonic `AtomicInteger` request code, `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`) is now passed to **both** `sendTextMessage` and `sendMultipartTextMessage`, replacing the old receiverless, request-code-0 `Intent("sms_sent")` that collided across parts. `MainActivity` registers a `BroadcastReceiver` for the action via `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` (unregistered in `onDestroy`), counts acknowledged (`resultCode == RESULT_OK`) vs rejected sends per batch, and updates `tvStatus`: `📤 Handed to radio: n/N`, then `✅ Sent` or `❌ Radio rejected N`. The immediate post-handoff wording is now truthful — `📤 Queued with radio for N recipient(s)` / button `📤 Queued` — instead of claiming "✅ Sent". Confirmation dialog kept. |
| G-8, G-9 | Not changed | Verified-working items; nothing to do. |
| G-10 | **Fixed** | `android.enableJetifier=true` deleted from `gradle.properties` (all dependencies are AndroidX; debug APK also shrank ~11.5 MB → ~6.1 MB). |
| G-11 | **Fixed** | Unused `xmlns:tools` declaration removed from `AndroidManifest.xml`. |
| G-12 | **Added & fixed** | Approximate-only location is now accepted: `getCurrentLocation()` proceeds with EITHER `ACCESS_FINE_LOCATION` OR `ACCESS_COARSE_LOCATION` (hard `SecurityException` only when both are denied; providers needing fine simply fail their guarded registration); `hasLocationPermission()` is the same OR check and a new `hasFineLocationPermission()` covers the exact-only case; `MainActivity.checkPermissions()` shows `⚠️ GPS permission: approximate only` with the upgrade button still visible when coarse is granted but fine is not. |
