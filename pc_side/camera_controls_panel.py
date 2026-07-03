# tools/camera_controls_panel.py
# Reusable camera-controls panel for the AI Scanner (imgui).
#
# This version supports TWO backends, switched automatically by the scanner:
#
#   * LOCAL  (wired/USB):  drives the cv2.VideoCapture the scanner already
#                          opened, via cap.set(CAP_PROP_*).
#   * REMOTE (wireless):   drives the PHONE over HTTP. cap.set() does NOTHING
#                          on an FFmpeg network stream, which is why the PC
#                          could never adjust the webcam in wireless mode. We
#                          now POST control changes to the phone's /control
#                          endpoint and POLL /controls so the two UIs stay 100%
#                          in sync in real time (a change on either side shows
#                          up on the other within ~one poll interval).
#
# Control set is identical on both ends (0..100 percentages):
#   brightness, exposure, contrast, saturation, gain, sharpness, zoom,
#   wb_temp, auto_wb
# Auto-exposure / autofocus / focus were intentionally REMOVED.

import time
import threading
import json
import urllib.request

try:
    import cv2
except ImportError:
    cv2 = None

import imgui


# Shared control definitions. (label, key, min, max). All sliders are 0..100
# percentages; the phone scales them to the webcam's real UVC range, and the
# LOCAL backend maps them to the OpenCV property range below.
CONTROL_DEFS = [
    ("Brightness", "brightness", 0.0, 100.0),
    ("Exposure",   "exposure",   0.0, 100.0),
    ("Contrast",   "contrast",   0.0, 100.0),
    ("Saturation", "saturation", 0.0, 100.0),
    ("Gain",       "gain",       0.0, 100.0),
    ("Sharpness",  "sharpness",  0.0, 100.0),
    ("Zoom",       "zoom",       0.0, 100.0),
    ("WB Temp",    "wb_temp",    0.0, 100.0),
]
TOGGLE_DEFS = [
    ("Auto WB", "auto_wb"),
]


def _local_prop_map():
    """key -> (OpenCV prop id, real_min, real_max) for the LOCAL/USB backend.
    The slider is 0..100; we scale into [real_min, real_max] when calling
    cap.set(). Exposure maps to CAP_PROP_EXPOSURE over a sane negative range."""
    if cv2 is None:
        return {}
    return {
        "brightness": (cv2.CAP_PROP_BRIGHTNESS,   0.0, 255.0),
        "exposure":   (cv2.CAP_PROP_EXPOSURE,    -13.0,  0.0),
        "contrast":   (cv2.CAP_PROP_CONTRAST,     0.0, 255.0),
        "saturation": (cv2.CAP_PROP_SATURATION,   0.0, 255.0),
        "gain":       (cv2.CAP_PROP_GAIN,         0.0, 255.0),
        "sharpness":  (cv2.CAP_PROP_SHARPNESS,    0.0, 255.0),
        "zoom":       (cv2.CAP_PROP_ZOOM,         0.0, 500.0),
        "wb_temp":    (cv2.CAP_PROP_WB_TEMPERATURE, 2000.0, 8000.0),
        "auto_wb":    (cv2.CAP_PROP_AUTO_WB,      0.0,   1.0),
    }


