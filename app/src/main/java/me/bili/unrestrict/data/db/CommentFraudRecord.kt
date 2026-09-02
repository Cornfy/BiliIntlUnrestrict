package me.bili.unrestrict.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.bili.unrestrict.data.model.CommentFraudStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "comment_fraud_records")
data class CommentFraudRecord(
    @PrimaryKey
    @SerialName("rpid")
    val rpid: Long,

    @SerialName("oid")
    val oid: Long,

    @SerialName("type")
    val type: Int = 1,

    @SerialName("root")
    val root: Long = 0L,

    @SerialName("parent")
    val parent: Long = 0L,

    @SerialName("uid")
    val uid: Long = 0L,

    @SerialName("source_id")
    val source_id: String? = null,

    @SerialName("origin_url")
    val origin_url: String? = null,

    @SerialName("message")
    val message: String = "",

    @SerialName("initial_status")
    val initial_status: String? = null,

    @SerialName("status")
    val status: String = "UNKNOWN",

    @SerialName("post_time")
    val post_time: Long = 0L,

    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
) {
    val fraudStatus: CommentFraudStatus
        get() = CommentFraudStatus.parse(status)

    val initialFraudStatus: CommentFraudStatus?
        get() = initial_status?.let { CommentFraudStatus.parse(it) }

    val typeName: String
        get() = when (type) {
            1 -> "视频 (type=1)"
            12 -> "专栏 (type=12)"
            11, 17 -> "动态 (type=$type)"
            else -> "业务 (type=$type)"
        }

    val fraudAssessment: String
        get() {
            val init = initialFraudStatus
            val curr = fraudStatus
            val timeDiff = timestamp - post_time
            val days = if (post_time > 0L && timeDiff > 0L) (timeDiff / (1000L * 60 * 60 * 24)).toInt() else 0

            return when {
                curr == CommentFraudStatus.UNKNOWN -> "⏳ 状态检测中 (等待系统处理...)"
                curr == CommentFraudStatus.NORMAL -> "🟢 评论正常显示 (路人视角可见)"
                init == CommentFraudStatus.NORMAL && curr == CommentFraudStatus.SHADOW_BANNED -> {
                    if (days > 0) "⚠️ 秋后算账 (发评 $days 天后转为 shadowBan)"
                    else "⚠️ 延迟拦截 (初检正常，后转为 shadowBan)"
                }
                init == CommentFraudStatus.NORMAL && curr == CommentFraudStatus.DELETED -> {
                    if (days > 0) "⚠️ 评论已失效 (发评 $days 天后已被删除或清理)"
                    else "⚠️ 延迟失效 (初检正常，后已被删除或清理)"
                }
                curr == CommentFraudStatus.SHADOW_BANNED -> "🔴 评论被 shadowBan (仅自己可见)"
                curr == CommentFraudStatus.DELETED -> "⚫ 评论已失效 (已被删除或不存在)"
                curr == CommentFraudStatus.UNDER_REVIEW -> "🟡 评论疑似审核中 (处于先审后发队列)"
                curr == CommentFraudStatus.INVISIBLE -> "🟠 评论被软屏蔽 (Invisible: 前端强制隐藏)"
                else -> "⚪ 状态待确定"
            }
        }
}
