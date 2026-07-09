package com.scannerbridge.bridge.ui

import android.os.Handler
import android.os.Looper
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

    // Throttle diag spam: at most one error message per 2 s reaches the UI.
    @Volatile private var lastDiagErrAt = 0L
    private fun diag(msg: String, err: Boolean = false) {
        if (err) {
            val now = System.currentTimeMillis()
            if (now - lastDiagErrAt < 2000) return
            lastDiagErrAt = now
        }
        try { callbacks?.onControlDiag(msg, err) } catch (_: Throwable) {}
    }

    var callbacks: Callbacks? = null
    @Volatile var frameBridge: FrameBridge? = null

    // Direct UVC control path (Android controlTransfer with real timeouts).
    @Volatile private var direct: UvcDirectControls? = null

    // Raw USB connection AUSBC opened
    @Volatile private var usbConn: android.hardware.usb.UsbDeviceConnection? = null

    private var binding: FragmentCameraBinding? = null

    private val reqWidth = 1920
    private val reqHeight = 1080

    @Volatile private var frameW = reqWidth
    @Volatile private var frameH = reqHeight

    private val DEBUG_EXPOSURE = true

    private val mainHandler = Handler(Looper.getMainLooper())

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
                usbConn = UvcDirectControls.connectionFrom(getCurrentCamera())
                
                // Initialize direct controls after a delay to avoid interfering 
                // with the camera's stream negotiation on EP0.
                direct = null
                diag("Scanner connected \u2014 starting controls\u2026")
                initDirectControlsWithDelay()

                resolveActualPreviewSize()
                frameBridge?.setResolution(frameW, frameH)
                try {
                    addPreviewDataCallBack(previewCallback)
                } catch (_: Throwable) {}
                try {
                    binding?.cameraRender?.setAspectRatio(frameW, frameH)
                } catch (_: Throwable) {}
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

    private fun initDirectControlsWithDelay() {
        val delayMs = 1200L
        mainHandler.postDelayed({
            if (isDetached || context == null || usbConn == null) {
                diag("Scanner control initialization skipped")
                return@postDelayed
            }
            val cam = getCurrentCamera()
            if (cam == null) {
                diag("Scanner control initialization skipped: camera closed")
                return@postDelayed
            }
            try {
                val d = UvcDirectControls.from(cam)
                if (d != null) {
                    d.diagSink = { msg -> diag(msg, false) }
                    if (d.probe()) {
                        direct = d
                        diag("Scanner connected \u2014 controls ready (Direct Path)")
                        Log.i("CameraBridge", "Control path successfully initialized: DIRECT")
                        return@postDelayed
                    }
                }
            } catch (t: Throwable) {
                Log.e("CameraBridge", "Failed to init direct controls", t)
            }
            
            // If direct path fails standard or custom probes, we fallback gracefully to native libuvc
            diag("Scanner connected \u2014 controls ready (Fallback Path)")
            Log.w("CameraBridge", "Direct control path failed probe. Falling back to LIBUVC.")
        }, delayMs)
    }

    fun ctlAbortStuckControl() {
        val conn = usbConn
        usbConn = null
        var didReset = false
        try {
            val m = conn?.javaClass?.getMethod("resetDevice")
            didReset = (m?.invoke(conn) as? Boolean) == true
            Log.w("CameraBridge", "ctlAbortStuckControl: resetDevice() -> $didReset")
        } catch (t: Throwable) {
            Log.w("CameraBridge", "resetDevice unavailable: $t")
        }
        try { conn?.close() } catch (_: Throwable) {}
        diag(
            if (didReset) "Scanner USB reset \u2014 restarting\u2026"
            else "Stuck USB control aborted \u2014 restarting scanner\u2026 " +
                 "(if controls stay dead, replug the scanner)",
            true
        )
    }

    fun ctlRecoverCamera() {
        expInited = false
        try {
            closeCamera()
        } catch (t: Throwable) {
            Log.w("CameraBridge", "ctlRecoverCamera: closeCamera threw", t)
        }
        view?.postDelayed({
            try {
                openCamera(getCameraView())
            } catch (t: Throwable) {
                Log.w("CameraBridge", "ctlRecoverCamera: openCamera threw", t)
            }
        }, 800)
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
        } catch (_: Throwable) {}
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
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
            val field = cam.javaClass.getDeclaredFields.firstOrNull {
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

    @Volatile private var directFailStreak = 0
    @Volatile private var lastAutoRecoverAt = 0L

    private fun directResult(ok: Boolean) {
        if (ok) {
            directFailStreak = 0
            return
        }
        val streak = ++directFailStreak
        val now = System.currentTimeMillis()
        if (streak >= 10 && now - lastAutoRecoverAt > 20000) {
            lastAutoRecoverAt = now
            directFailStreak = 0
            Log.w("CameraBridge", "direct control writes failing repeatedly — recovering camera")
            diag("Controls failing repeatedly \u2014 restarting scanner\u2026", true)
            view?.post { ctlRecoverCamera() }
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

    private fun findDeclaredMethod(
        cls: Class<*>, name: String, vararg params: Class<*>
    ): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name, *params)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {}
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
            } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        return null
    }

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
                val setMode = findDeclaredMethod(cls, "nativeSetExposureMode", longT, intT)
                val modeRes = try {
                    setMode?.invoke(uvc, ptr, 1) as? Int
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: nativeSetExposureMode threw", e)
                    null
                }

                try {
                    findDeclaredMethod(cls, "nativeUpdateExposureLimit", longT)?.invoke(uvc, ptr)
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: nativeUpdateExposureLimit threw", e)
                }
                var minV = (readDeclaredField(uvc, "mExposureMin") as? Int) ?: 0
                var maxV = (readDeclaredField(uvc, "mExposureMax") as? Int) ?: 0
                if (maxV <= minV) {
                    minV = 1
                    maxV = 5000
                }
                expMin = minV
                expMax = maxV
                expInited = true
                Log.d("CameraBridge", "ctlSetExposure init: mode(1)=$modeRes range=[$minV..$maxV]")
            }

            val scaled = expMin + clamped * (expMax - expMin) / 100

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
        } catch (_: Throwable) {}
    }

    companion object {
        fun newInstance() = CameraBridgeFragment()
    }
}
