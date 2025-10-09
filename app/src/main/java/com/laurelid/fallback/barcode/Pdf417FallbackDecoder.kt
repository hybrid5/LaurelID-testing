package com.laurelid.fallback.barcode

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * Provides a last-resort barcode path when a user does not have an mDL.
 */
class Pdf417FallbackDecoder {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_PDF417)
        .build()

    suspend fun decode(bitmap: Bitmap): Map<String, Any?>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient(options)
        val barcodes = scanner.process(image).await()
        val pdf417 = barcodes.firstOrNull() ?: return null
        return parsePayload(pdf417.rawValue ?: return null)
    }

    private fun parsePayload(rawValue: String): Map<String, Any?> {
        val lines = rawValue.lines()
        val map = mutableMapOf<String, Any?>()
        lines.forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1].trim()
            }
        }
        return map
    }
}
