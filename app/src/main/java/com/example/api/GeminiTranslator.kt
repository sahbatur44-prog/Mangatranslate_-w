package com.example.api

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Gemini API Request Models (Moshi Compatible) ---

data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null
)

data class Content(
    @Json(name = "parts") val parts: List<Part>
)

data class Part(
    @Json(name = "text") val text: String? = null
)

data class GenerationConfig(
    @Json(name = "responseMimeType") val responseMimeType: String? = null,
    @Json(name = "temperature") val temperature: Float? = null
)

// --- Gemini API Response Models (Moshi Compatible) ---

data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>?
)

data class Candidate(
    @Json(name = "content") val content: Content?
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun translateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d("GeminiTranslator", "Sending request: ${request.url}")
            chain.proceed(request)
        }
        .build()

    val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }
}

class GeminiTranslator {
    fun translateTextsOffline(texts: List<String>): List<String> {
        return texts.map { text ->
            val trimmed = text.trim().lowercase()
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
                else -> {
                    // Prepend elegant [Sanal] label to demonstrate speech bubble substitution
                    "[Sanal: $text]"
                }
            }
        }
    }

    suspend fun translateTexts(texts: List<String>, sourceLang: String): List<String> {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("GeminiTranslator", "API Key is missing or default placeholder value.")
            // Fast fallback or mock-simulation warning
            return texts.map { "[Key Eksik] $it" }
        }

        // Build list description as JSON-like list
        val stringifiedList = texts.mapIndexed { index, s -> "\"$index\": \"$s\"" }.joinToString(",\n")
        val prompt = """
            Sana manga/webtoon konuşma balonlarından çıkarılmış metinlerin indeksli bir listesini veriyorum.
            Bu metinleri bağlamlarına uygun olarak, konuşma akışını ve manga dil mantığını gözeterek son derece doğal, sürükleyici ve akıcı bir Türkçe'ye çevir.
            
            Giriş Metin Listesi:
            {
            $stringifiedList
            }
            
            Çevirileri KESİNLİKLE sadece orijinal indekslerin sırasına karşılık gelen bir JSON string dizisi (Array) halinde döndür. 
            Çıktı sadece ve sadece şu yapıda olmalıdır:
            [
              "çevrilmiş_metin_0",
              "çevrilmiş_metin_1",
              ...
            ]
            
            Hiçbir markdown tagı (```json gibi), ek açıklama veya önsöz/sonsöz ekleme. Sadece saf JSON array döndür. 
            Eğer bir metin boş veya çevrilemez bir çizim öğesi ise, onu boş dize "" olarak bırak.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = prompt)))
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.3f
            ),
            systemInstruction = Content(
                parts = listOf(Part(text = "Sen profesyonel bir Japonca, Korece ve İngilizce manga/webtoon Türkçe çevirmenisin. Diyalogları doğal manga diliyle çevirirsin."))
            )
        )

        return try {
            val response = RetrofitClient.service.translateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                Log.d("GeminiTranslator", "Gemini raw response: $jsonText")
                // Parse JSON text as array of strings
                val parsedList = try {
                    val listType = com.squareup.moshi.Types.newParameterizedType(List::class.java, String::class.java)
                    val adapter = RetrofitClient.moshi.adapter<List<String>>(listType)
                    adapter.fromJson(jsonText) ?: emptyList()
                } catch (e: Exception) {
                    Log.e("GeminiTranslator", "JSON parsing error: ${e.message}. Attempting simple split.")
                    extractArrayByRegex(jsonText)
                }

                if (parsedList.size == texts.size) {
                    parsedList
                } else {
                    Log.w("GeminiTranslator", "Response size mismatch. Expected ${texts.size}, got ${parsedList.size}. Merging...")
                    texts.mapIndexed { index, originalValue ->
                        parsedList.getOrNull(index) ?: "[Çevrilemedi: $originalValue]"
                    }
                }
            } else {
                Log.e("GeminiTranslator", "No response text candidate returned.")
                texts.map { "[Hata: Yanıt Boş]" }
            }
        } catch (e: Exception) {
            Log.e("GeminiTranslator", "Translation API failed: ${e.message}", e)
            texts.map { "[Bağlantı Hatası: ${e.localizedMessage}]" }
        }
    }

    /**
     * Regex fallback in case Gemini returns a slightly dirty JSON or malformed string array.
     */
    private fun extractArrayByRegex(jsonText: String): List<String> {
        val result = mutableListOf<String>()
        val regex = "\"[^\"]*\"".toRegex()
        val matches = regex.findAll(jsonText)
        for (match in matches) {
            val cleaned = match.value.trim('\"')
            result.add(cleaned)
        }
        return result
    }
}
