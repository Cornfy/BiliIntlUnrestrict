package me.bili.unrestrict.detector

private const val DEFAULT_PAGE_SIZE = 20

// 服务端响应码
private const val CODE_NOT_FOUND = -404          // "啥都木有"：该 oid/type 下不存在评论区
private const val CODE_COMMENT_CLOSED = 12002    // 评论功能已关闭
private const val CODE_ROOT_DELETED = 12022      // 根评论已删除

/**
 * 探针结论分类。核心是把"这次请求本身没成功"与"这个视角确定看不到"分开——
 * 前者一律不判定，后者才可以落锤。
 *
 *   OK          —— 拿到数据，可以判断"这个视角里有没有这条评论"
 *   GONE        —— 评论区 / 评论对象本身不存在（确定没有，不是暂时没有）
 *   UNAVAILABLE —— 网络异常 / 风控 / 其它非 0 码，本次无法判断，可重试
 */
enum class ProbeKind { OK, GONE, UNAVAILABLE }

/** 双视角裁定的落锤结论（与 CommentFraudStatus 同名，便于映射） */
enum class ProbeVerdict { NORMAL, INVISIBLE, SHADOW_BANNED, DELETED, UNKNOWN }

/** 一次视角探针的解读结果（纯数据，可独立单测） */
data class ProbeReading(
    val kind: ProbeKind,
    val code: Int = -1,
    val hit: Boolean = false,
    val invisible: Boolean = false,
    /** 本页【子评论自身】的发布时间区间；刻意不含响应里 data.root 的 ctime */
    val ctimeRange: Pair<Long, Long>? = null,
    val totalCount: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    val ok: Boolean get() = kind == ProbeKind.OK
}

/**
 * 探针响应解读与裁定（纯逻辑，无 Android 依赖，可单测）。
 *
 * 2026-10-15 修：楼中楼时序二分的区间原先是对**整段 JSON** 做正则取 min/max，
 * 而响应里 data.root 还带着根评论自己的 ctime（必然比所有子评论都早），
 * 于是每一页的区间都被拉成"从很久以前到现在"，二分会在错误的页上提前收敛：
 * 一条在第 2 页里的正常评论，被收敛到第 3 页 → 路人"未见" → 误判成已删除。
 * 现在区间只取 data.replies。
 */
object ProbeInterpreter {

    /** 响应码分类：把"确定没有"与"暂时拿不到"严格分开 */
    fun classifyCode(code: Int): ProbeKind = when (code) {
        0 -> ProbeKind.OK
        CODE_NOT_FOUND, CODE_COMMENT_CLOSED, CODE_ROOT_DELETED -> ProbeKind.GONE
        else -> ProbeKind.UNAVAILABLE
    }

