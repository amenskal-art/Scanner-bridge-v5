# tools/ai_scanner_tool.py
# Multi-Mode Image-to-3D Scanner UI

import os
import sys
import subprocess
import zipfile
import threading
import queue
import datetime
import time
import math
import tkinter as tk
from tkinter import filedialog
import re
import tempfile
import modal
import json

# Low-latency FFmpeg flags for the phone's MJPEG-over-HTTP stream.
#
# IMPORTANT: the phone stream is HTTP (multipart/x-mixed-replace), NOT RTSP.
# The old config only set RTSP/`stimeout` options, which FFmpeg IGNORES for
# HTTP — so when the phone briefly stopped sending frames (e.g. while it
# switched fullscreen<->portrait) FFmpeg fell back to its DEFAULT ~30 s
# blocking read, froze the UI, and then broke the stream
# ("Stream timeout triggered after 30031 ms").
#
# For HTTP we must set `timeout`/`rw_timeout` (microseconds). 3 s is plenty on
# a LAN and lets our reconnect logic kick in quickly instead of hanging.
os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
    "rtsp_transport;tcp"
    "|fflags;nobuffer"
    "|flags;low_delay"
    "|max_delay;0"
    "|reorder_queue_size;0"
    "|timeout;3000000"          # HTTP/TCP read timeout (us) = 3 s
    "|rw_timeout;3000000"       # read/write timeout (us)   = 3 s
    "|stimeout;3000000"         # RTSP socket timeout (us)  = 3 s (harmless)
)
# Hard cap on how long any single blocking read may stall (ms).
os.environ["OPENCV_FFMPEG_READ_TIMEOUT"] = "3000"

# On Windows, if the process is not DPI-aware, the OS renders the app at a
# lower resolution and stretches it — making EVERYTHING (including this UI)
# slightly blurry on scaled displays. Opt in to per-monitor DPI awareness.
# Harmless no-op if it's too late (window already created) or already set.
if os.name == "nt":
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)  # per-monitor DPI aware
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

try:
    import cv2
    import numpy as np
except ImportError:
    pass 

from tool_system import BaseTool
from tools.camera_calibration import CameraCalibrator
from tools.synthetic_depth_engine import SyntheticDepthEngine      # NEW
from tools.camera_controls_panel import CameraControlsPanel        # NEW
from tools.camera_identity import (                                # NEW
    enumerate_cameras, list_external_cameras, LockedCameraStore,
    pygrabber_available,
)
import imgui
from tools.scanner_pairing_gate import PairingGateMixin

