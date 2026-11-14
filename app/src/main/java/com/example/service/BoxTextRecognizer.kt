package com.example.service

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device ML Kit text recognizer used to immediately extract text from the
 * cropped selection box so Floating Web can search Google without relying on
 * an external API key or failing silently when no key is configured.
 */
object BoxTextRecognizer {

    private const val TAG = "BoxTextRecognizer"
    private const val MAX_QUERY_CHARS = 320

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun recognizeBitmap(
        bitmap: Bitmap,
        onComplete: (String) -> Unit,
    ) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    onComplete(cleanRecognizedText(result.text))
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "On-device OCR failed", e)
                    onComplete("")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to invoke on-device OCR", e)
            onComplete("")
        }
    }

    internal fun cleanRecognizedText(raw: String): String {
        val collapsed = raw
            .trim()
            .replace(Regex("\\s+"), " ")
        return if (collapsed.length <= MAX_QUERY_CHARS) {
            collapsed
        } else {
            collapsed.take(MAX_QUERY_CHARS).trimEnd()
        }
    }
}
