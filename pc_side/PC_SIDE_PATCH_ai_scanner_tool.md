PC-SIDE PATCH — tools/ai_scanner_tool.py
========================================

Apply these edits to tools/ai_scanner_tool.py. They are small and localized.
The full replacement for tools/camera_controls_panel.py is provided separately
(just overwrite that file).

There are 4 edits:

  1. Fix the FFmpeg stream timeout (the 30 s freeze / "Stream timeout triggered
     after 30031 ms"), and make the wireless grabber auto-reconnect.
  2. Tell the controls panel the phone address + whether we're wireless, so PC
     controls drive the phone and stay synced.
  3. Pass `wireless=` into cam_controls.draw().
  4. Remove the now-unused note about cap.set not working (cosmetic).


--------------------------------------------------------------------------
EDIT 1 — FFmpeg capture options (top of file)
--------------------------------------------------------------------------
REPLACE the whole `os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (...)` block
and the `OPENCV_FFMPEG_READ_TIMEOUT` line near the top of the file with:

    # Low-latency FFmpeg flags for the phone's MJPEG-over-HTTP stream.
    #
    # IMPORTANT: the phone stream is HTTP (multipart/x-mixed-replace), NOT RTSP.
    # The old config only set RTSP/`stimeout` options, which FFmpeg IGNORES for
    # HTTP — so when the phone briefly stopped sending frames (e.g. while it
    # switched fullscreen<->portrait) FFmpeg fell back to its DEFAULT ~30 s
    # blocking read, froze the UI, and then broke the stream
    # ("Stream timeout triggered after 30031 ms").
    #
    # For HTTP we must set `timeout`/`rw_timeout` (microseconds). 3 s is plenty
    # on a LAN and lets our reconnect logic kick in quickly instead of hanging.
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


--------------------------------------------------------------------------
EDIT 2 — wireless grabber auto-reconnect (in _stream_thread)
--------------------------------------------------------------------------
The network grabber thread `_grabber()` inside `_stream_thread` currently
GIVES UP after 100 failed reads (`if local_fail > 100: return`). When the phone
hiccups during a fullscreen toggle, that kills the grabber and the stream
freezes/breaks. Make it RECONNECT instead.

FIND this `_grabber` definition inside `_stream_thread` (the network one):

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
                    if local_fail > 100:
                        return

REPLACE it with:

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

NOTE: `_open_capture` is the nested function already defined in
`_stream_thread`, so it is in scope here.


--------------------------------------------------------------------------
EDIT 3 — point the controls panel at the phone (in _poll_pairing_result OR
         wherever camera_ip becomes known). Easiest: in draw_options, right
         after self._poll_pairing_result().
--------------------------------------------------------------------------
FIND (near the bottom of draw_options):

        try:
            self._poll_pairing_result()
        except Exception:
            pass

REPLACE with:

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


--------------------------------------------------------------------------
EDIT 4 — pass wireless= into the controls draw (in _draw_stream_window)
--------------------------------------------------------------------------
FIND:

        imgui.begin_child("cam_controls", ctrl_w, block_h, border=True)
        self.cam_controls.draw(self.stream_capture)
        imgui.end_child()

REPLACE with:

        imgui.begin_child("cam_controls", ctrl_w, block_h, border=True)
        # wireless == the active streaming source is the phone (0). In that mode
        # the panel talks to the phone over HTTP instead of cap.set().
        self.cam_controls.draw(self.stream_capture,
                               wireless=(self.active_source == 0))
        imgui.end_child()


That's all. After these edits:
  * PC controls work in wireless mode (they drive the phone).
  * Phone and PC controls are synced in real time (poll + push).
  * The 30 s freeze on fullscreen<->portrait is gone (3 s timeout + reconnect).
  * Auto-exposure / autofocus / focus are removed (they're simply not in the
    new CONTROL_DEFS / TOGGLE_DEFS in camera_controls_panel.py).
  * Exposure is present on BOTH sides and synced.
