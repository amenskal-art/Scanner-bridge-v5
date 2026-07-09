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
    }

    var callbacks: Callbacks? = null
    @Volatile var frameBridge: FrameBridge? = null

    // Direct UVC control path (Android controlTransfer with real timeouts).
    // ALL controls go through this; libuvc's synchronized control methods are
    // never called after open, so a camera stall can no longer deadlock the
    // UVCCamera monitor and freeze the app.
    @Volatile private var direct: UvcDirectControls? = null

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
                direct = UvcDirectControls.from(getCurrentCamera())
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
                callbacks?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                expInited = false
                direct = null
                callbacks?.onCameraClosed()
            }
        }
    }

    /**
     * Recovery for a wedged USB control endpoint: closing the device aborts
     * any control transfer stuck in libusb (their timeout is infinite in this
     * libuvc build), then we reopen after a short settle delay.
     */
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
    // All controls prefer the direct timeout-safe path. The old CameraUVC /
    // reflection paths remain ONLY as fallback when direct init failed.

    fun ctlSetBrightness(percent: Int) {
        if (direct?.setPu(UvcDirectControls.PU_BRIGHTNESS, percent) == true) return
        safe { it.setBrightness(percent.coerceIn(0, 100)) }
    }

    fun ctlSetContrast(percent: Int) {
        if (direct?.setPu(UvcDirectControls.PU_CONTRAST, percent) == true) return
        safe { it.setContrast(percent.coerceIn(0, 100)) }
    }

    fun ctlSetGain(percent: Int) {
        if (direct?.setPu(UvcDirectControls.PU_GAIN, percent) == true) return
        safe { it.setGain(percent.coerceIn(0, 100)) }
    }

    fun ctlSetSharpness(percent: Int) {
        if (direct?.setPu(UvcDirectControls.PU_SHARPNESS, percent) == true) return
        safe { it.setSharpness(percent.coerceIn(0, 100)) }
    }

    fun ctlSetSaturation(percent: Int) {
        if (direct?.setPu(UvcDirectControls.PU_SATURATION, percent) == true) return
        safe { it.setSaturation(percent.coerceIn(0, 100)) }
    }

    fun ctlSetAutoWhiteBalance(on: Boolean) {
        if (direct?.setAutoWhiteBalance(on) == true) return
        safe { it.setAutoWhiteBalance(on) }
    }

    fun ctlSetZoom(percent: Int) {
        if (direct?.setZoomPercent(percent) == true) return
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
        if (direct?.setExposurePercent(percent) == true) return
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
        if (direct?.setWbTempPercent(percent) == true) return
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
