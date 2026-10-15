package me.bili.unrestrict.data.model

enum class CommentFraudStatus {
    NORMAL,         // 正常显示 (路人视角可见)
    SHADOW_BANNED,  // 仅自己可见 (ShadowBan)
    DELETED,        // 已被秒删/失效
    UNDER_REVIEW,   // 疑似审核中（当前引擎不产出，待校准 state 字段后启用）
    INVISIBLE,      // 软屏蔽 (前端强制隐藏)
    UNKNOWN;        // 状态待确定（本轮未能定性）

    companion object {
        fun parse(raw: String): CommentFraudStatus {
            return when (raw.lowercase().replace("_", "").replace("-", "")) {
                "normal", "ok" -> NORMAL
                "shadowban", "shadowbanned" -> SHADOW_BANNED
                "deleted", "del" -> DELETED
                "invisible", "inv" -> INVISIBLE
                "underreview", "review", "auditing" -> UNDER_REVIEW
                else -> UNKNOWN
            }
        }
    }
}
