package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.TranslationHistory
import com.example.database.TranslationRepository
import com.example.utils.TranslationPipeline
import com.example.api.TargetLanguage
import com.example.api.SourceLanguage
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LanguagePackModel(
    val code: String,
    val name: String,
    val flag: String,
    val size: String,
    val isDownloaded: Boolean,
    val isDownloading: Boolean = false,
    val error: String? = null
)

sealed interface TranslationUiState {
    object Idle : TranslationUiState
    data class Loading(val step: String) : TranslationUiState
    data class Success(val result: TranslationPipeline.TranslationResult) : TranslationUiState
    data class Error(val message: String) : TranslationUiState
}

class MangaTranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TranslationRepository
    private val pipeline = TranslationPipeline(application)

    // Dynamic StateFlow of available offline ML Kit models
    val languagePacks = MutableStateFlow<List<LanguagePackModel>>(
        listOf(
            LanguagePackModel("ja", "Japonca (Kaynak)", "🇯🇵", "54 MB", false),
            LanguagePackModel("en", "İngilizce (Kaynak / Hedef)", "🇺🇸", "30 MB", false),
            LanguagePackModel("tr", "Türkçe (Hedef)", "🇹🇷", "42 MB", false),
            LanguagePackModel("de", "Almanca (Hedef)", "🇩🇪", "40 MB", false)
        )
    )

    init {
        val database = AppDatabase.getDatabase(application)
        repository = TranslationRepository(database.translationHistoryDao())
        refreshLanguagePacks()
    }

    fun refreshLanguagePacks() {
        val modelManager = RemoteModelManager.getInstance()
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { downloadedModels ->
                val downloadedCodes = downloadedModels.map { it.language }.toSet()
                Log.d("MangaTranslatorVM", "Downloaded model languages: $downloadedCodes")
                
                val currentList = languagePacks.value
                val newList = currentList.map { pack ->
                    val isDownloadedNow = downloadedCodes.contains(pack.code)
                    pack.copy(
                        isDownloaded = isDownloadedNow,
                        isDownloading = if (isDownloadedNow) false else pack.isDownloading
                    )
                }
                languagePacks.value = newList
            }
            .addOnFailureListener { e ->
                Log.e("MangaTranslatorVM", "Failed to get downloaded models", e)
            }
    }

    private fun mapCodeToLanguage(code: String): String {
        return when (code) {
            "ja" -> TranslateLanguage.JAPANESE
            "en" -> TranslateLanguage.ENGLISH
            "tr" -> TranslateLanguage.TURKISH
            "de" -> TranslateLanguage.GERMAN
            else -> code
        }
    }

    fun downloadLanguagePack(code: String) {
        val modelManager = RemoteModelManager.getInstance()
        val langTag = mapCodeToLanguage(code)
        val model = TranslateRemoteModel.Builder(langTag).build()
        
        languagePacks.value = languagePacks.value.map {
            if (it.code == code) it.copy(isDownloading = true, error = null) else it
        }
        
        val conditions = DownloadConditions.Builder().build()
        
        modelManager.download(model, conditions)
            .addOnSuccessListener {
                Log.d("MangaTranslatorVM", "Successfully triggered download for model: $code")
                refreshLanguagePacks()
                pollModelDownload(code)
            }
            .addOnFailureListener { e ->
                Log.e("MangaTranslatorVM", "Failed to start download for model: $code", e)
                languagePacks.value = languagePacks.value.map {
                    if (it.code == code) it.copy(isDownloading = false, error = "Yükleme başarısız: ${e.localizedMessage}") else it
                }
            }
    }

    fun deleteLanguagePack(code: String) {
        val modelManager = RemoteModelManager.getInstance()
        val langTag = mapCodeToLanguage(code)
        val model = TranslateRemoteModel.Builder(langTag).build()
        
        languagePacks.value = languagePacks.value.map {
            if (it.code == code) it.copy(isDownloading = true, error = null) else it
        }
        
        modelManager.deleteDownloadedModel(model)
            .addOnSuccessListener {
                Log.d("MangaTranslatorVM", "Successfully deleted model: $code")
                refreshLanguagePacks()
            }
            .addOnFailureListener { e ->
                Log.e("MangaTranslatorVM", "Failed to delete model: $code", e)
                languagePacks.value = languagePacks.value.map {
                    if (it.code == code) it.copy(isDownloading = false, error = "Silme başarısız: ${e.localizedMessage}") else it
                }
            }
    }

    private fun pollModelDownload(code: String) {
        viewModelScope.launch {
            var attempts = 0
            while (attempts < 30) {
                kotlinx.coroutines.delay(2000)
                val modelManager = RemoteModelManager.getInstance()
                val downloadedModels = try {
                    com.google.android.gms.tasks.Tasks.await(modelManager.getDownloadedModels(TranslateRemoteModel::class.java))
                } catch (e: Exception) {
                    null
                }
                if (downloadedModels != null) {
                    val downloadedCodes = downloadedModels.map { it.language }.toSet()
                    if (downloadedCodes.contains(code)) {
                        Log.d("MangaTranslatorVM", "Poll: Model $code is now downloaded!")
                        refreshLanguagePacks()
                        break
                    }
                }
                attempts++
            }
            refreshLanguagePacks()
        }
    }

    // Reactive Flow list of compiled Translation History
    val historyList: StateFlow<List<TranslationHistory>> = repository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _translationUiState = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val translationUiState: StateFlow<TranslationUiState> = _translationUiState

    // Configurations state flows
    val selectedSourceLang = MutableStateFlow(SourceLanguage.JAPANESE) // Source language selection for OCR
    val fontSizeMultiplier = MutableStateFlow(1.0f) // Sizing scalar for Canvas paint
    val isOfflineMode = MutableStateFlow(false) // Toggle between cloud Gemini or Offline/Sanal mapper
    val isDebugOverlayEnabled = MutableStateFlow(false) // Toggle debugging visual border boxes
    val selectedTargetLang = MutableStateFlow(TargetLanguage.TURKISH) // Selected target language for translations
    val autoOfflineFallbackAlert = MutableStateFlow<String?>(null)

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    fun translateLocalBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _translationUiState.value = TranslationUiState.Loading("Görüntü analiz ediliyor (OCR)...")
            try {
                var offlineModeToUse = isOfflineMode.value
                if (!offlineModeToUse && !hasInternetConnection()) {
                    val neededCodes = mutableSetOf<String>()
                    when (selectedSourceLang.value) {
                        SourceLanguage.JAPANESE -> neededCodes.add("ja")
                        SourceLanguage.ENGLISH -> neededCodes.add("en")
                        SourceLanguage.AUTO_DETECT -> {
                            neededCodes.add("ja")
                            neededCodes.add("en")
                        }
                    }
                    neededCodes.add(selectedTargetLang.value.code)

                    val undownloaded = languagePacks.value.filter { neededCodes.contains(it.code) && !it.isDownloaded }
                    if (undownloaded.isEmpty()) {
                        isOfflineMode.value = true
                        offlineModeToUse = true
                        autoOfflineFallbackAlert.value = "İnternet bulunamadı! Çevrimdışı dil paketleriniz kurulu olduğu için otomatik olarak Cihaz İçi Mod aktif edildi."
                        Log.d("MangaTranslatorVM", "No internet. Auto fallbacked to on-device translation.")
                    } else {
                        val missingNames = undownloaded.joinToString(", ") { "${it.flag} ${it.name}" }
                        _translationUiState.value = TranslationUiState.Error(
                            "İnternet bağlantısı yok! Çevrimdışı çeviriyi başlatmak istedik ancak gerekli dil paketleri eksik: $missingNames.\n\nLütfen cihaz içi dil modellerini indirin veya internete bağlanıp tekrar deneyin."
                        )
                        return@launch
                    }
                }

                // Perform translation
                val result = pipeline.translateMangaPage(
                    inputBitmap = bitmap,
                    sourceLanguage = selectedSourceLang.value,
                    fontSizeMultiplier = fontSizeMultiplier.value,
                    isOfflineMode = offlineModeToUse,
                    isDebugMode = isDebugOverlayEnabled.value,
                    targetLang = selectedTargetLang.value
                )

                if (result.textBlocksCount == 0) {
                    _translationUiState.value = TranslationUiState.Error("Ekran üzerinde çevrilebilecek herhangi bir metin tespit edilemedi!")
                    return@launch
                }

                _translationUiState.value = TranslationUiState.Loading("Sonuçlar kaydediliyor...")
                
                // Add to Room history
                result.savedFilePath?.let { filePath ->
                    val sourceLangLabel = if (result.detectedAsJapanese) {
                        if (selectedSourceLang.value == SourceLanguage.AUTO_DETECT) "Japonca (Oto)" else "Japonca"
                    } else {
                        if (selectedSourceLang.value == SourceLanguage.AUTO_DETECT) "İngilizce (Oto)" else "İngilizce"
                    }
                    val offlineLabel = if (offlineModeToUse) " (Çevrimdışı)" else ""
                    val entry = TranslationHistory(
                        title = "Manga Çevirisi$offlineLabel - ${result.textBlocksCount} Balon",
                        filePath = filePath,
                        sourceLang = sourceLangLabel,
                        targetLang = selectedTargetLang.value.displayName
                    )
                    repository.insert(entry)
                }

                _translationUiState.value = TranslationUiState.Success(result)
            } catch (e: Exception) {
                Log.e("MangaTranslatorVM", "Translation flow failed: ${e.message}", e)
                _translationUiState.value = TranslationUiState.Error("İşlem hatası oluştu: ${e.localizedMessage}")
            }
        }
    }

    fun deleteHistory(item: TranslationHistory) {
        viewModelScope.launch {
            try {
                val file = java.io.File(item.filePath)
                if (file.exists()) {
                    file.delete()
                    Log.d("MangaTranslatorVM", "Successfully deleted translated page image: ${item.filePath}")
                }
            } catch (e: Exception) {
                Log.e("MangaTranslatorVM", "Failed to delete file on disk: ${item.filePath}", e)
            }
            repository.deleteById(item.id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            try {
                val directory = java.io.File(getApplication<Application>().filesDir, "translated_pages")
                if (directory.exists() && directory.isDirectory) {
                    directory.listFiles()?.forEach { file ->
                        file.delete()
                    }
                    Log.d("MangaTranslatorVM", "Successfully cleared translated_pages files on disk.")
                }
            } catch (e: Exception) {
                Log.e("MangaTranslatorVM", "Failed to clear physical translated pages directory", e)
            }
            repository.clearAll()
        }
    }

    fun resetState() {
        _translationUiState.value = TranslationUiState.Idle
    }
}