class AIScannerTool(PairingGateMixin, BaseTool):
    def __init__(self, app_instance):
        super().__init__(app_instance)
        self.app = app_instance 
        self.name = "3D Scanner"
        self.icon_key = 'scan'  
        self.hotkey = '8'
        
        self.image_paths = []
        self.logs = ["System initialized."]
        
        self.is_processing = False
        self.is_deploying = False
        self.is_authenticating = False
        self.is_gpu_processing = False
        
        self.progress = 0.0
        self.progress_text = ""
        
        self.modal_config_path = os.path.expanduser("~/.modal.toml")
        self.is_authenticated = os.path.exists(self.modal_config_path)
        
        self.icons = {}
        self._icons_loaded = False
        
        # --- Scanning Mode ---
        # 0: Hunyuan (Metrology)      -> GLB mesh
        # 1: Pi3X (Reverse Engineering) -> PLY point cloud
        # 2: LingBot-Map (Scene Mapping — streaming, long sequences, sky mask) -> GLB
        # 3: Hunyuan (Real Estate)    -> PLY Gaussian splat (same repo as mode 0)
        self.scan_mode = 1

        # --- Scanning camera ---
        # Source type: 0 = Wireless (network/IP scanner), 1 = Wired (USB).
        # ("Wireless" / "Wired" replace the old Wi-Fi / USB labels.)
        # Default to WIRED: this avoids any automatic attempt to reach the
        # wireless IP address on startup (which produced the FFmpeg
        # 'tcp://...:8080 failed: Error number -138' message when no network
        # camera is present). Wireless only connects when explicitly clicked.
        self.camera_source_type = 1
        self.usb_camera_index = 0          # resolved automatically (no manual entry)
        self.camera_protocols = ["http://", "https://", "rtsp://", ""]
        self.camera_protocol = 0
        self.camera_ip = "192.168.1.100:8080/video"

        # --- Wired (USB) camera identity & lock ---
        # "First plug wins": the first external/wired camera detected becomes
        # THE authorized camera (its hardware fingerprint is persisted). The
        # app afterwards connects ONLY to that camera and refuses any other,
        # showing a "Waiting for authorized camera" state when it's absent.
        self.locked_camera = LockedCameraStore()
        self.authorized_cam_present = False     # locked cam currently attached?
        self.authorized_cam_index = -1          # its current OpenCV index
        self.wired_status = "idle"              # idle|searching|connected|waiting
        self.wired_status_detail = ""
        self._cam_watch_running = False
        self._cam_watch_thread = None
        self._wired_connecting = False          # guard against duplicate connects
        # active_source = what is ACTUALLY streaming right now (None/0/1),
        # decided by _stream_thread, NOT by which tab the user is viewing.
        # camera_source_type below is only the tab being VIEWED (cosmetic).
        self.active_source = None
        self._requested_source = None           # source the current stream opened for

        self.show_stream_window = False
        self.show_gallery_window = False
        self.stream_capture = None
        # Live connection state, refreshed each frame by draw_options and read
        # by the stream window's in-window connection panel.
        self._conn_devices = 0
        self._conn_online = False
        
        self.raw_frame_bgr = None 
        self.current_frame = None 
        self.replace_index = -1
        self.stream_tex_id = None
        self.thumbnails = {}
        self.capture_dir = tempfile.mkdtemp(prefix="ai_scanner_captures_")

        # --- Synthetic (fake) depth view + camera controls ---
        # The depth view is purely cosmetic. It is built from the SAME live
        # frame the stream produces, but it is NEVER captured or uploaded —
        # only real RGB stills go to the cloud model.
        #
        # Everything now lives INSIDE the single "Live Camera Stream" window:
        # the RGB feed and the depth feed sit side by side, and the camera
        # controls sit underneath. These two flags only toggle whether each
        # section is shown within that one window (no separate windows).
        self.show_depth_view = True        # show depth panel inside the window
        self.show_controls_view = True     # show controls panel inside window
        self.depth_engine = SyntheticDepthEngine()
        self.depth_frame = None            # stylized depth (RGB, display only)
        self.depth_tex_id = None
        self.cam_controls = CameraControlsPanel()

        # --- Calibration camera ---
        self.calib_settings_path = os.path.expanduser("~/.iqscanner_calib_camera.json")
        self.calib_camera_source_type = 1  
        self.calib_usb_camera_index = 0
        self.calib_camera_protocol = 0
        self.calib_camera_ip = "192.168.1.100:8080/video"
        self._load_calib_camera_settings()

        self.show_calibration_window = False
        self.calib_stream_capture = None
        self.calib_current_frame = None    
        self.calib_stream_tex_id = None
        self.calib_stream_running = False  

        # Video Recording properties
        self.is_recording = False
        self.is_paused = False
        self.video_writer = None
        self.last_sample_time = 0.0
        self.video_out_path = ""
        self.video_w = 0
        self.video_h = 0
        
        # Camera Calibration
        self.checkerboard_size = (5, 7)
        self.calibrator = CameraCalibrator(checkerboard_size=self.checkerboard_size)
        if self.calibrator.load():
            re_str = (f" (reproj err {self.calibrator.reproj_error:.3f}px)"
                      if self.calibrator.reproj_error is not None else "")
            self.logs.append(f"Loaded saved camera lens calibration{re_str}.")

        self.enable_undistortion = True
        self.calib_target_frames = 15

        self.calibrating = False

        self.ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')

        # --- High-resolution UI fonts (sharp text at any scale) ---
        # The font is loaded at several sizes; each label picks the nearest
        # one so glyphs render at ~1:1 instead of being scaled far from
        # their rasterized size (which is what causes blur).
        self._hires_fonts = []          # list of (px, font), ascending
        self._hires_tex_uploaded = False
        try:
            self._setup_hires_font()
        except Exception:
            self._hires_fonts = []

        # Start the background watcher that auto-detects the wired camera,
        # locks onto the first one seen, and connects instantly on plug-in.
        try:
            if os.name == "nt" and not pygrabber_available():
                self.log("Note: install 'pygrabber' for precise scanner "
                         "detection (pip install pygrabber).")
            self._start_camera_watch()
        except Exception:
            pass

        # QR pairing gate (PC shows QR, phone's webcam reads it, phone POSTs
        # its IP back here, we auto-connect the wireless stream).
        try:
            self._init_pairing_gate()
        except Exception:
            pass

    # =====================================================================
    # Wired-camera auto-detect + lock
    # =====================================================================
    def _start_camera_watch(self):
        if self._cam_watch_running:
            return
        self._cam_watch_running = True
        self._cam_watch_thread = threading.Thread(
            target=self._camera_watch_loop, daemon=True)
        self._cam_watch_thread.start()

    def _camera_watch_loop(self):
        """Poll attached cameras periodically. Keeps authorized_cam_present /
        authorized_cam_index in sync so the UI can show "Connected" the instant
        the authorized camera is plugged in, and "Waiting..." when it isn't.
        Also performs first-plug-wins locking.

        Full enumeration can be relatively expensive (on Windows it shells out
        to PowerShell), so we re-enumerate at a modest cadence rather than as
        fast as possible — fast enough to feel instant, light on the system."""
        while self._cam_watch_running:
            try:
                self._refresh_wired_state()
            except Exception:
                pass
            time.sleep(1.0)

    def _refresh_wired_state(self):
        externals = list_external_cameras()

        # First plug wins: adopt the first external camera as THE camera.
        if not self.locked_camera.is_set():
            if externals:
                self.locked_camera.lock_to(externals[0])
                self.log(f"Authorized scanner set: {externals[0].name}")
            else:
                self.authorized_cam_present = False
                self.authorized_cam_index = -1
                self.wired_status = "searching"
                self.wired_status_detail = "No wired scanner detected yet."
                return

        # We have a locked identity. Is it currently attached?
        match = None
        for c in externals:
            if c.matches_fingerprint(self.locked_camera.fingerprint):
                match = c
                break
        # Some platforms surface the locked cam outside the "external" filter;
        # check the full list too before giving up.
        if match is None:
            for c in enumerate_cameras():
                if c.matches_fingerprint(self.locked_camera.fingerprint):
                    match = c
                    break

        if match is not None:
            was_present = self.authorized_cam_present
            self.authorized_cam_present = True
            self.authorized_cam_index = match.index
            self.usb_camera_index = match.index
            detail = self.locked_camera.label or match.name

            just_detected = not was_present
            if just_detected:
                # Whole-tool behavior: on detection, switch the viewed tab to
                # Wired and stop any wireless stream so only the wired scanner
                # is ever live.
                self.camera_source_type = 1
                if self.active_source == 0:
                    self._requested_source = 1   # signal wireless loop to exit
                    old = self.stream_capture
                    self.stream_capture = None
                    self.active_source = None
                    if old is not None:
                        try:
                            old.release()
                        except Exception:
                            pass
                self.log(f"Scanner detected: {detail} — switched to Wired.")

            if self.active_source == 1 and self._cap_is_open(self.stream_capture):
                self.wired_status = "connected"
                self.wired_status_detail = detail
            else:
                self.wired_status = "ready"
                self.wired_status_detail = detail
                # Auto-connect the wired scanner instantly when the stream
                # window is open and nothing is currently streaming.
                if (self.show_stream_window and self.stream_capture is None
                        and self.active_source is None
                        and not getattr(self, "_wired_connecting", False)):
                    self._wired_connecting = True
                    def _connect_and_clear():
                        try:
                            self._stream_thread(requested_source=1)
                        finally:
                            self._wired_connecting = False
                    threading.Thread(target=_connect_and_clear, daemon=True).start()
        else:
            self.authorized_cam_present = False
            self.authorized_cam_index = -1
            self.wired_status = "waiting"
            self.wired_status_detail = (
                f"Waiting for authorized scanner: {self.locked_camera.label}"
                if self.locked_camera.label else "Waiting for authorized scanner")

    def _cap_is_open(self, cap):
        try:
            return cap is not None and cap.isOpened()
        except Exception:
            return False

    def _setup_hires_font(self):
        """Add a TrueType font to the imgui atlas at multiple sizes so the
        scanner UI text always renders close to a native rasterized size.
        Must run OUTSIDE the imgui frame (the atlas is locked between
        new_frame/render), which is why it's called from __init__."""
        io = imgui.get_io()

        candidates = []
        if os.name == "nt":
            windir = os.environ.get("WINDIR", r"C:\Windows")
            candidates += [os.path.join(windir, "Fonts", f) for f in
                           ("segoeui.ttf", "arial.ttf", "tahoma.ttf", "verdana.ttf")]
        candidates += [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/System/Library/Fonts/SFNS.ttf",
        ]
        font_path = next((p for p in candidates if os.path.exists(p)), None)
        if font_path is None:
            return

        # Denser size ladder = the chosen rasterized size is always within a
        # few percent of the requested one, so we can render at scale 1.0
        # (perfectly sharp) and the size error stays invisible.
        sizes = (11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0,
                 22.0, 24.0, 26.0, 28.0, 30.0, 32.0, 34.0, 36.0, 39.0, 42.0,
                 45.0, 48.0, 52.0, 56.0)

        # Oversampled rasterization makes glyphs noticeably sharper when
        # they're drawn at a slightly different size than rasterized.
        cfg = None
        try:
            FontConfig = (getattr(imgui, "FontConfig", None)
                          or getattr(imgui.core, "FontConfig", None))
            if FontConfig is not None:
                cfg = FontConfig(oversample_h=3, oversample_v=3)
        except Exception:
            cfg = None

        for px in sizes:
            try:
                if cfg is not None:
                    f = io.fonts.add_font_from_file_ttf(font_path, px, font_config=cfg)
                else:
                    f = io.fonts.add_font_from_file_ttf(font_path, px)
            except TypeError:
                f = io.fonts.add_font_from_file_ttf(font_path, px)
            self._hires_fonts.append((px, f))

        # Rebuild the atlas and try to upload it ourselves. If there's no GL
        # context yet (tool constructed before the renderer), the app's
        # renderer will pick the new fonts up when it builds its font texture.
        try:
            import OpenGL.GL as gl
            width, height, pixels = io.fonts.get_tex_data_as_rgba32()
            tex = gl.glGenTextures(1)
            gl.glBindTexture(gl.GL_TEXTURE_2D, tex)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
            gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
            gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA, width, height, 0,
                            gl.GL_RGBA, gl.GL_UNSIGNED_BYTE, pixels)
            io.fonts.texture_id = tex
            self._hires_tex_uploaded = True
            # NOTE: deliberately NOT calling io.fonts.clear_tex_data() so a
            # renderer that initializes after us can still re-upload the atlas.
        except Exception:
            self._hires_tex_uploaded = False

    def _push_ui_font(self):
        """Push a crisply rasterized TTF font for the regular imgui windows
        (live stream / gallery / calibration / popups), instead of the
        default 13px bitmap font being scaled up (which looks blurry).
        Returns True if a font was pushed (caller must pop_font())."""
        if not self._hires_fonts:
            return False
        target = 17.0 * getattr(self.app, "ui_scale", 1.0)
        font = min(self._hires_fonts, key=lambda pf: abs(pf[0] - target))[1]
        try:
            imgui.push_font(font)
            return True
        except Exception:
            return False

    def log(self, message):
        self.logs.append(message)

    def set_progress(self, val, text):
        self.progress = min(max(val, 0.0), 1.0)
        self.progress_text = text

    def _reset_progress(self):
        self.progress = 0.0
        self.progress_text = ""

    def _copy_activity_log(self):
        """Copy the full activity log (all lines, not just the few shown)
        to the system clipboard."""
        content = "\n".join(self.logs)
        copied = False
        # 1) imgui's own clipboard (works with the active GL backend)
        try:
            imgui.set_clipboard_text(content)
            copied = True
        except Exception:
            pass
        # 2) tkinter fallback
        if not copied:
            try:
                r = tk.Tk()
                r.withdraw()
                r.clipboard_clear()
                r.clipboard_append(content)
                r.update()  # flush to the OS clipboard before destroy
                r.destroy()
                copied = True
            except Exception:
                pass
        if copied:
            self.log(f"Log copied OK ({len(self.logs)} lines).")
        else:
            self.log("Error: Could not access clipboard.")

    # --- Config Persistence ---

    def _load_calib_camera_settings(self):
        try:
            if os.path.exists(self.calib_settings_path):
                with open(self.calib_settings_path, "r") as f:
                    d = json.load(f)
                self.calib_camera_source_type = int(d.get("source_type", 1))
                self.calib_usb_camera_index = int(d.get("usb_index", 0))
                self.calib_camera_protocol = int(d.get("protocol", 0))
                self.calib_camera_ip = str(d.get("ip", self.calib_camera_ip))
        except Exception:
            pass

    def _save_calib_camera_settings(self):
        try:
            with open(self.calib_settings_path, "w") as f:
                json.dump({
                    "source_type": self.calib_camera_source_type,
                    "usb_index": self.calib_usb_camera_index,
                    "protocol": self.calib_camera_protocol,
                    "ip": self.calib_camera_ip,
                }, f)
        except Exception:
            pass

    def _on_calibration_complete(self, success, info):
        if success:
            self.log(
                f"Calibration OK! Reprojection error {info['reproj_error']:.3f}px"
            )
        else:
            self.log(f"Error: Calibration failed: {info.get('error', 'unknown')}")
        self.calibrating = False

    def _calib_stream_thread(self):
        try:
            import cv2
        except ImportError:
            self.log("Error: cv2 not found.")
            self.calib_stream_running = False
            return

        is_network = (self.calib_camera_source_type == 0)

        if is_network:
            url = f"{self.camera_protocols[self.calib_camera_protocol]}{self.calib_camera_ip}"
            cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
        else:
            if os.name == 'nt':
                cap = cv2.VideoCapture(self.calib_usb_camera_index, cv2.CAP_DSHOW)
            else:
                cap = cv2.VideoCapture(self.calib_usb_camera_index)

        try:
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        except Exception:
            pass

        if not cap.isOpened():
            self.log("Error: Could not open the calibration scanner.")
            self.calib_stream_running = False
            return

        self.calib_stream_capture = cap
        self.calib_stream_running = True

        latest = [None]
        latest_lock = threading.Lock()
        grabber_alive = [is_network]

        def _grabber():
            local_fail = 0
            while grabber_alive[0] and self.calib_stream_running:
                if cap is None or not cap.isOpened():
                    time.sleep(0.05)
                    continue
                ok, f = cap.read()
                if ok and f is not None:
                    local_fail = 0
                    with latest_lock:
                        latest[0] = f
                else:
                    local_fail += 1
                    time.sleep(0.005 if local_fail < 20 else 0.1)
                    if local_fail > 100:
                        return

        if is_network:
            threading.Thread(target=_grabber, daemon=True).start()

        try:
            while self.calib_stream_running:
                if is_network:
                    with latest_lock:
                        frame = latest[0]
                        latest[0] = None
                    if frame is None:
                        time.sleep(0.003)
                        continue
                else:
                    ok, frame = cap.read()
                    if not ok or frame is None:
                        time.sleep(0.02)
                        continue

                display = frame
                if self.calibrating:
                    self.calibrator.feed_frame(frame)
                    overlay = self.calibrator.get_overlay()
                    if overlay is not None:
                        display = overlay

                self.calib_current_frame = cv2.cvtColor(display, cv2.COLOR_BGR2RGB)
        except Exception as e:
            self.log(f"Calibration stream error: {e}")
        finally:
            grabber_alive[0] = False
            try:
                cap.release()
            except Exception:
                pass
            self.calib_stream_capture = None
            self.calib_current_frame = None
            self.calib_stream_running = False

    def _stop_calib_stream(self):
        self.calib_stream_running = False

    def _update_calib_stream_texture(self, rgb_frame):
        try:
            import OpenGL.GL as gl
            import numpy as np
            if self.calib_stream_tex_id is None:
                self.calib_stream_tex_id = gl.glGenTextures(1)
            gl.glBindTexture(gl.GL_TEXTURE_2D, self.calib_stream_tex_id)
            gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
            h, w = rgb_frame.shape[:2]
            data = np.ascontiguousarray(rgb_frame)
            gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0,
                            gl.GL_RGB, gl.GL_UNSIGNED_BYTE, data)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
            return self.calib_stream_tex_id
        except Exception:
            return None

    def _lazy_load_icons(self):
        if self._icons_loaded:
            return
        self._icons_loaded = True

    def _open_file_dialog(self):
        root = tk.Tk()
        root.withdraw()
        root.attributes('-topmost', True)
        files = filedialog.askopenfilenames(
            title="Select Photos or Videos",
            filetypes=[
                ("All Media", "*.png *.jpg *.jpeg *.mp4 *.mov *.avi"),
                ("Photos", "*.png *.jpg *.jpeg"),
                ("Videos", "*.mp4 *.mov *.avi")
            ]
        )
        if files:
            self.image_paths.extend(list(files))
            self.log(f"Data Acquisition OK. Total: {len(self.image_paths)} files.")
        root.destroy()

    def _auth_thread(self):
        self.is_authenticating = True
        self.log("Opening web browser for authentication...")
        try:
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            process = subprocess.Popen(
                ["modal", "setup"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                creationflags=creation_flags
            )
            process.wait()
            if os.path.exists(self.modal_config_path):
                self.is_authenticated = True
                self.log("Cloud authentication OK.")
            else:
                self.log("Error: Login was cancelled or failed.")
        except Exception as e:
            self.log("Error: A problem occurred while trying to log in.")
        finally:
            self.is_authenticating = False

    def _deploy_thread(self):
        self.is_deploying = True
        self.set_progress(0.1, "Waking up cloud engine...")
        self.log("Engine initialization started...")
        try:
            current_dir = os.path.dirname(os.path.abspath(__file__))
            cloud_file = os.path.join(current_dir, "iqscanner_cloud.py")
            root_dir = os.path.dirname(current_dir)
            
            creation_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            env = os.environ.copy()
            env["PYTHONIOENCODING"] = "utf-8"
            
            process = subprocess.Popen(
                [sys.executable, "-m", "modal", "deploy", cloud_file],
                stdout=subprocess.PIPE, 
                stderr=subprocess.STDOUT, 
                text=True,
                encoding='utf-8',
                errors='replace',
                cwd=root_dir, 
                env=env,
                creationflags=creation_flags
            )
            
            for line in iter(process.stdout.readline, ''):
                if line:
                    clean_line = self.ansi_escape.sub('', line).strip()
                    if clean_line:
                        if "Created" in clean_line or "Deploying" in clean_line:
                            self.log("Setting up cloud connections...")
                            self.set_progress(0.4, "Allocating secure cloud space...")
                        elif "Attached" in clean_line:
                            self.log("Connecting storage...")
                            self.set_progress(0.7, "Attaching storage volumes...")
                        elif "Error" in clean_line:
                            self.log(f"Error details: {clean_line}")
                            self.set_progress(0.0, "Engine failed to start.")
                    
            process.stdout.close()
            process.wait()
            
            if process.returncode == 0:
                self.log("Engine Ready.")
                self.set_progress(1.0, "Engine is ready!")
                threading.Timer(2.0, self._reset_progress).start()
            else:
                self.log("Error: The cloud system failed to start properly.")
                self.set_progress(0.0, "Startup failed.")
        except Exception as e:
            self.log("Error: A critical problem occurred while starting the cloud system.")
            self.set_progress(0.0, "System error.")
        finally:
            self.is_deploying = False

    def _gpu_progress_simulator(self):
        target = 0.85
        current = self.progress
        while self.is_gpu_processing and current < target:
            time.sleep(1.0)
            current += 0.008  
            if current > target: 
                current = target
            
            if current < 0.50:
                text = "Processing feature matches..."
            elif current < 0.65:
                text = "Constructing dense point cloud..."
            elif current < 0.80:
                text = "Refining 3D geometry..."
            else:
                text = "Almost there... Finalizing data..."
                
            self.set_progress(current, text)

    def _preprocess_for_upload(self, src_path, tmp_dir):
        if not (self.enable_undistortion and self.calibrator.is_calibrated()):
            return src_path, os.path.basename(src_path)

        try:
            import cv2
            ext = os.path.splitext(src_path)[1].lower()
            base = os.path.basename(src_path)
            out_path = os.path.join(tmp_dir, base)

            if ext in (".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff"):
                img = cv2.imread(src_path, cv2.IMREAD_COLOR)
                if img is None:
                    return src_path, base
                undistorted = self.calibrator.undistort(img)
                cv2.imwrite(out_path, undistorted, [cv2.IMWRITE_JPEG_QUALITY, 95])
                return out_path, base

            if ext in (".mp4", ".mov", ".avi", ".mkv", ".webm"):
                cap = cv2.VideoCapture(src_path)
                if not cap.isOpened():
                    return src_path, base
                fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
                w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0

                out_name = os.path.splitext(base)[0] + "_undist.mp4"
                out_path = os.path.join(tmp_dir, out_name)
                fourcc = cv2.VideoWriter_fourcc(*"mp4v")
                writer = cv2.VideoWriter(out_path, fourcc, fps, (w, h))
                if not writer.isOpened():
                    cap.release()
                    return src_path, base

                idx = 0
                while True:
                    ok, frame = cap.read()
                    if not ok or frame is None:
                        break
                    writer.write(self.calibrator.undistort(frame))
                    idx += 1

                cap.release()
                writer.release()
                return out_path, out_name

            return src_path, base
        except Exception as e:
            self.log(f"Warning: Preprocessing failed for {os.path.basename(src_path)}")
            return src_path, os.path.basename(src_path)

    def _workflow_thread(self):
        local_zip = "temp_input.zip"
        preprocess_dir = tempfile.mkdtemp(prefix="ai_scanner_preprocess_")
        try:
            self.set_progress(0.02, "Gathering your media...")
            self.log("Gathering target files...")

            preprocessed = []
            if self.enable_undistortion and self.calibrator.is_calibrated():
                self.log("Applying lens correction to sequence...")
                for i, src in enumerate(self.image_paths):
                    self.set_progress(0.02 + 0.05 * (i / max(len(self.image_paths), 1)),
                                      f"Preparing media {i+1}/{len(self.image_paths)}...")
                    out_path, arc_name = self._preprocess_for_upload(src, preprocess_dir)
                    preprocessed.append((out_path, arc_name))
            else:
                preprocessed = [(p, os.path.basename(p)) for p in self.image_paths]

            self.set_progress(0.07, "Packaging your media...")
            with zipfile.ZipFile(local_zip, 'w') as zipf:
                for src_path, arc_name in preprocessed:
                    zipf.write(src_path, arc_name)

            self.log("Initiating cloud transfer...")
            upload_func = modal.Function.from_name("iqscanner-omega", "upload_chunk")
            
            chunk_size = 1024 * 1024 
            total_size = os.path.getsize(local_zip)
            uploaded = 0
            
            with open(local_zip, "rb") as f:
                is_first = True
                while True:
                    chunk = f.read(chunk_size)
                    if not chunk:
                        break
                    upload_func.remote(chunk, is_first)
                    uploaded += len(chunk)
                    is_first = False
                    progress_val = 0.05 + 0.30 * (uploaded / total_size)
                    self.set_progress(progress_val, f"Uploading... {int((uploaded / total_size) * 100)}%")

            self.set_progress(0.35, "Upload complete. Initializing Engine...")
            self.log("Building 3D geometry...")
            
            self.is_gpu_processing = True
            threading.Thread(target=self._gpu_progress_simulator, daemon=True).start()

            # ----- Select the cloud engine for this mode -----
            # Modes 0 and 3 both use the Hunyuan repo/function; mode 3 (Real
            # Estate) differs only in that it requests a Gaussian splat via
            # save_gs=True. Modes 1/2 are untouched.
            if self.scan_mode == 0:
                process_mesh_remote = modal.Function.from_name("iqscanner-omega", "process_mesh_hunyuan")
            elif self.scan_mode == 2:
                process_mesh_remote = modal.Function.from_name("iqscanner-omega", "process_mesh_lingbot")
            elif self.scan_mode == 3:
                process_mesh_remote = modal.Function.from_name("iqscanner-omega", "process_mesh_hunyuan")
            else:
                process_mesh_remote = modal.Function.from_name("iqscanner-omega", "process_mesh_pi3x")

            if self.scan_mode == 3:
                process_mesh_remote.remote(save_gs=True)   # Real Estate -> Gaussian splat
            else:
                process_mesh_remote.remote()

            self.is_gpu_processing = False
            self.set_progress(0.85, "Generation complete! Downloading...")
            self.log("Retrieving finalized model...")

            # Filesystem-safe timestamp in day-month-year_hour-minute order.
            # ('/' and ':' are illegal/path separators, so dd/mm/yyyy/hh/mm
            #  becomes dd-mm-yyyy_hh-mm.)
            dt_str = datetime.datetime.now().strftime("%d-%m-%Y_%H-%M")
            # Match the file type each engine actually produces:
            #   Pi3X (1)        -> PLY point cloud
            #   Real Estate (3) -> PLY Gaussian splat
            #   Metrology (0) & LingBot (2) -> GLB
            result_ext = ".ply" if self.scan_mode in (1, 3) else ".glb"
            # Name each result after its scan mode so files are self-describing.
            mode_names = {
                0: "Metrology",
                1: "ReverseEngineering",
                2: "SceneMapping",
                3: "RealEstate",
            }
            mode_label = mode_names.get(self.scan_mode, "Scan")
            filename = f"{mode_label}_{dt_str}{result_ext}"
            
            desktop_path = os.path.join(os.path.expanduser("~"), "Desktop")
            save_path = os.path.join(desktop_path, filename)

            get_size_remote = modal.Function.from_name("iqscanner-omega", "get_result_size")
            download_chunk_remote = modal.Function.from_name("iqscanner-omega", "download_chunk")
            cleanup_remote = modal.Function.from_name("iqscanner-omega", "cleanup_result")

            total_size = get_size_remote.remote()
            if total_size == 0:
                raise Exception("Result file missing on the cloud.")

            chunk_size = 1024 * 1024  
            bytes_received = 0
            max_retries = 3

            with open(save_path, "wb") as f:
                while bytes_received < total_size:
                    chunk = None
                    last_err = None
                    for attempt in range(max_retries):
                        try:
                            chunk = download_chunk_remote.remote(bytes_received, chunk_size)
                            break
                        except Exception as e:
                            last_err = e
                            time.sleep(2 ** attempt) 
                    if chunk is None:
                        raise Exception(f"Download failed after retries: {last_err}")
                    if not chunk:
                        raise Exception("Download stream ended prematurely.")

                    f.write(chunk)
                    bytes_received += len(chunk)

                    mb_done = bytes_received / (1024 * 1024)
                    mb_total = total_size / (1024 * 1024)
                    percent = bytes_received / total_size
                    self.set_progress(0.85 + 0.13 * percent, f"Downloading... {mb_done:.1f} / {mb_total:.1f} MB")

            try:
                cleanup_remote.remote()
            except Exception:
                pass
            
            self.set_progress(1.0, "3D Model Ready!")
            self.log(f"Success! Model exported to Desktop: {filename}")
            
            self.app.pending_file_mode = "append" if getattr(self.app, 'append_mode', True) else "replace"
            self.app.pending_file = save_path
            
            threading.Timer(3.0, self._reset_progress).start()
            
        except Exception as e:
            self.log(f"Error: Process failed. Details: {str(e)}")
            self.set_progress(0.0, "Process failed.")
        finally:
            if os.path.exists(local_zip):
                os.remove(local_zip)
            try:
                import shutil
                shutil.rmtree(preprocess_dir, ignore_errors=True)
            except Exception:
                pass
            self.is_processing = False
            self.is_gpu_processing = False

    def _stream_thread(self, requested_source=1):
        """Open and run a stream for an EXPLICIT source.
            requested_source = 1 -> wired (authorized USB scanner) [default]
            requested_source = 0 -> wireless (network/IP scanner)
        Wireless is ONLY ever opened when this is called with
        requested_source=0 (an explicit Connect). Nothing opens the wireless
        URL implicitly — that is what previously caused the tcp -138 errors."""
        try:
            import cv2
        except ImportError:
            self.log("Error: cv2 module not found.")
            self.show_stream_window = False
            return

        is_network = (requested_source == 0)
        url = None

        # Wired (USB) safety gate: never open anything but the authorized
        # scanner, and use its auto-detected index (no manual index entry).
        if not is_network:
            if not self.locked_camera.is_set() or not self.authorized_cam_present:
                self.log("Waiting for authorized scanner (not connected).")
                self.stream_capture = None
                self.active_source = None
                return
            wired_index = self.authorized_cam_index

        def _open_capture():
            nonlocal url
            if is_network:
                url = f"{self.camera_protocols[self.camera_protocol]}{self.camera_ip}"
                cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
            else:
                if os.name == 'nt':
                    cap = cv2.VideoCapture(wired_index, cv2.CAP_DSHOW)
                else:
                    cap = cv2.VideoCapture(wired_index)

            try:
                cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
            except Exception:
                pass
            return cap

        self._requested_source = requested_source
        self.stream_capture = _open_capture()

        if not self.stream_capture.isOpened():
            self.log("Error: Could not open scanner." if not is_network
                     else "Error: Could not reach wireless scanner.")
            try:
                self.stream_capture.release()
            except Exception:
                pass
            self.stream_capture = None  # don't leave a dead capture behind
            self.active_source = None
            if is_network:
                # Don't close the window for a failed wireless attempt; the
                # wired feed may still be the intended one.
                pass
            return

        # We are now genuinely streaming this source.
        self.active_source = requested_source

        latest = [None]
        latest_lock = threading.Lock()
        grabber_alive = [is_network]

        def _grabber():
            cap = self.stream_capture
            local_fail = 0
            while grabber_alive[0] and self.show_stream_window:
                if cap is None or not cap.isOpened():
                    time.sleep(0.05)
                    cap = self.stream_capture
                    continue
                ok, f = cap.read()
                if ok and f is not None:
                    local_fail = 0
                    with latest_lock:
                        latest[0] = f 
                else:
                    local_fail += 1
                    time.sleep(0.005 if local_fail < 20 else 0.1)
                    # The phone can pause frames briefly (e.g. while it switches
                    # fullscreen<->portrait). Don't give up — reopen the stream
                    # and keep going so neither device freezes/breaks.
                    if local_fail > 60:
                        try:
                            old = self.stream_capture
                            self.stream_capture = _open_capture()
                            if old is not None:
                                old.release()
                        except Exception:
                            pass
                        cap = self.stream_capture
                        local_fail = 0

        grabber_thread = None
        if is_network:
            grabber_thread = threading.Thread(target=_grabber, daemon=True)
            grabber_thread.start()

        consecutive_failures = 0

        try:
            while self.show_stream_window and self._requested_source == requested_source:
                # Wired safety: if the authorized scanner is no longer present,
                # stop streaming and let the UI fall back to "Waiting...".
                if not is_network and not self.authorized_cam_present:
                    self.log("Authorized scanner disconnected.")
                    break

                if not self.stream_capture.isOpened():
                    grabber_alive[0] = False
                    time.sleep(1.0)
                    # Only auto-reopen for network sources; wired reopen is
                    # driven by the watcher detecting the authorized scanner.
                    if not is_network:
                        break
                    self.stream_capture = _open_capture()
                    if is_network and self.stream_capture.isOpened():
                        grabber_alive[0] = True
                        grabber_thread = threading.Thread(target=_grabber, daemon=True)
                        grabber_thread.start()
                    continue

                if is_network:
                    with latest_lock:
                        frame = latest[0]
                        latest[0] = None
                    if frame is None:
                        time.sleep(0.003)
                        continue
                    ret = True
                else:
                    ret, frame = self.stream_capture.read()

                if ret and frame is not None:
                    consecutive_failures = 0
                    self.raw_frame_bgr = frame if is_network else frame.copy()

                    if self.enable_undistortion and self.calibrator.is_calibrated():
                        frame = self.calibrator.undistort(frame)

                    self.current_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

                    # 3D depth preview (display only — NOT uploaded). Built
                    # from the real frame; the model still only ever receives
                    # self.current_frame / captured RGB stills.
                    if self.show_depth_view:
                        depth_bgr = self.depth_engine.process(frame)
                        if depth_bgr is not None:
                            self.depth_frame = cv2.cvtColor(depth_bgr, cv2.COLOR_BGR2RGB)
                    else:
                        self.depth_frame = None

                else:
                    consecutive_failures += 1
                    if consecutive_failures > 5:
                        grabber_alive[0] = False
                        self.stream_capture.release()
                        time.sleep(1.0)
                        consecutive_failures = 0
                    else:
                        time.sleep(0.01)
        except Exception as e:
            pass
        finally:
            grabber_alive[0] = False
            if self.stream_capture:
                self.stream_capture.release()
            self.stream_capture = None
            self.current_frame = None
            self.raw_frame_bgr = None
            self.depth_frame = None
            if self.active_source == requested_source:
                self.active_source = None

    def _capture_current_frame(self):
        current_frame_local = self.current_frame
        if current_frame_local is None:
            return
            
        try:
            import cv2
            filename = f"capture_{int(time.time()*1000)}.jpg"
            filepath = os.path.join(self.capture_dir, filename)
            cv2.imwrite(filepath, cv2.cvtColor(current_frame_local, cv2.COLOR_RGB2BGR))
            
            if self.replace_index != -1 and self.replace_index < len(self.image_paths):
                old_path = self.image_paths[self.replace_index]
                if old_path in self.thumbnails:
                    del self.thumbnails[old_path]
                self.image_paths[self.replace_index] = filepath
                self.replace_index = -1
            else:
                self.image_paths.append(filepath)
                self.log(f"Data Acquisition: Extracted frame {filename}")
        except Exception as e:
            pass

    def _update_stream_texture(self, frame_rgb):
        import OpenGL.GL as gl
        h, w = frame_rgb.shape[:2]
        if self.stream_tex_id is None:
            self.stream_tex_id = gl.glGenTextures(1)
            
        gl.glBindTexture(gl.GL_TEXTURE_2D, self.stream_tex_id)
        gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
        gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0, gl.GL_RGB, gl.GL_UNSIGNED_BYTE, frame_rgb)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
        return self.stream_tex_id

    def _update_depth_texture(self, rgb_frame):
        """Upload the stylized (fake) depth frame to a GL texture for display."""
        try:
            import OpenGL.GL as gl
            import numpy as np
            if self.depth_tex_id is None:
                self.depth_tex_id = gl.glGenTextures(1)
            gl.glBindTexture(gl.GL_TEXTURE_2D, self.depth_tex_id)
            gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
            h, w = rgb_frame.shape[:2]
            data = np.ascontiguousarray(rgb_frame)
            gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0,
                            gl.GL_RGB, gl.GL_UNSIGNED_BYTE, data)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
            return self.depth_tex_id
        except Exception:
            return None

    def _get_thumbnail(self, path):
        if path in self.thumbnails:
            return self.thumbnails[path]
            
        try:
            import cv2
            import OpenGL.GL as gl
            img = cv2.imread(path)
            if img is None: 
                return None
            img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
            img = cv2.resize(img, (100, 100))
            h, w = img.shape[:2]
            
            tex_id = gl.glGenTextures(1)
            gl.glBindTexture(gl.GL_TEXTURE_2D, tex_id)
            gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
            gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0, gl.GL_RGB, gl.GL_UNSIGNED_BYTE, img)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
            
            self.thumbnails[path] = tex_id
            return tex_id
        except Exception:
            return None

    def _draw_connection_panel(self):
        """Connection panel inside the Live Scanner Stream window.
            Wireless -> network / IP scanner (protocol + address)
            Wired    -> the single AUTHORIZED USB scanner, auto-detected.
        The tab the user VIEWS (camera_source_type) is cosmetic and never
        starts/stops a stream. The tab that is genuinely STREAMING
        (active_source) is the one that gets the highlight, and the feeds
        below only ever show that active stream."""

        wireless_live = (self.active_source == 0
                         and self._cap_is_open(self.stream_capture))
        wired_live = (self.active_source == 1
                      and self._cap_is_open(self.stream_capture))

        active_col = (0.10, 0.62, 0.98, 1.0)

        def _push_active_tab_colors():
            pushed = 0
            for cname in ("COLOR_TAB", "COLOR_TAB_ACTIVE", "COLOR_TAB_HOVERED"):
                col = getattr(imgui, cname, None)
                if col is not None:
                    try:
                        imgui.push_style_color(col, *active_col)
                        pushed += 1
                    except Exception:
                        pass
            return pushed

        def _tab_selected(ret):
            if ret is None:
                return False
            sel = getattr(ret, "selected", None)
            if sel is not None:
                return bool(sel)
            if isinstance(ret, (tuple, list)) and ret:
                return bool(ret[0])
            return bool(ret)

        used_tabs = False
        tab_bar_open = False
        try:
            tab_bar_open = imgui.begin_tab_bar("ConnTabs")
        except Exception:
            tab_bar_open = False

        if tab_bar_open:
            used_tabs = True
            try:
                # ----- Wireless tab -----
                _pushed = _push_active_tab_colors() if wireless_live else 0
                ret = imgui.begin_tab_item("Wireless")
                if _pushed:
                    imgui.pop_style_color(_pushed)
                if _tab_selected(ret):
                    try:
                        self.camera_source_type = 0   # viewed tab only
                        self._draw_wireless_tab()
                    finally:
                        imgui.end_tab_item()

                # ----- Wired tab -----
                _pushed = _push_active_tab_colors() if wired_live else 0
                ret = imgui.begin_tab_item("Wired")
                if _pushed:
                    imgui.pop_style_color(_pushed)
                if _tab_selected(ret):
                    try:
                        self.camera_source_type = 1   # viewed tab only
                        self._draw_wired_tab()
                    finally:
                        imgui.end_tab_item()
            finally:
                imgui.end_tab_bar()

        if not used_tabs:
            # Fallback if the imgui build lacks a tab bar.
            if imgui.radio_button("Wireless##connsel", self.camera_source_type == 0):
                self.camera_source_type = 0
            imgui.same_line()
            if imgui.radio_button("Wired##connsel", self.camera_source_type == 1):
                self.camera_source_type = 1
            if self.camera_source_type == 0:
                self._draw_wireless_tab()
            else:
                self._draw_wired_tab()

    def _draw_wireless_tab(self):
        # The ONLY way to connect a wireless source is by pairing with the
        # authorized phone bridge via QR. There is deliberately no manual
        # protocol/IP entry: that would allow an unauthorized device to be
        # pointed at, exactly what we don't want. The phone's address is set
        # automatically by the pairing handshake.
        if self.active_source == 0 and self._cap_is_open(self.stream_capture):
            imgui.text_colored("\u25cf Connected", 0.30, 0.90, 0.45, 1.0)
            if self.camera_ip:
                imgui.same_line(0, self._ui(12))
                imgui.text_disabled(self.camera_ip)
        else:
            imgui.text_colored("\u25cf Not paired", 0.90, 0.40, 0.40, 1.0)

        imgui.dummy(0, self._ui(4))

        if self.active_source == 0 and self._cap_is_open(self.stream_capture):
            if imgui.button("Re-pair phone (QR)##wireless"):
                self.show_pair_window = True
                self._start_pairing_gate()
        else:
            if imgui.button("Pair phone (QR)##wireless"):
                self.show_pair_window = True
                self._start_pairing_gate()

    def _draw_wired_tab(self):
        # Status reflects the wired detection/stream state.
        if self.active_source == 1 and self._cap_is_open(self.stream_capture):
            imgui.text_colored("\u25cf Connected", 0.30, 0.90, 0.45, 1.0)
        else:
            st = getattr(self, "wired_status", "idle")
            if st == "ready":
                imgui.text_colored("\u25cf Scanner detected", 0.55, 0.85, 1.0, 1.0)
            elif st == "waiting":
                imgui.text_colored("\u25cf Waiting for scanner", 1.0, 0.72, 0.20, 1.0)
            elif st == "connected":
                imgui.text_colored("\u25cf Connected", 0.30, 0.90, 0.45, 1.0)
            else:
                imgui.text_colored("\u25cf Searching...", 0.75, 0.78, 0.82, 1.0)

    def _reconnect_stream(self, source=1):
        """Tear down any open capture and reopen for an EXPLICIT source.
        source: 0 wireless / 1 wired."""
        try:
            self.show_stream_window = True
            # Signal the currently-running stream loop to exit by changing the
            # requested source, then drop the capture.
            self._requested_source = source
            old = self.stream_capture
            self.stream_capture = None
            self.active_source = None
            if old is not None:
                try:
                    old.release()
                except Exception:
                    pass
            time.sleep(0.05)
            threading.Thread(target=self._stream_thread,
                             kwargs={"requested_source": source}, daemon=True).start()
        except Exception:
            pass

    def _draw_stream_window(self):
        """Live Scanner Stream: RGB + 3D depth preview side by side, with the
        scanner controls as a column on the right. The feeds only show when the
        source you are VIEWING is the one actually streaming."""
        # --- Feed sizing. Enlarged ~17% overall, with extra vertical size. ---
        # (1.17 width, 1.30 height so the window grows especially vertically.)
        feed_w = self._ui(655)           # 560 * 1.17
        feed_h = self._ui(572)           # 440 * 1.30
        pad_h = self._ui(154)            # 132 * 1.17 (toolbar+tabs+title-bar)
        ctrl_w = self._ui(398)           # 340 * 1.17
        gap = self._ui(12)

        # Depth and controls are ALWAYS shown (no toggles).
        n_feeds = 2
        feeds_block_w = n_feeds * feed_w + (n_feeds - 1) * gap
        content_w = feeds_block_w + gap + ctrl_w
        win_w = content_w + self._ui(33)

        body_h = max(feed_h, self._ui(598))   # 460 * 1.30
        win_h = body_h + pad_h + self._ui(19)

        # Clamp to the viewport so it never balloons off-screen.
        win_w, win_h = self._safe_window_size(win_w, win_h)
        disp_w, disp_h = self._logical_window_size()
        imgui.set_next_window_size(win_w, win_h, condition=imgui.ALWAYS)
        imgui.set_next_window_position((disp_w - win_w) * 0.5, (disp_h - win_h) * 0.5,
                                       condition=imgui.FIRST_USE_EVER)
        expanded, self.show_stream_window = imgui.begin("Live Scanner Stream", closable=True)
        if not expanded:
            imgui.end()
            return

        # ---------------- Toolbar ----------------
        space_pressed = False
        try:
            space_pressed = imgui.is_key_pressed(32)
        except Exception:
            pass

        if imgui.button("Capture (SPACE)") or space_pressed:
            self._capture_current_frame()

        imgui.same_line()
        if imgui.button("Finish"):
            self.show_stream_window = False
            self.replace_index = -1

        # (Depth/Controls checkboxes removed — both are always visible.)
        imgui.same_line(0, self._ui(16))
        if self.replace_index != -1:
            imgui.text_colored(f"Replacing {self.replace_index + 1}",
                               1.0, 0.5, 0.0, 1.0)
        else:
            imgui.text(f"Captured: {len(self.image_paths)}")

        imgui.separator()

        # ---------------- Connections panel (tabs + status, in-window) ------
        self._draw_connection_panel()

        imgui.separator()

        # ---------------- Feeds, packed tight side by side ----------------
        avail_w, avail_h = imgui.get_content_region_available()
        block_h = max(self._ui(380), avail_h)
        # Feeds expand to share the available width with the controls column.
        feeds_w = max(self._ui(320), avail_w - ctrl_w - gap)
        each_feed_w = max(self._ui(200), (feeds_w - gap) / 2.0)

        # The feeds belong to whichever source is ACTUALLY streaming. They are
        # only shown when the tab you are VIEWING matches that active source,
        # so switching to the Wireless tab while Wired is streaming does NOT
        # display the wired video under Wireless.
        viewing_active = (self.active_source is not None
                          and self.camera_source_type == self.active_source
                          and self._cap_is_open(self.stream_capture))

        rgb_frame = self.current_frame if viewing_active else None
        depth_frame = self.depth_frame if viewing_active else None

        # Placeholder text depends on what the viewed tab's state is.
        if self.camera_source_type == 0:
            placeholder = "Wireless scanner not connected."
        else:
            if getattr(self, "wired_status", "") == "waiting":
                placeholder = "Waiting for scanner..."
            elif self.active_source == 0:
                placeholder = "Wired scanner idle (wireless is active)."
            else:
                placeholder = "Connecting..."

        def _draw_feed(child_id, title, title_col, frame_rgb, tex_fn, w):
            imgui.begin_child(child_id, w, block_h, border=True)
            imgui.text_colored(title, *title_col)
            imgui.separator()
            if frame_rgb is not None:
                tex_id = tex_fn(frame_rgb)
                if tex_id:
                    fh, fw = frame_rgb.shape[:2]
                    cw, ch = imgui.get_content_region_available()
                    scale = min(cw / fw, ch / fh) if fw and fh else 0
                    if scale > 0:
                        # center the image in the child
                        iw, ih = int(fw * scale), int(fh * scale)
                        cur_x = imgui.get_cursor_pos_x()
                        if cw > iw:
                            imgui.set_cursor_pos_x(cur_x + (cw - iw) * 0.5)
                        imgui.image(tex_id, iw, ih)
            else:
                imgui.text_disabled(placeholder)
            imgui.end_child()

        # Live RGB feed.
        _draw_feed("rgb_feed", "Live View", (0.55, 0.85, 1.0, 1.0),
                   rgb_frame, self._update_stream_texture, each_feed_w)

        # 3D depth preview, right next to it.
        imgui.same_line(0.0, gap)
        _draw_feed("depth_feed", "3D Depth Preview", (1.0, 0.7, 0.2, 1.0),
                   depth_frame, self._update_depth_texture, each_feed_w)

        # ---------------- Scanner controls: vertical column on the right ----
        imgui.same_line(0.0, gap)
        imgui.begin_child("cam_controls", ctrl_w, block_h, border=True)
        # wireless == the active streaming source is the phone (0). In that mode
        # the panel talks to the phone over HTTP instead of cap.set().
        self.cam_controls.draw(self.stream_capture,
                               wireless=(self.active_source == 0))
        imgui.end_child()

        imgui.end()

    def _start_calibration_session(self):
        if not self.calib_stream_running:
            threading.Thread(target=self._calib_stream_thread, daemon=True).start()
            time.sleep(0.2)

        self.calibrating = True
        self.calibrator.start_session(
            target_frames=self.calib_target_frames,
            completion_callback=self._on_calibration_complete,
        )

    def _draw_calibration_window(self):
        w, h = self._safe_window_size(self._ui(560), self._ui(620))
        win_w, win_h = self._logical_window_size()
        imgui.set_next_window_size(w, h, condition=imgui.ALWAYS)
        imgui.set_next_window_position((win_w - w) * 0.5, (win_h - h) * 0.5,
                                       condition=imgui.FIRST_USE_EVER)

        expanded, self.show_calibration_window = imgui.begin("Scanner Lens Calibration", closable=True)
        if not expanded:
            if self.calibrating:
                self.calibrator.stop_session(finalize=False)
                self.calibrating = False
            if self.calib_stream_running:
                self._stop_calib_stream()
            imgui.end()
            return

        controls_disabled = self.calib_stream_running or self.calibrating
        if controls_disabled:
            imgui.push_style_var(imgui.STYLE_ALPHA, imgui.get_style().alpha * 0.5)

        prev_source = self.calib_camera_source_type
        if imgui.radio_button("Wireless##calib", self.calib_camera_source_type == 0):
            if not controls_disabled: self.calib_camera_source_type = 0
        imgui.same_line()
        if imgui.radio_button("Wired##calib", self.calib_camera_source_type == 1):
            if not controls_disabled: self.calib_camera_source_type = 1

        if self.calib_camera_source_type == 0:
            _, self.calib_camera_protocol = imgui.combo("Protocol##c", self.calib_camera_protocol, self.camera_protocols)
            _, self.calib_camera_ip = imgui.input_text("Address##c", self.calib_camera_ip, 256)
        else:
            _, self.calib_usb_camera_index = imgui.input_int("Camera Index##c", self.calib_usb_camera_index)

        if controls_disabled:
            imgui.pop_style_var(1)

        if prev_source != self.calib_camera_source_type:
            self._save_calib_camera_settings()

        imgui.dummy(0, 4)
        if not self.calib_stream_running:
            if imgui.button("Connect Calibration Scanner", width=-1):
                self._save_calib_camera_settings()
                threading.Thread(target=self._calib_stream_thread, daemon=True).start()
        else:
            if imgui.button("Disconnect Scanner", width=-1):
                if self.calibrating:
                    self.calibrator.stop_session(finalize=False)
                    self.calibrating = False
                self._stop_calib_stream()

        imgui.separator()

        calib_frame_local = self.calib_current_frame
        if calib_frame_local is not None:
            tex_id = self._update_calib_stream_texture(calib_frame_local)
            if tex_id:
                fh, fw = calib_frame_local.shape[:2]
                avail_w = imgui.get_content_region_available()[0]
                scale = min(avail_w / fw, 200 / fh)
                if scale > 0:
                    imgui.image(tex_id, int(fw * scale), int(fh * scale))
        else:
            imgui.text_disabled("Live preview...")

        if self.calibrating:
            status = self.calibrator.get_status()
            imgui.text(status["message"])
            if imgui.button("Cancel", width=-1):
                self.calibrator.stop_session(finalize=False)
                self.calibrating = False
        else:
            can_start = self.calib_stream_running
            if imgui.button("Start Calibration", width=-1) and can_start:
                self._start_calibration_session()

        imgui.end()

    def _ui(self, px):
        return int(px * getattr(self.app, "ui_scale", 1.0))

    def _logical_window_size(self):
        """Logical (point) window size, not framebuffer pixels.

        imgui lays windows out in logical points; using the framebuffer size
        (which is larger on HiDPI / OS-scaled displays) makes the pop-out
        windows balloon far past the viewport. Prefer glfw's window size when
        we can get it, and fall back to imgui's display size.
        """
        try:
            import glfw
            wnd = getattr(self.app, "window", None)
            if wnd is not None:
                w, h = glfw.get_window_size(wnd)
                if w > 0 and h > 0:
                    return float(w), float(h)
        except Exception:
            pass
        io = imgui.get_io()
        return float(io.display_size.x), float(io.display_size.y)

    def _safe_window_size(self, target_w, target_h, margin=40):
        m = self._ui(margin)
        win_w, win_h = self._logical_window_size()
        max_w = max(320, win_w - m)
        max_h = max(240, win_h - m)
        return min(target_w, max_w), min(target_h, max_h)

    def _draw_gallery_window(self):
        w, h = self._safe_window_size(self._ui(560), self._ui(480))
        win_w, win_h = self._logical_window_size()
        imgui.set_next_window_size(w, h, condition=imgui.ALWAYS)
        imgui.set_next_window_position((win_w - w) * 0.5, (win_h - h) * 0.5,
                                       condition=imgui.FIRST_USE_EVER)
        expanded, self.show_gallery_window = imgui.begin("Captured Images Gallery", closable=True)
        if not expanded:
            imgui.end()
            return

        if imgui.button("Close Gallery", width=-1):
            self.show_gallery_window = False

        imgui.dummy(0, 5)
        columns = 4
        imgui.columns(columns, "gallery", border=False)

        for i, path in enumerate(self.image_paths):
            imgui.push_id(str(i))
            tex_id = self._get_thumbnail(path)
            
            if tex_id:
                imgui.image(tex_id, 100, 100)
            else:
                imgui.text("Loading...")

            if imgui.button("Retake", width=100):
                self.replace_index = i
                self.show_stream_window = True
                threading.Thread(target=self._stream_thread,
                                 kwargs={"requested_source": 1}, daemon=True).start()
            
            imgui.pop_id()
            imgui.next_column()

        imgui.columns(1)
        imgui.end()

    # =========================================================================
    # SCANNER PRO v4.2  -  EXACT MOCKUP REPLICATION
    # The whole UI is drawn on the window draw-list against a fixed 2560x1438
    # design canvas (the resolution of the reference image), uniformly scaled
    # to whatever space is available, so positions / proportions / colors
    # match the picture 1:1 at any window size.
    # =========================================================================

    # ---------------- draw-list icon primitives ----------------

    # ---------------- custom PNG icons ----------------
    # Drop PNG files into  tools/icons/  to replace the built-in vector icons:
    #   icons/logo.png                 -> next to the SCANNER PRO headline
    #   icons/reverse_engineering.png  -> Reverse Engineering mode button
    #   icons/metrology.png            -> Metrology mode button
    #   icons/mapping.png              -> Scene Mapping (LingBot) mode button
    #   icons/real_estate.png          -> Real Estate (Gaussian splat) mode button
    # Transparent-background PNGs look best. Missing files fall back to the
    # built-in vector icons automatically.

    def _get_png_icon(self, filename):
        if not hasattr(self, "_png_icons"):
            self._png_icons = {}
        if filename in self._png_icons:
            return self._png_icons[filename]

        tex = None
        try:
            import cv2
            import numpy as np
            import OpenGL.GL as gl
            base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")
            path = os.path.join(base, filename)
            if os.path.exists(path):
                img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
                if img is not None:
                    if img.ndim == 2:
                        img = cv2.cvtColor(img, cv2.COLOR_GRAY2RGBA)
                    elif img.shape[2] == 3:
                        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGBA)
                    else:
                        img = cv2.cvtColor(img, cv2.COLOR_BGRA2RGBA)
                    h, w = img.shape[:2]
                    t = gl.glGenTextures(1)
                    gl.glBindTexture(gl.GL_TEXTURE_2D, t)
                    gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
                    gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA, w, h, 0,
                                    gl.GL_RGBA, gl.GL_UNSIGNED_BYTE,
                                    np.ascontiguousarray(img))
                    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
                    gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
                    tex = t
        except Exception:
            tex = None

        self._png_icons[filename] = tex
        return tex

    def _ic_gear(self, dl, cx, cy, r, col, th=2.0):
        dl.add_circle(cx, cy, r * 0.66, col, 24, th)
        dl.add_circle(cx, cy, r * 0.28, col, 16, th)
        for i in range(8):
            a = i * math.pi / 4.0
            dl.add_line(cx + math.cos(a) * r * 0.66, cy + math.sin(a) * r * 0.66,
                        cx + math.cos(a) * r, cy + math.sin(a) * r, col, th)

    def _ic_mesh(self, dl, x, y, w, h, col, th=1.5):
        sk = w * 0.30
        tl = (x + sk, y); tr = (x + w, y); br = (x + w - sk, y + h); bl = (x, y + h)
        pts = [tl, tr, br, bl]
        for i in range(4):
            a = pts[i]; b = pts[(i + 1) % 4]
            dl.add_line(a[0], a[1], b[0], b[1], col, th)

        def lerp(p, q, t):
            return (p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)

        for t in (1.0 / 3.0, 2.0 / 3.0):
            a = lerp(tl, tr, t); b = lerp(bl, br, t)
            dl.add_line(a[0], a[1], b[0], b[1], col, th * 0.8)
            a = lerp(tl, bl, t); b = lerp(tr, br, t)
            dl.add_line(a[0], a[1], b[0], b[1], col, th * 0.8)

    def _ic_caliper(self, dl, x, y, w, h, col, th=2.0):
        dl.add_line(x + w * 0.18, y, x + w * 0.18, y + h * 0.92, col, th)          # main beam
        dl.add_line(x, y + h * 0.06, x + w * 0.62, y + h * 0.06, col, th)          # fixed jaw
        dl.add_line(x + w * 0.62, y + h * 0.06, x + w * 0.62, y + h * 0.22, col, th)
        dl.add_line(x, y + h * 0.42, x + w * 0.50, y + h * 0.42, col, th)          # sliding jaw
        dl.add_line(x + w * 0.50, y + h * 0.42, x + w * 0.50, y + h * 0.28, col, th)
        dl.add_triangle(x + w * 0.55, y + h, x + w, y + h,                          # set square
                        x + w, y + h * 0.55, col, th)

    def _ic_gallery(self, dl, cx, cy, size, col, th=2.0):
        gap = size * 0.18
        b = (size - gap) / 2.0
        x0 = cx - size / 2.0
        y0 = cy - size / 2.0
        for ix in (0, 1):
            for iy in (0, 1):
                x = x0 + ix * (b + gap)
                y = y0 + iy * (b + gap)
                dl.add_rect(x, y, x + b, y + b, col, b * 0.22, 0, th)

    def _ic_upload(self, dl, cx, cy, size, col, th=2.0):
        s = size / 2.0
        dl.add_line(cx, cy - s * 0.95, cx, cy + s * 0.30, col, th)
        dl.add_line(cx, cy - s * 0.95, cx - s * 0.45, cy - s * 0.40, col, th)
        dl.add_line(cx, cy - s * 0.95, cx + s * 0.45, cy - s * 0.40, col, th)
        dl.add_line(cx - s * 0.90, cy + s * 0.30, cx - s * 0.90, cy + s * 0.90, col, th)
        dl.add_line(cx - s * 0.90, cy + s * 0.90, cx + s * 0.90, cy + s * 0.90, col, th)
        dl.add_line(cx + s * 0.90, cy + s * 0.90, cx + s * 0.90, cy + s * 0.30, col, th)

    def _ic_videocam(self, dl, cx, cy, size, col, th=2.0):
        s = size / 2.0
        x0 = cx - s; y0 = cy - s * 0.62
        x1 = cx + s * 0.35; y1 = cy + s * 0.62
        dl.add_rect(x0, y0, x1, y1, col, s * 0.25, 0, th)
        dl.add_triangle(x1 + s * 0.10, cy - s * 0.18,
                        x1 + s * 0.10, cy + s * 0.18,
                        cx + s * 1.00, cy - s * 0.42, col, th)
        dl.add_line(cx + s * 1.00, cy - s * 0.42, cx + s * 1.00, cy + s * 0.42, col, th)
        dl.add_line(cx + s * 1.00, cy + s * 0.42, x1 + s * 0.10, cy + s * 0.18, col, th)

    def _ic_webcam(self, dl, cx, cy, size, col, th=2.0):
        s = size / 2.0
        dl.add_circle(cx, cy - s * 0.22, s * 0.62, col, 24, th)
        dl.add_circle_filled(cx, cy - s * 0.22, s * 0.20, col)
        dl.add_line(cx, cy + s * 0.40, cx, cy + s * 0.80, col, th)
        dl.add_line(cx - s * 0.45, cy + s * 0.80, cx + s * 0.45, cy + s * 0.80, col, th)

    def _ic_network(self, dl, cx, cy, size, col, th=2.0):
        s = size / 2.0
        r = s * 0.26
        top = (cx, cy - s * 0.58)
        bl = (cx - s * 0.62, cy + s * 0.55)
        br = (cx + s * 0.62, cy + s * 0.55)
        dl.add_circle(top[0], top[1], r, col, 16, th)
        dl.add_circle(bl[0], bl[1], r, col, 16, th)
        dl.add_circle(br[0], br[1], r, col, 16, th)
        dl.add_line(top[0], top[1] + r, top[0], cy, col, th)
        dl.add_line(bl[0], cy, br[0], cy, col, th)
        dl.add_line(bl[0], cy, bl[0], bl[1] - r, col, th)
        dl.add_line(br[0], cy, br[0], br[1] - r, col, th)

    def _ic_copy(self, dl, x, y, size, col, th=2.0):
        """Two overlapping rounded squares — classic 'copy' glyph."""
        b = size * 0.68
        off = size * 0.32
        dl.add_rect(x, y, x + b, y + b, col, b * 0.18, 0, th)
        dl.add_rect(x + off, y + off, x + off + b, y + off + b, col, b * 0.18, 0, th)

    def _ic_map(self, dl, x, y, w, h, col, th=2.0):
        """Folded map with a route + location pin — LingBot mapping mode."""
        # folded map outline (three panels, alternating skew)
        top = y + h * 0.22
        bot = y + h * 0.92
        x0, x1, x2, x3 = x, x + w * 0.33, x + w * 0.66, x + w
        dy = h * 0.08
        pts_top = [(x0, top + dy), (x1, top - dy), (x2, top + dy), (x3, top - dy)]
        pts_bot = [(x0, bot + dy), (x1, bot - dy), (x2, bot + dy), (x3, bot - dy)]
        for i in range(3):
            dl.add_line(pts_top[i][0], pts_top[i][1], pts_top[i + 1][0], pts_top[i + 1][1], col, th)
            dl.add_line(pts_bot[i][0], pts_bot[i][1], pts_bot[i + 1][0], pts_bot[i + 1][1], col, th)
        for i in range(4):
            dl.add_line(pts_top[i][0], pts_top[i][1], pts_bot[i][0], pts_bot[i][1],
                        col, th if i in (0, 3) else th * 0.7)
        # dotted route across the map
        route = [(x + w * 0.12, bot - h * 0.12), (x + w * 0.34, top + h * 0.30),
                 (x + w * 0.56, bot - h * 0.20), (x + w * 0.78, top + h * 0.26)]
        for i in range(len(route) - 1):
            ax, ay = route[i]; bx, by = route[i + 1]
            for t in (0.15, 0.45, 0.75):
                dl.add_circle_filled(ax + (bx - ax) * t, ay + (by - ay) * t,
                                     max(1.0, th * 0.7), col)
        # location pin above the route end
        px, py = x + w * 0.78, top + h * 0.26
        pr = h * 0.16
        dl.add_circle(px, py - pr * 1.5, pr, col, 16, th)
        dl.add_circle_filled(px, py - pr * 1.5, pr * 0.35, col)
        dl.add_line(px - pr * 0.7, py - pr * 0.8, px, py, col, th)
        dl.add_line(px + pr * 0.7, py - pr * 0.8, px, py, col, th)

    def _ic_building(self, dl, x, y, w, h, col, th=2.0):
        """Apartment / real-estate building with a roof, window grid and door
        — vector fallback for the Real Estate mode button."""
        bx0, by0, bx1, by1 = x + w * 0.18, y + h * 0.12, x + w * 0.82, y + h * 0.95
        dl.add_rect(bx0, by0, bx1, by1, col, max(1.0, w * 0.04), 0, th)
        # roof line (simple gable)
        dl.add_line(bx0, by0, x + w * 0.5, y, col, th)
        dl.add_line(bx1, by0, x + w * 0.5, y, col, th)
        # window grid (3 rows x 2 cols)
        gw = (bx1 - bx0) / 5.0
        gh = (by1 - by0) / 7.0
        for r in range(3):
            for c in range(2):
                wx = bx0 + gw * (1.3 + c * 2.0)
                wy = by0 + gh * (1.0 + r * 1.8)
                dl.add_rect(wx, wy, wx + gw * 0.8, wy + gh * 0.9, col, 0, 0, th * 0.8)
        # door (centered at base)
        dx = x + w * 0.5
        dl.add_rect(dx - gw * 0.45, by1 - gh * 1.6, dx + gw * 0.45, by1, col, 0, 0, th * 0.8)

    def draw_options(self):
        self._lazy_load_icons()

        # ------------------------------------------------------------------
        # The host "3D Scanner Options" window draws its own opaque
        # background, which we cannot remove from inside it — so it gets
        # parked off-screen (effectively invisible) and the real UI renders
        # in a separate OVERLAY window with NO background at all. Only the
        # rounded SCANNER PRO frame is drawn; the 3D viewport shows through
        # everywhere else.
        # ------------------------------------------------------------------
        try:
            imgui.set_window_size(1.0, 1.0, condition=imgui.ALWAYS)
            imgui.set_window_position(-20000.0, -20000.0, condition=imgui.ALWAYS)
        except Exception:
            pass

        io = imgui.get_io()
        dw, dh = io.display_size.x, io.display_size.y
        # Snap the overlay window size & position to WHOLE pixels. A window
        # positioned at e.g. x=413.5 makes every glyph in it rasterize on a
        # half-pixel boundary -> the entire UI looks slightly soft.
        ov_w = math.floor(min(dw * 0.96, (dh * 0.96) * (2560.0 / 1438.0)))
        ov_h = math.floor(ov_w * (1438.0 / 2560.0))
        imgui.set_next_window_position(math.floor((dw - ov_w) * 0.5),
                                       math.floor((dh - ov_h) * 0.5),
                                       condition=imgui.ALWAYS)
        imgui.set_next_window_size(ov_w, ov_h, condition=imgui.ALWAYS)
        try:
            imgui.set_next_window_bg_alpha(0.0)
        except Exception:
            pass

        ov_flags = (imgui.WINDOW_NO_TITLE_BAR | imgui.WINDOW_NO_RESIZE |
                    imgui.WINDOW_NO_MOVE | imgui.WINDOW_NO_COLLAPSE |
                    imgui.WINDOW_NO_SCROLLBAR | imgui.WINDOW_NO_SAVED_SETTINGS)
        for extra in ("WINDOW_NO_BACKGROUND", "WINDOW_NO_SCROLL_WITH_MOUSE"):
            try:
                ov_flags |= getattr(imgui, extra)
            except Exception:
                pass

        imgui.push_style_var(imgui.STYLE_WINDOW_PADDING, (0.0, 0.0))
        imgui.begin("##ScannerProOverlay", flags=ov_flags)

        avail_w, avail_h = imgui.get_content_region_available()
        avail_w = max(avail_w, 640.0)
        avail_h = max(avail_h, 380.0)

        flags = imgui.WINDOW_NO_SCROLLBAR
        try:
            flags |= imgui.WINDOW_NO_SCROLL_WITH_MOUSE
        except Exception:
            pass

        # Transparent child too — nothing but the panel itself gets painted.
        imgui.push_style_color(imgui.COLOR_CHILD_BACKGROUND, 0.0, 0.0, 0.0, 0.0)
        imgui.begin_child("ScannerRoot", avail_w, avail_h, border=False, flags=flags)

        dl = imgui.get_window_draw_list()
        win_x, win_y = imgui.get_window_position()
        cur_x, cur_y = imgui.get_cursor_pos()
        origin_x = win_x + cur_x
        origin_y = win_y + cur_y

        # --- fixed 2560x1438 design canvas, uniformly scaled & centered ---
        s = min(avail_w / 2560.0, avail_h / 1438.0)
        W, H = 2560.0 * s, 1438.0 * s
        # Integer canvas origin -> shapes & glyphs land on pixel boundaries.
        ox = round(origin_x + (avail_w - W) * 0.5)
        oy = round(origin_y + (avail_h - H) * 0.5)

        def X(v): return ox + v * s
        def Y(v): return oy + v * s
        def S(v): return v * s

        C = imgui.get_color_u32_rgba
        base_font = max(imgui.get_font_size(), 1.0)

        # Pick the rasterized font size CLOSEST to the target (either
        # direction) so the residual scale is a few percent at most.
        hires_fonts = self._hires_fonts

        def _pick_font(target_px):
            if not hires_fonts:
                return None, base_font
            best = min(hires_fonts, key=lambda pf: abs(pf[0] - target_px))
            return best[1], best[0]

        def text(px, py, t, size=32, color=(0.92, 0.93, 0.95, 1.0), bold=False):
            target_px = max(9.0, size * s)
            font, font_px = _pick_font(target_px)
            if font is not None:
                # Render at the font's NATIVE rasterized size (scale 1.0).
                # Any fractional window-font-scale resamples the glyph atlas
                # and is the main source of slightly blurry text. The size
                # ladder is dense enough that the size error is invisible.
                imgui.push_font(font)
                imgui.set_window_font_scale(1.0)
            else:
                imgui.set_window_font_scale(target_px / font_px)
            lx = round(X(px)) - win_x
            ly = round(Y(py)) - win_y
            imgui.set_cursor_pos((lx, ly))
            imgui.text_colored(t, *color)
            if bold and target_px >= 14.0:
                imgui.set_cursor_pos((lx + 1.0, ly))
                imgui.text_colored(t, *color)
            imgui.set_window_font_scale(1.0)
            if font is not None:
                imgui.pop_font()

        def text_w(t, size):
            target_px = max(9.0, size * s)
            font, font_px = _pick_font(target_px)
            if font is not None:
                imgui.push_font(font)
                imgui.set_window_font_scale(1.0)
            else:
                imgui.set_window_font_scale(target_px / font_px)
            w = imgui.calc_text_size(t)[0]
            imgui.set_window_font_scale(1.0)
            if font is not None:
                imgui.pop_font()
            return w / s

        def hit(idstr, px, py, pw, ph):
            imgui.set_cursor_pos((X(px) - win_x, Y(py) - win_y))
            clicked = imgui.invisible_button(idstr, max(S(pw), 1.0), max(S(ph), 1.0))
            return clicked, imgui.is_item_hovered()

        lt = max(1.0, S(2.5))   # generic line thickness for icons

        # ==================== APP FRAME ====================
        dl.add_rect_filled(X(190), Y(58), X(2392), Y(1378), C(0.075, 0.086, 0.101, 1.0), S(30))
        dl.add_rect(X(190), Y(58), X(2392), Y(1378), C(0.20, 0.24, 0.29, 1.0), S(30), 0, max(1.0, S(2)))

        # Light-blue corner brackets (outside the frame corners)
        bc = C(0.66, 0.84, 0.97, 0.95)
        bw = max(1.5, S(6))
        bl_len = S(90)
        dl.add_line(X(182), Y(50), X(182) + bl_len, Y(50), bc, bw)        # TL
        dl.add_line(X(182), Y(50), X(182), Y(50) + bl_len, bc, bw)
        dl.add_line(X(2400), Y(50), X(2400) - bl_len, Y(50), bc, bw)      # TR
        dl.add_line(X(2400), Y(50), X(2400), Y(50) + bl_len, bc, bw)
        dl.add_line(X(182), Y(1386), X(182) + bl_len, Y(1386), bc, bw)    # BL
        dl.add_line(X(182), Y(1386), X(182), Y(1386) - bl_len, bc, bw)
        dl.add_line(X(2400), Y(1386), X(2400) - bl_len, Y(1386), bc, bw)  # BR
        dl.add_line(X(2400), Y(1386), X(2400), Y(1386) - bl_len, bc, bw)

        # OS window controls (decorative: minimize / maximize / close)
        wc = C(0.62, 0.66, 0.71, 1.0)
        dl.add_line(X(2240), Y(95), X(2262), Y(95), wc, lt)                       # minimize
        dl.add_rect(X(2288), Y(84), X(2308), Y(104), wc, S(2), 0, lt)             # maximize
        dl.add_line(X(2338), Y(84), X(2358), Y(104), wc, lt)                      # close
        dl.add_line(X(2358), Y(84), X(2338), Y(104), wc, lt)

        # ==================== TOP BAR ====================
        dl.add_rect_filled(X(222), Y(118), X(1822), Y(252), C(0.094, 0.104, 0.122, 1.0), S(12))
        dl.add_rect(X(222), Y(118), X(1822), Y(252), C(0.17, 0.19, 0.23, 1.0), S(12), 0, max(1.0, S(1.5)))

        # Logo "S" (blue emblem)
        # Logo: custom PNG (tools/icons/logo.png) if present, else blue "S"
        logo_tex = self._get_png_icon("logo.png")
        if logo_tex:
            dl.add_image(logo_tex, (X(258), Y(146)), (X(338), Y(226)))
        else:
            dl.add_rect_filled(X(262), Y(150), X(330), Y(222), C(0.075, 0.30, 0.66, 1.0), S(18))
            dl.add_rect(X(262), Y(150), X(330), Y(222), C(0.22, 0.55, 0.95, 1.0), S(18), 0, max(1.0, S(2)))
            sw = text_w("S", 52)
            text(296 - sw / 2.0, 158, "S", 52, (0.85, 0.93, 1.0, 1.0), bold=True)

        text(352, 158, "SCANNER PRO", 47, (0.93, 0.94, 0.96, 1.0), bold=True)
        title_w = text_w("SCANNER PRO", 47)
        text(352 + title_w + 20, 174, "v4.2", 30, (0.55, 0.59, 0.64, 1.0))

        # ----- Connection state (computed, NOT drawn on the main window) -----
        # The online/offline overlay lives ONLY inside the Live Scanner Stream
        # window. Here we just keep the live state up to date for other logic.
        # A capture object can exist but be dead (failed open, dropped stream),
        # so we must ask isOpened() — non-None is not enough.
        def _cap_alive(cap):
            try:
                return cap is not None and cap.isOpened()
            except Exception:
                return False

        devices = 0
        if _cap_alive(self.stream_capture):
            devices += 1
        if self.calib_stream_running and _cap_alive(self.calib_stream_capture):
            devices += 1
        self._conn_devices = devices
        self._conn_online = devices > 0

        # ==================== MAIN PANEL ====================
        dl.add_rect_filled(X(224), Y(268), X(2358), Y(1342), C(0.082, 0.090, 0.104, 1.0), S(18))
        dl.add_rect(X(224), Y(268), X(2358), Y(1342), C(0.16, 0.18, 0.22, 1.0), S(18), 0, max(1.0, S(1.5)))
        # sidebar / bottom dividers
        dl.add_line(X(432), Y(268), X(432), Y(1218), C(0.16, 0.18, 0.22, 1.0), max(1.0, S(1.5)))
        dl.add_line(X(224), Y(1218), X(2358), Y(1218), C(0.16, 0.18, 0.22, 1.0), max(1.0, S(1.5)))

        # ==================== SIDEBAR ====================
        icon_col = C(0.87, 0.90, 0.93, 1.0)
        sidebar = [
            ("##sb_gallery", "View Gallery", 278, "gallery"),
            ("##sb_load",    "Load Images",  462, "upload"),
            ("##sb_stream",  "Live Stream",  646, "videocam"),
            ("##sb_calib",   "Calibration",  830, "webcam"),
        ]
        item_h = 178
        for idx, (iid, label, y0, icon) in enumerate(sidebar):
            clicked, hov = hit(iid, 228, y0, 202, item_h)
            active = (idx == 0)  # "View Gallery" highlighted as in the mockup
            if active:
                dl.add_rect_filled(X(228), Y(y0), X(430), Y(y0 + item_h),
                                   C(0.115, 0.128, 0.150, 1.0), S(10))
                dl.add_rect_filled(X(228), Y(y0), X(237), Y(y0 + item_h),
                                   C(0.20, 0.78, 0.92, 1.0), S(4))
            elif hov:
                dl.add_rect_filled(X(228), Y(y0), X(430), Y(y0 + item_h),
                                   C(0.105, 0.115, 0.135, 1.0), S(10))

            cx, cy = X(329), Y(y0 + 64)
            isz = S(64)
            if icon == "gallery":
                self._ic_gallery(dl, cx, cy, isz, icon_col, lt)
            elif icon == "upload":
                self._ic_upload(dl, cx, cy, isz, icon_col, lt)
            elif icon == "videocam":
                self._ic_videocam(dl, cx, cy, isz, icon_col, lt)
            elif icon == "webcam":
                self._ic_webcam(dl, cx, cy, isz, icon_col, lt)

            lw = text_w(label, 30)
            text(329 - lw / 2.0, y0 + 116, label, 30, (0.80, 0.83, 0.86, 1.0))

            if clicked and not self.is_processing and not self.is_deploying:
                if icon == "gallery":
                    self.show_gallery_window = True
                elif icon == "upload":
                    self._open_file_dialog()
                elif icon == "videocam":
                    self.show_stream_window = True
                    self.replace_index = -1
                    threading.Thread(target=self._stream_thread,
                                     kwargs={"requested_source": 1}, daemon=True).start()
                elif icon == "webcam":
                    self.show_calibration_window = True

        # ==================== SCAN MODE ====================
        text(500, 300, "SCAN MODE", 62, (0.93, 0.94, 0.96, 1.0), bold=True)

        def mode_button(iid, label, y0, active, icon, sub=None):
            x0, x1 = 497, 1025
            y1 = y0 + 140
            clicked, hov = hit(iid, x0, y0, x1 - x0, y1 - y0)
            if active:
                # cyan outer glow
                for i in range(3):
                    a = 0.12 - i * 0.035
                    pad = S(4 + i * 5)
                    dl.add_rect(X(x0) - pad, Y(y0) - pad, X(x1) + pad, Y(y1) + pad,
                                C(0.13, 0.83, 0.92, max(a, 0.0)), S(26), 0, max(1.0, S(4)))
                fill = (0.052, 0.282, 0.305, 1.0) if not hov else (0.065, 0.315, 0.340, 1.0)
                dl.add_rect_filled(X(x0), Y(y0), X(x1), Y(y1), C(*fill), S(20))
                dl.add_rect(X(x0), Y(y0), X(x1), Y(y1),
                            C(0.16, 0.84, 0.93, 1.0), S(20), 0, max(1.5, S(3)))
                tcol = (0.94, 0.98, 0.99, 1.0)
                scol = (0.62, 0.88, 0.94, 1.0)
                ic = C(0.88, 0.97, 0.99, 1.0)
            else:
                fill = (0.094, 0.102, 0.118, 1.0) if not hov else (0.115, 0.125, 0.145, 1.0)
                dl.add_rect_filled(X(x0), Y(y0), X(x1), Y(y1), C(*fill), S(20))
                dl.add_rect(X(x0), Y(y0), X(x1), Y(y1),
                            C(0.27, 0.29, 0.33, 1.0), S(20), 0, max(1.0, S(1.5)))
                tcol = (0.88, 0.90, 0.92, 1.0)
                scol = (0.56, 0.60, 0.65, 1.0)
                ic = C(0.84, 0.87, 0.90, 1.0)

            # Custom PNG icons (tools/icons/*.png) override the vector ones
            png_name = {"re": "reverse_engineering.png",
                        "cal": "metrology.png",
                        "map": "mapping.png",
                        "estate": "real_estate.png"}[icon]
            png = self._get_png_icon(png_name)
            if png:
                dl.add_image(png, (X(548), Y(y0 + 27)), (X(634), Y(y0 + 113)))
            elif icon == "re":
                self._ic_gear(dl, X(585), Y(y0 + 48), S(28), ic, lt)
                self._ic_mesh(dl, X(560), Y(y0 + 72), S(72), S(44), ic, max(1.0, S(2)))
            elif icon == "map":
                self._ic_map(dl, X(545), Y(y0 + 30), S(86), S(80), ic, lt)
            elif icon == "estate":
                self._ic_building(dl, X(548), Y(y0 + 26), S(80), S(88), ic, lt)
            else:
                self._ic_caliper(dl, X(552), Y(y0 + 26), S(74), S(88), ic, lt)

            if sub:
                text(660, y0 + 26, label, 40, tcol)
                text(662, y0 + 80, sub, 24, scol)
            else:
                text(660, y0 + 42, label, 44, tcol)
            return clicked

        # Four mode buttons at the original 174px stride. The Activity Log
        # has been relocated to the bottom-right, so the left column is free
        # for the extra Real Estate button without any overlap.
        if mode_button("##mode_re", "Reverse Engineering", 402, self.scan_mode == 1, "re"):
            self.scan_mode = 1
        if mode_button("##mode_met", "Metrology", 576, self.scan_mode == 0, "cal"):
            self.scan_mode = 0
        if mode_button("##mode_map", "Scene Mapping", 750, self.scan_mode == 2, "map"):
            self.scan_mode = 2
        if mode_button("##mode_estate", "Real Estate", 924, self.scan_mode == 3, "estate"):
            self.scan_mode = 3

        # ==================== ACTIVITY LOG (compact right-side console) ====================
        # Kept on the right (well away from the SCAN MODE buttons) but sized as
        # a compact console rather than a full-height pane.
        alx0, aly0, alx1, aly1 = 1560, 360, 2330, 760
        dl.add_rect_filled(X(alx0), Y(aly0), X(alx1), Y(aly1), C(0.090, 0.099, 0.116, 1.0), S(16))
        dl.add_rect(X(alx0), Y(aly0), X(alx1), Y(aly1), C(0.18, 0.20, 0.24, 1.0), S(16), 0, max(1.0, S(1.5)))

        # Header strip
        dl.add_rect_filled(X(alx0), Y(aly0), X(alx1), Y(aly0 + 76), C(0.106, 0.116, 0.136, 1.0), S(16))
        text(alx0 + 30, aly0 + 22, "Activity Log", 38, (0.93, 0.94, 0.96, 1.0), bold=True)

        # ----- Copy-log button (top-right of the header) -----
        clx0, cly0 = alx1 - 158, aly0 + 16
        clx1, cly1 = alx1 - 24, aly0 + 64
        cl_click, cl_hov = hit("##copylog", clx0, cly0, clx1 - clx0, cly1 - cly0)
        cl_fill = (0.165, 0.185, 0.220, 1.0) if cl_hov else (0.135, 0.150, 0.178, 1.0)
        dl.add_rect_filled(X(clx0), Y(cly0), X(clx1), Y(cly1), C(*cl_fill), S(10))
        dl.add_rect(X(clx0), Y(cly0), X(clx1), Y(cly1),
                    C(0.30, 0.34, 0.40, 1.0), S(10), 0, max(1.0, S(1.5)))
        self._ic_copy(dl, X(clx0 + 20), Y(cly0 + 11), S(26),
                      C(0.82, 0.85, 0.89, 1.0), max(1.0, S(2)))
        text(clx0 + 62, cly0 + 8, "Copy", 27, (0.86, 0.89, 0.93, 1.0))
        if cl_click:
            self._copy_activity_log()

        # Column headers
        text(alx0 + 30, aly0 + 92, "Timestamp", 27, (0.55, 0.59, 0.64, 1.0))
        text(alx0 + 246, aly0 + 92, "Event", 27, (0.55, 0.59, 0.64, 1.0))
        dl.add_line(X(alx0 + 22), Y(aly0 + 130), X(alx1 - 22), Y(aly0 + 130),
                    C(0.18, 0.20, 0.24, 1.0), max(1.0, S(1)))

        # Rows. The pane is tall, so render the most recent lines that fit.
        now_str = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
        row_h = 50
        first_row_y = aly0 + 146
        max_rows = int((aly1 - 24 - first_row_y) / row_h)
        max_rows = max(1, max_rows)
        yy = first_row_y
        for log_msg in self.logs[-max_rows:]:
            text(alx0 + 30, yy, now_str, 30, (0.78, 0.81, 0.85, 1.0))
            if "Error" in log_msg:
                lcol = (1.0, 0.35, 0.35, 1.0)
            elif "Success" in log_msg or "OK" in log_msg:
                lcol = (0.35, 0.95, 0.45, 1.0)
            else:
                lcol = (0.90, 0.91, 0.93, 1.0)
            msg = log_msg[:64] + ("..." if len(log_msg) > 64 else "")
            text(alx0 + 246, yy, msg, 30, lcol)
            yy += row_h

        # ==================== BOTTOM BAR ====================
        text(470, 1252, "Status:", 34, (0.58, 0.62, 0.67, 1.0))
        st_w = text_w("Status:", 34)
        if self.is_processing:
            stxt, scol = f"Processing... {int(self.progress * 100)}%", (1.0, 0.78, 0.15, 1.0)
        elif self.is_deploying:
            stxt, scol = "Engine Starting...", (1.0, 0.62, 0.10, 1.0)
        else:
            stxt, scol = "Ready", (0.25, 0.95, 0.45, 1.0)
        text(470 + st_w + 16, 1252, stxt, 34, scol)

        # thin progress bar while working
        if (self.is_processing or self.is_deploying) and self.progress > 0.0:
            dl.add_rect_filled(X(470), Y(1306), X(1180), Y(1316), C(0.14, 0.16, 0.19, 1.0), S(5))
            dl.add_rect_filled(X(470), Y(1306), X(470 + 710 * self.progress), Y(1316),
                               C(0.10, 0.62, 0.98, 1.0), S(5))

        # ----- WAKE UP ENGINE (starts the cloud engine — original logic) -----
        ex0, ey0, ex1, ey1 = 820, 1228, 1190, 1300
        eclick, ehov = hit("##wake_engine", ex0, ey0, ex1 - ex0, ey1 - ey0)
        engine_busy = self.is_deploying or self.is_processing
        if engine_busy:
            efill = (0.10, 0.10, 0.11, 1.0)
            ebord = C(0.45, 0.32, 0.12, 1.0)
            etcol = (0.55, 0.50, 0.42, 1.0)
        else:
            efill = (0.135, 0.105, 0.055, 1.0) if not ehov else (0.17, 0.13, 0.07, 1.0)
            ebord = C(0.95, 0.62, 0.15, 1.0)
            etcol = (1.0, 0.78, 0.35, 1.0)
        dl.add_rect_filled(X(ex0), Y(ey0), X(ex1), Y(ey1), C(*efill), S(20))
        dl.add_rect(X(ex0), Y(ey0), X(ex1), Y(ey1), ebord, S(20), 0, max(1.0, S(2)))
        # power symbol
        pc = C(*etcol)
        dl.add_circle(X(ex0 + 52), Y(1264), S(20), pc, 24, lt)
        dl.add_line(X(ex0 + 52), Y(1264) - S(28), X(ex0 + 52), Y(1264) - S(4), pc, lt)
        wtxt = "Engine Starting..." if self.is_deploying else "Wake Up Engine"
        text(ex0 + 92, 1244, wtxt, 34, etcol, bold=True)
        if eclick and not engine_busy:
            threading.Thread(target=self._deploy_thread, daemon=True).start()

        # ----- CRAFT GEOMETRY (big blue button, centered) -----
        gx0, gy0, gx1, gy1 = 1300, 1220, 1775, 1306
        # raised "tab" backplate behind the button (as in the mockup)
        dl.add_rect_filled(X(1262), Y(1186), X(1812), Y(1318), C(0.066, 0.074, 0.088, 1.0), S(44))
        gclicked, ghover = hit("##craft", gx0, gy0, gx1 - gx0, gy1 - gy0)
        # glow
        for i in range(3):
            a = 0.16 - i * 0.05
            pad = S(4 + i * 5)
            dl.add_rect(X(gx0) - pad, Y(gy0) - pad, X(gx1) + pad, Y(gy1) + pad,
                        C(0.25, 0.65, 1.0, max(a, 0.0)), S(34), 0, max(1.0, S(4)))
        bfill = (0.05, 0.50, 0.92, 1.0) if not ghover else (0.08, 0.58, 1.0, 1.0)
        dl.add_rect_filled(X(gx0), Y(gy0), X(gx1), Y(gy1), C(*bfill), S(28))
        # top inner highlight (fakes the vertical gradient of the mockup)
        dl.add_line(X(gx0) + S(28), Y(gy0) + S(5), X(gx1) - S(28), Y(gy0) + S(5),
                    C(1.0, 1.0, 1.0, 0.30), max(1.5, S(4)))
        dl.add_rect(X(gx0), Y(gy0), X(gx1), Y(gy1),
                    C(0.42, 0.78, 1.0, 1.0), S(28), 0, max(1.0, S(2)))
        self._ic_gear(dl, X(1372), Y(1263), S(27), C(1.0, 1.0, 1.0, 1.0), lt)
        text(1418, 1238, "Craft Geometry", 45, (1.0, 1.0, 1.0, 1.0), bold=True)
        if gclicked:
            if not self.image_paths:
                self.log("Error: Please select photos or videos first.")
            elif not self.is_processing and not self.is_deploying:
                self.is_processing = True
                self.logs.clear()
                threading.Thread(target=self._workflow_thread, daemon=True).start()

        # (Connection settings now live inside the Live Camera Stream window's
        #  tabbed panel — no overlay popup here anymore.)

        imgui.set_window_font_scale(1.0)
        imgui.end_child()  # ScannerRoot
        imgui.pop_style_color(1)
        imgui.end()  # ##ScannerProOverlay
        imgui.pop_style_var(1)  # WINDOW_PADDING

        # Pop-out windows (live stream [incl. depth + controls], gallery,
        # calibration) — drawn with a crisp TTF UI font so they don't use the
        # blurry scaled bitmap font.
        # Apply a phone address the gate may have received (auto-connects).
        try:
            self._poll_pairing_result()
        except Exception:
            pass

        # Keep the controls panel's REMOTE target in sync with the phone so PC
        # sliders drive the phone (and mirror phone-side changes) in wireless
        # mode. The phone serves control endpoints on the SAME host as /video,
        # i.e. http://<phone-ip>:<port>.
        try:
            if self.active_source == 0 and self.camera_ip:
                proto = self.camera_protocols[self.camera_protocol] or "http://"
                # camera_ip is like "10.0.0.5:8080/video"; strip the path.
                host = self.camera_ip.split("/", 1)[0]
                self.cam_controls.set_remote_target(f"{proto}{host}")
            else:
                self.cam_controls.set_remote_target(None)
        except Exception:
            pass

        ui_font_pushed = self._push_ui_font()
        try:
            if self.show_stream_window:
                self._draw_stream_window()
            if self.show_gallery_window:
                self._draw_gallery_window()
            if self.show_calibration_window:
                self._draw_calibration_window()
            if getattr(self, "show_pair_window", False):
                self._draw_pair_window()
        finally:
            if ui_font_pushed:
                imgui.pop_font()
