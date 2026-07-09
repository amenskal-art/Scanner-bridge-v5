package com.scannerbridge.bridge.ui

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.jiangdg.ausbc.MultiCameraClient

/**
 * Sends UVC camera controls directly via Android's UsbDeviceConnection with a
 * REAL timeout, completely bypassing libuvc's control path.
 */
class UvcDirectControls private constructor(
    private val conn: UsbDeviceConnection,
    private val vcInterface: Int,
    private val cameraTerminalId: Int,
    private val processingUnitId: Int
) {

    // ---- UVC constants -----------------------------------------------------
    companion object {
        private const val TAG = "UvcDirect"
        private const val TIMEOUT_MS = 1500

        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val GET_INFO = 0x86
        private const val GET_MIN = 0x82
        private const val GET_MAX = 0x83
        private const val REQ_SET = 0x21 // host->dev, class, interface
        private const val REQ_GET = 0xA1 // dev->host, class, interface

        // Camera Terminal control selectors
        private const val CT_AE_MODE = 0x02            // 1 byte bitmap
        private const val CT_EXPOSURE_TIME_ABS = 0x04  // 4 bytes, 100 µs units
        private const val CT_ZOOM_ABS = 0x0B           // 2 bytes

        // Processing Unit control selectors
        const val PU_BRIGHTNESS = 0x02  // 2 bytes (signed)
        const val PU_CONTRAST = 0x03    // 2 bytes
        const val PU_GAIN = 0x04        // 2 bytes
        const val PU_SATURATION = 0x07  // 2 bytes
        const val PU_SHARPNESS = 0x08   // 2 bytes
        const val PU_GAMMA = 0x09       // 2 bytes
        const val PU_WB_TEMP = 0x0A     // 2 bytes
        const val PU_WB_TEMP_AUTO = 0x0B // 1 byte

        /**
         * Builds a UvcDirectControls from AUSBC's camera object by pulling the
         * UsbDeviceConnection it already opened and parsing the USB descriptors for the
         * VideoControl interface number, Camera Terminal ID, and Processing
         * Unit ID.
         */
        fun from(cam: MultiCameraClient.ICamera?): UvcDirectControls? {
            cam ?: return null
            val ctrlBlock = readField(cam, "mCtrlBlock") ?: run {
                Log.w(TAG, "mCtrlBlock not found on ${cam.javaClass.name}")
                return null
            }
            val conn = try {
                ctrlBlock.javaClass.getMethod("getConnection")
                    .invoke(ctrlBlock) as? UsbDeviceConnection
            } catch (t: Throwable) {
                Log.w(TAG, "getConnection() failed", t)
                null
            } ?: return null

            var vcIf = -1
            var ctId = -1
            var puId = -1

            val raw = try { conn.rawDescriptors } catch (t: Throwable) { null }
            if (raw != null && raw.size >= 12) {
                var inVc = false
                var i = 0
                while (i + 1 < raw.size) {
                    val len = raw[i].toInt() and 0xFF
                    if (len < 2 || i + len > raw.size) break
                    val type = raw[i + 1].toInt() and 0xFF
                    when (type) {
                        0x04 -> { // standard INTERFACE descriptor
                            val ifNum = raw[i + 2].toInt() and 0xFF
                            val klass = raw[i + 5].toInt() and 0xFF
                            val sub = raw[i + 6].toInt() and 0xFF
                            inVc = (klass == 0x0E && sub == 0x01) // Video / VideoControl
                            if (inVc) vcIf = ifNum
                        }
                        0x24 -> if (inVc && len >= 4) { // class-specific VC descriptor
                            when (raw[i + 2].toInt() and 0xFF) { // subtype
                                0x02 -> if (ctId < 0) ctId = raw[i + 3].toInt() and 0xFF // INPUT_TERMINAL
                                0x05 -> if (puId < 0) puId = raw[i + 3].toInt() and 0xFF // PROCESSING_UNIT
                            }
                        }
                    }
                    i += len
                }
            } else {
                Log.w(TAG, "rawDescriptors unavailable, constructing with standard defaults")
            }

            // Fail-safe: Fall back to consumer defaults if descriptors failed to return or parse
            if (vcIf < 0) vcIf = 0
            if (ctId < 0) ctId = 1
            if (puId < 0) puId = 2
            Log.d(TAG, "ready: vcInterface=$vcIf cameraTerminal=$ctId processingUnit=$puId")
            return UvcDirectControls(conn, vcIf, ctId, puId)
        }

        fun connectionFrom(cam: MultiCameraClient.ICamera?): UsbDeviceConnection? {
            cam ?: return null
            val ctrlBlock = readField(cam, "mCtrlBlock") ?: return null
            return try {
                ctrlBlock.javaClass.getMethod("getConnection")
                    .invoke(ctrlBlock) as? UsbDeviceConnection
            } catch (_: Throwable) {
                null
            }
        }

        private fun readField(obj: Any, name: String): Any? {
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
    }

    /** Optional sink for human-readable error messages (shown in the app UI). */
    @Volatile var diagSink: ((String) -> Unit)? = null

    // Cached (min, max) per (entity, selector); queried once with timeout.
    private val ranges = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()
    @Volatile private var aeManualSet = false

    @Volatile private var wbAutoOffSent = false

    private val transferLock = Any()

    // ---- raw transfer helpers ---------------------------------------------

    private fun setCur(entity: Int, cs: Int, value: Int, size: Int): Boolean {
        val buf = ByteArray(size)
        for (b in 0 until size) buf[b] = ((value shr (8 * b)) and 0xFF).toByte()
        val r = synchronized(transferLock) {
            conn.controlTransfer(
                REQ_SET, SET_CUR, cs shl 8, (entity shl 8) or vcInterface, buf, size, TIMEOUT_MS
            )
        }
        if (r < 0) {
            Log.w(TAG, "SET_CUR entity=$entity cs=0x${cs.toString(16)} val=$value FAILED ($r)")
            diagSink?.invoke("USB write failed: cs=0x${cs.toString(16)} r=$r")
        }
        return r >= 0
    }

    private fun getReq(req: Int, entity: Int, cs: Int, size: Int): Int? {
        val buf = ByteArray(size)
        val r = synchronized(transferLock) {
            conn.controlTransfer(
                REQ_GET, req, cs shl 8, (entity shl 8) or vcInterface, buf, size, TIMEOUT_MS
            )
        }
        if (r < 0) return null
        var v = 0
        for (b in 0 until size) v = v or ((buf[b].toInt() and 0xFF) shl (8 * b))
        if (size == 2 && (v and 0x8000) != 0) v = v or -0x10000
        return v
    }

    private fun rangeOf(entity: Int, cs: Int, size: Int, defMin: Int, defMax: Int): Pair<Int, Int> {
        val key = (entity shl 16) or cs
        ranges[key]?.let { return it }
        val min = getReq(GET_MIN, entity, cs, size)
        val max = getReq(GET_MAX, entity, cs, size)
        val pair = if (min != null && max != null && max > min) min to max else defMin to defMax
        ranges[key] = pair
        Log.d(TAG, "range entity=$entity cs=0x${cs.toString(16)}: ${pair.first}..${pair.second}")
        return pair
    }

    private fun scale(percent: Int, range: Pair<Int, Int>): Int {
        val p = percent.coerceIn(0, 100)
        return range.first + p * (range.second - range.first) / 100
    }

    /**
     * Executes a low-level USB GET_DESCRIPTOR request on the Device Descriptor (Endpoint 0).
     * This is handled entirely in device core firmware and acts as a foolproof check to
     * see if the underlying UsbDeviceConnection can issue control transfers.
     */
    private fun probeStandardUsb(): Boolean {
        val buf = ByteArray(18)
        val r = try {
            synchronized(transferLock) {
                conn.controlTransfer(
                    0x80, // REQ_DIR_IN | REQ_TYPE_STANDARD | REQ_RECIP_DEVICE
                    0x06, // GET_DESCRIPTOR
                    0x0100, // (USB_DT_DEVICE << 8)
                    0,
                    buf,
                    18,
                    TIMEOUT_MS
                )
            }
        } catch (t: Throwable) {
            -1
        }
        return r >= 0
    }

    /**
     * Probes the USB control path. 
     * If standard USB control transfers work, we assume the physical path is alive. 
     * We attempt standard UVC PU/CT requests. Even if those specific requests fail 
     * (e.g. because of custom vendor IDs or unsupported controls), we still approve 
     * the direct path because its reliable timeouts keep the native thread safe.
     */
    fun probe(): Boolean {
        if (!probeStandardUsb()) {
            Log.w(TAG, "probe: standard USB control transfer failed")
            diagSink?.invoke("Direct USB path not answering (standard probe failed)")
            return false
        }

        // Try standard class requests with the discovered/default selectors
        if (getReq(GET_CUR, processingUnitId, PU_BRIGHTNESS, 2) != null) return true
        if (getReq(GET_CUR, cameraTerminalId, CT_ZOOM_ABS, 2) != null) return true
        
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        if (getReq(GET_CUR, processingUnitId, PU_BRIGHTNESS, 2) != null) return true

        Log.i(TAG, "probe: standard USB transfers OK, but UVC PU/CT GET requests failed. Trusting direct path.")
        return true
    }

    // ---- public control API (all percent 0..100) ---------------------------

    fun setPu(cs: Int, percent: Int, defMin: Int = 0, defMax: Int = 255): Boolean {
        if (processingUnitId < 0) return false
        val range = rangeOf(processingUnitId, cs, 2, defMin, defMax)
        return setCur(processingUnitId, cs, scale(percent, range), 2)
    }

    fun setAutoWhiteBalance(on: Boolean): Boolean {
        if (processingUnitId < 0) return false
        val ok = setCur(processingUnitId, PU_WB_TEMP_AUTO, if (on) 1 else 0, 1)
        if (ok) wbAutoOffSent = !on
        return ok
    }

    fun setWbTempPercent(percent: Int): Boolean {
        if (processingUnitId < 0) return false
        if (!wbAutoOffSent) {
            if (setCur(processingUnitId, PU_WB_TEMP_AUTO, 0, 1)) wbAutoOffSent = true
        }
        val range = rangeOf(processingUnitId, PU_WB_TEMP, 2, 2800, 6500)
        return setCur(processingUnitId, PU_WB_TEMP, scale(percent, range), 2)
    }

    fun setZoomPercent(percent: Int): Boolean {
        if (cameraTerminalId < 0) return false
        val range = rangeOf(cameraTerminalId, CT_ZOOM_ABS, 2, 100, 400)
        return setCur(cameraTerminalId, CT_ZOOM_ABS, scale(percent, range), 2)
    }

    fun setExposurePercent(percent: Int): Boolean {
        if (cameraTerminalId < 0) return false
        if (!aeManualSet) {
            val ok = setCur(cameraTerminalId, CT_AE_MODE, 1, 1)
            if (!ok) setCur(cameraTerminalId, CT_AE_MODE, 4, 1)
            aeManualSet = true
        }
        val range = rangeOf(cameraTerminalId, CT_EXPOSURE_TIME_ABS, 4, 1, 5000)
        return setCur(cameraTerminalId, CT_EXPOSURE_TIME_ABS, scale(percent, range), 4)
    }
}
