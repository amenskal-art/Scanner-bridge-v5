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

    // Direct UVC control path – this is the ONLY path used. If it fails,
    // controls are simply skipped (no unsafe fallback).
    @Volatile private var direct: UvcDirectControls? = null
    @Volatile private var directControlsAvailable = false

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
                // Try to establish direct UVC control path; this is the only safe one.
                val ctrl = UvcDirectControls.from(getCurrentCamera())
                direct = ctrl
                directControlsAvailable = ctrl != null
                if (!directControlsAvailable) {
                    Log.w("CameraBridge", "Direct UVC controls unavailable – camera controls disabled")
                }
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
                directControlsAvailable = false
                callbacks?.onCameraClosed()
            }
            ICameraStateCallBack.State.ERROR -> {
                expInited = false
                direct = null
                directControlsAvailable = false
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

    // ---- Reflection helpers to access raw UVCCamera methods (only for exposure) ----

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

    // ---- Scanner controls (UVC) ----
    // All controls use ONLY the direct timeout-safe path. No fallback to CameraUVC.

    fun ctlSetBrightness(percent: Int) {
        if (directControlsAvailable) {
            direct?.setPu(UvcDirectControls.PU_BRIGHTNESS, percent)
        } else {
            Log.w("CameraBridge", "ctlSetBrightness skipped – direct controls unavailable")
        }
    }

    fun ctlSetContrast(percent: Int) {
        if (directControlsAvailable) {
            direct?.setPu(UvcDirectControls.PU_CONTRAST, percent)
        } else {
            Log.w("CameraBridge", "ctlSetContrast skipped – direct controls unavailable")
        }
    }

    fun ctlSetGain(percent: Int) {
        if (directControlsAvailable) {
            direct?.setPu(UvcDirectControls.PU_GAIN, percent)
        } else {
            Log.w("CameraBridge", "ctlSetGain skipped – direct controls unavailable")
        }
    }

    fun ctlSetSharpness(percent: Int) {
        if (directControlsAvailable) {
            direct?.setPu(UvcDirectControls.PU_SHARPNESS, percent)
        } else {
            Log.w("CameraBridge", "ctlSetSharpness skipped – direct controls unavailable")
        }
    }

    fun ctlSetSaturation(percent: Int) {
        if (directControlsAvailable) {
            direct?.setPu(UvcDirectControls.PU_SATURATION, percent)
        } else {
            Log.w("CameraBridge", "ctlSetSaturation skipped – direct controls unavailable")
        }
    }

    fun ctlSetAutoWhiteBalance(on: Boolean) {
        if (directControlsAvailable) {
            direct?.setAutoWhiteBalance(on)
        } else {
            Log.w("CameraBridge", "ctlSetAutoWhiteBalance skipped – direct controls unavailable")
        }
    }

    fun ctlSetZoom(percent: Int) {
        if (directControlsAvailable) {
            direct?.setZoomPercent(percent)
        } else {
            Log.w("CameraBridge", "ctlSetZoom skipped – direct controls unavailable")
        }
    }

    // ---- Private reflection helpers for exposure ----
    // The exposure API is only accessible via private native methods; we keep the
    // reflection fallback for exposure because UvcDirectControls has its own
    // setExposurePercent that should work on most cameras, but we keep the old
    // reflection as a secondary path if direct fails.

    @Volatile private var expInited = false
    @Volatile private var expMin = 1
    @Volatile private var expMax = 5000

    fun ctlSetExposure(percent: Int) {
        // First try the direct path – it uses UVC SET_CUR with timeout.
        if (directControlsAvailable && direct?.setExposurePercent(percent) == true) {
            return
        }
        // Fallback to the old reflection path (which may hang if camera stalls).
        // To avoid hanging forever, we'll only call it if the direct path is unavailable
        // AND we are confident the camera is not wedged. But we keep it as a last resort.
        // However, since we now set directControlsAvailable only when direct succeeds,
        // we can avoid this fallback entirely by removing it.
        // For safety, we disable the reflection fallback:
        Log.w("CameraBridge", "ctlSetExposure: direct path failed or unavailable – skipping exposure")
        return
    }

    fun ctlSetWbTemp(percent: Int) {
        if (directControlsAvailable) {
            direct?.setWbTempPercent(percent)
        } else {
            Log.w("CameraBridge", "ctlSetWbTemp skipped – direct controls unavailable")
        }
    }

    private inline fun safe(block: (com.jiangdg.ausbc.camera.CameraUVC) -> Unit) {
        try {
            val cam = getCurrentCamera()
            if (cam is com.jiangdg.ausbc.camera.CameraUVC) block(cam)
        } catch (_: Throwable) { }
    }

    // Reflection helpers (keep for exposure dump)
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

    companion object {
        fun newInstance() = CameraBridgeFragment()
    }
}
