package com.scannerbridge.bridge.ui

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
        return try {
            val field = cam.javaClass.getDeclaredField("mUVCCamera")
            field.isAccessible = true
            field.get(cam)
        } catch (e: Throwable) {
            try {
                val field = cam.javaClass.getDeclaredFields().firstOrNull { 
                    it.type.name.contains("UVCCamera") 
                }
                if (field != null) {
                    field.isAccessible = true
                    field.get(cam)
                } else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun callUvcMethod(uvcCamera: Any, methodName: String, vararg args: Any?): Any? {
        val method = uvcCamera.javaClass.getMethods().firstOrNull { m ->
            m.name == methodName && m.parameterTypes.size == args.size
        } ?: return null
        
        return method.invoke(uvcCamera, *args)
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
     * AUSBC 3.3.3's CameraUVC wrapper does not expose a real hardware exposure
     * setter (the native UVCCamera exposure binding is private/unreachable),
     * so Exposure is mapped to setGamma — the closest available UVC lever —
     * same as CameraUVC.setBrightness/setContrast/etc. This mirrors the fix
     * already described in CHANGES_ROUND3.md, which was never actually wired
     * into this method (it was left calling nonexistent reflection method
     * names, which silently did nothing).
     *
     * If a specific webcam doesn't implement the UVC gamma control in
     * hardware, this slider may have little visible effect on that device —
     * that's a hardware limitation, not a wiring bug.
     */
    fun ctlSetExposure(percent: Int) = safe { it.setGamma(percent.coerceIn(0, 100)) }

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
