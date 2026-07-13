package com.scannerbridge.bridge

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a global crash handler so that if anything throws an uncaught
 * exception (e.g. the AUSBC camera engine failing on this OS version), the full
 * stack trace is written to a file. MainActivity reads that file on next launch
 * and shows it on screen, so we never have a silent "app just closed" again.
 *
 * ROUND-12 ADDITION — the USBMonitor SecurityException is intercepted here and
 * treated as RECOVERABLE instead of fatal. Mechanics of that crash:
 *
 *   AUSBC's USBMonitor checks UsbManager.hasPermission(device), then POSTS
 *   processConnect() onto its own HandlerThread. The posted lambda builds a
 *   UsbControlBlock, whose constructor calls updateDeviceInfo() ->
 *   UsbDevice.getSerialNumber(). On Android 10+ that is a fresh binder call
 *   that re-checks permission AT THAT INSTANT. If the device detached or
 *   re-enumerated in the gap (exactly what our watchdog USB resets cause —
 *   the grant dies with the old device instance), the call throws
 *   SecurityException on a library thread that has no try/catch. AUSBC ships
 *   as a prebuilt AAR (and upstream is unmaintained), so the ONLY place we
 *   can intercept is the default uncaught-exception handler.
 *
 * Swallowing it keeps the process alive, but the crashed HandlerThread is
 * dead — that USBMonitor instance is inert (register/requestPermission all
 * post to a dead looper and silently no-op). So we also notify MainActivity
 * via [usbCrashHook]: it tears down the camera fragment and attaches a fresh
 * one, which builds a fresh MultiCameraClient + USBMonitor.
 */
class CrashApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isRecoverableUsbCrash(thread, throwable)) {
                try {
                    appendUsbRecoveryLog(throwable)
                } catch (_: Throwable) {
                    // never let the crash handler itself crash
                }
                val hook = usbCrashHook
                if (hook != null) {
                    Handler(Looper.getMainLooper()).post {
                        try { hook() } catch (_: Throwable) {}
                    }
                }
                // Do NOT hand off to the OS handler: the process survives.
                // Only the library's own HandlerThread dies, and the hook
                // above rebuilds the camera engine around that.
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("Scanner Bridge crashed.\n\n")
                    append("Thread: ${thread.name}\n")
                    append("Time: ${System.currentTimeMillis()}\n\n")
                    append(sw.toString())
                }
                crashFile(this).writeText(text)
            } catch (_: Throwable) {
                // never let the crash handler itself crash
            }
            // hand off to the default handler so the OS still finishes the crash
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * True only for the exact known-recoverable signature: a
     * SecurityException raised inside com.jiangdg.usb.USBMonitor on a
     * BACKGROUND thread. Main-thread exceptions are never swallowed —
     * after an uncaught main-thread exception the app is unusable anyway.
     */
    private fun isRecoverableUsbCrash(thread: Thread, t: Throwable): Boolean {
        if (thread === Looper.getMainLooper().thread) return false
        if (t !is SecurityException) return false
        return t.stackTrace.any {
            it.className.startsWith("com.jiangdg.usb.USBMonitor")
        }
    }

    private fun appendUsbRecoveryLog(t: Throwable) {
        val f = usbRecoveryFile(this)
        // Keep the log bounded: reset once it grows past ~64 KB.
        if (f.exists() && f.length() > 64_000) f.delete()
        f.appendText(
            "USB permission race at ${System.currentTimeMillis()}: " +
            "${t.message}\n"
        )
    }

    companion object {
        /**
         * Set by MainActivity (cleared in its onDestroy). Invoked on the
         * MAIN thread after a recoverable USBMonitor crash was swallowed;
         * the receiver must rebuild the camera fragment because the old
         * monitor thread is dead.
         */
        @Volatile var usbCrashHook: (() -> Unit)? = null

        fun crashFile(app: Application): File =
            File(app.filesDir, "last_crash.txt")

        fun usbRecoveryFile(app: Application): File =
            File(app.filesDir, "usb_recovery_log.txt")

        fun readAndClear(app: Application): String? {
            val f = File(app.filesDir, "last_crash.txt")
            if (!f.exists()) return null
            return try {
                val t = f.readText()
                f.delete()
                t
            } catch (_: Throwable) {
                null
            }
        }
    }
}