class RemoteControlClient:
    """Talks to the phone's MjpegServer control endpoints.

    POSTs are debounced/coalesced on a background thread so dragging a slider
    doesn't flood the network; the latest pending value per key always wins.
    A poll thread mirrors phone-side changes back into `values`.
    """

    def __init__(self):
        self._base = None                 # e.g. "http://10.0.0.5:8080"
        self._lock = threading.Lock()
        self._pending = {}                # key -> value waiting to be sent
        self._values = {}                 # key -> last known value (synced)
        self._version = -1
        self._remote_source = ""
        self._running = False
        self._send_thread = None
        self._poll_thread = None
        self._last_local_send = 0.0
        # Set when the poll loop pulled a phone-originated change the UI should
        # adopt. The panel reads & clears this to update its sliders.
        self.remote_dirty = False

    # -- lifecycle ----------------------------------------------------------
    def set_base_url(self, base):
        """base like 'http://10.0.0.5:8080' (no trailing slash). None stops."""
        with self._lock:
            if base == self._base:
                return
            self._base = base.rstrip("/") if base else None
            self._version = -1
            self._values.clear()
            self._pending.clear()
        if self._base and not self._running:
            self.start()

    def start(self):
        if self._running:
            return
        self._running = True
        self._send_thread = threading.Thread(target=self._send_loop, daemon=True)
        self._poll_thread = threading.Thread(target=self._poll_loop, daemon=True)
        self._send_thread.start()
        self._poll_thread.start()

    def stop(self):
        self._running = False

    # -- API used by the panel ---------------------------------------------
    def push(self, key, value):
        """User moved a slider on the PC -> queue it for the phone."""
        with self._lock:
            self._pending[key] = int(round(value))
            self._values[key] = int(round(value))
            self._last_local_send = time.time()

    def snapshot(self):
        with self._lock:
            return dict(self._values)

    # -- background workers -------------------------------------------------
    def _send_loop(self):
        while self._running:
            base = self._base
            batch = None
            with self._lock:
                if self._pending:
                    batch = self._pending
                    self._pending = {}
            if base and batch:
                try:
                    data = json.dumps(batch).encode("utf-8")
                    req = urllib.request.Request(
                        base + "/control", data=data,
                        headers={"Content-Type": "application/json"},
                        method="POST")
                    with urllib.request.urlopen(req, timeout=1.5) as r:
                        obj = json.loads(r.read().decode("utf-8"))
                        # Adopt the authoritative state the phone returns.
                        self._absorb(obj, mark_dirty=False)
                except Exception:
                    pass
            time.sleep(0.05)   # ~20 control updates/sec max

    def _poll_loop(self):
        while self._running:
            base = self._base
            if base:
                try:
                    req = urllib.request.Request(base + "/controls", method="GET")
                    with urllib.request.urlopen(req, timeout=1.5) as r:
                        obj = json.loads(r.read().decode("utf-8"))
                    # Only adopt phone-originated changes; ignore our own echoes
                    # and don't stomp a value the user is actively dragging.
                    ver = int(obj.get("version", -1))
                    src = str(obj.get("source", ""))
                    if ver != self._version:
                        self._version = ver
                        recently_local = (time.time() - self._last_local_send) < 0.4
                        if src != "pc" and not recently_local:
                            self._absorb(obj, mark_dirty=True)
                        else:
                            # keep our cache fresh without flagging the UI
                            self._absorb(obj, mark_dirty=False)
                except Exception:
                    pass
            time.sleep(0.15)   # ~6-7 polls/sec: snappy, light on the network

    def _absorb(self, obj, mark_dirty):
        changed = False
        with self._lock:
            for _, key, _, _ in CONTROL_DEFS:
                if key in obj:
                    v = int(obj[key])
                    if self._values.get(key) != v:
                        self._values[key] = v
                        changed = True
            for _, key in TOGGLE_DEFS:
                if key in obj:
                    v = int(obj[key])
                    if self._values.get(key) != v:
                        self._values[key] = v
                        changed = True
        if mark_dirty and changed:
            self.remote_dirty = True


