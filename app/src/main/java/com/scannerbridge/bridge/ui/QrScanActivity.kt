package com.scannerbridge.bridge.ui

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions

/**
 * Full-screen QR scanner using the PHONE'S OWN back camera (CameraX + ML Kit).
 *
 * The phone camera autofocuses and reads a QR off a PC screen instantly, which
 * the USB webcam could not do reliably. This activity does ONE job: scan the
 * PC's pairing QR and return its raw text to the caller via setResult.
 *
 * Result: RESULT_OK + intent extra EXTRA_QR = decoded string.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR = "qr_text"
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val preview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val hint = TextView(this).apply {
            text = "Point the scanner at the QR code on your PC screen"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x88000000.toInt())
            setPadding(32, 24, 32, 24)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = android.view.Gravity.BOTTOM
            layoutParams = lp
        }
        root.addView(preview)
        root.addView(hint)
        setContentView(root)

        startScanner(preview)
    }

    private fun startScanner(preview: PreviewView) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        val controller = LifecycleCameraController(this)
        val mainExec = ContextCompat.getMainExecutor(this)

        controller.setImageAnalysisAnalyzer(
            mainExec,
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExec
            ) { result ->
                if (done) return@MlKitAnalyzer
                val codes = result?.getValue(scanner)
                val raw = codes?.firstOrNull()?.rawValue
                if (!raw.isNullOrBlank()) {
                    done = true
                    val data = android.content.Intent().putExtra(EXTRA_QR, raw)
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
        )

        controller.bindToLifecycle(this)
        preview.controller = controller
    }
}
