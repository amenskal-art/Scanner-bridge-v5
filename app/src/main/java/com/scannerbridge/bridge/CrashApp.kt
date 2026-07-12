package com.scannerbridge.bridge

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a global crash handler so that if anything throws an uncaught
 * exception (e.g. the AUSBC camera engine failing on this OS version), the full
 * stack trace is written to a file. MainActivity reads that file on next launch
 * and shows it on screen, so we never have a silent "app just closed" again.
 */
/**
 * Hook used to RESCUE (not crash on) AUSBC's known USBMonitor failure: its
 * worker thread throws SecurityException building a UsbControlBlock when a
 * permission race hits (device re-enumerated, permission grant not final).
 * Killing the whole app for that is pointless — only the USBMonitor thread
 * is lost, and it can be rebuilt. MainActivity installs the callback.
 */
object UsbRescue {
    @Volatile var onUsbMonitorCrash: (() -> Unit)? = null
}

class CrashApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val rescued = isUsbMonitorPermissionCrash(thread, throwable)
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append(
                        if (rescued) "Scanner Bridge USB engine crash (RESCUED \u2014 app kept alive).\n\n"
                        else "Scanner Bridge crashed.\n\n"
                    )
                    append("Thread: ${thread.name}\n")
                    append("Time: ${System.currentTimeMillis()}\n\n")
                    append(sw.toString())
                }
                crashFile(this).writeText(text)
            } catch (_: Throwable) {
                // never let the crash handler itself crash
            }
            if (rescued) {
                // Swallow: the USBMonitor thread dies, the app lives. The
                // rescue callback rebuilds the camera client (fresh
                // USBMonitor + thread) and reconnects.
                try { UsbRescue.onUsbMonitorCrash?.invoke() } catch (_: Throwable) {}
                return@setDefaultUncaughtExceptionHandler
            }
            // hand off to the default handler so the OS still finishes the crash
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isUsbMonitorPermissionCrash(thread: Thread, t: Throwable): Boolean {
        // Never swallow main-thread crashes: the looper is unrecoverable.
        if (thread.name == "main") return false
        var e: Throwable? = t
        while (e != null) {
            if (e is SecurityException && e.stackTrace.any {
                    it.className.startsWith("com.jiangdg.usb.USBMonitor")
                }
            ) return true
            e = e.cause
        }
        return false
    }

    companion object {
        fun crashFile(app: Application): File =
            File(app.filesDir, "last_crash.txt")

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
