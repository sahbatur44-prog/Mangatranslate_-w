package com.example.api

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OnDeviceTranslator {
    
    private fun mapSourceLang(source: SourceLanguage): String {
        return when (source) {
            SourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
            SourceLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            SourceLanguage.AUTO_DETECT -> TranslateLanguage.JAPANESE
        }
    }

    private fun mapTargetLang(target: TargetLanguage): String {
        return when (target) {
            TargetLanguage.TURKISH -> TranslateLanguage.TURKISH
            TargetLanguage.ENGLISH -> TranslateLanguage.ENGLISH
            TargetLanguage.GERMAN -> TranslateLanguage.GERMAN
        }
    }

    fun cleanInputText(text: String, isJapanese: Boolean): String {
        if (text.isBlank()) return ""
        var cleaned = text.trim()
        if (isJapanese) {
            // Remove all spaces inside Japanese words because Japanese doesn't use spacing,
            // but OCR often segments characters with spaces when reading vertically
            cleaned = cleaned.replace(" ", "")
            cleaned = cleaned.replace("　", "") // Japanese full-width space
            cleaned = cleaned.replace("\n", "") // Remove newlines to keep sentence flow
            cleaned = cleaned.replace("\r", "")
        } else {
            // English / Latin lines:
            // Remove hyphenated newline breaks: e.g., "beauti- \n ful" -> "beautiful"
            cleaned = cleaned.replace(Regex("-\\s*\\n\\s*"), "")
            // Replace remaining newlines with spaces
            cleaned = cleaned.replace(Regex("\\s*\\n\\s*"), " ")
            // Reduce multiple spaces to single
            cleaned = cleaned.replace(Regex("\\s+"), " ")
        }
        return cleaned.trim()
    }

    fun applyContextualGlossary(text: String, isJapanese: Boolean, targetLang: TargetLanguage): String? {
        if (targetLang != TargetLanguage.TURKISH) return null
        
        val trimmed = text.trim().lowercase()
        // Japanese sound effects & expressions often repeated or missed by ML Kit
        if (isJapanese) {
            when {
                trimmed == "え" || trimmed == "え？" || trimmed == "えっ" || trimmed == "えっ？" -> return "Ha?!"
                trimmed == "何" || trimmed == "なに" || trimmed == "なに？" || trimmed == "ナニ" -> return "Ne?!"
                trimmed == "バカ" || trimmed == "ばか" -> return "Aptal!"
                trimmed == "助けて" || trimmed == "たすけて" -> return "Yardım et!"
                trimmed == "逃げろ" || trimmed == "にげろ" -> return "Kaç!"
                trimmed == "あ" || trimmed == "あっ" -> return "Ah!"
                trimmed == "きゃあ" || trimmed == "キャー" -> return "Kyaaa!"
                trimmed == "ゴゴゴ" -> return "GOGOGO! (Uğultu)"
                trimmed == "ドキドキ" -> return "Küt küt (Kalp atışı)"
                trimmed == "ニコ" -> return "Gülümseme"
                trimmed == "シーン" -> return "Sessizlik..."
                trimmed.contains("お兄ちゃん") || trimmed.contains("おにいちゃん") -> return "Ağabey!"
                trimmed.contains("お姉ちゃん") || trimmed.contains("おねeちゃん") || trimmed.contains("おねえちゃん") -> return "Abla!"
            }
        } else {
            // English typical manga-isms or stutter phrases
            when {
                trimmed.startsWith("n-nani") || trimmed == "nani?!" -> return "N-Ne?!"
                trimmed.startsWith("w-what") -> return "N-Ne?!"
                trimmed.startsWith("w-wait") -> return "B-Bekle..."
                trimmed.startsWith("d-don't") -> return "Y-Yapma..."
                trimmed.startsWith("s-stop") -> return "D-Dur..."
                trimmed.startsWith("p-please") -> return "L-Lütfen..."
                trimmed.startsWith("n-no") -> return "H-Hayır..."
                trimmed.startsWith("y-yes") -> return "E-Evet..."
                trimmed == "dummy" || trimmed == "jerk" -> return "Pislik!"
                trimmed == "gogogo" -> return "GOGOGO..."
                trimmed == "sigh" -> return "*İç çeker*"
                trimmed == "gasp" -> return "*Nefesi kesilir*"
                trimmed == "pant" || trimmed == "pant pant" -> return "*Soluk soluğa*"
                trimmed == "thump" || trimmed == "thump thump" -> return "*Küt küt*"
            }
        }
        return null
    }

    fun formatTranslatedOutput(text: String, targetLang: TargetLanguage): String {
        if (text.isBlank()) return ""
        var formatted = text.trim()
        
        // 1. Correct Turkish character spacing for punctuation
        // e.g. "Evet !", "Ne ?" -> "Evet!", "Ne?"
        formatted = formatted.replace(Regex("\\s+([!?,.])"), "$1")
        // e.g. "..." preceded by spaces
        formatted = formatted.replace(Regex("\\s+(\\.\\.\\.)"), "$1")
        
        // 2. Fix Turkish translations of typical question words that end up as active statements sometimes
        if (targetLang == TargetLanguage.TURKISH) {
            if (formatted.lowercase() == "gerçekten") formatted = "Gerçekten mi?"
            if (formatted.lowercase() == "neden") formatted = "Neden?"
            if (formatted.lowercase() == "nereye") formatted = "Nereye?"
            if (formatted.lowercase() == "nasıl") formatted = "Nasıl?"
            if (formatted.lowercase() == "kim") formatted = "Kim?"
            if (formatted.lowercase() == "ne") formatted = "Ne?"
        }

        // 3. Sentence-level capitalization
        // Capitalize the very first character of the string if it's a letter
        if (formatted.isNotEmpty() && formatted[0].isLowerCase()) {
            formatted = formatted.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        // Capitalize after periods, exclamations, question marks
        // Regex to match . ! ? followed by space and a lowercase letter
        val pattern = Regex("([.!?]\\s+)([a-zçğıöşü])")
        formatted = formatted.replace(pattern) { matchResult ->
            val punctuationAndSpace = matchResult.groupValues[1]
            val lowercaseLetter = matchResult.groupValues[2]
            punctuationAndSpace + lowercaseLetter.uppercase()
        }

        return formatted
    }

    fun advancedFallbackTranslate(text: String, isJapanese: Boolean, targetLang: TargetLanguage): String {
        // First try custom contextual glossary
        val glossaryMatch = applyContextualGlossary(text, isJapanese, targetLang)
        if (glossaryMatch != null) return glossaryMatch

        val trimmed = text.trim().lowercase()
        // If it's Turkish target, provide sensible manga/manhwa translations of standard words
        if (targetLang == TargetLanguage.TURKISH) {
            return when {
                trimmed.contains("hello") || trimmed.contains("konnichi") || trimmed.contains("moshi") -> "Merhaba!"
                trimmed.contains("thank") || trimmed.contains("arigat") -> "Teşekkürler!"
                trimmed.contains("sorry") || trimmed.contains("gomen") || trimmed.contains("sumimasen") -> "Üzgünüm!"
                trimmed.contains("excuse") -> "Affedersiniz!"
                trimmed.contains("idiot") || trimmed.contains("baka") -> "Aptal!"
                trimmed.contains("what") || trimmed.contains("nani") -> "Ne?!"
                trimmed.contains("why") -> "Neden?"
                trimmed.contains("who") -> "Kim?"
                trimmed.contains("where") -> "Nerede?"
                trimmed.contains("how") -> "Nasıl?"
                trimmed.contains("wow") || trimmed.contains("sugoi") -> "Harika!"
                trimmed.contains("really") || trimmed.contains("hontou") -> "Gerçekten mi?"
                trimmed.contains("cute") || trimmed.contains("kawaii") -> "Çok şirin!"
                trimmed.contains("no") && trimmed.length < 5 -> "Hayır!"
                trimmed.contains("yes") || trimmed.contains("hai") -> "Evet!"
                trimmed.contains("stop") || trimmed.contains("yamete") -> "Dur!"
                trimmed.contains("wait") || trimmed.contains("matte") -> "Bekle!"
                trimmed.contains("help") || trimmed.contains("tasukete") -> "Yardım et!"
                trimmed.contains("run") || trimmed.contains("nigero") -> "Kaç!"
                trimmed.contains("look") || trimmed.contains("mite") -> "Bak!"
                trimmed.contains("listen") || trimmed.contains("kiite") -> "Dinle!"
                trimmed.contains("die") || trimmed.contains("shine") -> "Öl!"
                trimmed.contains("impossible") || trimmed.contains("masaka") -> "İmkansız!"
                trimmed.contains("dangerous") || trimmed.contains("abunai") -> "Tehlikeli!"
                trimmed.contains("love") || trimmed.contains("suki") || trimmed.contains("aishite") -> "Seni seviyorum."
                trimmed.contains("hate") -> "Nefret ediyorum!"
                trimmed.contains("happy") || trimmed.contains("ureshii") -> "Çok mutluyum!"
                trimmed.contains("sad") -> "Üzgün..."
                trimmed.contains("scared") || trimmed.contains("kowai") -> "Korkunç!"
                trimmed.contains("good morning") || trimmed.contains("ohayou") -> "Günaydın!"
                trimmed.contains("good night") || trimmed.contains("oyasumi") -> "İyi geceler!"
                trimmed.contains("understand") || trimmed.contains("wakatta") -> "Anladım."
                trimmed.contains("don't know") -> "Bilmiyorum."
                trimmed.contains("delicious") || trimmed.contains("oishii") -> "Lezzetli!"
                trimmed.contains("farewell") || trimmed.contains("sayonara") -> "Elveda!"
                trimmed.contains("hurt") || trimmed.contains("itai") -> "Acıyor!"
                trimmed.contains("awesome") || trimmed.contains("kakkoii") -> "Çok havalı!"
                trimmed.contains("secret") || trimmed.contains("himitsu") -> "Sır!"
                trimmed.contains("promise") || trimmed.contains("yakusoku") -> "Söz mü?"
                trimmed.contains("monster") || trimmed.contains("bakemono") -> "Canavar!"
                trimmed.contains("friend") || trimmed.contains("tomodachi") -> "Arkadaş"
                trimmed.contains("family") || trimmed.contains("kazoku") -> "Aile"
                trimmed.contains("strong") || trimmed.contains("tsuyoi") -> "Güçlü!"
                trimmed.contains("weak") -> "Zayıf..."
                trimmed.contains("miracle") -> "Mucize!"
                else -> text
            }
        }
        
        return fallbackTranslate(text, targetLang)
    }

    suspend fun translate(
        texts: List<String>,
        source: SourceLanguage,
        isActuallyJapanese: Boolean,
        target: TargetLanguage
    ): List<String> = withContext(Dispatchers.IO) {
        val srcLangCode = if (source == SourceLanguage.AUTO_DETECT) {
            if (isActuallyJapanese) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH
        } else {
            mapSourceLang(source)
        }
        val targetLangCode = mapTargetLang(target)

        Log.d("OnDeviceTranslator", "Translating on-device from $srcLangCode to $targetLangCode")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcLangCode)
            .setTargetLanguage(targetLangCode)
            .build()
        
        val translator = Translation.getClient(options)
        
        try {
            val conditions = DownloadConditions.Builder().build()
            Log.d("OnDeviceTranslator", "Checking / Downloading on-device models...")
            Tasks.await(translator.downloadModelIfNeeded(conditions))
            Log.d("OnDeviceTranslator", "On-device models are ready. Starting translations...")

            texts.map { rawText ->
                if (rawText.isBlank()) return@map ""
                // 1. Clean input text
                val cleanedText = cleanInputText(rawText, isActuallyJapanese)
                if (cleanedText.isBlank()) return@map ""

                // 2. Custom glossary bypass for high accuracy
                val glossaryMatch = applyContextualGlossary(cleanedText, isActuallyJapanese, target)
                if (glossaryMatch != null) {
                    return@map formatTranslatedOutput(glossaryMatch, target)
                }

                try {
                    val result = Tasks.await(translator.translate(cleanedText))
                    // 3. Post-process translated output
                    formatTranslatedOutput(result, target)
                } catch (e: Exception) {
                    Log.e("OnDeviceTranslator", "Failed to translate block: '$cleanedText'", e)
                    val fallback = advancedFallbackTranslate(cleanedText, isActuallyJapanese, target)
                    formatTranslatedOutput(fallback, target)
                }
            }
        } catch (e: Exception) {
            Log.e("OnDeviceTranslator", "On-device ML Kit translator not ready or failed: ${e.message}. Using offline mapper fallback.", e)
            texts.map { rawText ->
                val cleanedText = cleanInputText(rawText, isActuallyJapanese)
                val fallback = advancedFallbackTranslate(cleanedText, isActuallyJapanese, target)
                formatTranslatedOutput(fallback, target)
            }
        } finally {
            translator.close()
        }
    }

    fun fallbackTranslate(text: String, targetLang: TargetLanguage): String {
        val trimmed = text.trim().lowercase()
        return when (targetLang) {
            TargetLanguage.TURKISH -> {
                when {
                    trimmed.contains("what") || trimmed.contains("nani") -> "Ne?!"
                    trimmed.contains("no") && trimmed.length < 5 -> "Hayır!"
                    trimmed.contains("yes") || trimmed.contains("hai") -> "Evet!"
                    trimmed.contains("huh") || trimmed.contains("e?") -> "Ha?!"
                    trimmed.contains("stop") || trimmed.contains("yamete") -> "Dur!"
                    trimmed.contains("wait") || trimmed.contains("matte") -> "Bekle..."
                    trimmed.contains("know") -> "Biliyorum."
                    trimmed.contains("think") -> "Bence..."
                    trimmed.contains("really") -> "Gerçekten mi?"
                    trimmed.contains("thank") || trimmed.contains("arigat") -> "Teşekkürler!"
                    trimmed.contains("sorry") || trimmed.contains("gomen") -> "Üzgünüm!"
                    trimmed.contains("idiot") || trimmed.contains("baka") -> "Aptal!"
                    trimmed.contains("wow") || trimmed.contains("sugoi") -> "Harika!"
                    trimmed.contains("cute") || trimmed.contains("kawaii") -> "Sevimli!"
                    trimmed.contains("hello") || trimmed.contains("konnichi") -> "Merhaba!"
                    trimmed.contains("die") || trimmed.contains("shine") -> "Öl!"
                    trimmed.contains("why") || trimmed.contains("douse") -> "Neden?"
                    trimmed.contains("help") || trimmed.contains("tasukete") -> "Yardım et!"
                    trimmed.contains("run") || trimmed.contains("nigero") -> "Kaç!"
                    else -> "[Sanal: $text]"
                }
            }
            TargetLanguage.ENGLISH -> {
                when {
                    trimmed.contains("what") || trimmed.contains("nani") -> "What?!"
                    trimmed.contains("no") && trimmed.length < 5 -> "No!"
                    trimmed.contains("yes") || trimmed.contains("hai") -> "Yes!"
                    trimmed.contains("huh") || trimmed.contains("e?") -> "Huh?!"
                    trimmed.contains("stop") || trimmed.contains("yamete") -> "Stop!"
                    trimmed.contains("wait") || trimmed.contains("matte") -> "Wait..."
                    trimmed.contains("know") -> "I know."
                    trimmed.contains("think") -> "I think..."
                    trimmed.contains("really") -> "Really?"
                    trimmed.contains("thank") || trimmed.contains("arigat") -> "Thank you!"
                    trimmed.contains("sorry") || trimmed.contains("gomen") -> "Sorry!"
                    trimmed.contains("idiot") || trimmed.contains("baka") -> "Idiot!"
                    trimmed.contains("wow") || trimmed.contains("sugoi") -> "Wow!"
                    trimmed.contains("cute") || trimmed.contains("kawaii") -> "Cute!"
                    trimmed.contains("hello") || trimmed.contains("konnichi") -> "Hello!"
                    trimmed.contains("die") || trimmed.contains("shine") -> "Die!"
                    trimmed.contains("why") || trimmed.contains("douse") -> "Why?"
                    trimmed.contains("help") || trimmed.contains("tasukete") -> "Help me!"
                    trimmed.contains("run") || trimmed.contains("nigero") -> "Run!"
                    else -> "[Sanal: $text]"
                }
            }
            TargetLanguage.GERMAN -> {
                when {
                    trimmed.contains("what") || trimmed.contains("nani") -> "Was?!"
                    trimmed.contains("no") && trimmed.length < 5 -> "Nein!"
                    trimmed.contains("yes") || trimmed.contains("hai") -> "Ja!"
                    trimmed.contains("huh") || trimmed.contains("e?") -> "Hä?!"
                    trimmed.contains("stop") || trimmed.contains("yamete") -> "Stopp!"
                    trimmed.contains("wait") || trimmed.contains("matte") -> "Warte..."
                    trimmed.contains("know") -> "Ich weiß."
                    trimmed.contains("think") -> "Ich denke..."
                    trimmed.contains("really") -> "Wirklich?"
                    trimmed.contains("thank") || trimmed.contains("arigat") -> "Danke!"
                    trimmed.contains("sorry") || trimmed.contains("gomen") -> "Es tut mir leid!"
                    trimmed.contains("idiot") || trimmed.contains("baka") -> "Idiot!"
                    trimmed.contains("wow") || trimmed.contains("sugoi") -> "Wahnsinn!"
                    trimmed.contains("cute") || trimmed.contains("kawaii") -> "Süß!"
                    trimmed.contains("hello") || trimmed.contains("konnichi") -> "Hallo!"
                    trimmed.contains("die") || trimmed.contains("shine") -> "Stirb!"
                    trimmed.contains("why") || trimmed.contains("douse") -> "Warum?"
                    trimmed.contains("help") || trimmed.contains("tasukete") -> "Hilfe!"
                    trimmed.contains("run") || trimmed.contains("nigero") -> "Lauf!"
                    else -> "[Sanal: $text]"
                }
            }
        }
    }
}
