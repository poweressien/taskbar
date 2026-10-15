# EdgeTaskbar

Native Android (Kotlin) floating dock inspired by the Windows 11 taskbar. Package `com.inwekobong.edgetaskbar`, minSdk 26, target/compile 36.

## Build
1. Open this folder in Android Studio (Ladybug or newer). It will create the Gradle wrapper and sync.
   - Or from a terminal with Gradle 8.11+ and an SDK: copy `local.properties.example` to `local.properties`, set `sdk.dir`, run `gradle assembleDebug`.
2. APK: `app/build/outputs/apk/debug/app-debug.apk`.
3. Unit tests: `gradle testDebugUnitTest`.

Versions: AGP 8.9.1, Kotlin 2.0.21, DataStore 1.1.1.

## Not yet verified
This code was written without an Android SDK available, so it has **not been compiled or run**. Expect to fix a few compile errors on first sync, and test on your phone: overlay placement/insets, handle drag, outside-tap collapse, boot start, and keyboard detection.

## Architecture
- `data/` DataStore settings + pinned/recent lists (package names, ordered), `AppCatalog` (PackageManager, cached, launcher `<queries>`, no QUERY_ALL_PACKAGES).
- `overlay/OverlayManager` owns the windows: small handle, dock (ACTION_OUTSIDE collapses), 1px non-touchable keyboard probe. No full-screen touch interceptor.
- `overlay/OverlayService` foreground service (`specialUse`, with Stop and Hide/Show notification actions). Idempotent start/stop, stops itself when overlay permission is revoked.
- `boot/BootReceiver` opt-in start after reboot; posts a notification if blocked.
- `ui/` onboarding/permission, settings, app picker (search, pin/unpin, drag reorder, swipe to unpin). Views are built in code rather than XML.

## Known limits
- Android cannot make other apps resize around the dock; it floats above them.
- Keyboard detection from an overlay is best effort and may not fire on some devices/IMEs.
- Recents show only apps launched from this dock.
- Auto-start after reboot may be blocked by OEM firmware; `specialUse` foreground services need a Play Console declaration if published.
- Pre-API-30 inset handling is approximate.
