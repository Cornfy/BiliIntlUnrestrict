package me.bili.unrestrict.data.model

import me.bili.unrestrict.data.db.CommentFraudRecord

/**
 * 发评历史的本地检索。
 *
 * 记录量在单设备规模，且 UI 已订阅全量列表，因此这里做的是内存线性过滤，
 * 不需要额外的 SQL 查询。抽取为纯逻辑以便单元测试。
 */
object CommentFraudSearch {

    /** 解析后的检索条件；[keyword] 为空表示「不过滤」。 */
    data class Query(
        val keyword: String = "",
        /** 关键词整体可解析为数字时，额外支持按评论 ID / 评论区 ID 精确命中。 */
        val numeric: Long? = null
    ) {
        val isEmpty: Boolean get() = keyword.isEmpty()
    }

    fun parse(raw: String): Query {
        val keyword = raw.trim()
        return Query(keyword = keyword, numeric = keyword.toLongOrNull())
    }

    fun matches(record: CommentFraudRecord, query: Query): Boolean {
        if (query.isEmpty) return true
        query.numeric?.let { id ->
            if (record.rpid == id || record.oid == id) return true
        }
        return contains(record.message, query.keyword) || contains(record.source_id, query.keyword)
    }

    fun filter(records: List<CommentFraudRecord>, rawQuery: String): List<CommentFraudRecord> {
        val query = parse(rawQuery)
        if (query.isEmpty) return records
        return records.filter { matches(it, query) }
    }

    /**
     * 命中区间，按出现顺序排列且互不重叠，供 UI 做高亮。
     * 关键词为空或无命中时返回空列表。
     */
    fun highlightRanges(text: String, keyword: String): List<IntRange> {
        val target = keyword.trim()
        if (target.isEmpty() || target.length > text.length) return emptyList()

        val ranges = ArrayList<IntRange>()
        var from = 0
        while (from <= text.length - target.length) {
            val start = text.indexOf(target, startIndex = from, ignoreCase = true)
            if (start < 0) break
            val end = start + target.length
            ranges.add(start until end)
            from = end
        }
        return ranges
    }

    private fun contains(value: String?, keyword: String): Boolean =
        !value.isNullOrEmpty() && value.contains(keyword, ignoreCase = true)
}
