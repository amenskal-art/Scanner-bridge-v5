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
            ICameraStateCallBack.State.CLOSED -> callbacks?.onCameraClosed()
            ICameraStateCallBack.State.ERROR -> callbacks?.onCameraClosed()
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
                uvc.javaClass.methods
                    .filter { it.name.contains("exposure", ignoreCase = true) }
                    .distinctBy { it.name + it.parameterTypes.joinToString() }
                    .sortedBy { it.name }
                    .forEach {
                        Log.d(
                            "CameraBridge",
                            "  mUVCCamera candidate: ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}) -> ${it.returnType.simpleName}"
                        )
                    }
            }
            Log.d("CameraBridge", "===== EXPOSURE METHOD DUMP END =====")
        }
    }

    // ---- Scanner controls (UVC) ------------------------------------------

    fun ctlSetBrightness(percent: Int) = safe { it.setBrightness(percent.coerceIn(0, 100)) }
    fun ctlSetContrast(percent: Int) = safe { it.setContrast(percent.coerceIn(0, 100)) }
    fun ctlSetGain(percent: Int) = safe { it.setGain(percent.coerceIn(0, 100)) }
    fun ctlSetSharpness(percent: Int) = safe { it.setSharpness(percent.coerceIn(0, 100)) }
    fun ctlSetSaturation(percent: Int) = safe { it.setSaturation(percent.coerceIn(0, 100)) }
    fun ctlSetAutoWhiteBalance(on: Boolean) = safe { it.setAutoWhiteBalance(on) }

    fun ctlSetZoom(percent: Int) {
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

    /**
     * Tries every plausible real exposure control across BOTH the CameraUVC
     * wrapper and the raw mUVCCamera, logging every attempt, and stops at the
     * first one that succeeds (invoke doesn't throw / method exists).
     *
     * This deliberately does NOT assume any single method name is correct —
     * previous attempts (guessed reflection names, then setGamma) both failed
     * on this device, so we now probe broadly and log everything so the real
     * answer comes from THIS device's logcat, not another guess.
     */
    fun ctlSetExposure(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        var appliedVia: String? = null

        safe { cam ->
            // ---- 1) Try direct wrapper methods on CameraUVC (percentage-style) ----
            for (name in listOf(
                "setExposure", "setExposureVal", "setExposureLevel",
                "setExposureCompensation", "setExposureTime"
            )) {
                try {
                    val m = cam.javaClass.methods.firstOrNull {
                        it.name == name && it.parameterTypes.size == 1 &&
                            (it.parameterTypes[0] == Int::class.java || it.parameterTypes[0] == Integer::class.java)
                    }
                    if (m != null) {
                        m.invoke(cam, clamped)
                        appliedVia = "CameraUVC.$name(percent)"
                        Log.d("CameraBridge", "ctlSetExposure: applied via $appliedVia")
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: CameraUVC.$name threw", e)
                }
            }

            if (appliedVia != null) return@safe

            // ---- 2) Fall back to the raw mUVCCamera, trying mode + value setters ----
            val uvc = getUvcCamera(cam)
            if (uvc == null) {
                Log.w("CameraBridge", "ctlSetExposure: mUVCCamera not reachable via reflection")
                return@safe
            }

            // Put the device in manual exposure mode first if such a method exists.
            // UVC bitmask: 1=manual, 2=auto, 4=shutter priority, 8=aperture priority.
            // Some forks use plain booleans instead — try both shapes.
            for (name in listOf("setExposureMode", "updateExposureMode")) {
                try {
                    val res = callUvcMethod(uvc, name, 1)
                    if (res != null || uvc.javaClass.methods.any { it.name == name }) {
                        Log.d("CameraBridge", "ctlSetExposure: $name(1) invoked (result=$res)")
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: $name(1) threw", e)
                }
            }
            for (name in listOf("setAutoExposure", "setExposureAuto")) {
                try {
                    val res = callUvcMethod(uvc, name, false)
                    if (res != null || uvc.javaClass.methods.any { it.name == name }) {
                        Log.d("CameraBridge", "ctlSetExposure: $name(false) invoked (result=$res)")
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: $name(false) threw", e)
                }
            }

            // Query real hardware min/max if exposed, else use a wide UVC-typical
            // absolute-exposure-time range as a fallback (100us units).
            var minVal: Int? = null
            for (name in listOf("getExposureMin", "getExposureValMin", "getExposureMinVal", "getExposureAbsMin")) {
                try {
                    val res = callUvcMethod(uvc, name) as? Int
                    if (res != null) {
                        minVal = res
                        Log.d("CameraBridge", "ctlSetExposure: $name() = $res")
                        break
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: $name() threw", e)
                }
            }
            var maxVal: Int? = null
            for (name in listOf("getExposureMax", "getExposureValMax", "getExposureMaxVal", "getExposureAbsMax")) {
                try {
                    val res = callUvcMethod(uvc, name) as? Int
                    if (res != null) {
                        maxVal = res
                        Log.d("CameraBridge", "ctlSetExposure: $name() = $res")
                        break
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: $name() threw", e)
                }
            }

            val realMin = minVal ?: 1
            val realMax = maxVal ?: 10000
            val scaled = if (realMax > realMin) {
                realMin + (clamped * (realMax - realMin) / 100)
            } else {
                clamped
            }

            for (name in listOf(
                "setExposureVal", "setExposure", "setExposureLevel",
                "setExposureAbs", "setExposureTime", "setPropExposure"
            )) {
                try {
                    val res = callUvcMethod(uvc, name, scaled)
                    if (res != null) {
                        appliedVia = "mUVCCamera.$name($scaled)"
                        Log.d("CameraBridge", "ctlSetExposure: applied via $appliedVia")
                    } else if (uvc.javaClass.methods.any { it.name == name }) {
                        // method exists but returned null (e.g. void) — still counts as applied
                        appliedVia = "mUVCCamera.$name($scaled) [void]"
                        Log.d("CameraBridge", "ctlSetExposure: applied via $appliedVia")
                    }
                } catch (e: Throwable) {
                    Log.w("CameraBridge", "ctlSetExposure: mUVCCamera.$name($scaled) threw", e)
                }
            }
        }

        if (appliedVia == null) {
            Log.e(
                "CameraBridge",
                "ctlSetExposure($clamped): NO working method found on this device/AUSBC build. " +
                    "Check the 'EXPOSURE METHOD DUMP' logged at camera-open time for the real API " +
                    "surface, then hardcode that exact method name here."
            )
        }
    }

    fun ctlSetWbTemp(percent: Int) {
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
