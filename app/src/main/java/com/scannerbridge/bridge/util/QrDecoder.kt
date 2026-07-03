package com.scannerbridge.bridge.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decodes a QR code directly from a UVC NV21 frame.
 *
 * ZXing only needs the luminance (Y) plane, which for NV21 is exactly the
 * first width*height bytes of the buffer. The critical correctness rule is
 * that the WIDTH used here must be the true row stride of the Y plane,
 * otherwise every row is offset and the decoded image is sheared (the QR
 * never resolves even though a phone camera reads the same code fine).
 *
 * AUSBC sometimes reports a requested preview size that differs from the
 * actual buffer it delivers, so we DERIVE the true dimensions from the
 * byte-array length (NV21 size == w*h*3/2) and only trust the reported width
 * when it is consistent with the buffer.
 */
class QrDecoder {

    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
    )

    fun decodeNv21(nv21: ByteArray, reportedW: Int, reportedH: Int): String? {
        val (w, h) = resolveDimensions(nv21.size, reportedW, reportedH) ?: return null

        // 1) Full frame, normal then inverted.
        decodeRegion(nv21, w, h, 0, 0, w, h, false)?.let { return it }
        decodeRegion(nv21, w, h, 0, 0, w, h, true)?.let { return it }

        // 2) Centered square crop (QR is square; this removes the distorted
        //    edges of a wide UVC frame and makes the modules more uniform).
        val side = minOf(w, h)
        val left = (w - side) / 2
        val top = (h - side) / 2
        decodeRegion(nv21, w, h, left, top, side, side, false)?.let { return it }
        decodeRegion(nv21, w, h, left, top, side, side, true)?.let { return it }

        // 3) Dark / low-contrast webcam fallback: stretch the luminance
        //    histogram of the centered crop so faint modules separate, then
        //    retry. This is what rescues underexposed UVC feeds that a phone
        //    camera reads fine.
        val boosted = contrastBoostedCrop(nv21, w, h, left, top, side, side)
        if (boosted != null) {
            decodeRegion(boosted, side, side, 0, 0, side, side, false)?.let { return it }
            decodeRegion(boosted, side, side, 0, 0, side, side, true)?.let { return it }
        }

        return null
    }

    /**
     * Extract the Y-plane crop and stretch its contrast to full 0..255 range
     * (simple min/max normalization). Returns an NV21-shaped buffer where the
     * Y plane is the boosted crop (chroma is irrelevant for luminance decode).
     */
    private fun contrastBoostedCrop(
        nv21: ByteArray, w: Int, h: Int, left: Int, top: Int, cw: Int, ch: Int
    ): ByteArray? {
        return try {
            val ySize = cw * ch
            val out = ByteArray(ySize + ySize / 2)   // NV21 sized
            // find min/max luminance in the crop
            var mn = 255
            var mx = 0
            for (y in 0 until ch) {
                val row = (top + y) * w + left
                for (x in 0 until cw) {
                    val v = nv21[row + x].toInt() and 0xFF
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
            }
            val range = (mx - mn).coerceAtLeast(1)
            var i = 0
            for (y in 0 until ch) {
                val row = (top + y) * w + left
                for (x in 0 until cw) {
                    val v = nv21[row + x].toInt() and 0xFF
                    val stretched = ((v - mn) * 255) / range
                    out[i++] = stretched.toByte()
                }
            }
            // neutral chroma
            for (j in ySize until out.size) out[j] = 128.toByte()
            out
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Work out the true (width, height) of the Y plane. NV21 buffers are
     * w*h*3/2 bytes. If the reported width divides the buffer correctly we
     * trust it; otherwise we fall back to common stride guesses so a
     * mismatched report can't shear the image.
     */
    private fun resolveDimensions(size: Int, reportedW: Int, reportedH: Int): Pair<Int, Int>? {
        if (size <= 0) return null

        // total pixels in the Y plane = size * 2 / 3 (NV21 = 1.5 bytes/px)
        val yPixels = size * 2 / 3
        if (yPixels <= 0) return null

        // If the reported width cleanly produces the right pixel count, use it.
        if (reportedW > 0) {
            val derivedH = yPixels / reportedW
            if (derivedH > 0 && reportedW * derivedH == yPixels) {
                return reportedW to derivedH
            }
        }

        // If the reported height works as a divisor, use it.
        if (reportedH > 0) {
            val derivedW = yPixels / reportedH
            if (derivedW > 0 && derivedW * reportedH == yPixels) {
                return derivedW to reportedH
            }
        }

        // Last resort: try common UVC widths and accept the first that divides.
        for (cand in intArrayOf(1280, 1920, 1024, 960, 800, 720, 640, 352, 320)) {
            val dh = yPixels / cand
            if (dh > 0 && cand * dh == yPixels) return cand to dh
        }
        return null
    }

    private fun decodeRegion(
        nv21: ByteArray, w: Int, h: Int,
        left: Int, top: Int, regionW: Int, regionH: Int,
        invert: Boolean
    ): String? {
        return try {
            val source = PlanarYUVLuminanceSource(
                nv21, w, h, left, top, regionW, regionH, false
            )
            val lumi = if (invert) source.invert() else source
            val bitmap = BinaryBitmap(HybridBinarizer(lumi))
            reader.decode(bitmap, hints).text
        } catch (_: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }
}
