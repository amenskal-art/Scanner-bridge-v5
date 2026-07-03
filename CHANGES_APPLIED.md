# Scanner Bridge — edits applied

All requested changes are in the source. Build the APK exactly as before
(GitHub Actions → see HOW_TO_BUILD_APK.md). The build environment here can't
reach Google/Maven/JitPack, so no APK is produced in-chat — your existing
GitHub Actions workflow (`.github/workflows/build-apk.yml`) compiles it.

## 1. "Camera" → "Scanner" everywhere user-visible
- strings.xml: subtitle and waiting text now say "Scanner".
- activity_main.xml: "Live Scanner Feed", "SCANNER CONTROLS", help text,
  pairing steps, and the bottom hint all say scanner.
- MainActivity status texts: "Scanner ready" / "No scanner", pairing/crash
  toasts, etc.
- QrScanActivity hint and the MJPEG placeholder ("starting scanner...").
- Code identifiers (view IDs, AUSBC class names, the CAMERA permission) are
  intentionally left unchanged — renaming those would break the build with no
  visible benefit.

## 2. Empty blue button next to the live feed — fixed
The button next to "0 fps" was the Fullscreen button rendering as a blank blue
pill (no visible label). It now uses the solid primary style with a bold dark
"Fullscreen" label and an explicit min width, so it always shows its text.

## 3. Autofocus removed
The "Autofocus" checkbox is gone from Scanner Controls (it wasn't supported on
the device). The Auto WB toggle remains. All autofocus code paths removed.

## 4. Brightness fixed
Root cause was twofold:
  a) The old code routed Brightness to `setGamma` because of an incorrect note
     claiming AUSBC has no `setBrightness`. It DOES (verified against AUSBC
     3.3.3 source — `CameraUVC.setBrightness`, `UVCCamera.setBrightness` which
     documents its parameter as a percentage). Brightness now calls the real
     `setBrightness`.
  b) UVC control setters only take effect after the camera is open, and the
     initial slider values were never pushed. `applyInitialCameraControls()` is
     now called from `onCameraOpened()`, so the starting positions take effect.
AUSBC scales the 0–100 slider value into the device's absolute range
internally, so the sliders pass 0–100 straight through (no manual mapping).
The "Brightness (gamma)" label is now just "Brightness".

## 5. IP address feature removed
The "This phone: http://…:8080/video" field is gone from the layout and all
code references to `addressText` are removed.

## 6. Native full-size live feed
The webcam request was capped at 1280×720. It now requests 1920×1080 and
accepts the camera's largest supported size (`resolveActualPreviewSize()` picks
the max). The preview already letterboxes to the true aspect ratio
(`setAspectRatio`), so the phone shows the full native frame; the PC already
received full size.

## 7. Professional fullscreen (YouTube-style)
Rewrote fullscreen completely:
- The layout root is now a FrameLayout containing the normal scrollable
  content PLUS a dedicated full-screen overlay.
- Entering fullscreen re-parents the live preview into the overlay so it fills
  the entire display edge-to-edge — no black card borders in landscape.
- Landscape + immersive (status/nav bars hidden), swipe to reveal bars.
- An "Exit" button (and the Back gesture) returns to the normal vertical view,
  like tapping the fullscreen toggle on YouTube.
- A "Controls" button overlaid on the video shows/hides a floating Scanner
  Controls panel during fullscreen ("Controls" ⇄ "Hide"). The same controls
  card is reused (re-parented), so the sliders behave identically.
