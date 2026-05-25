package com.example.api

enum class SourceLanguage(val displayName: String, val description: String) {
    JAPANESE("Japonca", "Japonca (Manga)"),
    ENGLISH("İngilizce", "İngilizce (Webtoon)"),
    AUTO_DETECT("Otomatik", "Otomatik Tespit (Yoğunluk Analizi)")
}
