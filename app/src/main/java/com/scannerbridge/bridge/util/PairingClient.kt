package com.scannerbridge.bridge.util

import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Parses the PC's QR payload and calls the PC's pairing gate back with this
 * phone's own stream address.
 *
 * QR payload (JSON):  {"v":1,"ip":"192.168.1.10","port":8765,"token":"abc"}
 * Callback POST to:   http://<pc_ip>:<port>/pair
 *   body: {"ip": phoneIp, "port": 8080, "path": "/video",
 *          "token": <echoed>, "name": <device name>}
 */
object PairingClient {

    private const val TAG = "PairingClient"

    data class PcTarget(val ip: String, val port: Int, val token: String)

    /** Returns a PcTarget if [qrText] is a valid pairing payload, else null. */
    fun parsePcQr(qrText: String): PcTarget? {
        return try {
            val o = JSONObject(qrText.trim())
            val ip = o.optString("ip", "")
            val port = o.optInt("port", 0)
            val token = o.optString("token", "")
            if (ip.isBlank() || port <= 0 || token.isBlank()) null
            else PcTarget(ip, port, token)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * POSTs this phone's stream address to the PC gate.
     * @return true on HTTP 200 with {"ok":true}
     */
    fun sendAddress(
        target: PcTarget,
        phoneIp: String,
        streamPort: Int,
        deviceName: String,
        streamPath: String = "/video"
    ): Boolean {
        val urlStr = "http://${target.ip}:${target.port}/pair"
        return try {
            val body = JSONObject().apply {
                put("ip", phoneIp)
                put("port", streamPort)
                put("path", streamPath)
                put("token", target.token)
                put("name", deviceName)
            }.toString()

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { os: OutputStream ->
                os.write(body.toByteArray())
            }
            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Throwable) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()
            Log.i(TAG, "pair POST -> $code $resp")
            code == 200 && resp.contains("\"ok\":true")
        } catch (e: Throwable) {
            Log.w(TAG, "pair POST failed: ${e.message}")
            false
        }
    }
}
