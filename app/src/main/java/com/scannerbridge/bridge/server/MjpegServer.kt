package com.scannerbridge.bridge.server

import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A tiny single-purpose HTTP server that streams MJPEG over
 * multipart/x-mixed-replace -- the exact format OpenCV/FFmpeg's
 * VideoCapture(url, CAP_FFMPEG) consumes on the PC side.
 *
 * Endpoints:
 *   GET  /video    -> multipart/x-mixed-replace MJPEG stream
 *   GET  /         -> minimal status / preview HTML page
 *   GET  /health   -> "ok" (used by the PC tool to verify reachability)
 *   GET  /controls -> JSON snapshot of the shared control state (+ version)
 *   POST /control  -> set one or more controls (PC -> phone). Body is JSON,
 *                     either {"name":"brightness","value":70} or a bulk
 *                     {"brightness":70,"contrast":55,...}. Returns the new
 *                     state JSON so the caller is immediately in sync.
 *
 * Design notes:
 *  - There is ONE latest frame (AtomicReference). Each connected client
 *    runs its own writer loop and sends whatever the newest frame is at
 *    its own pace, so a slow client never blocks the camera or others.
 *  - Frames are pushed in via [submitFrame] from the UVC callback.
 *  - Controls are the shared source of truth in [controlState]. Both the phone
 *    UI and the PC read/write it; the PC polls /controls to mirror phone
 *    changes, and POSTs /control to push its own. [onControlsChanged] notifies
 *    the Activity so it can apply changed values to the live UVC camera.
 */
