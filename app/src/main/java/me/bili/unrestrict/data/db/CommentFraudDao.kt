package me.bili.unrestrict.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentFraudDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: CommentFraudRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<CommentFraudRecord>)

    @Query("SELECT * FROM comment_fraud_records ORDER BY CASE WHEN post_time > 0 THEN post_time ELSE timestamp END DESC")
    fun getAllRecordsFlow(): Flow<List<CommentFraudRecord>>

    @Query("SELECT * FROM comment_fraud_records ORDER BY CASE WHEN post_time > 0 THEN post_time ELSE timestamp END DESC")
    suspend fun getAllRecords(): List<CommentFraudRecord>

    @Query("SELECT * FROM comment_fraud_records WHERE rpid = :rpid LIMIT 1")
    suspend fun getRecordByRpid(rpid: Long): CommentFraudRecord?

    @Query("DELETE FROM comment_fraud_records WHERE rpid = :rpid")
    suspend fun deleteByRpid(rpid: Long)

    @Query("DELETE FROM comment_fraud_records")
    suspend fun clearAll()
}
