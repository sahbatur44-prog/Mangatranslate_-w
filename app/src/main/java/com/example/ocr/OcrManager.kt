package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.api.SourceLanguage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val isFiltered: Boolean = false,
    val filterReason: String = ""
)

class OcrManager(private val context: Context) {

    fun determineLanguageAuto(bitmap: Bitmap): Boolean {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val result = Tasks.await(recognizer.process(image))
            var totalLength = 0
            var spaceCount = 0
            var verticalCount = 0
            val blockCount = result.textBlocks.size

            for (block in result.textBlocks) {
                val text = block.text
                if (text.isNotBlank()) {
                    totalLength += text.replace(" ", "").length
                    spaceCount += text.count { it == ' ' }
                    val rect = block.boundingBox
                    if (rect != null) {
                        val w = rect.width()
                        val h = rect.height()
                        if (h > w * 1.5f) {
                            verticalCount++
                        }
                    }
                }
            }

            val spaceRatio = if (totalLength > 0) spaceCount.toFloat() / totalLength.toFloat() else 0f
            val verticalRatio = if (blockCount > 0) verticalCount.toFloat() / blockCount.toFloat() else 0f

            Log.d("OcrManager", "Auto-detection metrics: totalLength=$totalLength, spaceCount=$spaceCount, spaceRatio=$spaceRatio, verticalRatio=$verticalRatio, blockCount=$blockCount")

            val isJapanese = when {
                blockCount == 0 -> true
                spaceRatio < 0.08f -> true
                verticalRatio > 0.15f -> true
                else -> false
            }
            Log.d("OcrManager", "Auto-detection result: isJapanese=$isJapanese")
            isJapanese
        } catch (e: Exception) {
            Log.e("OcrManager", "Auto-language detection failed: ${e.message}", e)
            true
        } finally {
            recognizer.close()
        }
    }

    fun detectText(bitmap: Bitmap, sourceLanguage: SourceLanguage, includeFiltered: Boolean = false): Pair<List<OcrBlock>, Boolean> {
        val isJapanese = when (sourceLanguage) {
            SourceLanguage.JAPANESE -> true
            SourceLanguage.ENGLISH -> false
            SourceLanguage.AUTO_DETECT -> {
                Log.d("OcrManager", "Auto-detect selected. Running quick pre-pass character density check...")
                determineLanguageAuto(bitmap)
            }
        }

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

            var totalDetected = 0
            for (block in result.textBlocks) {
                val text = block.text
                val rect = block.boundingBox
                if (rect != null && text.isNotBlank()) {
                    totalDetected++
                    val w = rect.width()
                    val h = rect.height()
                    val area = w * h
                    val ratio = w.toFloat() / h.coerceAtLeast(1).toFloat()

                    // 1. Speck / Noise Filter: Extremely small blocks are likely dust, small panel notes, page numbers, or noise specks
                    val isTooSmall = w < 12 || h < 12 || area < 120

                    // 2. Extreme Aspect Ratio Filter:
                    // Vertical speech bubbles in Japanese manga are common, but they still have reasonable width.
                    // Sound effects or speed lines can stretch vertically or horizontally in highly skewed boxes.
                    val minRatio = if (isJapanese) 0.08f else 0.15f
                    val maxRatio = if (isJapanese) 6.0f else 8.0f
                    val isExtremeRatio = ratio < minRatio || ratio > maxRatio

                    // 3. Page Edge Headers / Full-page borders:
                    // If a text block spans nearly the entire width or height of the page, it's likely a border artifact or extreme publisher text.
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height
                    val isPageMarginArtifact = w > (bitmapWidth * 0.92) || h > (bitmapHeight * 0.92)

                    val shouldFilter = isTooSmall || isExtremeRatio || isPageMarginArtifact
                    
                    if (shouldFilter) {
                        val reason = when {
                            isTooSmall -> "Boyut (Küçük)"
                            isExtremeRatio -> "Boy Oranı (Sıradışı)"
                            isPageMarginArtifact -> "Kenar Boşluğu"
                            else -> "Bilinmeyen"
                        }
                        Log.d("OcrManager", "Filtered out non-speech-bubble block: '$text' (w=$w, h=$h, ratio=${String.format("%.2f", ratio)}, area=$area, reason=$reason)")
                        if (includeFiltered) {
                            blocks.add(OcrBlock(text = text, boundingBox = rect, isFiltered = true, filterReason = reason))
                        }
                        continue
                    }

                    blocks.add(OcrBlock(text = text, boundingBox = rect, isFiltered = false))
                }
            }
            Log.d("OcrManager", "Successfully detected and filtered blocks: $totalDetected found, ${blocks.size} kept after speech bubble filtering. Used isJapanese=$isJapanese")
            Pair(blocks, isJapanese)
        } catch (e: Exception) {
            Log.e("OcrManager", "OCR detection failed: ${e.message}", e)
            Pair(emptyList(), isJapanese)
        } finally {
            recognizer.close()
        }
    }
}