class MjpegServer(private val port: Int = 8080) {

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "scannerbridgeframe"
        private const val TARGET_FPS = 30
    }

    interface Listener {
        fun onClientCountChanged(count: Int)
        fun onServerError(message: String)
        /**
         * Called (off the main thread) when the PC pushed control changes.
         * [changed] is the list of control names that actually changed. The
         * Activity should apply these to the camera and refresh its UI.
         */
        fun onControlsChanged(changed: List<String>, source: String) {}
    }

    var listener: Listener? = null

    /** Shared control state (single source of truth for phone + PC). */
    val controlState = ControlState()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    /** Newest JPEG bytes, shared by all client writers. */
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val frameSeq = AtomicInteger(0)
    @Volatile private var cachedPlaceholder: ByteArray? = null

    private val clientCount = AtomicInteger(0)

    val isRunning: Boolean get() = running.get()
    val connectedClients: Int get() = clientCount.get()

    fun start() {
        if (running.getAndSet(true)) return
        acceptExecutor.execute {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                serverSocket = s
                Log.i(TAG, "MJPEG server listening on :$port")
                while (running.get()) {
                    val client = try {
                        s.accept()
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept() failed", e)
                        break
                    }
                    clientExecutor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                listener?.onServerError(e.message ?: "Server error")
                running.set(false)
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        latestJpeg.set(null)
        Log.i(TAG, "MJPEG server stopped")
    }

    /** Push a fresh JPEG frame; replaces any not-yet-sent frame. */
    fun submitFrame(jpeg: ByteArray) {
        latestJpeg.set(jpeg)
        frameSeq.incrementAndGet()
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.tcpNoDelay = true
                val input = sock.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                // Read header lines, capturing Content-Length for POST bodies.
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrElse(0) { "GET" }.uppercase()
                val path = parts.getOrElse(1) { "/" }

                val out = BufferedOutputStream(sock.getOutputStream())

                when {
                    method == "POST" && path.startsWith("/control") -> {
                        // Read exactly Content-Length chars of body.
                        val body = if (contentLength > 0) {
                            val buf = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val r = input.read(buf, read, contentLength - read)
                                if (r < 0) break
                                read += r
                            }
                            String(buf, 0, read)
                        } else ""
                        handleControlPost(out, body)
                    }
                    path.startsWith("/controls") -> writeJson(out, controlState.toJson().toString())
                    path.startsWith("/video") -> streamMjpeg(out)
                    path.startsWith("/health") -> writeText(out, "ok")
                    else -> writeLandingPage(out)
                }
            } catch (_: Exception) {
                // client disconnected; ignore
            }
        }
    }

    /** Parse a /control POST body and apply it to the shared state. */
    private fun handleControlPost(out: OutputStream, body: String) {
        val changed: List<String> = try {
            val obj = org.json.JSONObject(if (body.isBlank()) "{}" else body)
            if (obj.has("name") && obj.has("value")) {
                // Single-control form.
                val name = obj.getString("name")
                val value = obj.getInt("value")
                if (controlState.applyOne(name, value, "pc")) listOf(name) else emptyList()
            } else {
                // Bulk form.
                controlState.applyJson(obj, "pc")
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (changed.isNotEmpty()) {
            try { listener?.onControlsChanged(changed, "pc") } catch (_: Throwable) {}
        }
        writeJson(out, controlState.toJson().toString())
    }

    private fun writeJson(out: OutputStream, json: String) {
        val bytes = json.toByteArray()
        val header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeText(out: OutputStream, body: String) {
        val bytes = body.toByteArray()
        val header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun writeLandingPage(out: OutputStream) {
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <title>Scanner Bridge</title>
            <style>body{background:#12161c;color:#e8eef5;font-family:sans-serif;
            text-align:center;padding:24px}img{max-width:96%;border-radius:12px;
            border:1px solid #1f2933}h2{color:#28d0e8}</style></head>
            <body><h2>Scanner Bridge</h2>
            <p>Live MJPEG endpoint: <code>/video</code></p>
            <img src="/video"/></body></html>
        """.trimIndent()
        val bytes = html.toByteArray()
        val header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun streamMjpeg(out: OutputStream) {
        val header = "HTTP/1.0 200 OK\r\n" +
                "Cache-Control: no-cache, no-store, max-age=0, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()

        val count = clientCount.incrementAndGet()
        listener?.onClientCountChanged(count)

        var lastSeq = -1
        val frameIntervalMs = (1000 / TARGET_FPS).toLong()
        try {
            // Send an immediate placeholder frame so the PC's FFmpeg-based
            // VideoCapture sees a valid first JPEG right away and isOpened()
            // returns true. Without this, if the webcam isn't feeding frames
            // yet, FFmpeg waits for the first frame, times out (~30s), and the
            // PC reports "Could not reach wireless scanner" even though the
            // connection succeeded. Real frames replace this as soon as the
            // webcam starts.
            var sentAny = false
            run {
                val first = latestJpeg.get() ?: placeholderJpeg()
                writeFramePart(out, first)
                sentAny = true
            }

            // Keep sending the placeholder at a slow cadence until real frames
            // arrive, so the connection never sits empty and time out.
            var waitedMs = 0L
            while (running.get() && latestJpeg.get() == null) {
                Thread.sleep(200)
                waitedMs += 200
                writeFramePart(out, placeholderJpeg())
            }

            while (running.get()) {
                val seq = frameSeq.get()
                if (seq == lastSeq) {
                    Thread.sleep(2)
                    continue
                }
                lastSeq = seq
                val jpeg = latestJpeg.get() ?: continue
                writeFramePart(out, jpeg)
                Thread.sleep(frameIntervalMs)
            }
        } catch (_: Exception) {
            // client gone / write failed
        } finally {
            val c = clientCount.decrementAndGet()
            listener?.onClientCountChanged(c)
        }
    }

    private fun writeFramePart(out: OutputStream, jpeg: ByteArray) {
        val partHeader = "--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n\r\n"
        out.write(partHeader.toByteArray())
        out.write(jpeg)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    /** A tiny solid-color JPEG used before the webcam starts feeding frames. */
    private fun placeholderJpeg(): ByteArray {
        cachedPlaceholder?.let { return it }
        val w = 640; val h = 480
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(10, 14, 20))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(40, 208, 232)
            textSize = 28f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText("Scanner Bridge \u2014 starting scanner...", w / 2f, h / 2f, paint)
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
        bmp.recycle()
        val bytes = baos.toByteArray()
        cachedPlaceholder = bytes
        return bytes
    }
}
