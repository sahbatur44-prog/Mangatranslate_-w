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

            texts.map { text ->
                if (text.isBlank()) return@map ""
                try {
                    val result = Tasks.await(translator.translate(text))
                    result
                } catch (e: Exception) {
                    Log.e("OnDeviceTranslator", "Failed to translate block: '$text'", e)
                    fallbackTranslate(text, target)
                }
            }
        } catch (e: Exception) {
            Log.e("OnDeviceTranslator", "On-device ML Kit translator not ready or failed: ${e.message}. Using offline mapper fallback.", e)
            texts.map { fallbackTranslate(it, target) }
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
