# Scanner Bridge (Android)

Turns an Android phone into a **bridge** between a USB-C UVC webcam and your
PC's Scanner Pro tool. The pairing is done by the **webcam reading a QR code off
the PC screen** — no typing, no PC webcam needed.

```
                 (1) PC shows a QR  ───────────────┐
                                                    ▼ webcam looks at PC screen
USB webcam ──USB-C──▶ Android  ──(2) decodes QR (PC ip+token)
                         │
                         ├─(3) starts MJPEG server on :8080
                         └─(4) POSTs its own IP to the PC's pairing gate
                                                    │
                              (5) PC auto-connects ◀┘ to phone:8080/video
                                                    ▼
                         live feed: phone ──Wi-Fi MJPEG──▶ PC Wireless tab
```

### The flow in words
1. On the PC: Scanner Pro → Live Scanner Stream → **Wireless** tab → **Pair phone (QR)**.
   The PC shows a QR encoding `{pc_ip, pairing_port, token}` and starts a small
   listener ("the gate").
2. On the phone: plug in the USB-C webcam, open Scanner Bridge, tap **Scan PC Code**.
   The app scans the **live webcam frames** for the QR — point the webcam at the
   PC screen.
3. When decoded, the phone starts its MJPEG server and **POSTs its own IP** back
   to `http://<pc_ip>:<pairing_port>/pair` (like sending a message).
4. The PC gate receives it, validates the token, and **auto-connects** the
   wireless stream to `http://<phone_ip>:8080/video`.

Both devices must be on the **same Wi-Fi/LAN**. The token in the QR stops any
other device from pairing.

## Why MJPEG
Your PC tool already opens wireless cameras with
`cv2.VideoCapture(url, CAP_FFMPEG)` and a default of
`http://192.168.1.100:8080/video`. The app serves exactly that on `:8080/video`,
so the live stream is a drop-in source — only the small pairing-gate hooks are
added on the PC (see `PC_SIDE_integration_guide.md`).

## Build
1. Open this folder in **Android Studio** (Giraffe or newer).
2. Let it sync (AUSBC from JitPack, ZXing from Maven Central).
3. Connect an Android phone with **USB-OTG** support; enable USB debugging.
4. Run. Grant Camera + Notifications on first launch.

> AUSBC needs `armeabi-v7a` / `arm64-v8a` and OTG support. No root.

## Project layout
```
app/src/main/
  AndroidManifest.xml
  java/com/scannerbridge/bridge/
    ui/MainActivity.kt            # scan flow, pairing, stats, UI
    ui/CameraBridgeFragment.kt    # AUSBC UVC capture; NV21 -> bridge + QR scan
    server/MjpegServer.kt         # multipart/x-mixed-replace HTTP server
    server/FrameBridge.kt         # NV21 -> JPEG, pushes to server
    server/StreamForegroundService.kt  # keep-alive + wake lock
    util/QrDecoder.kt             # ZXing QR decode straight off NV21 frames
    util/PairingClient.kt         # parse PC QR + POST phone IP to the gate
    util/NetworkUtils.kt          # phone LAN IP
  res/...                         # dark-slate + cyan premium UI

PC_SIDE_scanner_pairing_gate.py   # drop into tools/ on the PC
PC_SIDE_integration_guide.md      # 5 edits to ai_scanner_tool.py
```

## Tuning
- JPEG quality / FPS: `FrameBridge.jpegQuality` (70), `MjpegServer.TARGET_FPS` (30).
- Capture resolution: `CameraBridgeFragment.reqWidth/reqHeight` (1280x720).
- Pairing port: `PairingGateMixin.PAIR_PORT` on the PC (8765) — keep both sides equal.

## Known constraints
- Guest / "client isolation" Wi-Fi blocks phone↔PC traffic. Use a normal LAN or
  the phone's hotspot (PC joins it).
- The QR must be reasonably sharp on the PC screen for the webcam to read it;
  the scanner retries inverted automatically.
- AUSBC's `onPreviewData` signature has varied across 3.x. This targets 3.3.3's
  `onPreviewData(data, format)`. If Studio flags the override, match your
  version's signature — body is unchanged.

## AUSBC 3.3.3 API notes (already applied)

This project is wired for the **3.3.3** artifact as published in the Liferay
Maven mirror, which differs slightly from AUSBC's GitHub master:

- `CameraRequest.Builder` in 3.3.3 has **no** `setPreviewFormat(...)`. It's
  omitted; the engine uses its default and `setRawPreviewData(true)` still
  delivers NV21 frames to our callback.
- `IPreviewDataCallBack.onPreviewData` in 3.3.3 takes **four** args
  `(data, width, height, format)`. The fragment uses that signature and reads
  the real width/height straight from the callback.

If you ever bump the AUSBC version and the build complains about either of
these, check that version's `CameraRequest.Builder` and `IPreviewDataCallBack`
and match the signatures — the callback body itself doesn't change.

## v1.1 stability fixes (Android 14/15/16)

- compile/target SDK raised to 35 for modern OS behavior.
- Global crash handler (`CrashApp`): if the app ever crashes, the next launch
  shows the full error ON SCREEN with a Copy button — no logcat needed.
- Camera fragment now attaches safely after permission grant (posted to the
  main looper, wrapped in try/catch) so an engine failure shows a message
  instead of silently closing the app.
- Foreground service start is crash-proof and declares its
  `connectedDevice` type (required on Android 14+); if the OS refuses it,
  streaming still works while the app is open.
