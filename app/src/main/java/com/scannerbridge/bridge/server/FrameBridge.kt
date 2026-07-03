package com.scannerbridge.bridge.server

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts raw UVC preview frames (NV21) coming from AUSBC into JPEG bytes
 * and forwards them to the [MjpegServer].
 *
 * Encoding runs on the caller's preview thread but is cheap (hardware-backed
 * YuvImage.compressToJpeg). A frame is dropped if the previous one is still
 * encoding, so we never build a backlog.
 */
class FrameBridge(private val server: MjpegServer) {

    @Volatile var jpegQuality: Int = 70
    @Volatile private var width: Int = 0
    @Volatile private var height: Int = 0

    val frameWidth: Int get() = width
    val frameHeight: Int get() = height

    private val encoding = AtomicInteger(0)
    private var reuseBuffer = ByteArrayOutputStream(64 * 1024)

    val fpsCounter = FpsCounter()

    fun setResolution(w: Int, h: Int) {
        width = w
        height = h
    }

    /**
     * @param nv21 raw frame in NV21 (AUSBC default preview format)
     */
    fun onFrame(nv21: ByteArray, w: Int = width, h: Int = height) {
        if (w <= 0 || h <= 0) return
        // Drop frame if a prior encode is still in flight.
        if (!encoding.compareAndSet(0, 1)) return
        try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            reuseBuffer.reset()
            yuv.compressToJpeg(Rect(0, 0, w, h), jpegQuality, reuseBuffer)
            server.submitFrame(reuseBuffer.toByteArray())
            fpsCounter.tick()
        } catch (_: Throwable) {
            // bad frame; skip
        } finally {
            encoding.set(0)
        }
    }

    /**
     * @param rgba raw frame in RGBA_8888 (AUSBC delivers this in some
     *             render/device configurations instead of NV21).
     */
    fun onFrameRgba(rgba: ByteArray, w: Int = width, h: Int = height) {
        if (w <= 0 || h <= 0) return
        val expected = w * h * 4
        if (rgba.size < expected) return
        if (!encoding.compareAndSet(0, 1)) return
        var bmp: android.graphics.Bitmap? = null
        try {
            bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
            reuseBuffer.reset()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG,
                         jpegQuality, reuseBuffer)
            server.submitFrame(reuseBuffer.toByteArray())
            fpsCounter.tick()
        } catch (_: Throwable) {
            // bad frame; skip
        } finally {
            bmp?.recycle()
            encoding.set(0)
        }
    }

    class FpsCounter {
        private var frames = 0
        private var windowStart = System.currentTimeMillis()
        @Volatile var fps: Int = 0
            private set

        fun tick() {
            frames++
            val now = System.currentTimeMillis()
            val elapsed = now - windowStart
            if (elapsed >= 1000) {
                fps = (frames * 1000 / elapsed).toInt()
                frames = 0
                windowStart = now
            }
        }
    }
}