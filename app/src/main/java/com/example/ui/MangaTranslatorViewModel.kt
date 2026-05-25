package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.TranslationHistory
import com.example.database.TranslationRepository
import com.example.utils.TranslationPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TranslationUiState {
    object Idle : TranslationUiState
    data class Loading(val step: String) : TranslationUiState
    data class Success(val result: TranslationPipeline.TranslationResult) : TranslationUiState
    data class Error(val message: String) : TranslationUiState
}

class MangaTranslatorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TranslationRepository
    private val pipeline = TranslationPipeline(application)

    init {
        val database = AppDatabase.getDatabase(application)
        repository = TranslationRepository(database.translationHistoryDao())
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
    val isJapaneseMode = MutableStateFlow(true) // Toggle between Japanese/Latin ML Kit recognition
    val fontSizeMultiplier = MutableStateFlow(1.0f) // Sizing scalar for Canvas paint
    val isOfflineMode = MutableStateFlow(false) // Toggle between cloud Gemini or Offline/Sanal mapper

    fun translateLocalBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _translationUiState.value = TranslationUiState.Loading("Görüntü analiz ediliyor (OCR)...")
            try {
                // Perform translation
                val result = pipeline.translateMangaPage(
                    inputBitmap = bitmap,
                    isJapanese = isJapaneseMode.value,
                    fontSizeMultiplier = fontSizeMultiplier.value,
                    isOfflineMode = isOfflineMode.value
                )

                if (result.textBlocksCount == 0) {
                    _translationUiState.value = TranslationUiState.Error("Ekran üzerinde çevrilebilecek herhangi bir metin tespit edilemedi!")
                    return@launch
                }

                _translationUiState.value = TranslationUiState.Loading("Sonuçlar kaydediliyor...")
                
                // Add to Room history
                result.savedFilePath?.let { filePath ->
                    val sourceLangLabel = if (isJapaneseMode.value) "Japonca" else "İngilizce"
                    val offlineLabel = if (isOfflineMode.value) " (Sanal)" else ""
                    val entry = TranslationHistory(
                        title = "Manga Çevirisi$offlineLabel - ${result.textBlocksCount} Balon",
                        filePath = filePath,
                        sourceLang = sourceLangLabel,
                        targetLang = "Türkçe"
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

    fun deleteHistory(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun resetState() {
        _translationUiState.value = TranslationUiState.Idle
    }
}
