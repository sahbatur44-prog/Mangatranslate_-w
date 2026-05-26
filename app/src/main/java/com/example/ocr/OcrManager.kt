package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
        Log.d("OcrManager", "Initiating pre-pass text density & directionality layout analysis on source image...")
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(safeBitmap, 0)
        return try {
            val result = Tasks.await(recognizer.process(image))
            
            val totalBlocks = result.textBlocks.size
            if (totalBlocks == 0) {
                Log.d("OcrManager", "No text detected in pre-pass layout scan. Defaulting to Japanese mode.")
                return true
            }

            var totalCharacters = 0
            var spaceChars = 0
            var alphabeticLetters = 0
            var verticalBlockCount = 0
            var horizontalBlockCount = 0
            var squareBlockCount = 0
            
            var totalBlockArea = 0L
            val imgWidth = bitmap.width.toFloat()
            val imgHeight = bitmap.height.toFloat()
            val totalImgArea = (imgWidth * imgHeight).toLong()

            // Directionality metrics inside block lines
            var verticalLineCount = 0
            var horizontalLineCount = 0

            for (block in result.textBlocks) {
                val text = block.text
                val rect = block.boundingBox
                
                if (rect != null && text.isNotBlank()) {
                    val blockW = rect.width()
                    val blockH = rect.height()
                    totalBlockArea += (blockW * blockH)

                    // Classify block directionality shape
                    val blockRatio = blockW.toFloat() / blockH.coerceAtLeast(1).toFloat()
                    when {
                        blockRatio < 0.75f -> verticalBlockCount++  // Height is noticeably greater than width
                        blockRatio > 1.33f -> horizontalBlockCount++ // Width is noticeably greater than height
                        else -> squareBlockCount++
                    }

                    // Count characters
                    totalCharacters += text.length
                    spaceChars += text.count { it == ' ' || it == '　' }
                    alphabeticLetters += text.count { it.isLetter() }

                    // Analyze directionality of lines inside the text block to detect vertical lines/columns
                    for (line in block.lines) {
                        val lineRect = line.boundingBox
                        if (lineRect != null) {
                            val lineW = lineRect.width()
                            val lineH = lineRect.height()
                            val lineRatio = lineW.toFloat() / lineH.coerceAtLeast(1).toFloat()
                            if (lineRatio < 0.8f) {
                                verticalLineCount++
                            } else if (lineRatio > 1.2f) {
                                horizontalLineCount++
                            }
                        }
                    }
                }
            }

            // Calculation of directional and density ratios
            val spaceRatio = if (totalCharacters > 0) spaceChars.toFloat() / totalCharacters.toFloat() else 0f
            val alphaRatio = if (totalCharacters > 0) alphabeticLetters.toFloat() / totalCharacters.toFloat() else 0f
            
            val verticalBlockRatio = verticalBlockCount.toFloat() / totalBlocks.toFloat()
            val horizontalBlockRatio = horizontalBlockCount.toFloat() / totalBlocks.toFloat()
            
            val totalLines = (verticalLineCount + horizontalLineCount).coerceAtLeast(1)
            val verticalLineRatio = verticalLineCount.toFloat() / totalLines.toFloat()

            // Spatial text density calculations (how dense is text clustered)
            val textCoverageRatio = totalBlockArea.toDouble() / totalImgArea.toDouble()
            val charDensityPerUnitArea = if (totalBlockArea > 0) (totalCharacters.toDouble() / totalBlockArea.toDouble()) * 1000.0 else 0.0

            Log.d("OcrManager", "--- Otomatik Tespit (Auto-Detect) Metrics ---")
            Log.d("OcrManager", "Total blocks: $totalBlocks, Total characters: $totalCharacters")
            Log.d("OcrManager", "Alphabetic ratio (Letters / Total): ${String.format("%.3f", alphaRatio)} (English target is high, Japanese target is low due to garbage Latin translations)")
            Log.d("OcrManager", "Space ratio (Spaces / Total): ${String.format("%.3f", spaceRatio)} (English target is ~0.15, Japanese is < 0.05)")
            Log.d("OcrManager", "Block Directionality: Vertical=$verticalBlockCount, Horizontal=$horizontalBlockCount, Square=$squareBlockCount (Ratio Vert=${String.format("%.2f", verticalBlockRatio)})")
            Log.d("OcrManager", "Line Directionality: Vertical=$verticalLineCount, Horizontal=$horizontalLineCount (Ratio Vert=${String.format("%.2f", verticalLineRatio)})")
            Log.d("OcrManager", "Text area coverage ratio: ${String.format("%.4f", textCoverageRatio)}")
            Log.d("OcrManager", "Character spatial packing density: ${String.format("%.4f", charDensityPerUnitArea)}")

            // Core Decision Model for Manga Layout (Weighted voting based on directionality + textual features)
            var japaneseScore = 0

            // 1. Density & spacing characteristics of Asian vertical OCR versus English words
            if (spaceRatio < 0.07f) japaneseScore += 3
            if (spaceRatio < 0.04f) japaneseScore += 2

            // 2. High alphabetic ratio represents structured English words; low alphabetic ratio represents garbled single-character blocks (typical of Japanese scanned via Latin)
            if (alphaRatio < 0.50f) japaneseScore += 3
            if (alphaRatio < 0.35f) japaneseScore += 2
            if (alphaRatio > 0.70f) japaneseScore -= 4

            // 3. Directionality features (Vertical text columns in manga vs Horizontal paragraphs)
            if (verticalBlockRatio > 0.40f) japaneseScore += 3
            if (verticalBlockRatio > 0.20f) japaneseScore += 2
            if (horizontalBlockRatio > 0.65f) japaneseScore -= 3

            // 4. Line directionality analysis (individual columns read right-to-left are taller than they are wide)
            if (verticalLineRatio > 0.30f) japaneseScore += 3
            if (verticalLineRatio > 0.50f) japaneseScore += 2
            if (verticalLineRatio < 0.10f) japaneseScore -= 2

            // Final evaluation
            val isJapanese = japaneseScore >= 2
            Log.d("OcrManager", "Decision logic score table: JapaneseScore=$japaneseScore -> Result: isJapanese=$isJapanese")
            isJapanese
        } catch (e: Exception) {
            Log.e("OcrManager", "Auto-language detection failed: ${e.message}", e)
            true // Fallback to Japanese by default for manga reader app
        } finally {
            recognizer.close()
        }
    }

    fun preprocessBitmapForOcr(src: Bitmap): Bitmap {
        Log.d("OcrManager", "Applying grayscale high-contrast pre-processing for better OCR...")
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint().apply {
            val colorMatrix = ColorMatrix().apply {
                setSaturation(0f) // Grayscale
                val scale = 2.2f
                val translate = -110f
                val mat = floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
                set(mat)
            }
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    fun isSpeechNoise(text: String, isJapanese: Boolean): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        
        // Remove typical non-word action characters, SFX markers, panel outlines
        val cleanSymbols = trimmed.replace(Regex("[!?.\\-~_\\s|/\\\\(),:;*\"]"), "")
        if (cleanSymbols.isEmpty()) return true
        
        if (isJapanese) {
            val onlyLines = trimmed.all { it == 'ー' || it == '｜' || it == '…' || it == ' ' || it == '•' || it == 'っ' || it == 'ッ' }
            if (onlyLines) return true
        } else {
            val hasLetters = trimmed.any { it.isLetter() }
            if (!hasLetters) return true
        }
        return false
    }

    fun mergeProximityBlocks(blocks: List<OcrBlock>, isJapanese: Boolean): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        Log.d("OcrManager", "Running bubble proximity clustering on ${blocks.size} blocks...")

        val n = blocks.size
        val visited = BooleanArray(n) { false }
        val clusters = mutableListOf<List<OcrBlock>>()

        // Find connected components (clusters of blocks that are in close proximity)
        for (i in 0 until n) {
            if (visited[i]) continue
            
            val clusterBlocks = mutableListOf<OcrBlock>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            visited[i] = true

            while (queue.isNotEmpty()) {
                val currIdx = queue.removeFirst()
                val currBlock = blocks[currIdx]
                clusterBlocks.add(currBlock)

                for (j in 0 until n) {
                    if (!visited[j]) {
                        val candidate = blocks[j]
                        if (shouldMerge(currBlock.boundingBox, candidate.boundingBox, isJapanese)) {
                            visited[j] = true
                            queue.add(j)
                        }
                    }
                }
            }
            clusters.add(clusterBlocks)
        }

        val mergedList = mutableListOf<OcrBlock>()
        for (cluster in clusters) {
            if (cluster.isEmpty()) continue

            // 1. Calculate overall bounding box encompassing current cluster
            var minLeft = Int.MAX_VALUE
            var minTop = Int.MAX_VALUE
            var maxRight = Int.MIN_VALUE
            var maxBottom = Int.MIN_VALUE
            var anyFiltered = false
            var filterReason = ""

            for (block in cluster) {
                val rect = block.boundingBox
                if (rect.left < minLeft) minLeft = rect.left
                if (rect.top < minTop) minTop = rect.top
                if (rect.right > maxRight) maxRight = rect.right
                if (rect.bottom > maxBottom) maxBottom = rect.bottom
                if (block.isFiltered) {
                    anyFiltered = true
                    filterReason = block.filterReason
                }
            }
            val mergedRect = Rect(minLeft, minTop, maxRight, maxBottom)

            // 2. Perform layout directionality text merge
            val mergedText = if (isJapanese) {
                // Vertical layout: Right-to-Left vertical columns. Inside each column: Top-to-Bottom character cells/sub-blocks.
                val sortedByRightToLeft = cluster.sortedByDescending { it.boundingBox.centerX() }
                val columns = mutableListOf<MutableList<OcrBlock>>()
                
                for (block in sortedByRightToLeft) {
                    var assignedColumn: MutableList<OcrBlock>? = null
                    val bRect = block.boundingBox
                    for (col in columns) {
                        val overlaps = col.any { colBlock ->
                            val cRect = colBlock.boundingBox
                            val xOverlap = Math.min(cRect.right, bRect.right) - Math.max(cRect.left, bRect.left)
                            val minWidth = Math.min(cRect.width(), bRect.width())
                            // Group if substantial horizontal projection overlap, or if centerlines are within tolerance
                            xOverlap > minWidth * 0.35f || Math.abs(cRect.centerX() - bRect.centerX()) < Math.max(cRect.width(), bRect.width()) * 0.45f
                        }
                        if (overlaps) {
                            assignedColumn = col
                            break
                        }
                    }
                    if (assignedColumn != null) {
                        assignedColumn.add(block)
                    } else {
                        columns.add(mutableListOf(block))
                    }
                }

                // Sort columns top-to-bottom
                for (col in columns) {
                    col.sortBy { it.boundingBox.top }
                }

                // Join right-to-left columns gracefully
                columns.joinToString("\n") { col ->
                    col.joinToString("\n") { it.text }
                }
            } else {
                // Horizontal layout: Top-to-Bottom blocks. Inside each row: Left-to-Right words.
                val sortedByTopToBottom = cluster.sortedBy { it.boundingBox.top }
                val rows = mutableListOf<MutableList<OcrBlock>>()

                for (block in sortedByTopToBottom) {
                    var assignedRow: MutableList<OcrBlock>? = null
                    val bRect = block.boundingBox
                    for (row in rows) {
                        val overlaps = row.any { rowBlock ->
                            val rRect = rowBlock.boundingBox
                            val yOverlap = Math.min(rRect.bottom, bRect.bottom) - Math.max(rRect.top, bRect.top)
                            val minHeight = Math.min(rRect.height(), bRect.height())
                            yOverlap > minHeight * 0.35f || Math.abs(rRect.centerY() - bRect.centerY()) < Math.max(rRect.height(), bRect.height()) * 0.45f
                        }
                        if (overlaps) {
                            assignedRow = row
                            break
                        }
                    }
                    if (assignedRow != null) {
                        assignedRow.add(block)
                    } else {
                        rows.add(mutableListOf(block))
                    }
                }

                // Sort rows left-to-right
                for (row in rows) {
                    row.sortBy { it.boundingBox.left }
                }

                // Join top-to-bottom rows gracefully
                rows.joinToString(" ") { row ->
                    row.joinToString(" ") { it.text }
                }
            }

            mergedList.add(
                OcrBlock(
                    text = mergedText,
                    boundingBox = mergedRect,
                    isFiltered = anyFiltered,
                    filterReason = filterReason
                )
            )
        }
        Log.d("OcrManager", "Bubble proximity clustering completed. Reduced from ${blocks.size} to ${mergedList.size} blocks.")
        return mergedList
    }

    private fun shouldMerge(r1: Rect, r2: Rect, isJapanese: Boolean): Boolean {
        // Multi-line vertical text columns tend to be closer horizontally and overlap more vertically.
        // Multi-line horizontal paragraphs tend to be closer vertically and overlap horizontally.
        val thresholdX = if (isJapanese) 40 else 25
        val thresholdY = if (isJapanese) 25 else 40
        
        val xDistance = Math.max(0, Math.max(r1.left - r2.right, r2.left - r1.right))
        val yDistance = Math.max(0, Math.max(r1.top - r2.bottom, r2.top - r1.bottom))
        
        return xDistance <= thresholdX && yDistance <= thresholdY
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

        // Apply contrast preprocessing specifically for OCR recognition
        val processedBitmap = preprocessBitmapForOcr(bitmap)
        val image = InputImage.fromBitmap(processedBitmap, 0)
        return try {
            val result = Tasks.await(recognizer.process(image))
            val rawBlocks = mutableListOf<OcrBlock>()

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
                    val minRatio = if (isJapanese) 0.08f else 0.15f
                    val maxRatio = if (isJapanese) 6.0f else 8.0f
                    val isExtremeRatio = ratio < minRatio || ratio > maxRatio

                    // 3. Page Edge Headers / Full-page borders:
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height
                    val isPageMarginArtifact = w > (bitmapWidth * 0.92) || h > (bitmapHeight * 0.92)

                    // 4. Custom SFX & Raw lines filter
                    val isNoise = isSpeechNoise(text, isJapanese)

                    val shouldFilter = isTooSmall || isExtremeRatio || isPageMarginArtifact || isNoise
                    
                    if (shouldFilter) {
                        val reason = when {
                            isTooSmall -> "Boyut (Küçük)"
                            isExtremeRatio -> "Boy Oranı (Sıradışı)"
                            isPageMarginArtifact -> "Kenar Boşluğu"
                            isNoise -> "Çizgi/SFX Gürültüsü"
                            else -> "Bilinmeyen"
                        }
                        Log.d("OcrManager", "Filtered out non-speech-bubble block: '$text' (w=$w, h=$h, ratio=${String.format("%.2f", ratio)}, area=$area, reason=$reason)")
                        if (includeFiltered) {
                            rawBlocks.add(OcrBlock(text = text, boundingBox = rect, isFiltered = true, filterReason = reason))
                        }
                        continue
                    }

                    rawBlocks.add(OcrBlock(text = text, boundingBox = rect, isFiltered = false))
                }
            }

            // Distinguish between filtered and accepted blocks for clean merge operation
            val acceptedList = rawBlocks.filter { !it.isFiltered }
            val filteredList = rawBlocks.filter { it.isFiltered }

            // Group close-proximity blocks into cohesive speech bubbles!
            val mergedAcceptedList = mergeProximityBlocks(acceptedList, isJapanese)

            val finalBlocks = if (includeFiltered) {
                mergedAcceptedList + filteredList
            } else {
                mergedAcceptedList
            }

            Log.d("OcrManager", "Successfully detected and filtered blocks: $totalDetected found, ${finalBlocks.size} merged & kept after speech bubble filtering. Used isJapanese=$isJapanese")
            Pair(finalBlocks, isJapanese)
        } catch (e: Exception) {
            Log.e("OcrManager", "OCR detection failed: ${e.message}", e)
            Pair(emptyList(), isJapanese)
        } finally {
            recognizer.close()
            // Recyclability optimization: processedBitmap was created dynamically
            try {
                if (processedBitmap != bitmap) {
                    processedBitmap.recycle()
                }
            } catch (ignored: Exception) {}
        }
    }
}
