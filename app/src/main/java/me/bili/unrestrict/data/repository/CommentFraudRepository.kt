package me.bili.unrestrict.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bili.unrestrict.data.model.CommentBackup
import me.bili.unrestrict.data.db.AppDatabase
import me.bili.unrestrict.data.db.CommentFraudRecord
import me.bili.unrestrict.data.model.CommentFraudStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object CommentFraudRepository {

    private val writeMutex = Mutex()
    private fun undoFile(context: Context) = AtomicFile(File(context.filesDir, "comments-undo.json"))
    fun canUndo(context: Context): Boolean = undoFile(context).baseFile.exists()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun getDao(context: Context) = AppDatabase.getDatabase(context).commentFraudDao()

    suspend fun saveRecord(context: Context, record: CommentFraudRecord) = withContext(Dispatchers.IO) {
        if (record.rpid <= 0L) return@withContext
        try {
            writeMutex.withLock {
                val existing = getDao(context).getRecordByRpid(record.rpid)
                val merged = record.copy(
                    uid = if (record.uid > 0L) record.uid else (existing?.uid ?: 0L),
                    source_id = record.source_id ?: existing?.source_id,
                    origin_url = record.origin_url ?: existing?.origin_url,
                    message = record.message.ifBlank { existing?.message.orEmpty() },
                    status = if (record.fraudStatus == CommentFraudStatus.UNKNOWN && existing?.fraudStatus != null && existing.fraudStatus != CommentFraudStatus.UNKNOWN)
                        existing.status else record.status,
                    timestamp = if (record.fraudStatus == CommentFraudStatus.UNKNOWN && existing?.fraudStatus != null && existing.fraudStatus != CommentFraudStatus.UNKNOWN)
                        existing.timestamp else record.timestamp,
                    initial_status = listOf(existing?.initial_status, record.initial_status, record.status)
                        .firstOrNull { it != null && CommentFraudStatus.parse(it) != CommentFraudStatus.UNKNOWN }
                        ?: record.status,
                    post_time = if (record.post_time > 0L) record.post_time else (existing?.post_time ?: 0L)
                )
                getDao(context).insertOrUpdate(merged)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllRecordsFlow(context: Context): Flow<List<CommentFraudRecord>> {
        return getDao(context).getAllRecordsFlow()
    }

    private fun writeBackup(context: Context, bytes: ByteArray) {
        val file = undoFile(context)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }

    /** Caller holds writeMutex. Validation/merge must finish before touching the undo slot. */
    private suspend fun replaceWithUndo(context: Context, old: List<CommentFraudRecord>, next: List<CommentFraudRecord>) {
        val db = AppDatabase.getDatabase(context)
        val previous = if (canUndo(context)) undoFile(context).readFully() else null
        try {
            writeBackup(context, json.encodeToString(old).toByteArray(Charsets.UTF_8))
            db.withTransaction { db.commentFraudDao().clearAll(); db.commentFraudDao().insertAll(next) }
        } catch (e: Exception) {
            if (previous == null) undoFile(context).delete() else writeBackup(context, previous)
            throw e
        }
    }

    suspend fun deleteLocalRecord(context: Context, rpid: Long) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val old = getDao(context).getAllRecords()
            if (old.any { it.rpid == rpid }) replaceWithUndo(context, old, old.filter { it.rpid != rpid })
        }
    }

    suspend fun clearAllRecords(context: Context) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val old = getDao(context).getAllRecords()
            if (old.isNotEmpty()) replaceWithUndo(context, old, emptyList())
        }
    }

    suspend fun undoLastLocalChange(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            writeMutex.withLock {
                val old = json.decodeFromString<List<CommentFraudRecord>>(undoFile(context).readFully().toString(Charsets.UTF_8))
                val db = AppDatabase.getDatabase(context)
                db.withTransaction { getDao(context).clearAll(); getDao(context).insertAll(old) }
                undoFile(context).delete()
            }
        }
    }

    suspend fun exportToJson(context: Context): String = withContext(Dispatchers.IO) {
        CommentBackup.encode(getDao(context).getAllRecords())
    }

    suspend fun importFromJson(context: Context, jsonContent: String): Result<CommentBackup.Plan> = withContext(Dispatchers.IO) {
        runCatching {
            val imported = CommentBackup.decode(jsonContent)
            writeMutex.withLock {
                val old = getDao(context).getAllRecords()
                val plan = CommentBackup.merge(old, imported)
                if (old.associateBy { it.rpid } != plan.records.associateBy { it.rpid }) {
                    replaceWithUndo(context, old, plan.records)
                }
                plan
            }
        }
    }

    /**
     * 调用 B 站官方接口物理抹除评论
     */
    suspend fun deleteBiliComment(context: Context, record: CommentFraudRecord): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val sp = context.getSharedPreferences("module_config", Context.MODE_PRIVATE)
            val cookie = sp.getString("bili_cookie", "").orEmpty()
            val csrf = Regex("""bili_jct=([^;]+)""").find(cookie)?.groupValues?.get(1).orEmpty()

            if (csrf.isBlank()) {
                return@withContext Result.failure(Exception("未获取到 B 站登录凭证(bili_jct)，请先在 B 站发一条评论同步凭证"))
            }

            val formBody = okhttp3.FormBody.Builder()
                .add("oid", record.oid.toString())
                .add("type", record.type.toString())
                .add("rpid", record.rpid.toString())
                .add("csrf", csrf)
                .build()

            val request = Request.Builder()
                .url("https://api.bilibili.com/x/v2/reply/del")
                .post(formBody)
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", "https://www.bilibili.com")
                .header("Referer", "https://www.bilibili.com")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            val json = org.json.JSONObject(bodyStr)
            val code = json.optInt("code", -1)

            if (code == 0) {
                // 官方删评成功，同步清理本地记录
                // Remote deletion cannot be undone; do not present it as a reversible local action.
                writeMutex.withLock { getDao(context).deleteByRpid(record.rpid) }
                Result.success(Unit)
            } else {
                Result.failure(Exception(json.optString("message", "删评失败: code=$code")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 历史面板单条复检：双重视角真值裁决
     */
    suspend fun recheckRecord(context: Context, record: CommentFraudRecord): Result<CommentFraudStatus> = withContext(Dispatchers.IO) {
        try {
            val sentAt = if (record.post_time > 0L) record.post_time / 1000L else 0L
            val sp = context.getSharedPreferences("module_config", Context.MODE_PRIVATE)
            val cookie = sp.getString("bili_cookie", "").orEmpty()
            val newStatus = me.bili.unrestrict.detector.CommentProbeEngine.evaluateCommentStatus(
                context = context,
                oid = record.oid,
                type = record.type,
                rpid = record.rpid,
                root = record.root,
                sentAtSeconds = sentAt,
                cookie = cookie
            )

            writeMutex.withLock {
                val current = getDao(context).getRecordByRpid(record.rpid)
                // Do not resurrect a record removed while the request was in flight.
                if (current != null && (newStatus != CommentFraudStatus.UNKNOWN || current.fraudStatus == CommentFraudStatus.UNKNOWN)) {
                    getDao(context).insertOrUpdate(current.copy(
                        status = newStatus.name,
                        initial_status = if (current.initialFraudStatus == null || current.initialFraudStatus == CommentFraudStatus.UNKNOWN)
                            newStatus.name else current.initial_status,
                        timestamp = System.currentTimeMillis()))
                }
            }
            Result.success(newStatus)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
