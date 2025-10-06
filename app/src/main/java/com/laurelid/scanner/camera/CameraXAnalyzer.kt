package com.laurelid.scanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import com.laurelid.util.Logger
import com.laurelid.util.await
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CameraXAnalyzer(
    private val scope: CoroutineScope,
    private val barcodeScanner: BarcodeScanner,
    private val onPayload: suspend (String) -> Unit,
    private val shouldProcess: () -> Boolean,
) : ImageAnalysis.Analyzer {

    private var currentJob: Job? = null

    override fun analyze(imageProxy: ImageProxy) {
        if (!shouldProcess()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val barcodes = barcodeScanner.process(inputImage).await()
                val payload = barcodes.firstOrNull { barcode ->
                    barcode.format == Barcode.FORMAT_QR_CODE && !barcode.rawValue.isNullOrBlank()
                }?.rawValue
                if (payload != null && shouldProcess()) {
                    onPayload(payload)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Logger.e(TAG, "Barcode scanning failed", error)
            } finally {
                imageProxy.close()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXAnalyzer"
    }
}
