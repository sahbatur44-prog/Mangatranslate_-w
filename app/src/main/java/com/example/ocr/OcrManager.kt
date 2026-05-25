package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(
    val text: String,
    val boundingBox: Rect
)

class OcrManager(private val context: Context) {

    fun detectText(bitmap: Bitmap, isJapanese: Boolean): List<OcrBlock> {
        val recognizer: TextRecognizer = if (isJapanese) {
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            // Task can block the calling background thread, so we use Tasks.await to run it synchronously on our worker coroutine
            val result = Tasks.await(recognizer.process(image))
            val blocks = mutableListOf<OcrBlock>()

            for (block in result.textBlocks) {
                val text = block.text
                val rect = block.boundingBox
                if (rect != null && text.isNotBlank()) {
                    blocks.add(OcrBlock(text = text, boundingBox = rect))
                }
            }
            Log.d("OcrManager", "Successfully detected ${blocks.size} text blocks.")
            blocks
        } catch (e: Exception) {
            Log.e("OcrManager", "OCR detection failed: ${e.message}", e)
            emptyList()
        } finally {
            recognizer.close()
        }
    }
}
