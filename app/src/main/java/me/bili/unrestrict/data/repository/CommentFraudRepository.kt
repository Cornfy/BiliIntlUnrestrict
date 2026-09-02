package me.bili.unrestrict.data.repository

import android.content.Context
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
            val existing = getDao(context).getRecordByRpid(record.rpid)
            val merged = record.copy(
                uid = if (record.uid > 0L) record.uid else (existing?.uid ?: 0L),
                source_id = record.source_id ?: existing?.source_id,
                origin_url = record.origin_url ?: existing?.origin_url,
                message = record.message.ifBlank { existing?.message.orEmpty() },
                initial_status = (record.initial_status ?: existing?.initial_status) ?: record.status,
                post_time = if (record.post_time > 0L) record.post_time else (existing?.post_time ?: 0L)
            )
            getDao(context).insertOrUpdate(merged)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllRecordsFlow(context: Context): Flow<List<CommentFraudRecord>> {
        return getDao(context).getAllRecordsFlow()
    }

    suspend fun deleteLocalRecord(context: Context, rpid: Long) = withContext(Dispatchers.IO) {
        getDao(context).deleteByRpid(rpid)
    }

    suspend fun clearAllRecords(context: Context) = withContext(Dispatchers.IO) {
        getDao(context).clearAll()
    }

    suspend fun exportToJson(context: Context): String = withContext(Dispatchers.IO) {
        val records = getDao(context).getAllRecords()
        json.encodeToString(records)
    }

    suspend fun importFromJson(context: Context, jsonContent: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val importedList = json.decodeFromString<List<CommentFraudRecord>>(jsonContent)
            if (importedList.isNotEmpty()) {
                getDao(context).insertAll(importedList)
            }
            Result.success(importedList.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 路人视角单条复检（完全对齐 biliSendCheck 标准 RawCurl 伪装与真值裁决）
     */
    suspend fun recheckRecord(context: Context, record: CommentFraudRecord): Result<CommentFraudStatus> = withContext(Dispatchers.IO) {
        try {
            val url = if (record.root > 0L) {
                // 楼中楼
                "https://api.bilibili.com/x/v2/reply/reply?oid=${record.oid}&type=${record.type}&root=${record.root}&ps=20&pn=1"
            } else {
                // 根评论
                "https://api.bilibili.com/x/v2/reply/main?oid=${record.oid}&type=${record.type}&mode=2&next=0&seek_rpid=${record.rpid}&ps=20"
            }

            // 构造 100% 对齐 Web 浏览器端标准的抓包报文
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", "https://www.bilibili.com")
                .header("Referer", "https://www.bilibili.com")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()

            val rpidPattern = """"rpid":\s*${record.rpid}""".toRegex()

            val newStatus = when {
                // 1. 成功在路人视角找到 RPID -> 正常
                rpidPattern.containsMatchIn(body) -> CommentFraudStatus.NORMAL
                // 2. 接口明确提示 12022 -> 已失效
                body.contains("\"code\":12022") || body.contains("\"code\": 12022") -> CommentFraudStatus.DELETED
                // 3. 只有当接口正常返回 0 且里面确实没有该评论，才是真 ShadowBan
                body.contains("\"code\":0") || body.contains("\"code\": 0") -> CommentFraudStatus.SHADOW_BANNED
                // 4. 其余情况 (WAF、-352 等)，归为 UNKNOWN 绝不误判
                else -> CommentFraudStatus.UNKNOWN
            }

            val updated = record.copy(
                status = newStatus.name,
                timestamp = System.currentTimeMillis()
            )
            getDao(context).insertOrUpdate(updated)
            Result.success(newStatus)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
