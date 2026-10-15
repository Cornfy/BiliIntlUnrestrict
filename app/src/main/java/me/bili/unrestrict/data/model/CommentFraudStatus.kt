package me.bili.unrestrict.data.model

enum class CommentFraudStatus {
    NORMAL,         // 正常显示 (路人视角可见)
    SHADOW_BANNED,  // 仅自己可见 (ShadowBan)
    DELETED,        // 已被秒删/失效
    UNDER_REVIEW,   // 疑似审核中（当前引擎不产出，待校准 state 字段后启用）
    INVISIBLE,      // 软屏蔽 (前端强制隐藏)
    ROOT_GONE,      // 根评论已消失：楼中楼接口报 12006，说明它挂靠的根评论不见了，
                    // 这条子评论自身是否还在【无法核查】——刻意不并进 DELETED
    UNKNOWN;        // 状态待确定（本轮未能定性）

    companion object {
        fun parse(raw: String): CommentFraudStatus {
            return when (raw.lowercase().replace("_", "").replace("-", "")) {
                "normal", "ok" -> NORMAL
                "shadowban", "shadowbanned" -> SHADOW_BANNED
                "deleted", "del" -> DELETED
                "invisible", "inv" -> INVISIBLE
                "underreview", "review", "auditing" -> UNDER_REVIEW
                "rootgone", "rootmissing", "parentgone" -> ROOT_GONE
                else -> UNKNOWN
            }
        }
    }
}