class CameraControlsPanel:
    def __init__(self):
        self._cap_id = None
        self._values = {}            # key -> 0..100 slider value
        self._status = ""
        self._status_time = 0.0
        self.remote = RemoteControlClient()
        self._mode = "local"         # "local" or "remote"

        # seed defaults
        for _, key, lo, hi in CONTROL_DEFS:
            self._values[key] = 50.0
        for _, key in TOGGLE_DEFS:
            self._values[key] = 100.0  # auto_wb on

    # ------------------------------------------------------------------ #
    def set_remote_target(self, base_url):
        """Called by the scanner when the WIRELESS phone address is known."""
        self.remote.set_base_url(base_url)

    def _sync_from_capture(self, cap):
        """Read current values off a LOCAL capture once, mapped to 0..100."""
        pm = _local_prop_map()
        for _, key, _, _ in CONTROL_DEFS:
            prop, lo, hi = pm.get(key, (None, 0.0, 100.0))
            if prop is None:
                continue
            try:
                cur = cap.get(prop)
            except Exception:
                cur = -1
            if cur is not None and cur != -1 and hi > lo:
                self._values[key] = max(0.0, min(100.0, (cur - lo) * 100.0 / (hi - lo)))
        for _, key in TOGGLE_DEFS:
            prop, lo, hi = pm.get(key, (None, 0.0, 1.0))
            if prop is None:
                continue
            try:
                cur = cap.get(prop)
            except Exception:
                cur = -1
            if cur is not None and cur != -1:
                self._values[key] = 100.0 if cur >= 0.5 else 0.0
        self._cap_id = id(cap)

    def _set_local(self, cap, key, value01):
        """Apply a 0..100 value to a LOCAL capture (scaled to prop range)."""
        pm = _local_prop_map()
        if key not in pm:
            return
        prop, lo, hi = pm[key]
        real = lo + (hi - lo) * (value01 / 100.0)
        try:
            # Manual exposure ONLY takes effect if auto-exposure is off. We
            # removed the auto-exposure UI control, but we must still switch the
            # device to manual internally, or CAP_PROP_EXPOSURE is ignored
            # (that's why exposure appeared to "do nothing"). DirectShow uses
            # 0.25 = manual, 0.75 = auto for CAP_PROP_AUTO_EXPOSURE.
            if key == "exposure" and cv2 is not None:
                try:
                    cap.set(cv2.CAP_PROP_AUTO_EXPOSURE, 0.25)
                except Exception:
                    pass
            ok = bool(cap.set(prop, float(real)))
        except Exception:
            ok = False
        self._status = f"{key}: {value01:.0f}%  ok={ok}"
        self._status_time = time.time()

    # ------------------------------------------------------------------ #
    def draw(self, cap, wireless=False):
        """Render the controls.

        cap:       the scanner's live cv2.VideoCapture (LOCAL/USB), or None.
        wireless:  True when the active source is the phone (REMOTE backend).
        """
        if cv2 is None and not wireless:
            imgui.text_disabled("OpenCV (cv2) not available.")
            return

        self._mode = "remote" if wireless else "local"

        if self._mode == "remote":
            # Adopt any phone-originated changes pulled by the poll loop.
            if self.remote.remote_dirty:
                self.remote.remote_dirty = False
                snap = self.remote.snapshot()
                for k, v in snap.items():
                    self._values[k] = float(v)
            header = "Camera Controls  (synced with phone)"
        else:
            alive = False
            try:
                alive = cap is not None and cap.isOpened()
            except Exception:
                alive = False
            if not alive:
                imgui.text_disabled("Connect a scanner to adjust its controls.")
                return
            if id(cap) != self._cap_id:
                self._sync_from_capture(cap)
            header = "Camera Controls"

        imgui.text_colored(header, 0.4, 0.8, 1.0, 1.0)
        imgui.text_disabled("Adjust before or during scanning.")
        imgui.separator()

        # Continuous sliders.
        for label, key, lo, hi in CONTROL_DEFS:
            val = float(self._values.get(key, 50.0))
            imgui.text(label)
            imgui.push_item_width(-1)
            changed, new_val = imgui.slider_float(f"##cam_{key}", val, lo, hi, "%.0f")
            imgui.pop_item_width()
            if changed:
                self._values[key] = new_val
                if self._mode == "remote":
                    self.remote.push(key, new_val)
                else:
                    self._set_local(cap, key, new_val)

        # Toggles.
        for label, key in TOGGLE_DEFS:
            cur_on = float(self._values.get(key, 100.0)) >= 50.0
            changed, new_on = imgui.checkbox(f"{label}##cam_{key}", cur_on)
            if changed:
                v = 100.0 if new_on else 0.0
                self._values[key] = v
                if self._mode == "remote":
                    self.remote.push(key, v)
                else:
                    self._set_local(cap, key, v)

        imgui.separator()
        if self._mode == "remote":
            imgui.text_disabled("Changes apply to the phone in real time.")
        else:
            if imgui.button("Re-read From Scanner", width=-1):
                if cap is not None:
                    self._sync_from_capture(cap)

        if self._status and (time.time() - self._status_time) < 4.0:
            imgui.text_colored(self._status, 0.6, 0.8, 0.6, 1.0)
