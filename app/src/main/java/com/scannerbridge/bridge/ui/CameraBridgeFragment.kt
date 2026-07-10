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
    @Volatile private var lastDiagErrAt = 0L
    @Volatile private var lastDiagMsg = ""
    @Volatile private var lastDiagMsgAt = 0L
    private fun diag(msg: String, err: Boolean = false) {
        val now = System.currentTimeMillis()
        if (err) {
            if (now - lastDiagErrAt < 2000) return
            lastDiagErrAt = now
        } else {
            if (msg == lastDiagMsg && now - lastDiagMsgAt < 2000) return
        }
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

    // Raw USB connection AUSBC opened — kept so the watchdog can close() it
    // to abort a control transfer wedged inside libuvc's infinite timeout.
    @Volatile private var usbConn: android.hardware.usb.UsbDeviceConnection? = null

    private var binding: FragmentCameraBinding? = null

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
                frameW = width
                frameH = height
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
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                expInited = false
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
                direct = UvcDirectControls.from(getCurrentCamera())?.also { d ->
                    d.diagSink = { msg -> diag(msg, true) }
                }
                Log.i(
                    "CameraBridge",
                    "control path: " + if (direct != null)
                        "DIRECT (timeout-safe, lazy init)" else "LIBUVC (direct unavailable)"
                )
                diag("Scanner connected \u2014 controls ready")
                resolveActualPreviewSize()
                frameBridge?.setResolution(frameW, frameH)
                try {
                    addPreviewDataCallBack(previewCallback)
                } catch (_: Throwable) {
                }
                try {
                    binding?.cameraRender?.setAspectRatio(frameW, frameH)
                } catch (_: Throwable) {
                }
                if (DEBUG_EXPOSURE) {
                    dumpExposureSurface()
                }
                callbacks?.onCameraOpened(frameW, frameH)
            }
            ICameraStateCallBack.State.CLOSED -> {
                expInited = false
                direct = null
                usbConn = null
                callbacks?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                expInited = false
                direct = null
                usbConn = null
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
        val delayMs = if (deviceWasReset) 2500L else 800L
        deviceWasReset = false
        try {
            closeCamera()
        } catch (t: Throwable) {
            Log.w("CameraBridge", "ctlRecoverCamera: closeCamera threw", t)
        }
        val tryOpen = Runnable {
            try {
                openCamera(getCameraView())
            } catch (t: Throwable) {
                Log.w("CameraBridge", "ctlRecoverCamera: openCamera threw", t)
            }
        }
        mainH.postDelayed({
            tryOpen.run()
            // Retry once more ONLY if the camera didn't come up (e.g. the
            // reopen raced the post-reset re-enumeration). The usbConn check
            // prevents the double-open storm that used to produce a burst of
            // "Scanner connected" messages after every reset.
            mainH.postDelayed({
                if (usbConn == null) {
                    Log.w("CameraBridge", "ctlRecoverCamera: retrying open after reset")
                    tryOpen.run()
                }
            }, 3000)
        }, delayMs)
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
            directEverOk = true
            directFailStreak = 0
            directFirstUseFails = 0
            return
        }
        if (!directEverOk) {
            if (++directFirstUseFails >= 3) {
                Log.w("CameraBridge", "direct path never answered — falling back to libuvc for this session")
                diag("Direct USB path not answering \u2014 using library path", true)
                direct = null
            }
            return
        }
        val streak = ++directFailStreak
        val now = System.currentTimeMillis()
        if (streak >= 10 && now - lastAutoRecoverAt > 20000) {
            lastAutoRecoverAt = now
            directFailStreak = 0
            Log.w("CameraBridge", "direct control writes failing repeatedly — recovering camera")
            diag("Controls failing repeatedly \u2014 restarting scanner\u2026", true)
            // Recovery includes closeCamera(), which may block briefly —
            // never run it on the main thread.
            Thread({ ctlRecoverCamera() }, "ScannerRecovery-direct").start()
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

    fun ctlSetWbTemp(percent: Int) {
        direct?.let { directResult(it.setWbTempPercent(percent)); return }
        safe { cam ->
            val uvc = getUvcCamera(cam) ?: return@safe
            try {
                try {
                    callUvcMethod(uvc, "setAutoWhiteBalance", false)
                } catch (_: Throwable) {}

                var minVal: Int? = null
                for (methodName in listOf("getWhiteBalanceMin", "getWhiteBalanceTempMin", "getWbTempMin")) {
                    try {
                        val res = callUvcMethod(uvc, methodName) as? Int
                        if (res != null) {
                            minVal = res
                            break
                        }
                    } catch (_: Throwable) {}
                }

                var maxVal: Int? = null
                for (methodName in listOf("getWhiteBalanceMax", "getWhiteBalanceTempMax", "getWbTempMax")) {
                    try {
                        val res = callUvcMethod(uvc, methodName) as? Int
                        if (res != null) {
                            maxVal = res
                            break
                        }
                    } catch (_: Throwable) {}
                }

                val realMin = minVal ?: 2800
                val realMax = maxVal ?: 6500

                if (realMax > realMin) {
                    val scaled = realMin + (percent.coerceIn(0, 100) * (realMax - realMin) / 100)
                    for (methodName in listOf("setWhiteBalance", "setWhiteBalanceTemp", "setWbTemp")) {
                        try {
                            callUvcMethod(uvc, methodName, scaled)
                        } catch (_: Throwable) {}
                    }
                }
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
    }
}
