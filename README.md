# EdgeTaskbar

A Windows 11-style taskbar for Android (Kotlin). Pinned bar at the bottom, optional Back/Home/Recents at the top,
floating dock on any edge, launch apps into split/quad/free windows. No backend, no analytics.

## Build
Push to GitHub: Actions builds `EdgeTaskbar-debug-apk` (Actions tab, latest run, Artifacts). Or open in Android Studio.

## Setup on the phone
1. Open the app, tap **Grant overlay permission**, allow it, go back, tap **Start taskbar**.
2. **Choose apps** to pin. Swipe the icons to page; minimize button collapses the bar to a handle.
3. Optional: Back/Home/Recents at the top needs the Accessibility service (Android 13+ sideload: App info, menu, Allow restricted settings).
4. Optional: set EdgeTaskbar as home app.
5. Xiaomi/Oppo/Vivo/Tecno: set Battery to No restrictions and allow pop-up windows from background.

## Honest limits
- Android cannot resize other apps around a dock, so the bar floats above them; use minimize when you need the whole screen.
- The system navigation bar cannot be hidden or moved; use Gesture navigation for a clean bottom edge.
- Multi-window: Android draws/moves/resizes the windows. Needs free-form support (Developer options); apps that block resizing open full screen. Audio is decided by each app.
- Keyboard detection from an overlay is best effort.
- Start-after-reboot may be blocked by the phone maker.
