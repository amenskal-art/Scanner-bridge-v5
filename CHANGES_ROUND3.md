# Scanner Bridge — round 3 fixes

Three issues from this round, plus what changed.

================================================================
1. Phone freezes / crashes ("isn't responding") when adjusting controls
================================================================
CAUSE: every SeekBar move called the AUSBC UVC setter directly on the UI
thread, inside onProgressChanged (which fires many times per second while
dragging). Each UVC setter does a synchronous USB control transfer that can
block ~10-40 ms, so a drag flooded the main thread -> ANR -> crash.

FIX (MainActivity):
* All camera-control writes now go through a SINGLE background worker thread
  that COALESCES the latest value per control (a fast drag collapses to the
  newest value) and throttles writes (~30 ms apart) so the USB device is never
  saturated. The UI thread only enqueues — it never touches the device.
* This applies to BOTH user drags AND PC-pushed changes AND the initial values
  applied on camera open. None of them block the UI thread anymore.
* applyInitialCameraControls now reads the SeekBar values on the UI thread and
  enqueues the camera writes, so we never read a View off-thread either.

================================================================
2. Exposure didn't work on any device
================================================================
PC (wired): CAP_PROP_EXPOSURE is IGNORED while the camera is in auto-exposure
mode. We had removed the auto-exposure control, so the camera stayed in auto
and the exposure slider did nothing. FIX (camera_controls_panel.py): when the
exposure slider changes, the panel first switches the device to MANUAL exposure
(CAP_PROP_AUTO_EXPOSURE = 0.25, the DirectShow "manual" value) and then sets the
exposure — so it now takes effect. (No auto-exposure UI is shown; it's handled
internally.)

Phone / PC-wireless: AUSBC 3.3.3's CameraUVC wrapper exposes NO hardware
exposure setter (the native binding is private and unreachable without
reflection). The phone therefore maps Exposure -> gamma, the exposure-adjacent
UVC control. The 0..100 value still syncs 1:1 with the PC. HONEST CAVEAT: if a
specific webcam doesn't implement the UVC gamma control, the phone's Exposure
slider may have little visible effect on the phone preview — that's a hardware
limitation of that webcam, not a wiring bug. Brightness (real setBrightness),
contrast, gain, saturation, sharpness and zoom are unaffected.

================================================================
3. Live feed lagged behind the page when scrolling
================================================================
CAUSE: the previous fix put the camera preview in a root-level overlay that was
repositioned by a global-layout listener as you scrolled. The listener runs
slightly behind the scroll, so the video visibly "floated" and caught up a
moment later.

FIX (layout + MainActivity): the camera preview now lives DIRECTLY inside the
scrolling content (its normal card), so it scrolls natively with the page —
zero lag, no overlay, no listener.

The freeze-free fullscreen is preserved WITHOUT re-parenting: entering
fullscreen expands the SAME preview card in place (hide the other cards + the
header/padding, stretch the card to the screen height, lock scroll, go
landscape + immersive). The camera TextureView never leaves its parent, so its
SurfaceTexture is never destroyed -> no camera-pipeline rebuild -> no freeze on
the fullscreen toggle, and the activity handles rotation in-place
(configChanges) so rotating never recreates it either.

================================================================
Files
================================================================
Android (build the APK as before via GitHub Actions):
  app/src/main/res/layout/activity_main.xml   (preview back in-content)
  app/src/main/java/.../ui/MainActivity.kt     (control worker, in-place fullscreen)
  app/src/main/java/.../ui/CameraBridgeFragment.kt (exposure mapping note)

PC (pc_side/):
  camera_controls_panel.py   (REPLACE tools/camera_controls_panel.py — exposure
                              manual-mode fix is here)
  ai_scanner_tool.py         (full edited tool from the previous round; unchanged
                              this round)
  PC_SIDE_PATCH_ai_scanner_tool.md (the 4 edits, if you prefer patching by hand)
