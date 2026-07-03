package com.scannerbridge.bridge.server

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for the scanner's adjustable controls, shared by the
 * phone UI and the PC over HTTP. Both sides read and write this; a monotonic
 * [version] lets each side detect when the other changed something so the two
 * UIs stay in real-time sync.
 *
 * All control values are integer PERCENTAGES (0..100) so they're device- and
 * platform-independent. AUSBC scales these into the webcam's absolute UVC
 * range on the phone; the PC just mirrors the same 0..100 sliders.
 *
 * Controls intentionally mirror the PC panel MINUS the ones that don't apply
 * to a UVC stream driven remotely (auto-exposure / autofocus / focus were
 * removed per spec). The shared set is:
 *   brightness, contrast, gain, sharpness, zoom, exposure, saturation,
 *   wb_temp, auto_wb
 */
class ControlState {

    // Continuous controls (0..100). Defaults chosen to match the phone UI's
    // initial SeekBar positions.
    @Volatile var brightness: Int = 60
    @Volatile var contrast: Int = 60
    @Volatile var gain: Int = 50
    @Volatile var sharpness: Int = 50
    @Volatile var zoom: Int = 0
    @Volatile var exposure: Int = 50
    @Volatile var saturation: Int = 60
    @Volatile var wbTemp: Int = 50          // white-balance temperature 0..100

    // Toggle.
    @Volatile var autoWb: Boolean = true

    /** Bumped on every change so pollers can detect updates. */
    private val versionCounter = AtomicInteger(0)
    val version: Int get() = versionCounter.get()

    /** Wall-clock of the last change (ms) — purely informational. */
    private val lastChange = AtomicLong(System.currentTimeMillis())

    /**
     * Who most recently wrote a value. Lets the phone UI avoid fighting a value
     * the PC just pushed (and vice-versa) by knowing the change came from the
     * other side. "ui" = phone, "pc" = PC, "" = initial.
     */
    @Volatile var lastSource: String = ""

    @Synchronized
    fun bump(source: String) {
        versionCounter.incrementAndGet()
        lastChange.set(System.currentTimeMillis())
        lastSource = source
    }

    /** Apply one named control from a percentage value. Returns true if known. */
    @Synchronized
    fun applyOne(name: String, value: Int, source: String): Boolean {
        val v = value.coerceIn(0, 100)
        when (name) {
            "brightness" -> brightness = v
            "contrast" -> contrast = v
            "gain" -> gain = v
            "sharpness" -> sharpness = v
            "zoom" -> zoom = v
            "exposure" -> exposure = v
            "saturation" -> saturation = v
            "wb_temp" -> wbTemp = v
            "auto_wb" -> autoWb = v >= 50
            else -> return false
        }
        bump(source)
        return true
    }

    @Synchronized
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", version)
        o.put("source", lastSource)
        o.put("brightness", brightness)
        o.put("contrast", contrast)
        o.put("gain", gain)
        o.put("sharpness", sharpness)
        o.put("zoom", zoom)
        o.put("exposure", exposure)
        o.put("saturation", saturation)
        o.put("wb_temp", wbTemp)
        o.put("auto_wb", if (autoWb) 100 else 0)
        return o
    }

    /**
     * Bulk apply from a JSON object (PC -> phone). Only keys present are
     * changed. Returns the set of names that actually changed so the caller can
     * push just those to the camera.
     */
    @Synchronized
    fun applyJson(obj: JSONObject, source: String): List<String> {
        val changed = ArrayList<String>()
        val names = listOf(
            "brightness", "contrast", "gain", "sharpness", "zoom",
            "exposure", "saturation", "wb_temp", "auto_wb"
        )
        for (n in names) {
            if (obj.has(n)) {
                val before = currentValueOf(n)
                applyOneNoBump(n, obj.getInt(n))
                if (currentValueOf(n) != before) changed.add(n)
            }
        }
        if (changed.isNotEmpty()) bump(source)
        return changed
    }

    private fun applyOneNoBump(name: String, value: Int) {
        val v = value.coerceIn(0, 100)
        when (name) {
            "brightness" -> brightness = v
            "contrast" -> contrast = v
            "gain" -> gain = v
            "sharpness" -> sharpness = v
            "zoom" -> zoom = v
            "exposure" -> exposure = v
            "saturation" -> saturation = v
            "wb_temp" -> wbTemp = v
            "auto_wb" -> autoWb = v >= 50
        }
    }

    private fun currentValueOf(name: String): Int = when (name) {
        "brightness" -> brightness
        "contrast" -> contrast
        "gain" -> gain
        "sharpness" -> sharpness
        "zoom" -> zoom
        "exposure" -> exposure
        "saturation" -> saturation
        "wb_temp" -> wbTemp
        "auto_wb" -> if (autoWb) 100 else 0
        else -> -1
    }
}
