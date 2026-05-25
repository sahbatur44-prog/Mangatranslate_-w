package com.example.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "translation_history")
data class TranslationHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val filePath: String, // Local storage file path to translated image
    val timestamp: Long = System.currentTimeMillis(),
    val sourceLang: String,
    val targetLang: String
)

@Dao
interface TranslationHistoryDao {
    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranslationHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: TranslationHistory): Long

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM translation_history")
    suspend fun clearAllHistory()
}

@Database(entities = [TranslationHistory::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "manga_translator_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class TranslationRepository(private val dao: TranslationHistoryDao) {
    val allHistory: Flow<List<TranslationHistory>> = dao.getAllHistory()

    suspend fun insert(history: TranslationHistory): Long {
        return dao.insertHistory(history)
    }

    suspend fun deleteById(id: Int) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAll() {
        dao.clearAllHistory()
    }
}
