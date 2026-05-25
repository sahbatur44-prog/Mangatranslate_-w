package com.example.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.database.AppDatabase
import kotlinx.coroutines.*
import java.io.File

class StorageManagerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var periodicJob: Job? = null

    companion object {
        private const val TAG = "StorageManagerService"
        const val ACTION_START_CLEANUP = "com.example.action.START_CLEANUP"
        
        // 7 days in milliseconds: 7 * 24 * 60 * 60 * 1000
        private const val SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000L
        
        // Periodic check interval (e.g. check every 6 hours)
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "StorageManagerService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "StorageManagerService onStartCommand with action: ${intent?.action}")
        startPeriodicCleanup()
        return START_STICKY
    }

    private fun startPeriodicCleanup() {
        if (periodicJob?.isActive == true) {
            Log.d(TAG, "Periodic cleanup job is already active.")
            return
        }

        Log.d(TAG, "Initializing periodic cleanup job.")
        periodicJob = serviceScope.launch {
            while (isActive) {
                try {
                    performCleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during direct file cleanup: ${e.message}", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun performCleanup() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing scan of 'translated_pages' directory for items older than 7 days.")
        val directory = File(filesDir, "translated_pages")
        if (!directory.exists() || !directory.isDirectory) {
            Log.d(TAG, "'translated_pages' directory does not exist yet. Skipping.")
            return@withContext
        }

        val currentTime = System.currentTimeMillis()
        val cutoffTime = currentTime - SEVEN_DAYS_MS

        val files = directory.listFiles()
        if (files == null || files.isEmpty()) {
            Log.d(TAG, "No files found in 'translated_pages'.")
            return@withContext
        }

        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.translationHistoryDao()
        var deletedCount = 0

        for (file in files) {
            if (file.isFile) {
                val lastModified = file.lastModified()
                if (lastModified < cutoffTime) {
                    val filePath = file.absolutePath
                    val isDeleted = file.delete()
                    if (isDeleted) {
                        deletedCount++
                        Log.d(TAG, "Deleted old file: ${file.name} (Age: ${(currentTime - lastModified) / (1000 * 60 * 60 * 24)} days old)")
                        try {
                            dao.deleteHistoryByFilePath(filePath)
                            Log.d(TAG, "Removed obsolete history log matching: $filePath")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to purge database registration for $filePath: ${e.message}", e)
                        }
                    } else {
                        Log.w(TAG, "Unable to delete file: ${file.name}")
                    }
                }
            }
        }
        Log.d(TAG, "Storage cleanup pass finished. Pruned a total of $deletedCount files.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "StorageManagerService being destroyed. Cancelling coroutine scopes.")
        serviceJob.cancel()
        super.onDestroy()
    }
}
