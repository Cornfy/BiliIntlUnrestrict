package me.bili.unrestrict.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "module_logs")
data class LogRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val level: String,
    val message: String
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogRecord)

    @Query("SELECT * FROM module_logs ORDER BY timestamp DESC LIMIT 300")
    fun getRecentLogsFlow(): Flow<List<LogRecord>>

    @Query("SELECT * FROM module_logs ORDER BY timestamp DESC LIMIT 300")
    suspend fun getRecentLogs(): List<LogRecord>

    @Query("DELETE FROM module_logs")
    suspend fun clearAll()

    // 自动清理超出 300 条的旧日志
    @Query("DELETE FROM module_logs WHERE id NOT IN (SELECT id FROM module_logs ORDER BY timestamp DESC LIMIT 300)")
    suspend fun pruneOldLogs()
}
