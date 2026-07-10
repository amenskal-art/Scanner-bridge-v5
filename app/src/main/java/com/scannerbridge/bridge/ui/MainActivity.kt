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
                binding.fpsBadge.text = "$fps fps"
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

    private val writePendingControlsRunnable = object : Runnable {
        override fun run() {
            val names = ArrayList(pendingControls.keys)
            for (n in names) {
                val v = pendingControls.remove(n) ?: continue
                if (lastApplied[n] == v) continue // no change -> no USB traffic
                controlBusySince = System.currentTimeMillis()
                try {
                    applyControlToCamera(n, v)
                    lastApplied[n] = v
                    Thread.sleep(50) // pace EP0 writes; bursts wedge cheap firmware
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

    /**
     * Called once a second from the stats ticker. If a control write has been
     * stuck inside JNI for >4 s, the webcam has stalled the USB control
     * endpoint and that HandlerThread will never come back on its own.
     * Recovery: abandon the wedged thread, spin up a fresh one, and
     * close/reopen the camera — closing the device handle aborts the pending
     * libusb transfer, which also lets the old thread finally exit.
     */
    private fun checkControlWatchdog() {
        val since = controlBusySince
        if (since == 0L || recovering) return
        if (System.currentTimeMillis() - since < 4000) return

        recovering = true
        controlBusySince = 0L
        toast("Scanner controls stalled — resetting…")
        onControlDiag("Control write stalled >4 s \u2014 forcing scanner reset", true)

        try { controlThread.quitSafely() } catch (_: Throwable) {}
        controlThread = android.os.HandlerThread("ScannerControlThread-r").apply { start() }
        controlHandler = Handler(controlThread.looper)
        pendingControls.clear()
        lastApplied.clear()

        // Order matters: closing the raw USB fd aborts the control transfer
        // wedged inside libuvc (its timeout is infinite) and releases the
        // UVCCamera monitor the wedged thread holds. Only AFTER that can
        // closeCamera()/openCamera() actually run — calling them first just
        // queued them behind the dead monitor forever, which is why the old
        // recovery never brought controls back.
        cameraFragment?.ctlAbortStuckControl()
        cameraFragment?.ctlRecoverCamera()

        // Allow another recovery attempt after things settle.
        ui.postDelayed({ recovering = false }, 5000)
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
                    enqueueControl("auto_wb", 0)
                    server?.controlState?.applyOne("auto_wb", 0, "ui")
                }
                
                enqueueControl(name, value)
                server?.controlState?.applyOne(name, value, "ui")
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
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

    private fun applyInitialCameraControls() {
        // Delay well past stream startup: libusb issues its own control
        // traffic while the stream spins up, and mixing our writes into that
        // window is what wedges this camera's firmware.
        ui.postDelayed({ applyInitialCameraControlsNow() }, 1500)
    }

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
            for ((k, v) in values) enqueueControl(k, v)
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
        lastApplied.clear() // fresh device state -> re-send everything
        if (frameBridge != null) {
            cameraFragment?.frameBridge = frameBridge
        }
        applyInitialCameraControls()
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.GONE
            updateUi()
        }
    }

    override fun onCameraClosed() {
        cameraReady = false
        runOnUiThread {
            binding.previewPlaceholder.visibility = View.VISIBLE
            updateUi()
        }
    }

    // ---------------- server callbacks ----------------
    override fun onClientCountChanged(count: Int) {
        // Stats displays removed
    }

    override fun onServerError(message: String) {
        runOnUiThread { toast("Server error: $message") }
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
