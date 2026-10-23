package me.bili.unrestrict.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.bili.unrestrict.data.db.CommentFraudRecord

/** Legacy-compatible arrays with numeric IDs; import also accepts decimal-string IDs. */
object CommentBackup {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private const val MAX_TIME = 9_007_199_254_740_991L
    data class Change(val before: CommentFraudStatus, val after: CommentFraudStatus)
    data class Plan(val records: List<CommentFraudRecord>, val added: Int,
                    val changes: Map<Long, Change>, val kept: Int)

    // The published clients use Long. Keep both their field types and array layout.
    fun encode(records: List<CommentFraudRecord>): String = json.encodeToString(records.map { record ->
        record.copy(status = record.fraudStatus.name,
            initial_status = record.initial_status?.let { CommentFraudStatus.parse(it).name })
    })

    fun decode(text: String): List<CommentFraudRecord> {
        val doc = json.parseToJsonElement(text)
        val rows = if (doc is JsonArray) doc else {
            require(doc is JsonObject && doc["format"] == JsonPrimitive("bili-comment-backup") &&
                doc["version"] == JsonPrimitive(1)) { "不支持的备份格式或版本" }
            doc["records"] as? JsonArray ?: throw IllegalArgumentException("缺少 records 数组")
        }
        val seen = mutableSetOf<Long>()
        return rows.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("记录必须是对象")
            fun integer(key: String, default: Long? = null): Long {
                val value = obj[key] ?: return default ?: throw IllegalArgumentException("缺少 $key")
                val raw = (value as? JsonPrimitive)?.content ?: throw IllegalArgumentException("$key 必须是整数或数字字符串")
                require(raw.matches(Regex("[0-9]+"))) { "$key 不是十进制非负整数" }
                return raw.toLongOrNull() ?: throw IllegalArgumentException("$key 超出 Long 范围")
            }
            fun text(key: String): String? {
                val value = obj[key] ?: return null
                if (value == JsonNull) return null
                require(value is JsonPrimitive && value.isString) { "$key 必须是字符串" }
                return value.content
            }
            fun status(key: String): String? = text(key)?.let {
                val parsed = CommentFraudStatus.parse(it)
                require(parsed != CommentFraudStatus.UNKNOWN || it.equals("unknown", true)) { "不支持的状态: $it" }
                parsed.name
            }
            val rpid = integer("rpid")
            val oid = integer("oid")
            require(rpid > 0 && oid > 0 && seen.add(rpid)) { "ID 无效或备份含重复评论" }
            val type = integer("type", 1)
            require(type in 1..Int.MAX_VALUE.toLong()) { "type 无效" }
            val post = integer("post_time", 0)
            val checked = integer("timestamp", 0)
            require(post <= MAX_TIME && checked <= MAX_TIME) { "时间超出安全整数范围" }
            CommentFraudRecord(rpid = rpid, oid = oid, type = type.toInt(),
                root = integer("root", 0), parent = integer("parent", 0), uid = integer("uid", 0),
                message = text("message").orEmpty(), source_id = text("source_id"), origin_url = text("origin_url"),
                status = status("status") ?: "UNKNOWN", initial_status = status("initial_status"),
                post_time = post, timestamp = checked)
        }
    }

    fun merge(local: List<CommentFraudRecord>, incoming: List<CommentFraudRecord>): Plan {
        val records = local.associateBy { it.rpid }.toMutableMap()
        val changes = linkedMapOf<Long, Change>()
        var added = 0
        var kept = 0
        for (item in incoming) {
            val old = records[item.rpid]
            if (old == null) {
                records[item.rpid] = item.copy(initial_status = listOf(item.initial_status, item.status)
                    .firstOrNull { it != null && CommentFraudStatus.parse(it) != CommentFraudStatus.UNKNOWN } ?: "UNKNOWN")
                added++; continue
            }
            require(old.oid == item.oid && old.type == item.type &&
                listOf(old.root to item.root, old.parent to item.parent, old.uid to item.uid)
                    .all { (a, b) -> a == 0L || b == 0L || a == b }) { "评论 ${item.rpid} 的所属评论区或身份字段冲突，未导入任何记录" }
            val retain = item.fraudStatus == CommentFraudStatus.UNKNOWN && old.fraudStatus != CommentFraudStatus.UNKNOWN
            if (retain) kept++
            val current = if (retain) old.fraudStatus else item.fraudStatus
            if (current != old.fraudStatus) changes[item.rpid] = Change(old.fraudStatus, current)
            records[item.rpid] = old.copy(
                root = old.root.takeIf { it != 0L } ?: item.root,
                parent = old.parent.takeIf { it != 0L } ?: item.parent,
                uid = old.uid.takeIf { it != 0L } ?: item.uid,
                message = old.message.ifBlank { item.message },
                source_id = old.source_id?.takeIf { it.isNotBlank() } ?: item.source_id,
                origin_url = old.origin_url?.takeIf { it.isNotBlank() } ?: item.origin_url,
                post_time = old.post_time.takeIf { it > 0 } ?: item.post_time,
                initial_status = listOf(old.initial_status, item.initial_status, current.name)
                    .firstOrNull { it != null && CommentFraudStatus.parse(it) != CommentFraudStatus.UNKNOWN } ?: "UNKNOWN",
                status = current.name, timestamp = if (retain) old.timestamp else item.timestamp)
        }
        return Plan(records.values.toList(), added, changes, kept)
    }
}
