package com.example.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    INFO, WARN, ERROR, DEBUG
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatTime(): String {
        return try {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            "$timestamp"
        }
    }
}

object AppLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private const val MAX_LOGS = 300

    @Synchronized
    private fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        val current = _logs.value.toMutableList()
        current.add(0, entry) // Newest logs first
        if (current.size > MAX_LOGS) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog(LogLevel.INFO, tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog(LogLevel.DEBUG, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addLog(LogLevel.WARN, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            addLog(LogLevel.ERROR, tag, "$message\nDetail: ${throwable.localizedMessage}\n${Log.getStackTraceString(throwable)}")
        } else {
            Log.e(tag, message)
            addLog(LogLevel.ERROR, tag, message)
        }
    }

    fun clear() {
        _logs.value = emptyList()
        addLog(LogLevel.INFO, "AppLogger", "Sistem günlükleri sıfırlandı.")
    }
}