    /** 取 `"key":<整数>` 的首个匹配（"rcount" 不会被 "count" 误匹配，反之亦然） */
    fun firstInt(text: String?, key: String): Int? {
        if (text.isNullOrEmpty()) return null
        val match = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** 从 openAt 处的开括号起，取到配对的闭括号（跳过字符串字面量里的括号） */
    fun bracketBlock(text: String, openAt: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = openAt
        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(openAt, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    /**
     * 取出 `"key":{...}` / `"key":[...]` 的原文片段。
     * 同名键在别处出现且值类型不符时会被跳过——例如每条子评论都带 "root":<数字>，
     * 只有 data.root 才是对象；每条子评论的 "replies" 是 null，只有 data.replies 才是数组。
     */
    fun blockOf(text: String?, key: String, open: Char, close: Char): String? {
        if (text.isNullOrEmpty()) return null
        val marker = "\"$key\""
        var from = 0
        while (true) {
            val keyAt = text.indexOf(marker, from)
            if (keyAt < 0) return null
            val colon = text.indexOf(':', keyAt + marker.length)
            if (colon < 0) return null
            var index = colon + 1
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length && text[index] == open) return bracketBlock(text, index, open, close)
            from = keyAt + marker.length
        }
    }

    /** 数组片段里所有 ctime 的最小/最大值 */
    fun ctimeRangeIn(arrayBlock: String?): Pair<Long, Long>? {
        if (arrayBlock.isNullOrEmpty()) return null
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (match in Regex("\"ctime\"\\s*:\\s*(\\d+)").findAll(arrayBlock)) {
            val ctime = match.groupValues[1].toLongOrNull() ?: continue
            if (ctime > 0L) {
                if (ctime < min) min = ctime
                if (ctime > max) max = ctime
            }
        }
        return if (min == Long.MAX_VALUE) null else min to max
    }

    /** 命中判定：rpid 后面不能再接数字，避免 3155357223841 被当成 315535722384 */
    fun hasRpid(body: String, rpid: Long): Boolean =
        Regex("\"rpid\"\\s*:\\s*$rpid(?!\\d)").containsMatchIn(body)

    /**
     * 命中对象是否被标记为隐藏（软屏蔽）。
     * 字段顺序不保证，`invisible` 可能在 `rpid` 之前；[^{}] 保证匹配不跨越对象边界，
     * 因此不会把相邻评论的 invisible 误算到目标身上。
     */
    fun isInvisible(body: String, rpid: Long): Boolean {
        val forward = Regex("\"rpid\"\\s*:\\s*$rpid(?!\\d)[^{}]*?\"invisible\"\\s*:\\s*true")
        val backward = Regex("\"invisible\"\\s*:\\s*true[^{}]*?\"rpid\"\\s*:\\s*$rpid(?!\\d)")
        return forward.containsMatchIn(body) || backward.containsMatchIn(body)
    }

    /** 解读一次探针响应 */
    fun readingOf(body: String?, rpid: Long): ProbeReading {
        if (body.isNullOrBlank()) return ProbeReading(ProbeKind.UNAVAILABLE)

        val code = firstInt(body, "code") ?: return ProbeReading(ProbeKind.UNAVAILABLE)
        val kind = classifyCode(code)
        if (kind != ProbeKind.OK) return ProbeReading(kind, code)

        val hit = hasRpid(body, rpid)
        val invisible = hit && isInvisible(body, rpid)

        // 页大小与总量优先取 data.page（该接口把 ps 锁死在 20，必须按真实 page.size 算页数）
        val pageBlock = blockOf(body, "page", '{', '}')
        val rootBlock = blockOf(body, "root", '{', '}')
        val pageSize = firstInt(pageBlock, "size") ?: DEFAULT_PAGE_SIZE
        // 按语义优先级取值：page.count 是列表真实总量，其次才是根评论的子评论数
        val totalCount = firstInt(pageBlock, "count")
            ?: firstInt(rootBlock, "rcount")
            ?: firstInt(rootBlock, "count")
            ?: 0

        // ★ 时间区间只取 data.replies（详见类注释）
        val ctimeRange = ctimeRangeIn(blockOf(body, "replies", '[', ']'))

        return ProbeReading(kind, 0, hit, invisible, ctimeRange, totalCount, pageSize)
    }

    fun lastPage(totalCount: Int, pageSize: Int): Int {
        val size = if (pageSize > 0) pageSize else DEFAULT_PAGE_SIZE
        return maxOf(1, (maxOf(totalCount, 0) + size - 1) / size)
    }

    /**
     * 双视角裁定（纯逻辑）。
     * anon 必须是"请求成功"或"确定不存在（GONE）"的探针；UNAVAILABLE 一律不落锤。
     * 返回 SHADOW_BANNED 时只是候选结论，recheck 为空表示还需延迟复验路人视角。
     */
    fun verdictOf(anon: ProbeReading, auth: ProbeReading?, recheck: ProbeReading? = null): ProbeVerdict {
        if (anon.hit) return if (anon.invisible) ProbeVerdict.INVISIBLE else ProbeVerdict.NORMAL
        if (anon.kind == ProbeKind.UNAVAILABLE) return ProbeVerdict.UNKNOWN

        if (auth != null && auth.hit) {
            if (recheck != null) {
                if (recheck.hit) {
                    return if (recheck.invisible) ProbeVerdict.INVISIBLE else ProbeVerdict.NORMAL
                }
                if (!recheck.ok) return ProbeVerdict.UNKNOWN
                return ProbeVerdict.SHADOW_BANNED
            }
            return ProbeVerdict.SHADOW_BANNED
        }

        if (anon.kind == ProbeKind.GONE) return ProbeVerdict.DELETED
        if (auth == null || auth.kind == ProbeKind.UNAVAILABLE) return ProbeVerdict.UNKNOWN
        return ProbeVerdict.DELETED
    }
}
