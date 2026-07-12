package com.scannerbridge.bridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.scannerbridge.bridge.CrashApp
import com.scannerbridge.bridge.R
import com.scannerbridge.bridge.databinding.ActivityMainBinding
import com.scannerbridge.bridge.server.FrameBridge
import com.scannerbridge.bridge.server.MjpegServer
import com.scannerbridge.bridge.server.StreamForegroundService
import com.scannerbridge.bridge.util.NetworkUtils
import com.scannerbridge.bridge.util.PairingClient
import kotlin.concurrent.thread

/**
 * Flow (PC shows the QR, this phone's webcam reads it):
 *  1. Plug in the USB-C webcam -> live feed shows.
 *  2. Tap "Scan PC Code". The webcam frames are scanned for the PC's QR.
 *  3. On decode: parse {pc_ip, port, token}, start the MJPEG server, then POST
 *     this phone's own stream address back to the PC gate.
 *  4. PC auto-connects to this phone's stream.
 */
class MainActivity : AppCompatActivity(),
    CameraBridgeFragment.Callbacks, MjpegServer.Listener {

    private lateinit var binding: ActivityMainBinding

    private val streamPort = 8080
    private var server: MjpegServer? = null
    private var frameBridge: FrameBridge? = null
    private var cameraFragment: CameraBridgeFragment? = null

    private var cameraReady = false
    private var streaming = false
    private var scanning = false
    private var pairedPcName = ""

    private val ui = Handler(Looper.getMainLooper())

    private val permReq = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { attachCameraFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        binding.actionButton.setOnClickListener { toggleScanning() }
        binding.stopButton.setOnClickListener { stopEverything() }
        setupCameraControls()
        setupFullscreen()

        val lastCrash = CrashApp.readAndClear(application)
        if (lastCrash != null) {
            showCrashDialog(lastCrash)
        }

        requestRuntimePermissions()
        startStatsTicker()
        updateUi()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFullscreen) {
            binding.rootContainer.post {
                val h = binding.rootContainer.height.takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels
                binding.previewCard.layoutParams = binding.previewCard.layoutParams.apply {
                    height = h
                }
            }
        } else {
            // Exit-fullscreen rotation just completed: only NOW does
            // previewHost have its portrait width, so only now can the
            // host height be computed correctly. Doing this inside
            // exitFullscreen() ran with the LANDSCAPE width still in
            // effect and produced a wrong (too tall) box — the black
            // borders after leaving fullscreen.
            binding.previewHost.post { fitPortraitPreviewHost() }
        }
    }

    private fun showCrashDialog(text: String) {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Previous crash details")
                .setMessage(text.take(4000))
                .setPositiveButton("Copy") { _, _ ->
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cb.setPrimaryClip(
                        android.content.ClipData.newPlainText("crash", text))
                    toast("Crash log copied")
                }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (_: Throwable) {}
    }

    // ---------------- permissions ----------------
    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permReq.launch(needed.toTypedArray())
        else attachCameraFragment()
    }

    private fun attachCameraFragment() {
        if (cameraFragment != null) return
        ui.post {
            if (cameraFragment != null) return@post
            if (isFinishing || isDestroyed) return@post
            try {
                val frag = CameraBridgeFragment.newInstance().apply {
                    callbacks = this@MainActivity
                }
                cameraFragment = frag
                supportFragmentManager.beginTransaction()
                    .replace(binding.previewHost.id, frag)
                    .commitAllowingStateLoss()
            } catch (t: Throwable) {
                cameraFragment = null
                binding.scanHint.text =
                    "Scanner engine failed to start."
                toast("Scanner init failed")
            }
        }
    }

    // ---------------- scan control ----------------
    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(QrScanActivity.EXTRA_QR)
            if (!text.isNullOrBlank()) handleScannedQr(text)
        }
    }

    private fun toggleScanning() {
        if (streaming) { toast("Already paired and streaming"); return }
        qrScanLauncher.launch(android.content.Intent(this, QrScanActivity::class.java))
    }

    private fun handleScannedQr(text: String) {
        val target = PairingClient.parsePcQr(text) ?: run {
            toast("That QR isn't a Scanner Pro pairing code.")
            return
        }
        binding.scanHint.text = "Code read \u2014 connecting to PC..."
        updateUi()
        pairWithPc(target)
    }

    private fun pairWithPc(target: PairingClient.PcTarget) {
        val phoneIp = NetworkUtils.getLocalIpAddress()
        if (phoneIp == null) {
            runOnUiThread { toast("No Wi-Fi. Join the same network as the PC.") }
            return
        }

        startStreaming(phoneIp)

        thread {
            val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val ok = PairingClient.sendAddress(
                target = target,
                phoneIp = phoneIp,
                streamPort = streamPort,
                deviceName = name
            )
            runOnUiThread {
                if (ok) {
                    pairedPcName = target.ip
                    binding.scanHint.text = "Paired. The PC is now receiving your scanner."
                    toast("Paired with PC")
                } else {
                    binding.scanHint.text =
                        "Couldn't reach the PC gate. Same Wi-Fi? Try again."
                    toast("Pairing callback failed")
                }
                updateUi()
            }
        }
    }

    // ---------------- streaming ----------------
    private fun startStreaming(phoneIp: String) {
        if (streaming) return
        val srv = MjpegServer(streamPort).apply { listener = this@MainActivity }
        val bridge = FrameBridge(srv)
        server = srv
        frameBridge = bridge
        cameraFragment?.frameBridge = bridge
        srv.start()
        streaming = true
        serverBindRetries = 0

        val url = NetworkUtils.buildStreamUrl(phoneIp, streamPort)
        try {
            val svc = Intent(this, StreamForegroundService::class.java)
                .putExtra(StreamForegroundService.EXTRA_URL, url)
            ContextCompat.startForegroundService(this, svc)
        } catch (t: Throwable) {
            runOnUiThread { binding.scanHint.text =
                "Streaming (note: background keep-alive unavailable on this OS)." }
        }

        runOnUiThread {
            updateUi()
        }
    }

    private fun stopEverything() {
        scanning = false
        cameraFragment?.frameBridge = null
        server?.stop()
        server = null
        frameBridge = null
        streaming = false
        pairedPcName = ""
        stopService(Intent(this, StreamForegroundService::class.java))
        binding.scanHint.text = "Tap Scan PC Code, then aim the phone camera at the QR code."
        updateUi()
        toast("Stopped")
    }

    // ---------------- UI ----------------
    private fun updateUi() {
        binding.actionButton.text = when {
            streaming -> "Paired \u2713"
            scanning -> "Scanning... (tap to stop)"
            else -> "Scan PC Code"
        }
        binding.actionButton.isEnabled = !streaming
        binding.stopButton.visibility = if (streaming || scanning) View.VISIBLE else View.GONE

        val online = streaming
        binding.statusDot.setBackgroundResource(
            if (online) R.drawable.dot_online else R.drawable.dot_offline)
        binding.statusText.text = when {
            streaming -> "Streaming"
            scanning -> "Scanning"
            cameraReady -> "Scanner ready"
            else -> "No scanner"
        }
    }

    private fun startStatsTicker() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                val fps = frameBridge?.fpsCounter?.fps ?: 0
                binding.fpsBadge.text =
                    if (lastVideoW > 0) "$fps fps \u00b7 ${lastVideoW}\u00d7${lastVideoH}"
                    else "$fps fps"
                checkControlWatchdog()
                ui.postDelayed(this, 1000)
            }
        }, 1000)
    }

    // ---------------- scanner controls (HandlerThread) ----------------
    @Volatile private var applyingRemote = false

    private val pendingControls = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Last value actually written to the camera per control. Skipping
    // redundant writes keeps USB EP0 traffic to a minimum — flooding the
    // control endpoint is what wedges cheap UVC firmware.
    private val lastApplied = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Watchdog: timestamp set just before a JNI control write starts, cleared
    // when it returns. libuvc in this build uses an INFINITE control-transfer
    // timeout, so if the webcam ever stalls a request, the write never
    // returns and this thread is wedged forever. The watchdog detects that
    // and recovers (see checkControlWatchdog).
    @Volatile private var controlBusySince = 0L
    @Volatile private var recovering = false

    // Dedicated background thread to run USB control JNI tasks safely.
    // var (not val): the watchdog abandons a wedged thread and replaces it.
    private var controlThread = android.os.HandlerThread("ScannerControlThread").apply { start() }
    private var controlHandler = Handler(controlThread.looper)

    // Set on every camera-open. For the first controlQuietMs after an open
    // NO control write reaches the camera: libusb issues its own control
    // traffic while the stream spins up, and mixing our writes into that
    // window is what wedges this camera's firmware (field-tested). Writes
    // requested during the window aren't lost — they stay queued and flush
    // the moment the window closes.
    @Volatile private var cameraOpenedAt = 0L
    private val CONTROL_QUIET_MS = 3000L
    // A reopen right after a forced USB reset is the firmware's most fragile
    // moment (just power-cycled + full stream re-negotiation). Give it a
    // longer quiet window there — the round-8 field log showed the automatic
    // re-apply of touched controls stalling ~9 s after every reset-reopen,
    // which is what kept the reset loop feeding itself.
    private val CONTROL_QUIET_AFTER_RESET_MS = 6000L
    @Volatile private var controlQuietMs = CONTROL_QUIET_MS
    @Volatile private var lastRecoveryAt = 0L

    private val writePendingControlsRunnable = object : Runnable {
        override fun run() {
            val quietLeft = (cameraOpenedAt + controlQuietMs) - System.currentTimeMillis()
            if (quietLeft > 0) {
                controlHandler.postDelayed(this, quietLeft)
                return
            }
            if (pendingControls.isEmpty()) return
            val frag = cameraFragment
            val libMode = frag?.isDirectControlActive() != true
            // Library path on THIS camera: EP0 never answers while the isoch
            // stream is running (round-8 field log: >15 s of silence, then
            // reset). Quiesce the stream, flush the whole queue, resume —
            // a ~0.3-0.8 s frozen frame on the PC instead of a wedged
            // thread + USB reset + reconnect cascade.
            var paused = false
            val pauseStart = System.currentTimeMillis()
            if (libMode && frag != null) {
                paused = frag.ctlPauseStream()
            }
            try {
                var passes = 0
                while (true) {
                    drainOnce(paused)
                    passes++
                    if (!paused || passes >= 12) break
                    if (pendingControls.isNotEmpty()) continue
                    // Queue is empty: linger so values still arriving from an
                    // in-progress drag ride the SAME pause window instead of
                    // forcing pause/resume churn several times a second
                    // (round-9 log showed 2 cycles/s during drags).
                    try { Thread.sleep(400) } catch (_: InterruptedException) {}
                    if (pendingControls.isEmpty()) break
                }
            } finally {
                if (paused) {
                    frag?.ctlResumeStream()
                    reportPauseCycle(System.currentTimeMillis() - pauseStart)
                }
            }
        }

        private fun drainOnce(paused: Boolean) {
            val names = ArrayList(pendingControls.keys)
            for (n in names) {
                val v = pendingControls.remove(n) ?: continue
                if (lastApplied[n] == v) continue // no change -> no USB traffic
                // Arm the watchdog ONLY for libuvc writes: they have no
                // timeout of their own, so only they can wedge a thread.
                // The direct path self-timeouts and is never watched.
                val watched = cameraFragment?.isDirectControlActive() != true
                if (watched) controlBusySince = System.currentTimeMillis()
                val t0 = System.currentTimeMillis()
                try {
                    applyControlToCamera(n, v)
                    lastApplied[n] = v
                    if (watched) {
                        val took = System.currentTimeMillis() - t0
                        if (took > 2000) reportSlowLibWrite(took)
                    }
                    // With the stream paused, EP0 answers in milliseconds —
                    // short gap. An unpaused libuvc write keeps the long
                    // gap (bursts wedge cheap firmware).
                    Thread.sleep(if (watched && !paused) 250 else 50)
                } catch (_: Throwable) {
                } finally {
                    controlBusySince = 0L
                }
            }
        }
    }

    private fun enqueueControl(name: String, value: Int) {
        pendingControls[name] = value
        controlHandler.removeCallbacks(writePendingControlsRunnable)
        // Debounce & throttle writes sequentially. 100 ms (was 30) — during a
        // slider drag only the newest value per control survives, cutting
        // control-endpoint traffic to <=10 writes/sec per control.
        controlHandler.postDelayed(writePendingControlsRunnable, 100)
    }

    // Two-stage library-path watchdog.
    //
    // WHY THE OLD 4 s THRESHOLD WAS WRONG (round-8 field log): on this
    // camera EVERY libuvc control write under full stream + PC-client load
    // takes longer than 4 s to answer — but it DOES answer. libuvc's
    // infinite timeout was getting every one of those writes through
    // eventually (round-5 evidence). The 4 s watchdog therefore classified
    // every ordinary slider adjustment as "wedged" and fired the nuclear
    // USB reset — freeze, reconnect, automatic re-apply of controls, which
    // stalled again 9 s after the reopen, reset again… the exact loop in
    // the field log. A slow write is NOT a wedged write.
    //
    // New behavior:
    //   * >= 5 s  -> informational notice, keep waiting. The write runs on
    //               the dedicated control HandlerThread; the UI and the
    //               stream are untouched while it waits.
    //   * >= 15 s -> NOW it's a genuinely wedged transfer (firmware EP0
    //               dead). Same nuclear recovery as before: abandon the
    //               wedged thread, close the fd to abort the transfer,
    //               real USB port reset, reopen.
    private val LIB_SLOW_NOTICE_MS = 5000L
    private val LIB_STALL_RESET_MS = 15000L
    @Volatile private var slowNoticeShownFor = 0L

    // One pause-cycle line per 5 s max — proof of life, not spam.
    @Volatile private var lastPauseReportAt = 0L
    private fun reportPauseCycle(tookMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastPauseReportAt < 5000) return
        lastPauseReportAt = now
        onControlDiag("Controls applied \u2014 stream paused $tookMs ms", false)
    }

    // At most one "answered slowly" info line per 10 s — it's a breadcrumb,
    // not spam.
    @Volatile private var lastSlowReportAt = 0L
    private fun reportSlowLibWrite(tookMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSlowReportAt < 10000) return
        lastSlowReportAt = now
        val secs = String.format(java.util.Locale.US, "%.1f", tookMs / 1000.0)
        onControlDiag("Control applied \u2014 scanner answered in $secs s (slow under stream load)", false)
    }

    private fun checkControlWatchdog() {
        val since = controlBusySince
        if (since == 0L || recovering) return
        val stuckMs = System.currentTimeMillis() - since
        if (stuckMs >= LIB_SLOW_NOTICE_MS && slowNoticeShownFor != since) {
            slowNoticeShownFor = since
            onControlDiag(
                "Scanner answering slowly \u2014 waiting (stream unaffected)\u2026", false)
        }
        if (stuckMs < LIB_STALL_RESET_MS) return

        recovering = true
        lastRecoveryAt = System.currentTimeMillis()
        controlBusySince = 0L
        toast("Scanner controls stalled — resetting…")
        onControlDiag("Control write stalled >${LIB_STALL_RESET_MS / 1000} s \u2014 forcing scanner reset", true)

        try { controlThread.quitSafely() } catch (_: Throwable) {}
        controlThread = android.os.HandlerThread("ScannerControlThread-r").apply { start() }
        controlHandler = Handler(controlThread.looper)
        pendingControls.clear()
        lastApplied.clear()

        // Order matters: closing the raw USB fd aborts the control transfer
        // wedged inside libuvc (its timeout is infinite) and releases the
        // UVCCamera monitor the wedged thread holds. Only AFTER that can
        // closeCamera()/openCamera() actually run — calling them first just
        // queued them behind the dead monitor forever.
        //
        // CRITICAL: run the whole sequence OFF the main thread. closeCamera()
        // inside ctlRecoverCamera has to take the UVCCamera monitor; if the
        // wedged thread hasn't fully released it yet, that call blocks. On
        // the main thread that block was an ANR — the app "froze" while the
        // stream (separate threads) kept running. On a worker it's harmless.
        thread(name = "ScannerRecovery") {
            try {
                cameraFragment?.ctlAbortStuckControl()
                // Give the aborted transfer a beat to unwind out of JNI and
                // release the monitor before we try to take it.
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                cameraFragment?.ctlRecoverCamera()
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "recovery sequence threw", t)
            }
        }

        // Allow another recovery attempt after things settle.
        ui.postDelayed({ recovering = false }, 8000)
    }

    // ---------------- on-screen control diagnostics ----------------
    // No Logcat needed: the latest control-path event is shown as a small
    // status line at the top of Scanner Controls, errors also pop a toast
    // (throttled), and TAPPING the status line copies the full diagnostic
    // history to the clipboard so it can be pasted into a chat/bug report.
    private var diagView: android.widget.TextView? = null
    private val diagLog = ArrayDeque<String>()
    private var lastDiagToastAt = 0L

    private fun setupDiagView() {
        val tv = android.widget.TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9aa7b5"))
            text = "Control path: waiting for scanner\u2026"
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
            setOnClickListener {
                if (diagLog.isEmpty()) return@setOnClickListener
                val cb = getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                    "scanner-diag", diagLog.joinToString("\n")))
                toast("Diagnostics copied \u2014 paste anywhere")
            }
        }
        binding.controlsBody.addView(tv, 0)
        diagView = tv
    }

    override fun onControlDiag(message: String, isError: Boolean) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(diagLog) {
            diagLog.addLast("[$ts] $message")
            while (diagLog.size > 60) diagLog.removeFirst()
        }
        runOnUiThread {
            diagView?.apply {
                text = "$message  (tap to copy log)"
                setTextColor(Color.parseColor(if (isError) "#ff7a7a" else "#9aa7b5"))
            }
            if (isError) {
                val now = System.currentTimeMillis()
                if (now - lastDiagToastAt > 5000) {
                    lastDiagToastAt = now
                    toast("Scanner: $message")
                }
            }
        }
    }

    private fun setupCameraControls() {
        setupDiagView()
        binding.controlsHeader.setOnClickListener {
            val body = binding.controlsBody
            val showing = body.visibility == View.VISIBLE
            body.visibility = if (showing) View.GONE else View.VISIBLE
            binding.controlsToggle.text = if (showing) "Show" else "Hide"
        }

        // Dual-Controls Synchronization Architecture
        bindSeekBarPair(binding.sbBrightness, binding.fsSbBrightness, "brightness")
        bindSeekBarPair(binding.sbExposure,   binding.fsSbExposure,   "exposure")
        bindSeekBarPair(binding.sbContrast,   binding.fsSbContrast,   "contrast")
        bindSeekBarPair(binding.sbSaturation, binding.fsSbSaturation, "saturation")
        bindSeekBarPair(binding.sbGain,       binding.fsSbGain,       "gain")
        bindSeekBarPair(binding.sbSharpness,  binding.fsSbSharpness,  "sharpness")
        bindSeekBarPair(binding.sbZoom,       binding.fsSbZoom,       "zoom")
        bindSeekBarPair(binding.sbWbTemp,     binding.fsSbWbTemp,     "wb_temp")

        val autoWbListener = android.widget.CompoundButton.OnCheckedChangeListener { buttonView, checked ->
            if (applyingRemote) return@OnCheckedChangeListener
            val other = if (buttonView == binding.cbAutoWb) binding.fsCbAutoWb else binding.cbAutoWb
            if (other.isChecked != checked) {
                other.isChecked = checked
            }
            userTouched.add("auto_wb")
            enqueueControl("auto_wb", if (checked) 100 else 0)
            server?.controlState?.applyOne("auto_wb", if (checked) 100 else 0, "ui")
        }
        binding.cbAutoWb.setOnCheckedChangeListener(autoWbListener)
        binding.fsCbAutoWb.setOnCheckedChangeListener(autoWbListener)
    }

    private fun bindSeekBarPair(sbNormal: android.widget.SeekBar, sbFs: android.widget.SeekBar, name: String) {
        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser || applyingRemote) return
                
                // Mirror progress to the matching layout slider
                val other = if (s == sbNormal) sbFs else sbNormal
                other.progress = value
                
                // Toggle Auto WB off on manual temperature changes
                if (name == "wb_temp") {
                    if (binding.cbAutoWb.isChecked) binding.cbAutoWb.isChecked = false
                    if (binding.fsCbAutoWb.isChecked) binding.fsCbAutoWb.isChecked = false
                    userTouched.add("auto_wb")
                    enqueueControl("auto_wb", 0)
                    server?.controlState?.applyOne("auto_wb", 0, "ui")
                }
                
                userTouched.add(name)
                enqueueControl(name, value)
                server?.controlState?.applyOne(name, value, "ui")
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                // Guarantee the RELEASE value lands even if intermediate drag
                // values were coalesced away while a slow write was in flight.
                val v = s?.progress ?: return
                if (applyingRemote) return
                userTouched.add(name)
                enqueueControl(name, v)
                server?.controlState?.applyOne(name, v, "ui")
            }
        }
        sbNormal.setOnSeekBarChangeListener(listener)
        sbFs.setOnSeekBarChangeListener(listener)
    }

    private fun applyControlToCamera(name: String, value: Int) {
        val frag = cameraFragment ?: return
        when (name) {
            "brightness" -> frag.ctlSetBrightness(value)
            "exposure"   -> frag.ctlSetExposure(value)
            "contrast"   -> frag.ctlSetContrast(value)
            "saturation" -> frag.ctlSetSaturation(value)
            "gain"       -> frag.ctlSetGain(value)
            "sharpness"  -> frag.ctlSetSharpness(value)
            "zoom"       -> frag.ctlSetZoom(value)
            "wb_temp"    -> frag.ctlSetWbTemp(value)
            "auto_wb"    -> frag.ctlSetAutoWhiteBalance(value >= 50)
        }
    }

    override fun onControlsChanged(changed: List<String>, source: String) {
        val cs = server?.controlState ?: return
        runOnUiThread {
            applyingRemote = true
            try {
                for (name in changed) {
                    val v = when (name) {
                        "brightness" -> cs.brightness
                        "exposure"   -> cs.exposure
                        "contrast"   -> cs.contrast
                        "saturation" -> cs.saturation
                        "gain"       -> cs.gain
                        "sharpness"  -> cs.sharpness
                        "zoom"       -> cs.zoom
                        "wb_temp"    -> cs.wbTemp
                        "auto_wb"    -> if (cs.autoWb) 100 else 0
                        else -> continue
                    }
                    userTouched.add(name)
                    enqueueControl(name, v)
                    
                    // Update portrait and fullscreen SeekBars simultaneously
                    when (name) {
                        "brightness" -> {
                            binding.sbBrightness.progress = v
                            binding.fsSbBrightness.progress = v
                        }
                        "exposure"   -> {
                            binding.sbExposure.progress = v
                            binding.fsSbExposure.progress = v
                        }
                        "contrast"   -> {
                            binding.sbContrast.progress = v
                            binding.fsSbContrast.progress = v
                        }
                        "saturation" -> {
                            binding.sbSaturation.progress = v
                            binding.fsSbSaturation.progress = v
                        }
                        "gain"       -> {
                            binding.sbGain.progress = v
                            binding.fsSbGain.progress = v
                        }
                        "sharpness"  -> {
                            binding.sbSharpness.progress = v
                            binding.fsSbSharpness.progress = v
                        }
                        "zoom"       -> {
                            binding.sbZoom.progress = v
                            binding.fsSbZoom.progress = v
                        }
                        "wb_temp"    -> {
                            binding.sbWbTemp.progress = v
                            binding.fsSbWbTemp.progress = v
                        }
                        "auto_wb"    -> binding.cbAutoWb.isChecked = (v >= 50)
                    }
                }
            } finally {
                applyingRemote = false
            }
        }
    }

    // Bumped on every camera open/close. The delayed initial-controls burst
    // only fires if its generation still matches — so when the camera
    // reopens 3 times in quick succession after a USB reset (AUSBC's
    // attach-triggered open racing our recovery reopen), only ONE burst of
    // 9 writes reaches the camera instead of three stacked bursts flooding
    // EP0 right when the firmware is most fragile.
    @Volatile private var openGeneration = 0
    private var pendingInitialControls: Runnable? = null

    private fun applyInitialCameraControls(gen: Int) {
        pendingInitialControls?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            if (gen == openGeneration && cameraReady) applyInitialCameraControlsNow()
        }
        pendingInitialControls = r
        // Past the quiet window: the writer thread wouldn't send earlier
        // anyway, this just avoids pointless queue churn.
        ui.postDelayed(r, controlQuietMs + 200)
    }

    // Controls the user (or the PC) has actually adjusted this session.
    // ONLY these are re-applied on camera open. The old behavior pushed all
    // 9 controls on every open, and each control's FIRST direct write costs
    // 3-5 USB transfers (range GETs + SET) — ~25 back-to-back EP0 transfers
    // into a camera whose bus is saturated by the 1080p stream. That burst
    // is what wedged the firmware a few seconds after every open (field log:
    // failures from cs=0x7/saturation onward). Untouched controls keep the
    // camera's own hardware defaults, which is what libuvc gave it anyway.
    private val userTouched: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun applyInitialCameraControlsNow() {
        runOnUiThread {
            val values = linkedMapOf(
                "auto_wb" to (if (binding.cbAutoWb.isChecked) 100 else 0),
                "brightness" to binding.sbBrightness.progress,
                "exposure" to binding.sbExposure.progress,
                "contrast" to binding.sbContrast.progress,
                "saturation" to binding.sbSaturation.progress,
                "gain" to binding.sbGain.progress,
                "sharpness" to binding.sbSharpness.progress,
                "zoom" to binding.sbZoom.progress,
                "wb_temp" to binding.sbWbTemp.progress,
            )
            server?.controlState?.let { cs ->
                for ((k, v) in values) cs.applyOne(k, v, "ui")
            }
            var n = 0
            for ((k, v) in values) {
                if (k in userTouched) {
                    enqueueControl(k, v)
                    n++
                }
            }
            android.util.Log.i(
                "MainActivity",
                "initial controls: re-applying $n adjusted control(s) (of ${values.size})"
            )
        }
    }

    // ---------------- preview sizing (portrait) ----------------
    // Last frame size the camera reported — drives the preview box aspect.
    @Volatile private var lastVideoW = 0
    @Volatile private var lastVideoH = 0

    /**
     * Portrait: size the preview box to the VIDEO's aspect ratio instead of
     * the old fixed 240 dp. With a 16:9 feed inside a fixed-height box the
     * render either letterboxed (small) or, when AUSBC's aspect pass lost the
     * race, stretched to fill (squished). Matching the box to the video makes
     * the feed fill the card edge-to-edge at the correct ratio — same
     * proportions as the PC window. Fullscreen manages its own sizing.
     */
    private fun fitPortraitPreviewHost() {
        if (isFullscreen) return
        val vw = lastVideoW
        val vh = lastVideoH
        if (vw <= 0 || vh <= 0) return
        binding.previewHost.post {
            if (isFullscreen) return@post
            val w = binding.previewHost.width
            if (w <= 0) return@post
            var h = w * vh / vw
            // Portrait-orientation cameras: don't let the box eat the screen.
            val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            if (h > maxH) h = maxH
            if (binding.previewHost.layoutParams.height != h) {
                binding.previewHost.layoutParams =
                    binding.previewHost.layoutParams.apply { height = h }
            }
            cameraFragment?.refitPreview()
        }
    }

    // ---------------- fullscreen / landscape ----------------
    private var isFullscreen = false
    private var savedHostHeight = -1
    private var savedScrollY = 0

    private fun setupFullscreen() {
        binding.fullscreenButton.setOnClickListener { enterFullscreen() }
        binding.fsExitButton.setOnClickListener { exitFullscreen() }
        binding.fsToggleControlsButton.setOnClickListener { toggleFullscreenControls() }
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        isFullscreen = true

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars(true)

        savedScrollY = binding.contentScroll.scrollY
        savedHostHeight = binding.previewHost.layoutParams.height

        setSiblingCardsVisible(View.GONE)
        binding.previewHeaderRow.visibility = View.GONE
        binding.previewCard.setPadding(0, 0, 0, 0)
        (binding.previewCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = 0; binding.previewCard.layoutParams = it
        }
        binding.previewCard.background = null

        val fillH = binding.rootContainer.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        binding.previewCard.layoutParams = binding.previewCard.layoutParams.apply {
            height = fillH
        }
        binding.previewCardInner.layoutParams = binding.previewCardInner.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.previewHost.layoutParams = binding.previewHost.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.rootContainer.post {
            if (isFullscreen) {
                val h = binding.rootContainer.height.takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels
                binding.previewCard.layoutParams = binding.previewCard.layoutParams.apply {
                    height = h
                }
            }
        }

        binding.contentScroll.scrollTo(0, 0)
        binding.fullscreenOverlay.visibility = View.VISIBLE
        cameraFragment?.refitPreview()

        binding.fsControlsScroll.visibility = View.GONE
        binding.fsToggleControlsButton.text = "Controls"
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        hideSystemBars(false)

        binding.fullscreenOverlay.visibility = View.GONE

        setSiblingCardsVisible(View.VISIBLE)
        binding.previewHeaderRow.visibility = View.VISIBLE
        val pad = (12 * resources.displayMetrics.density).toInt()
        binding.previewCard.setPadding(pad, pad, pad, pad)
        (binding.previewCard.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (14 * resources.displayMetrics.density).toInt()
            binding.previewCard.layoutParams = it
        }
        binding.previewCard.setBackgroundResource(R.drawable.card_panel)

        binding.previewCard.layoutParams = binding.previewCard.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.previewCardInner.layoutParams = binding.previewCardInner.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        binding.previewHost.layoutParams = binding.previewHost.layoutParams.apply {
            height = if (savedHostHeight > 0) savedHostHeight
                     else (240 * resources.displayMetrics.density).toInt()
        }

        binding.contentScroll.post { binding.contentScroll.scrollTo(0, savedScrollY) }
        // NOTE: no fitPortraitPreviewHost() here — at this point the window
        // still has landscape dimensions; onConfigurationChanged does the
        // refit once the rotation back to portrait has actually happened.
    }

    private fun setSiblingCardsVisible(visibility: Int) {
        val scrollContent = binding.contentScroll.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until scrollContent.childCount) {
            val child = scrollContent.getChildAt(i)
            if (child.id != binding.previewCard.id) {
                child.visibility = visibility
            }
        }
    }

    private fun toggleFullscreenControls() {
        val panel = binding.fsControlsScroll
        val showing = panel.visibility == View.VISIBLE
        panel.visibility = if (showing) View.GONE else View.VISIBLE
        binding.fsToggleControlsButton.text = if (showing) "Controls" else "Hide"
    }

    private fun hideSystemBars(hide: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            exitFullscreen()
            return
        }
        super.onBackPressed()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- camera callbacks ----------------
    override fun onCameraOpened(width: Int, height: Int) {
        cameraReady = true
        cameraOpenedAt = System.currentTimeMillis()
        // Reopen shortly after a forced reset -> firmware just power-cycled,
        // hold control writes back longer than after a normal open.
        controlQuietMs =
            if (cameraOpenedAt - lastRecoveryAt < 30000) CONTROL_QUIET_AFTER_RESET_MS
            else CONTROL_QUIET_MS
        lastVideoW = width
        lastVideoH = height
        val gen = ++openGeneration
        lastApplied.clear() // fresh device state -> re-send everything
        if (frameBridge != null) {
            cameraFragment?.frameBridge = frameBridge
        }
        applyInitialCameraControls(gen)
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.GONE
            fitPortraitPreviewHost()
            updateUi()
        }
    }

    override fun onCameraClosed() {
        cameraReady = false
        openGeneration++
        pendingInitialControls?.let { ui.removeCallbacks(it) }
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.VISIBLE
            updateUi()
        }
    }

    // ---------------- server callbacks ----------------
    override fun onClientCountChanged(count: Int) {
        // Stats displays removed
    }

    @Volatile private var serverBindRetries = 0

    override fun onServerError(message: String) {
        runOnUiThread {
            toast("Server error: $message")
            // "EADDRINUSE": a previous socket (duplicate activity instance
            // launched by USB_DEVICE_ATTACHED, or a just-crashed session)
            // hadn't released :8080. launchMode=singleTask removes the
            // duplicate-activity cause; if it still happens, retry the bind
            // instead of leaving the PC with no stream.
            if (streaming && message.contains("EADDRINUSE", ignoreCase = true)
                && serverBindRetries < 3) {
                serverBindRetries++
                ui.postDelayed({ if (streaming) server?.start() }, 1500)
            }
        }
    }

    override fun onDestroy() {
        if (streaming || scanning) stopEverything()
        try {
            controlThread.quitSafely()
        } catch (_: Throwable) {
            try { controlThread.quit() } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}
