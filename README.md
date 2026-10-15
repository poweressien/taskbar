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

## Bottom bar, top nav buttons, home screen, windows
- **Pinned bar** (Settings, on by default): full-width dock fixed above the bottom inset, always expanded, never collapses.
- **Top nav buttons** (Back/Home/Recents): drawn and performed by `NavAccessibilityService`. It reads no screen content. Enable it in Accessibility settings (Android 13+ sideloads: App info > menu > Allow restricted settings).
- **Home screen**: `HomeActivity` is an optional launcher. Pick it via Settings > Set as home app. It shows your wallpaper and an app grid clear of both bars.
- **Windows**: dock button cycles Full / Split / Quad / Free-form. Apps launch into slots using launch bounds; Android draws, drags and resizes the windows. Needs multi-window support (free-form usually needs developer options). Apps that disable resizing open full screen.
- Android cannot hide or move its own nav bar: use Gestures navigation for a clean bottom edge. Apps are not resized around the bars, only windows launched by EdgeTaskbar respect them.

## Known limits
- Android cannot make other apps resize around the dock; it floats above them.
- Keyboard detection from an overlay is best effort and may not fire on some devices/IMEs.
- Recents show only apps launched from this dock.
- Auto-start after reboot may be blocked by OEM firmware; `specialUse` foreground services need a Play Console declaration if published.
- Pre-API-30 inset handling is approximate.
