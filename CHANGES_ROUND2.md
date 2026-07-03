# Scanner Bridge — round 2 edits

Covers the Android app changes AND the PC-side (Python) changes for this round.
Build the Android APK exactly as before (GitHub Actions; see HOW_TO_BUILD_APK.md).
The PC side is plain Python you run directly — see the two files in pc_side/.

================================================================
ANDROID APP
================================================================

## Fullscreen "Controls" button was blank
Material's Button applies a theme background TINT over `android:background`,
which painted the dark pill solid cyan and hid the cyan label. Added
`android:backgroundTint="@null"` to the fullscreen Controls button (and the
other custom-drawable buttons: Fullscreen, Scan, Stop, Exit) so the authored
drawable + text show correctly.

## Phone live feed looked compressed / letterboxed (not full size)
AUSBC's AspectRatioTextureView always *fits* (letterboxes) to the camera
aspect, so landscape fullscreen had big black side bars. The preview is now
CENTER-CROPPED to fill the screen in fullscreen: the preview host is sized to
the true frame aspect ratio and overscanned past the screen edges, and the root
clips the overflow — no black bars, YouTube-style. Portrait keeps the normal
fit inside the card.

## Freeze when switching fullscreen <-> portrait (could break the stream)
Root cause: the old code RE-PARENTED the camera TextureView between the card
and the fullscreen overlay. Re-parenting a TextureView destroys its
SurfaceTexture, forcing AUSBC to tear down and rebuild the whole camera
pipeline — a long stall that also interrupted the MJPEG frames to the PC (so
BOTH devices froze, and it sometimes broke).

Fix: the camera now lives in ONE permanent host (`previewHost`) that is a direct
child of the root and is NEVER re-parented. In portrait a layout listener glues
it over a `previewSlot` placeholder in the card; in fullscreen it's just resized
to fill. The activity also declares broad `configChanges` and handles rotation
in-place, so rotating never recreates the activity / camera. Result: instant
fullscreen toggle, no teardown, no freeze on either device.

## Camera controls — parity + real-time sync with the PC
* Added EXPOSURE, SATURATION and WB TEMP sliders to the phone so it has the same
  control set as the PC.
* All phone controls now read/write a shared ControlState that is exposed over
  HTTP, so the PC and phone stay 100% in sync (see "control sync" below).
* Exposure note: AUSBC 3.3.3 has no hardware exposure setter, so the phone maps
  Exposure -> gamma (closest available lever). WB Temp has no wrapper setter
  either, so on the phone it syncs the slider value but is a hardware no-op.
  Both still move in lock-step with the PC.

## New phone HTTP control endpoints (in MjpegServer)
* GET  /controls  -> JSON snapshot of all controls + a version number.
* POST /control   -> set one ({"name","value"}) or many ({"brightness":70,...}).
The phone applies changes to the live UVC camera and notifies the UI; the PC
polls /controls to mirror phone-side changes and POSTs /control to push its own.

================================================================
PC SIDE (Python)  —  see pc_side/
================================================================

## camera_controls_panel.py  (REPLACE your tools/camera_controls_panel.py)
Rewritten with two backends:
* LOCAL (wired/USB): drives cap.set(CAP_PROP_*) as before.
* REMOTE (wireless): drives the PHONE over HTTP. Previously cap.set() did
  nothing on an FFmpeg network stream, so the PC could not adjust the webcam in
  wireless mode — only the phone could. Now the PC POSTs to the phone's
  /control and POLLS /controls, so PC and phone are synced in real time.
* Removed Auto Exposure, Autofocus and Focus.
* Added Exposure (present and synced on both sides).
Control set (both sides): brightness, exposure, contrast, saturation, gain,
sharpness, zoom, wb_temp, auto_wb.

## PC_SIDE_PATCH_ai_scanner_tool.md  (apply 4 small edits to ai_scanner_tool.py)
1. Fix the FFmpeg 30 s stream timeout/freeze: the phone stream is HTTP, not
   RTSP, so the old `stimeout` options were ignored and FFmpeg used its default
   ~30 s blocking read. Set `timeout`/`rw_timeout` to 3 s.
2. Make the wireless grabber RECONNECT instead of giving up when the phone
   briefly pauses frames (e.g. during a fullscreen toggle) — fixes the freeze
   on the PC side too.
3. Point the controls panel at the phone (set_remote_target) when wireless.
4. Pass `wireless=(self.active_source == 0)` into cam_controls.draw().
