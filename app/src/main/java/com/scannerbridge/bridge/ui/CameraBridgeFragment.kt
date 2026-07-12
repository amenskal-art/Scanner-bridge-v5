package com.scannerbridge.bridge.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.IAspectRatio
import com.scannerbridge.bridge.databinding.FragmentCameraBinding
import com.scannerbridge.bridge.server.FrameBridge

/**
 * Hosts the AUSBC UVC camera and forwards each raw preview frame (NV21) to a
 * [FrameBridge].
 */
class CameraBridgeFragment : CameraFragment() {

    interface Callbacks {
        fun onCameraOpened(width: Int, height: Int)
        fun onCameraClosed()
        /** Human-readable control diagnostics for the on-screen status line. */
        fun onControlDiag(message: String, isError: Boolean) {}
    }

    // Throttle diag spam: at most one error message per 2 s reaches the UI,
    // and an identical info message repeated within 2 s is dropped (duplicate
    // OPENED events after a USB reset used to print "controls ready" 3-4x).
    // force=true bypasses BOTH throttles — used for control-path state
    // changes, which the round-5 field log proved can land inside the 2 s
    // window of an ordinary write-failure message and vanish, hiding the
    // most important line in the whole log.
    @Volatile private var lastDiagErrAt = 0L
    @Volatile private var lastDiagMsg = ""
    @Volatile private var lastDiagMsgAt = 0L
    private fun diag(msg: String, err: Boolean = false, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            if (err) {
                if (now - lastDiagErrAt < 2000) return
            } else {
                if (msg == lastDiagMsg && now - lastDiagMsgAt < 2000) return
            }
        }
        if (err) lastDiagErrAt = now
        lastDiagMsg = msg
        lastDiagMsgAt = now
        try { callbacks?.onControlDiag(msg, err) } catch (_: Throwable) {}
    }

    var callbacks: Callbacks? = null
    @Volatile var frameBridge: FrameBridge? = null

    // Direct UVC control path (Android controlTransfer with real timeouts).
    // ALL controls go through this; libuvc's synchronized control methods are
    // never called after open, so a camera stall can no longer deadlock the
    // UVCCamera monitor and freeze the app.
    @Volatile private var direct: UvcDirectControls? = null

    /**
     * True while the timeout-safe direct path is handling controls. The
     * MainActivity watchdog uses this: on the direct path a "busy" control
     * write is just transfers timing out safely (a first-use sequence is 3-5
     * transfers x 1.5 s worst case = up to ~7.5 s), NOT a wedged thread — so
     * the 4 s reset threshold must not apply. Only the libuvc path can hang
     * forever.
     */
    fun isDirectControlActive(): Boolean = direct != null

    // Raw USB connection AUSBC opened — kept so the watchdog can close() it
    // to abort a control transfer wedged inside libuvc's infinite timeout.
    @Volatile private var usbConn: android.hardware.usb.UsbDeviceConnection? = null

    private var binding: FragmentCameraBinding? = null

    // ---- Preview fit-to-container --------------------------------------
    // Fixes the "small / squished in-app preview": AUSBC re-adds the render
    // TextureView MATCH_PARENT into the container, and when the container's
    // aspect doesn't match the video's, the GL output can end up stretched
    // to fill the wrong-shaped box (AspectRatioTextureView's own measure
    // pass races the surface creation). We size the view EXACTLY to the
    // largest frameW:frameH rectangle that fits the container, centered —
    // then the view aspect == video aspect and nothing can distort.
    @Volatile private var lastFitW = 0
    @Volatile private var lastFitH = 0

    private fun fitRenderToContainer() {
        val b = binding ?: return
        val cw = b.cameraContainer.width
        val ch = b.cameraContainer.height
        val vw = frameW
        val vh = frameH
        if (cw <= 0 || ch <= 0 || vw <= 0 || vh <= 0) return
        var w = cw
        var h = cw * vh / vw
        if (h > ch) {
            h = ch
            w = ch * vw / vh
        }
        if (w == lastFitW && h == lastFitH) return
        lastFitW = w
        lastFitH = h
        b.cameraRender.layoutParams = android.widget.FrameLayout.LayoutParams(
            w, h, android.view.Gravity.CENTER
        )
    }

    /** Refit the render view to its container; safe from any thread. */
    fun refitPreview() {
        mainH.post {
            lastFitW = 0 // container may have changed size -> force reapply
            fitRenderToContainer()
        }
    }

    // ---- Actual negotiated video size + auto resolution upgrade -----------
    //
    // ROOT CAUSE of the small / 4:3 / black-bars preview (round-8
    // screenshots): AUSBC's getSuitableSize() has a trap — if the exact
    // requested size isn't in the camera's mode list and no same-aspect
    // size matches, it prefers ANY mode with width==640 or height==480
    // (its DEFAULT_PREVIEW_* constants) over the largest available mode.
    // So "request 1920x1080" silently negotiates e.g. 640x480. The
    // negotiated value is written back into mCameraRequest, and the frame
    // callback delivers at that size — so BOTH the phone preview AND the
    // frames streamed to the PC are the small mode. Meanwhile the old
    // resolveActualPreviewSize() guessed from the ADVERTISED size list
    // (which does contain 1920x1080), so the preview box was shaped 16:9
    // while the real frames were 4:3 — that mismatch is exactly the black
    // bars in the screenshots.

    /** frameW/frameH := the size AUSBC actually negotiated (not a guess). */
    private fun readNegotiatedSize() {
        try {
            val cam = getCurrentCamera() ?: return
            val req = readDeclaredField(cam, "mCameraRequest")
            val w = (req?.let { readDeclaredField(it, "previewWidth") }) as? Int ?: 0
            val h = (req?.let { readDeclaredField(it, "previewHeight") }) as? Int ?: 0
            if (w > 0 && h > 0) {
                frameW = w
                frameH = h
                return
            }
        } catch (_: Throwable) {
        }
        resolveActualPreviewSize() // legacy guess, only as fallback
    }

    // One attempt per fragment lifetime: if the camera negotiated less than
    // the best mode it advertises, close/reopen at the best mode.
    @Volatile private var resolutionFixTried = false

    private fun pickBestSize(
        sizes: List<com.jiangdg.ausbc.camera.bean.PreviewSize>
    ): com.jiangdg.ausbc.camera.bean.PreviewSize? {
        if (sizes.isEmpty()) return null
        // 1) exact request
        sizes.firstOrNull { it.width == reqWidth && it.height == reqHeight }
            ?.let { return it }
        val cap = reqWidth * reqHeight
        // 2) largest 16:9 not above the requested pixel budget
        sizes.filter { it.width * 9 == it.height * 16 && it.width * it.height <= cap }
            .maxByOrNull { it.width * it.height }
            ?.let { return it }
        // 3) largest anything within budget, else the smallest available
        return sizes.filter { it.width * it.height <= cap }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    /** One-time dump of every mode the camera advertises (both formats). */
    @Volatile private var modesDiagShown = false
    private fun diagCameraModes() {
        if (modesDiagShown) return
        modesDiagShown = true
        try {
            val cam = getCurrentCamera() ?: return
            val mj = try { cam.getAllPreviewSizes() } catch (_: Throwable) { null }
            val mjTxt = mj?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "${it.width}\u00d7${it.height}" } ?: "none"
            diag("MJPEG modes: $mjTxt", err = false, force = true)
            // The YUYV list too (FRAME_FORMAT_YUYV == 0): if the camera
            // hides its big modes in the other format, this shows it.
            val uvc = (cam as? com.jiangdg.ausbc.camera.CameraUVC)
                ?.let { getUvcCamera(it) } ?: return
            val m = uvc.javaClass.methods.firstOrNull {
                it.name == "getSupportedSizeList" && it.parameterTypes.size == 1
            } ?: return
            val yuv = m.invoke(uvc, 0) as? List<*>
            if (!yuv.isNullOrEmpty()) {
                val t = yuv.joinToString(", ") { s ->
                    val w = s?.let { readDeclaredField(it, "width") } ?: "?"
                    val h = s?.let { readDeclaredField(it, "height") } ?: "?"
                    "$w\u00d7$h"
                }
                diag("YUYV modes: $t", err = false, force = true)
            }
        } catch (_: Throwable) {
        }
    }

    private fun maybeUpgradeResolution() {
        if (resolutionFixTried) return
        val cam = getCurrentCamera() ?: return
        val sizes = try { cam.getAllPreviewSizes() } catch (_: Throwable) { null }
        if (sizes.isNullOrEmpty()) return
        val best = pickBestSize(sizes) ?: return
        if (best.width * best.height <= frameW * frameH) {
            // Tell the field log WHY no upgrade is coming, once.
            resolutionFixTried = true
            diag(
                "${frameW}\u00d7${frameH} is the largest MJPEG mode this camera offers",
                err = false, force = true
            )
            return
        }
        resolutionFixTried = true
        diag(
            "Camera opened at ${frameW}\u00d7${frameH} \u2014 " +
                "switching to ${best.width}\u00d7${best.height}\u2026",
            err = false, force = true
        )
        // updateResolution() closes + reopens the camera itself; give the
        // OPENED handling a beat to finish first. Called via reflection so
        // the build can't break if this AUSBC artifact lacks the method.
        mainH.postDelayed({
            try {
                val c = getCurrentCamera() ?: return@postDelayed
                val m = c.javaClass.methods.firstOrNull {
                    it.name == "updateResolution" && it.parameterTypes.size == 2
                }
                if (m != null) {
                    m.invoke(c, best.width, best.height)
                } else {
                    diag("This engine build can't switch resolution", true)
                }
            } catch (t: Throwable) {
                Log.w("CameraBridge", "updateResolution failed", t)
            }
        }, 400)
    }

    // ---- Stream pause/resume around control writes -------------------------
    //
    // Round-8 field data: this camera NEVER answers EP0 control requests
    // while its isochronous stream is running — a libuvc write sat >15 s in
    // silence, and direct usbfs transfers STALL instantly. No timeout value
    // fixes that; the only reliable way to deliver a control write to this
    // firmware is to quiesce the stream first. stopPreview() halts the
    // isoch engine (EP0 becomes responsive in milliseconds), the queued
    // writes are applied, then startPreview() resumes — a ~0.3-0.8 s frozen
    // frame on the PC instead of a wedged thread + full USB reset.
    //
    // CRITICAL detail from the AUSBC source: UVCCamera.stopPreview() also
    // calls setFrameCallback(null, 0) — it CLEARS the native frame
    // callback. Resume must re-register CameraUVC's private frameCallBack
    // or NV21 frames (and with them the whole PC stream) silently die.
    @Volatile private var streamPaused = false

    // GL-mode resume target, captured at pause time. Round-9 blackout root
    // cause (verified in AUSBC native source, UVCPreview.cpp): native
    // stopPreview() does ANativeWindow_release(mPreviewWindow) and NULLs
    // it, and native startPreview() REFUSES to spawn its streaming thread
    // while the window is NULL. So resume was a silent no-op — no preview
    // thread, no frames: black phone preview AND dead PC stream. The
    // SurfaceTexture AUSBC's GL pipeline consumes lives in
    // RenderManager.mCameraSurfaceTexture; re-attach it BEFORE
    // startPreview() and the whole pipeline comes back.
    @Volatile private var resumeTexture: android.graphics.SurfaceTexture? = null

    /** Halt the isoch stream so EP0 control writes can get through. */
    fun ctlPauseStream(): Boolean {
        if (streamPaused) return true
        return try {
            val cam = getCurrentCamera() as? com.jiangdg.ausbc.camera.CameraUVC
                ?: return false
            val uvc = getUvcCamera(cam) ?: return false
            // Resolve the resume surface FIRST — pausing without a way to
            // resume would strand the stream black (the round-9 bug).
            val rm = readDeclaredField(cam, "mRenderManager")
            val st = rm?.let { readDeclaredField(it, "mCameraSurfaceTexture") }
                as? android.graphics.SurfaceTexture
            if (st == null) {
                Log.w("CameraBridge", "no camera SurfaceTexture; not pausing")
                return false
            }
            resumeTexture = st
            uvc.javaClass.getMethod("stopPreview").invoke(uvc)
            // Let the firmware digest the stream-stop (it's an EP0
            // SET_INTERFACE itself) before the first control write lands —
            // writing into a half-completed stop is a plausible trigger for
            // the round-10 wedge-while-paused.
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
            streamPaused = true
            true
        } catch (t: Throwable) {
            Log.w("CameraBridge", "ctlPauseStream failed", t)
            resumeTexture = null
            false
        }
    }

    /** Re-attach the render surface, restart the stream, restore callback. */
    fun ctlResumeStream() {
        if (!streamPaused) return
        streamPaused = false
        try {
            val cam = getCurrentCamera() as? com.jiangdg.ausbc.camera.CameraUVC
                ?: return
            val uvc = getUvcCamera(cam) ?: return
            // 1. Re-attach the render surface stopPreview() released —
            //    without this startPreview() won't even start its thread.
            resumeTexture?.let { st ->
                val m = uvc.javaClass.methods.firstOrNull {
                    it.name == "setPreviewTexture" && it.parameterTypes.size == 1
                }
                m?.invoke(uvc, st)
            }
            // 2. Restart the isoch stream.
            uvc.javaClass.getMethod("startPreview").invoke(uvc)
            // 3. Put the raw frame callback back (stopPreview cleared it
            //    natively: setFrameCallback(null, 0)). Without this the PC
            //    stream never resumes. 4 == UVCCamera.PIXEL_FORMAT_YUV420SP.
            try {
                val cb = readDeclaredField(cam, "frameCallBack")
                if (cb != null) {
                    val m = uvc.javaClass.methods.firstOrNull {
                        it.name == "setFrameCallback" && it.parameterTypes.size == 2
                    }
                    m?.invoke(uvc, cb, 4)
                }
            } catch (t: Throwable) {
                Log.w("CameraBridge", "frame callback re-register failed", t)
            }
        } catch (t: Throwable) {
            Log.w("CameraBridge", "ctlResumeStream failed", t)
        } finally {
            resumeTexture = null
        }
    }

    private val reqWidth = 1920
    private val reqHeight = 1080

    @Volatile private var frameW = reqWidth
    @Volatile private var frameH = reqHeight

    // Leave true until exposure is confirmed working. It only dumps once per
    // camera-open and is harmless in production, but you can flip to false
    // once ctlSetExposure is confirmed working.
    private val DEBUG_EXPOSURE = true

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        val b = FragmentCameraBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun getCameraView(): IAspectRatio? = binding?.cameraRender

    override fun getCameraViewContainer(): ViewGroup? = binding?.cameraContainer

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(reqWidth)
            .setPreviewHeight(reqHeight)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(true)
            .create()
    }

    private val previewCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(
            data: ByteArray?,
            width: Int,
            height: Int,
            format: IPreviewDataCallBack.DataFormat
        ) {
            if (data == null || data.isEmpty()) return

            val w = if (width > 0) width else frameW
            val h = if (height > 0) height else frameH
            if (width > 0 && height > 0) {
                if (width != frameW || height != frameH) {
                    frameW = width
                    frameH = height
                    refitPreview() // real stream size differs from requested
                }
            }

            val isNv21 = (format == IPreviewDataCallBack.DataFormat.NV21)
            frameBridge?.let {
                it.setResolution(w, h)
                if (isNv21) {
                    it.onFrame(data, w, h)
                } else {
                    it.onFrameRgba(data, w, h)
                }
            }
        }
    }

    override fun initView() {
        super.initView()
        addPreviewDataCallBack(previewCallback)
        lastFitW = 0
        lastFitH = 0
        // Refit whenever the container changes size (fullscreen toggle,
        // rotation, portrait host resize).
        binding?.cameraContainer?.addOnLayoutChangeListener {
                _, l, t, r, btm, ol, ot, orr, ob ->
            if ((r - l) != (orr - ol) || (btm - t) != (ob - ot)) {
                mainH.post { fitRenderToContainer() }
            }
        }
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                expInited = false
                wbLibInited = false
                wbLibSetter = null
                directFailStreak = 0
                directFirstUseFails = 0
                directEverOk = false
                usbConn = UvcDirectControls.connectionFrom(getCurrentCamera())
                // Build the timeout-safe direct path WITHOUT touching EP0.
                // Construction only parses the kernel-cached USB descriptors
                // (getRawDescriptors is a usbfs ioctl — zero bus traffic) and
                // probe() is intentionally NOT called: field test showed that
                // sending raw control transfers while libusb is negotiating
                // stream startup wedges this camera's firmware for the whole
                // session. The first real transfer on this path happens only
                // when a control is actually changed, and MainActivity gates
                // all control writes behind a quiet period after open.
                //
                // Every write on this path has a REAL 1.5 s timeout, so a
                // stalled request returns -1 instead of wedging a thread
                // inside libuvc's infinite-timeout JNI while holding the
                // UVCCamera monitor. The watchdog + USB reset remain as a
                // last resort, no longer the primary recovery.
                val devKey = currentDeviceKey()
                if (isDirectBlocked(devKey)) {
                    // This camera already proved it rejects app-level control
                    // transfers (persisted per vid:pid). Skip the direct
                    // attempt entirely — library path from the first write,
                    // no failed-write noise at the start of the session.
                    direct = null
                    Log.i("CameraBridge",
                        "control path: LIBUVC (direct blocked for $devKey)")
                    diag("Scanner connected \u2014 controls ready (library mode)")
                } else {
                    direct = UvcDirectControls.from(getCurrentCamera())?.also { d ->
                        d.diagSink = { msg -> diag(msg, true) }
                    }
                    Log.i(
                        "CameraBridge",
                        "control path: " + if (direct != null)
                            "DIRECT (timeout-safe, lazy init)" else "LIBUVC (direct unavailable)"
                    )
                    diag("Scanner connected \u2014 controls ready")
                }
                streamPaused = false
                readNegotiatedSize()
                diag("Video: ${frameW}\u00d7${frameH}", err = false, force = true)
                diagCameraModes()
                maybeUpgradeResolution()
                frameBridge?.setResolution(frameW, frameH)
                try {
                    addPreviewDataCallBack(previewCallback)
                } catch (_: Throwable) {
                }
                try {
                    binding?.cameraRender?.setAspectRatio(frameW, frameH)
                } catch (_: Throwable) {
                }
                refitPreview()
                if (DEBUG_EXPOSURE) {
                    dumpExposureSurface()
                }
                callbacks?.onCameraOpened(frameW, frameH)
            }
            ICameraStateCallBack.State.CLOSED -> {
                expInited = false
                wbLibInited = false
                wbLibSetter = null
                direct = null
                usbConn = null
                streamPaused = false
                callbacks?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                expInited = false
                wbLibInited = false
                wbLibSetter = null
                direct = null
                usbConn = null
                streamPaused = false
                callbacks?.onCameraClosed()
            }
        }
    }

    /**
     * Recovery for a wedged USB control endpoint: closing the device aborts
     * any control transfer stuck in libusb (their timeout is infinite in this
     * libuvc build), then we reopen after a short settle delay.
     */
    /**
     * Nuclear unblock for a control transfer wedged inside libuvc (infinite
     * timeout, holding the UVCCamera monitor). Closing the raw device fd is
     * the ONLY thing that aborts that transfer — closeCamera() can't, because
     * it needs the very monitor the wedged thread holds. After this, the
     * monitor is released, close/reopen can actually run.
     */
    /**
     * TIER-1 abort: kill the wedged control transfer WITHOUT a USB port
     * reset. mCtrlBlock is AUSBC's UsbControlBlock — closing it closes the
     * UsbDeviceConnection libusb is running on, so the kernel fails the
     * in-flight URB, libuvc's infinite-timeout call returns with an error,
     * and the UVCCamera monitor is released. The device stays ENUMERATED:
     * recovery is a plain closeCamera()/openCamera(), seconds — not the
     * re-enumeration cascade a port reset triggers (3 minutes of downtime
     * in the round-10 field log).
     */
    fun ctlSoftAbortStuckControl() {
        try {
            val cam = getCurrentCamera()
            val cb = cam?.let { readDeclaredField(it, "mCtrlBlock") }
            val m = cb?.javaClass?.methods?.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            }
            m?.invoke(cb)
            Log.w("CameraBridge", "soft abort: camera ctrl block closed")
        } catch (t: Throwable) {
            Log.w("CameraBridge", "soft abort failed", t)
        }
        // Our own direct-path fd too (same device), if it exists.
        try { usbConn?.close() } catch (_: Throwable) {}
        usbConn = null
        direct = null
        deviceWasReset = false
        diag("Stuck control aborted \u2014 restarting camera engine\u2026", true)
    }

    fun ctlAbortStuckControl() {
        val conn = usbConn
        usbConn = null
        direct = null   // it holds this same connection; unusable after close
        // 1) Try a REAL USB device reset (hidden API, works on many devices).
        //    Closing the fd only unblocks OUR thread; the camera's firmware
        //    stays wedged and the next write stalls again. A port reset
        //    power-cycles the device logic — the only true recovery short of
        //    replugging the cable.
        var didReset = false
        try {
            val m = conn?.javaClass?.getMethod("resetDevice")
            didReset = (m?.invoke(conn) as? Boolean) == true
            deviceWasReset = didReset
            Log.w("CameraBridge", "ctlAbortStuckControl: resetDevice() -> $didReset")
        } catch (t: Throwable) {
            Log.w("CameraBridge", "resetDevice unavailable: $t")
        }
        // 2) Close the fd regardless — aborts the transfer stuck inside
        //    libuvc's infinite timeout and releases the UVCCamera monitor.
        try { conn?.close() } catch (_: Throwable) {}
        diag(
            if (didReset) "Scanner USB reset \u2014 restarting\u2026"
            else "Stuck USB control aborted \u2014 restarting scanner\u2026 " +
                 "(if controls stay dead, replug the scanner)",
            true
        )
    }

    // Set when ctlAbortStuckControl performed a real USB port reset. The
    // device re-enumerates after that (detach + attach), so the reopen must
    // wait longer, and a second attempt is scheduled in case the first one
    // fires while the device is still enumerating.
    @Volatile private var deviceWasReset = false

    // Main-looper handler for the parts of recovery that must touch views.
    private val mainH = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Safe to call from ANY thread — and it SHOULD be called from a
     * background thread. closeCamera() has to acquire the UVCCamera monitor;
     * if a wedged control transfer hasn't fully released it yet, that call
     * blocks. Running it on the caller's (background) thread means a slow
     * release stalls a worker, not the main thread — this was the cause of
     * the "app freezes but keeps streaming" ANRs during recovery.
     */
    fun ctlRecoverCamera() {
        expInited = false
        wbLibInited = false
        wbLibSetter = null
        val wasReset = deviceWasReset
        deviceWasReset = false
        try {
            closeCamera()
        } catch (t: Throwable) {
            Log.w("CameraBridge", "ctlRecoverCamera: closeCamera threw", t)
        }
        val tryOpenIfNeeded = Runnable {
            // Only open if the camera hasn't already come back. After a real
            // USB reset the device re-enumerates and AUSBC's attach handling
            // reopens it BY ITSELF — our unconditional reopen plus the retry
            // used to race that, producing 3 opens (and 3 "Scanner connected"
            // lines, and 3 scheduled control bursts) per reset.
            if (usbConn != null) return@Runnable
            try {
                openCamera(getCameraView())
            } catch (t: Throwable) {
                Log.w("CameraBridge", "ctlRecoverCamera: openCamera threw", t)
            }
        }
        if (wasReset) {
            // Give the re-enumeration + AUSBC's own attach-open 4 s to land;
            // step in only if it didn't, with one further retry.
            mainH.postDelayed({
                tryOpenIfNeeded.run()
                mainH.postDelayed({ tryOpenIfNeeded.run() }, 3000)
            }, 4000)
        } else {
            // fd-close only (no re-enumeration): nothing reopens on its own.
            mainH.postDelayed({
                tryOpenIfNeeded.run()
                mainH.postDelayed({ tryOpenIfNeeded.run() }, 3000)
            }, 800)
        }
    }

    private fun resolveActualPreviewSize() {
        try {
            val cam = getCurrentCamera()
            val sizes = cam?.getAllPreviewSizes()
            if (!sizes.isNullOrEmpty()) {
                val match = sizes.firstOrNull {
                    it.width == reqWidth && it.height == reqHeight
                } ?: sizes.maxByOrNull { it.width * it.height }
                if (match != null) {
                    frameW = match.width
                    frameH = match.height
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ---- Reflection helpers to access raw UVCCamera methods ----------------

    private fun getUvcCamera(cam: com.jiangdg.ausbc.camera.CameraUVC): Any? {
        val direct = try {
            val field = cam.javaClass.getDeclaredField("mUVCCamera")
            field.isAccessible = true
            field.get(cam)
        } catch (e: Throwable) {
            null
        }
        if (direct != null) return direct

        return try {
            val field = cam.javaClass.declaredFields.firstOrNull {
                it.type.name.contains("UVCCamera")
            }
            if (field != null) {
                field.isAccessible = true
                field.get(cam)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun callUvcMethod(uvcCamera: Any, methodName: String, vararg args: Any?): Any? {
        val method = uvcCamera.javaClass.getMethods().firstOrNull { m ->
            m.name == methodName && m.parameterTypes.size == args.size
        } ?: return null

        return method.invoke(uvcCamera, *args)
    }

    /**
     * ONE-TIME DIAGNOSTIC. Logs, for BOTH the CameraUVC wrapper and the raw
     * mUVCCamera object:
     *   - every public method whose name contains "exposure" (any case),
     *     with its parameter types, so we can see the REAL API instead of
     *     guessing names.
     * Filter Logcat for tag "CameraBridge" after opening the camera.
     */
    private fun dumpExposureSurface() {
        safe { cam ->
            Log.d("CameraBridge", "===== EXPOSURE METHOD DUMP START =====")
            Log.d("CameraBridge", "CameraUVC class: ${cam.javaClass.name}")
            cam.javaClass.methods
                .filter { it.name.contains("exposure", ignoreCase = true) }
                .distinctBy { it.name + it.parameterTypes.joinToString() }
                .sortedBy { it.name }
                .forEach {
                    Log.d(
                        "CameraBridge",
                        "  CameraUVC candidate: ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}) -> ${it.returnType.simpleName}"
                    )
                }

            val uvc = getUvcCamera(cam)
            if (uvc == null) {
                Log.w("CameraBridge", "  mUVCCamera field: NOT FOUND (reflection failed)")
            } else {
                Log.d("CameraBridge", "  mUVCCamera class: ${uvc.javaClass.name}")
                // NOTE: use declaredMethods, not methods — the exposure API in
                // this AUSBC build is PRIVATE native, invisible to .methods.
                var c: Class<*>? = uvc.javaClass
                while (c != null && c != Any::class.java) {
                    c.declaredMethods
                        .filter { it.name.contains("exposure", ignoreCase = true) }
                        .sortedBy { it.name }
                        .forEach {
                            Log.d(
                                "CameraBridge",
                                "  mUVCCamera candidate [${c?.simpleName}]: ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}) -> ${it.returnType.simpleName}"
                            )
                        }
                    c = c.superclass
                }
                val ptr = readDeclaredField(uvc, "mNativePtr")
                val ctrl = readDeclaredField(uvc, "mControlSupports")
                Log.d("CameraBridge", "  mNativePtr=$ptr mControlSupports=$ctrl (bit 0x02=AE mode, 0x08=exposure-abs)")
            }
            Log.d("CameraBridge", "===== EXPOSURE METHOD DUMP END =====")
        }
    }

    // ---- Scanner controls (UVC) ------------------------------------------
    //
    // RULE: once the direct timeout-safe path initialized for this camera
    // session, it is the ONLY control path. If a direct write fails we DROP
    // the write (and count it) — we NEVER fall back to libuvc's synchronized
    // control methods. Their CTRL_TIMEOUT is 0 (= infinite in libusb), so one
    // stalled request wedges the calling thread inside JNI while it holds the
    // UVCCamera monitor. From that moment closeCamera()/openCamera() queue up
    // behind the same monitor and controls are dead for good, even though the
    // stream (isochronous endpoint, separate thread) keeps running — which is
    // exactly the "video fine, controls dead after a short freeze" failure.
    //
    // The libuvc paths below survive ONLY for the case where direct init
    // failed at camera-open (direct == null), i.e. we never had a safe path.

    // Consecutive direct-write failures. A stalled EP0 usually recovers only
    // with a device close/reopen, so after a few failures in a row we trigger
    // one automatic recovery instead of silently eating writes forever.
    @Volatile private var directFailStreak = 0
    @Volatile private var lastAutoRecoverAt = 0L
    // First-use validation, replacing the old startup probe(): some devices
    // never answer app-level control transfers while libusb owns the
    // interface. If the FIRST few direct writes all fail (path never worked
    // once), fall back to the libuvc path for the session instead of eating
    // every write. Once a direct write has succeeded, we never fall back —
    // libuvc's infinite timeout is exactly what we're avoiding.
    @Volatile private var directFirstUseFails = 0
    @Volatile private var directEverOk = false

    private fun directResult(ok: Boolean) {
        if (ok) {
            if (!directEverOk) {
                directEverOk = true
                // One-time confirmation, with the camera's ACTUAL answer
                // time — this number tells us how close to the timeout this
                // device runs under load, without needing Logcat.
                val ms = direct?.lastSetMs ?: -1
                diag("Direct controls OK (answered in $ms ms)", err = false, force = true)
            }
            directFailStreak = 0
            directFirstUseFails = 0
            return
        }
        if (!directEverOk) {
            // With the 4 s timeout + retry, one failed SET means the camera
            // didn't answer for ~8.4 s. Five of those in a row (~40 s of
            // silence) is real evidence the app-level path doesn't work on
            // this device — the previous threshold of 3 with a 1.5 s timeout
            // tripped on a merely SLOW camera and dumped us onto libuvc,
            // whose infinite-timeout write then wedged and triggered the
            // reset. This message is forced past the diag throttle: in the
            // round-5 log it was swallowed and the fallback was invisible.
            if (++directFirstUseFails >= 5) {
                Log.w("CameraBridge", "direct path never answered — falling back to libuvc for this session")
                diag("Direct USB path not answering \u2014 switching to library path", err = true, force = true)
                direct = null
                // Remember this device permanently: the failures come back
                // instantly (STALL), not as timeouts — this hardware will
                // never accept the direct path, so future connects go
                // straight to the library path with zero failed writes.
                blockDirect(currentDeviceKey())
            }
            return
        }
        val streak = ++directFailStreak
        val now = System.currentTimeMillis()
        if (streak >= 6 && now - lastAutoRecoverAt > 20000) {
            lastAutoRecoverAt = now
            directFailStreak = 0
            Log.w("CameraBridge", "direct control writes failing repeatedly — resetting camera")
            diag("Controls failing repeatedly \u2014 resetting scanner\u2026", true)
            // 6+ consecutive timeouts means the firmware's control endpoint
            // has wedged. close/reopen alone does NOT unwedge it (field
            // tested) — only a real USB port reset power-cycles the device
            // logic. Run the full abort+reset+recover sequence, off-main.
            Thread({
                try {
                    ctlAbortStuckControl()
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    ctlRecoverCamera()
                } catch (t: Throwable) {
                    Log.w("CameraBridge", "streak recovery threw", t)
                }
            }, "ScannerRecovery-direct").start()
        }
    }

    fun ctlSetBrightness(percent: Int) {
        direct?.let { directResult(it.setPu(UvcDirectControls.PU_BRIGHTNESS, percent)); return }
        safe { it.setBrightness(percent.coerceIn(0, 100)) }
    }

    fun ctlSetContrast(percent: Int) {
        direct?.let { directResult(it.setPu(UvcDirectControls.PU_CONTRAST, percent)); return }
        safe { it.setContrast(percent.coerceIn(0, 100)) }
    }

    fun ctlSetGain(percent: Int) {
        direct?.let { directResult(it.setPu(UvcDirectControls.PU_GAIN, percent)); return }
        safe { it.setGain(percent.coerceIn(0, 100)) }
    }

    fun ctlSetSharpness(percent: Int) {
        direct?.let { directResult(it.setPu(UvcDirectControls.PU_SHARPNESS, percent)); return }
        safe { it.setSharpness(percent.coerceIn(0, 100)) }
    }

    fun ctlSetSaturation(percent: Int) {
        direct?.let { directResult(it.setPu(UvcDirectControls.PU_SATURATION, percent)); return }
        safe { it.setSaturation(percent.coerceIn(0, 100)) }
    }

    fun ctlSetAutoWhiteBalance(on: Boolean) {
        direct?.let { directResult(it.setAutoWhiteBalance(on)); return }
        safe { it.setAutoWhiteBalance(on) }
    }

    fun ctlSetZoom(percent: Int) {
        direct?.let { directResult(it.setZoomPercent(percent)); return }
        var applied = false
        safe { cam ->
            val uvc = getUvcCamera(cam)
            if (uvc != null) {
                try {
                    val minVal = (callUvcMethod(uvc, "getZoomMin") as? Int) ?: 0
                    val maxVal = (callUvcMethod(uvc, "getZoomMax") as? Int) ?: 100
                    if (maxVal > minVal) {
                        val scaled = minVal + (percent.coerceIn(0, 100) * (maxVal - minVal) / 100)
                        callUvcMethod(uvc, "setZoom", scaled)
                        applied = true
                    }
                } catch (_: Throwable) {}
            }
            if (!applied) {
                try {
                    cam.setZoom(percent.coerceIn(0, 100))
                } catch (_: Throwable) {}
            }
        }
    }

    // ---- Private reflection helpers ---------------------------------------
    //
    // WHY: In the AUSBC version this app compiles against, neither CameraUVC
    // nor com.jiangdg.uvc.UVCCamera exposes ANY *public* exposure method.
    // The exposure API only exists as PRIVATE native methods:
    //     private static native int nativeSetExposureMode(long id, int mode)
    //     private        native int nativeUpdateExposureLimit(long id)
    //     private static native int nativeSetExposure(long id, int exposure)
    // Probing with javaClass.methods (public-only) therefore finds nothing,
    // which is exactly why the old ctlSetExposure logged "NO working method
    // found". We call the natives directly using mNativePtr instead.

    private fun findDeclaredMethod(
        cls: Class<*>, name: String, vararg params: Class<*>
    ): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *params)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
            }
            c = c.superclass
        }
        return null
    }

    private fun readDeclaredField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    /**
     * Sets manual absolute exposure by invoking UVCCamera's private native
     * methods directly (there is no public exposure API in this AUSBC build).
     *
     * Sequence (matters!):
     *  1. nativeSetExposureMode(ptr, 1)  -> UVC AE mode: 1=manual, 2=auto,
     *     4=shutter priority, 8=aperture priority. Most webcams reject
     *     exposure-time writes unless the mode is manual first.
     *  2. nativeUpdateExposureLimit(ptr) -> fills mExposureMin/Max/Def.
     *     (Known upstream bug: it checks the wrong support bitmask, so it can
     *     fail on some cameras — we fall back to a sane 100µs-unit range.)
     *  3. nativeSetExposure(ptr, absValue) -> uvc_set_exposure_abs.
     *
     * Native calls return 0 on success, negative libuvc error codes on failure.
     */
    // Exposure init cache — mode + limits are written/queried ONCE per
    // camera-open. Re-sending them on every slider tick multiplied USB
    // control traffic ~6x and helped wedge cheap webcam firmware.
    @Volatile private var expInited = false
    @Volatile private var expMin = 1
    @Volatile private var expMax = 5000

    fun ctlSetExposure(percent: Int) {
        direct?.let { directResult(it.setExposurePercent(percent)); return }
        val clamped = percent.coerceIn(0, 100)
        safe { cam ->
            val uvc = getUvcCamera(cam)
            if (uvc == null) {
                Log.w("CameraBridge", "ctlSetExposure: mUVCCamera not reachable via reflection")
                return@safe
            }

            val ptr = (readDeclaredField(uvc, "mNativePtr") as? Long) ?: 0L
            if (ptr == 0L) {
                Log.w("CameraBridge", "ctlSetExposure: mNativePtr is 0 (camera not connected?)")
                return@safe
            }

            val cls = uvc.javaClass
            val longT = Long::class.javaPrimitiveType!!
            val intT = Int::class.javaPrimitiveType!!

            if (!expInited) {
                // 1) Force manual AE mode, once.
                val setMode = findDeclaredMethod(cls, "nativeSetExposureMode", longT, intT)
                val modeRes = try {
                    setMode?.invoke(uvc, ptr, 1) as? Int
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: nativeSetExposureMode threw", e)
                    null
                }

                // 2) Query the camera's real exposure-time range, once.
                try {
                    findDeclaredMethod(cls, "nativeUpdateExposureLimit", longT)?.invoke(uvc, ptr)
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: nativeUpdateExposureLimit threw", e)
                }
                var minV = (readDeclaredField(uvc, "mExposureMin") as? Int) ?: 0
                var maxV = (readDeclaredField(uvc, "mExposureMax") as? Int) ?: 0
                if (maxV <= minV) {
                    // Fallback: UVC exposure-time-absolute is in 100 µs units.
                    minV = 1
                    maxV = 5000
                }
                expMin = minV
                expMax = maxV
                expInited = true
                Log.d("CameraBridge", "ctlSetExposure init: mode(1)=$modeRes range=[$minV..$maxV]")
            }

            val scaled = expMin + clamped * (expMax - expMin) / 100

            // 3) Write absolute exposure — single control transfer per tick.
            val setExp = findDeclaredMethod(cls, "nativeSetExposure", longT, intT)
            if (setExp == null) {
                Log.e("CameraBridge", "ctlSetExposure: nativeSetExposure not found on ${cls.name}")
                return@safe
            }
            val res = try {
                setExp.invoke(uvc, ptr, scaled) as? Int
            } catch (e: Throwable) {
                Log.e("CameraBridge", "ctlSetExposure: nativeSetExposure threw", e)
                null
            }
            Log.d(
                "CameraBridge",
                "ctlSetExposure($clamped%): value=$scaled result=$res (0=OK, negative=libuvc error)"
            )
        }
    }

    // libuvc wb_temp session cache. The old code did auto-WB-off + min/max
    // queries + ALL THREE setter-name attempts on EVERY slider tick — a burst
    // of infinite-timeout libuvc transfers per tick, which is exactly the
    // load profile that wedges this camera on the library path. Now each of
    // those happens ONCE per camera open, and a drag tick costs exactly one
    // transfer through one resolved setter.
    @Volatile private var wbLibInited = false
    @Volatile private var wbLibMin = 2800
    @Volatile private var wbLibMax = 6500
    @Volatile private var wbLibSetter: java.lang.reflect.Method? = null

    fun ctlSetWbTemp(percent: Int) {
        direct?.let { directResult(it.setWbTempPercent(percent)); return }
        safe { cam ->
            val uvc = getUvcCamera(cam) ?: return@safe
            try {
                if (!wbLibInited) {
                    try {
                        callUvcMethod(uvc, "setAutoWhiteBalance", false)
                    } catch (_: Throwable) {}

                    for (methodName in listOf("getWhiteBalanceMin", "getWhiteBalanceTempMin", "getWbTempMin")) {
                        val res = try { callUvcMethod(uvc, methodName) as? Int } catch (_: Throwable) { null }
                        if (res != null) { wbLibMin = res; break }
                    }
                    for (methodName in listOf("getWhiteBalanceMax", "getWhiteBalanceTempMax", "getWbTempMax")) {
                        val res = try { callUvcMethod(uvc, methodName) as? Int } catch (_: Throwable) { null }
                        if (res != null) { wbLibMax = res; break }
                    }
                    // Resolve the setter METHOD once; a tick then invokes
                    // exactly one method instead of probing three names.
                    wbLibSetter = listOf("setWhiteBalance", "setWhiteBalanceTemp", "setWbTemp")
                        .firstNotNullOfOrNull { name ->
                            uvc.javaClass.methods.firstOrNull {
                                it.name == name && it.parameterTypes.size == 1
                            }
                        }
                    if (wbLibMax <= wbLibMin) { wbLibMin = 2800; wbLibMax = 6500 }
                    wbLibInited = true
                }

                val scaled = wbLibMin + (percent.coerceIn(0, 100) * (wbLibMax - wbLibMin) / 100)
                try {
                    wbLibSetter?.invoke(uvc, scaled)
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }

    private inline fun safe(block: (com.jiangdg.ausbc.camera.CameraUVC) -> Unit) {
        try {
            val cam = getCurrentCamera()
            if (cam is com.jiangdg.ausbc.camera.CameraUVC) block(cam)
        } catch (_: Throwable) { }
    }

    companion object {
        fun newInstance() = CameraBridgeFragment()

        // Devices (vid:pid) where the direct app-level control path proved
        // non-functional — they STALL every UsbDeviceConnection transfer
        // while libusb owns the interface (instant r=-1, not timeouts).
        // Once detected, the device goes on this list (in-memory + persisted)
        // and every future open goes STRAIGHT to the library path, instead
        // of burning 15-30 s of failed writes rediscovering it per connect.
        private val directBlockedThisRun:
            MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
        private const val PREFS = "scanner_ctrl"
        private const val PREF_BLOCKED = "direct_blocked_devices"
    }

    private fun currentDeviceKey(): String {
        return try {
            val cam = getCurrentCamera() ?: return "unknown"
            val ctrl = readDeclaredField(cam, "mCtrlBlock") ?: return "unknown"
            val dev = try {
                ctrl.javaClass.getMethod("getDevice").invoke(ctrl)
                        as? android.hardware.usb.UsbDevice
            } catch (_: Throwable) { null } ?: return "unknown"
            "${dev.vendorId}:${dev.productId}"
        } catch (_: Throwable) { "unknown" }
    }

    private fun isDirectBlocked(key: String): Boolean {
        if (key in directBlockedThisRun) return true
        return try {
            val prefs = context?.getSharedPreferences(
                PREFS, android.content.Context.MODE_PRIVATE) ?: return false
            prefs.getStringSet(PREF_BLOCKED, emptySet())?.contains(key) == true
        } catch (_: Throwable) { false }
    }

    private fun blockDirect(key: String) {
        directBlockedThisRun.add(key)
        try {
            val prefs = context?.getSharedPreferences(
                PREFS, android.content.Context.MODE_PRIVATE) ?: return
            val cur = HashSet(prefs.getStringSet(PREF_BLOCKED, emptySet()) ?: emptySet())
            cur.add(key)
            prefs.edit().putStringSet(PREF_BLOCKED, cur).apply()
        } catch (_: Throwable) {}
    }
}
