package com.example.utils

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.api.GeminiTranslator
import com.example.ocr.OcrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TranslationPipeline(private val context: Context) {

    private val ocrManager = OcrManager(context)
    private val translator = GeminiTranslator()

    data class TranslationResult(
        val originalBitmap: Bitmap,
        val translatedBitmap: Bitmap,
        val textBlocksCount: Int,
        val savedFilePath: String? = null
    )

    suspend fun translateMangaPage(
        inputBitmap: Bitmap,
        isJapanese: Boolean,
        fontSizeMultiplier: Float = 1.0f,
        maskColor: Int = Color.WHITE,
        isOfflineMode: Boolean = false
    ): TranslationResult = withContext(Dispatchers.Default) {
        Log.d("TranslationPipeline", "Starting manga page translation...")

        // Step 1: Perform on-device OCR
        val ocrBlocks = ocrManager.detectText(inputBitmap, isJapanese)
        if (ocrBlocks.isEmpty()) {
            Log.w("TranslationPipeline", "No text detected on the page.")
            return@withContext TranslationResult(inputBitmap, inputBitmap, 0)
        }

        // Step 2: Extract text array for translation
        val originalTexts = ocrBlocks.map { it.text }

        // Step 3: Handle translation (either API or local offline simulator)
        val translatedTexts = if (isOfflineMode) {
            translator.translateTextsOffline(originalTexts)
        } else {
            val sourceLanguageName = if (isJapanese) "Japonca" else "İngilizce"
            translator.translateTexts(originalTexts, sourceLanguageName)
        }

        // Step 4: Create a mutable copy of the original bitmap to paint translations
        val outputBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }

        val maskPaint = Paint().apply {
            color = maskColor
            style = Paint.Style.FILL
        }

        // Dynamic calculation of bubble colors or solid fallback
        for (i in ocrBlocks.indices) {
            val block = ocrBlocks[i]
            val rect = block.boundingBox
            val translatedText = translatedTexts.getOrNull(i) ?: block.text

            // Clear original text: Paint a matching bubble background color
            // Standard manga speech bubbles are White/Cream. We can determine if we can use a custom background, or solid color.
            canvas.drawRect(rect, maskPaint)

            // Draw wrap-around translated text using StaticLayout
            val padding = 6 // pixels
            val availableWidth = (rect.width() - (padding * 2)).coerceAtLeast(30)
            val availableHeight = rect.height().coerceAtLeast(20)

            // Auto-scale font size to fit the bubble bounding box comfortably
            var idealFontSize = (availableHeight * 0.25f * fontSizeMultiplier).coerceIn(16f, 55f)
            var staticLayout: StaticLayout
            
            // Iterative sizing to ensure it fits the box
            do {
                textPaint.textSize = idealFontSize
                // Calculate StaticLayout
                staticLayout = StaticLayout.Builder.obtain(
                    translatedText,
                    0,
                    translatedText.length,
                    textPaint,
                    availableWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.0f)
                    .setIncludePad(false)
                    .build()

                if (staticLayout.height <= availableHeight || idealFontSize <= 12f) {
                    break
                }
                idealFontSize -= 2f // Decrease size if text is too tall
            } while (idealFontSize > 10f)

            // Draw StaticLayout centered vertically inside the rectangle
            canvas.save()
            val verticalTranslation = rect.top + (rect.height() - staticLayout.height) / 2f
            canvas.translate((rect.left + padding).toFloat(), verticalTranslation)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        // Step 5: Save translated image to internal storage
        val directory = File(context.filesDir, "translated_pages")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val fileName = "manga_${System.currentTimeMillis()}.png"
        val file = File(directory, fileName)
        
        try {
            FileOutputStream(file).use { out ->
                outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("TranslationPipeline", "Translated page saved successfully to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("TranslationPipeline", "Error saving translated page bitmap: ${e.message}", e)
        }

        return@withContext TranslationResult(
            originalBitmap = inputBitmap,
            translatedBitmap = outputBitmap,
            textBlocksCount = ocrBlocks.size,
            savedFilePath = file.absolutePath
        )
    }
}
