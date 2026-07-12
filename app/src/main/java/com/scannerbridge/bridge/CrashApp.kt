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
class CrashApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
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
