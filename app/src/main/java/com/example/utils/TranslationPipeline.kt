package com.example.utils

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.api.GeminiTranslator
import com.example.api.TargetLanguage
import com.example.api.SourceLanguage
import com.example.api.OnDeviceTranslator
import com.example.ocr.OcrManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class TranslationPipeline(context: Context) {

    private val appContext = context.applicationContext
    private val ocrManager = OcrManager(appContext)
    private val translator = GeminiTranslator()

    data class TranslationResult(
        val originalBitmap: Bitmap,
        val translatedBitmap: Bitmap,
        val textBlocksCount: Int,
        val savedFilePath: String? = null,
        val detectedAsJapanese: Boolean = true
    )

    suspend fun translateMangaPage(
        inputBitmap: Bitmap,
        sourceLanguage: SourceLanguage,
        fontSizeMultiplier: Float = 1.0f,
        maskColor: Int = Color.WHITE,
        isOfflineMode: Boolean = false,
        isDebugMode: Boolean = false,
        targetLang: TargetLanguage = TargetLanguage.TURKISH
    ): TranslationResult = withContext(Dispatchers.Default) {
        Log.d("TranslationPipeline", "Starting manga page translation...")

        // Step 1: Perform on-device OCR
        val (ocrBlocks, isActuallyJapanese) = ocrManager.detectText(inputBitmap, sourceLanguage, includeFiltered = isDebugMode)
        val processableBlocks = ocrBlocks.filter { !it.isFiltered }
        val filteredBlocks = ocrBlocks.filter { it.isFiltered }

        if (processableBlocks.isEmpty() && filteredBlocks.isEmpty()) {
            Log.w("TranslationPipeline", "No text detected on the page.")
            return@withContext TranslationResult(inputBitmap, inputBitmap, 0, null, isActuallyJapanese)
        }

        // Step 2: Extract text array for translation
        val originalTexts = processableBlocks.map { it.text }

        // Step 3: Handle translation (either API or local offline simulator)
        val translatedTexts = if (originalTexts.isEmpty()) {
            emptyList()
        } else if (isOfflineMode) {
            OnDeviceTranslator.translate(originalTexts, sourceLanguage, isActuallyJapanese, targetLang)
        } else {
            val sourceLanguageName = if (isActuallyJapanese) "Japonca" else "İngilizce"
            translator.translateTexts(originalTexts, sourceLanguageName, targetLang)
        }

        // Step 4: Create a mutable copy of the original bitmap to paint translations
        val safeBitmap = if (inputBitmap.config == Bitmap.Config.HARDWARE) {
            inputBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            inputBitmap
        }
        val outputBitmap = safeBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (safeBitmap != inputBitmap) {
            try {
                safeBitmap.recycle()
            } catch (ignored: Exception) {}
        }
        val canvas = Canvas(outputBitmap)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }

        val maskPaint = Paint().apply {
            color = maskColor
            style = Paint.Style.FILL
        }

        val debugGreenPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val debugRedPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val debugOverlayPaint = Paint().apply {
            color = Color.argb(40, 255, 0, 0)
            style = Paint.Style.FILL
        }

        val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }

        // Dynamic calculation of bubble colors or solid fallback
        for (i in processableBlocks.indices) {
            val block = processableBlocks[i]
            val rect = block.boundingBox
            val translatedText = translatedTexts.getOrNull(i) ?: block.text

            // Clear original text: Paint a matching bubble background color
            // Standard manga speech bubbles are White/Cream. We expand the mask bounds slightly outwards
            // to ensure no letter edges/tailing pixels are left unmasked (Puffy Masking Expansion)
            val padX = (rect.width() * 0.05f).coerceIn(5f, 15f).toInt()
            val padY = (rect.height() * 0.05f).coerceIn(5f, 15f).toInt()
            val expandedRect = Rect(
                (rect.left - padX).coerceAtLeast(0),
                (rect.top - padY).coerceAtLeast(0),
                (rect.right + padX).coerceAtMost(inputBitmap.width),
                (rect.bottom + padY).coerceAtMost(inputBitmap.height)
            )
            canvas.drawRect(expandedRect, maskPaint)

            // Draw wrap-around translated text using StaticLayout with safe margins
            val horizontalPadding = (rect.width() * 0.08f).coerceIn(4f, 16f).toInt()
            val availableWidth = (rect.width() - (horizontalPadding * 2)).coerceAtLeast(40)
            val availableHeight = rect.height().coerceAtLeast(20)

            // Auto-scale font size to fit the bubble bounding box comfortably
            var idealFontSize = (availableHeight * 0.23f * fontSizeMultiplier).coerceIn(16f, 52f)
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

                if (staticLayout.height <= availableHeight || idealFontSize <= 13f) {
                    break
                }
                idealFontSize -= 1.5f // Decrease size if text is too tall
            } while (idealFontSize > 11f)

            // Draw StaticLayout centered vertically inside the rectangle
            canvas.save()
            val verticalTranslation = rect.top + (rect.height() - staticLayout.height) / 2f
            canvas.translate((rect.left + horizontalPadding).toFloat(), verticalTranslation)
            staticLayout.draw(canvas)
            canvas.restore()

            if (isDebugMode) {
                canvas.drawRect(rect, debugGreenPaint)
                val label = "KABUL #${i + 1}"
                val labelWidth = debugTextPaint.measureText(label)
                val labelHeight = 18f
                canvas.drawRect(
                    rect.left.toFloat(),
                    (rect.top.toFloat() - labelHeight).coerceAtLeast(0f),
                    rect.left.toFloat() + labelWidth + 8f,
                    rect.top.toFloat(),
                    Paint().apply { color = Color.parseColor("#1B5E20") }
                )
                canvas.drawText(
                    label,
                    rect.left.toFloat() + 4f,
                    (rect.top.toFloat() - 4f).coerceAtLeast(12f),
                    debugTextPaint
                )
            }
        }

        if (isDebugMode) {
            for (i in filteredBlocks.indices) {
                val block = filteredBlocks[i]
                val rect = block.boundingBox
                
                canvas.drawRect(rect, debugRedPaint)
                canvas.drawRect(rect, debugOverlayPaint)
                
                val label = "RED: ${block.filterReason}"
                val labelWidth = debugTextPaint.measureText(label)
                val labelHeight = 18f
                canvas.drawRect(
                    rect.left.toFloat(),
                    (rect.top.toFloat() - labelHeight).coerceAtLeast(0f),
                    rect.left.toFloat() + labelWidth + 8f,
                    rect.top.toFloat(),
                    Paint().apply { color = Color.parseColor("#B71C1C") }
                )
                canvas.drawText(
                    label,
                    rect.left.toFloat() + 4f,
                    (rect.top.toFloat() - 4f).coerceAtLeast(12f),
                    debugTextPaint
                )
            }
        }

        // Step 5: Save translated image to internal storage
        val directory = File(appContext.filesDir, "translated_pages")
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
            textBlocksCount = processableBlocks.size,
            savedFilePath = file.absolutePath,
            detectedAsJapanese = isActuallyJapanese
        )
    }
}
