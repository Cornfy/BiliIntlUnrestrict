package me.bili.unrestrict.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针解读与裁定回归测试（JVM 纯逻辑）。
 *
 * 只覆盖"怎么解读响应、怎么裁定"，不覆盖"接口在真机上会返回什么"——
 * 后者属于真机验收范围，不由本测试担保。
 */
class ProbeInterpreterTest {

    // ---------- 夹具：仿照 x/v2/reply/reply 的真实字段布局 ----------
    // data 的键顺序真实为 page → upper → replies → root（子评论在前、根评论在后）；
    // 每条子评论都带 "root":<数字> 与 "replies":null，只有 data.root 是对象。
    private fun subReplyJson(rpid: Long, ctime: Long, invisible: Boolean = false): String =
        """{"rpid":$rpid,"root":$ROOT_ID,"ctime":$ctime,"invisible":$invisible,"replies":null}"""

    private fun pageBody(
        replyCtimes: List<Long>,
        pageCount: Int = 122,
        pageSize: Int = 20,
        rootCtime: Long = 1_700_000_000L,
        rootRcount: Int = 122,
        rootCount: Int = 139,
        code: Int = 0,
        rpidBase: Long = RPID_BASE
    ): String {
        val replies = replyCtimes.mapIndexed { index, ctime -> subReplyJson(rpidBase + index, ctime) }
        return """{"code":$code,"message":"0","ttl":1,"data":{""" +
            """"page":{"num":1,"size":$pageSize,"count":$pageCount},""" +
            """"upper":{"mid":1},""" +
            """"replies":[${replies.joinToString(",")}],""" +
            """"root":{"rpid":$ROOT_ID,"ctime":$rootCtime,"count":$rootCount,"rcount":$rootRcount,"replies":null},""" +
            """"config":{},"control":{}}}"""
    }

    // ---------- 响应码分类 ----------

    @Test
    fun `响应码分类 区分确定不存在与暂时拿不到`() {
        assertEquals(ProbeKind.OK, ProbeInterpreter.classifyCode(0))
        assertEquals(ProbeKind.GONE, ProbeInterpreter.classifyCode(-404))       // 啥都木有
        assertEquals(ProbeKind.GONE, ProbeInterpreter.classifyCode(12002))      // 评论功能已关闭
        assertEquals(ProbeKind.GONE, ProbeInterpreter.classifyCode(12022))      // 根评论已删除
        assertEquals(ProbeKind.GONE, ProbeInterpreter.classifyCode(12006))      // 没有该评论（楼中楼 root 不存在）
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.classifyCode(-352))  // 风控
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.classifyCode(12061)) // 审核中
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.classifyCode(-1))
    }

    @Test
    fun `响应码分类 404 是评论区不存在而不是请求失败（回归）`() {
        // 曾把 -404 归入"请求失败"→ 判定永远停在"检测中"，一条确实已删除的评论永远结不了案
        val reading = ProbeInterpreter.readingOf("""{"code":-404,"message":"啥都木有"}""", TARGET_RPID)
        assertEquals(ProbeKind.GONE, reading.kind)
        assertEquals(-404, reading.code)
        assertFalse(reading.hit)
    }

    @Test
    fun `响应码分类 空响应与非法 JSON 都算请求失败`() {
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.readingOf(null, TARGET_RPID).kind)
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.readingOf("", TARGET_RPID).kind)
        assertEquals(ProbeKind.UNAVAILABLE, ProbeInterpreter.readingOf("<html>502</html>", TARGET_RPID).kind)
    }

    // ---------- 时间区间：本次故障的核心 ----------

    @Test
    fun `时间区间只取 data_replies 不得混入根评论的 ctime（回归）`() {
        val body = pageBody(
            replyCtimes = listOf(1_787_828_597L, 1_788_109_445L),
            rootCtime = 1_700_000_000L   // 根评论远早于所有子评论
        )
        val range = ProbeInterpreter.readingOf(body, TARGET_RPID).ctimeRange
        assertEquals(1_787_828_597L to 1_788_109_445L, range)
        // 若把 data.root.ctime 混进来，下界会变成 1700000000，二分就会在错误的页上收敛
        assertTrue(range!!.first > 1_700_000_000L)
    }

    @Test
    fun `时序二分在真实分页布局上收敛到目标所在页（原故障链复现）`() {
        // 7 页、目标在第 2 页；根评论 ctime 早于所有子评论
        val rootCtime = 1_768_099_990L
        val page2 = pageBody(
            replyCtimes = listOf(1_787_828_597L, 1_787_909_410L, 1_788_109_445L),
            rootCtime = rootCtime
        )
        val page3 = pageBody(
            replyCtimes = listOf(1_788_110_333L, 1_788_510_917L),
            rootCtime = rootCtime,
            rpidBase = RPID_BASE + 1_000    // 这一页里没有目标
        )
        val page1 = pageBody(
            replyCtimes = listOf(1_768_100_534L, 1_787_827_709L),
            rootCtime = rootCtime,
            rpidBase = RPID_BASE + 2_000
        )
        val sentAt = 1_787_909_410L   // 目标自己的发布时间

        assertTrue(ProbeInterpreter.hasRpid(page2, TARGET_RPID))
        assertFalse(ProbeInterpreter.hasRpid(page3, TARGET_RPID))

        // 二分看第 3 页：区间应从子评论起算 → 目标比它早 → 向左收
        val range3 = ProbeInterpreter.readingOf(page3, TARGET_RPID).ctimeRange!!
        assertEquals(1_788_110_333L, range3.first)
        assertTrue(sentAt < range3.first)

        // 再看第 1 页：目标比它晚 → 向右收 → 落到第 2 页命中
        val range1 = ProbeInterpreter.readingOf(page1, TARGET_RPID).ctimeRange!!
        assertTrue(sentAt > range1.second)
    }

    @Test
    fun `时间区间对页内顺序不敏感（乱序也取真实 min_max）`() {
        // 区间取的是整页真实 min/max，而不是 replies[0] / replies[last]，
        // 因此不依赖"页内按 ctime 升序"这个假设：置顶、审核插入等打乱顺序也不会算错边界。
        val ascending = pageBody(replyCtimes = listOf(1_788_100_000L, 1_788_200_000L, 1_788_300_000L))
        val descending = pageBody(replyCtimes = listOf(1_788_300_000L, 1_788_200_000L, 1_788_100_000L))
        val scrambled = pageBody(replyCtimes = listOf(1_788_200_000L, 1_788_300_000L, 1_788_100_000L))

        val expected = 1_788_100_000L to 1_788_300_000L
        assertEquals(expected, ProbeInterpreter.readingOf(ascending, TARGET_RPID).ctimeRange)
        assertEquals(expected, ProbeInterpreter.readingOf(descending, TARGET_RPID).ctimeRange)
        assertEquals(expected, ProbeInterpreter.readingOf(scrambled, TARGET_RPID).ctimeRange)
    }

    @Test
    fun `无有效 ctime 时区间为 null 交由调用方按不可信处理`() {
        val body = pageBody(replyCtimes = emptyList())
        assertNull(ProbeInterpreter.readingOf(body, TARGET_RPID).ctimeRange)
    }

    // ---------- 总量与页大小 ----------

    @Test
    fun `总量与页大小取自 data_page`() {
        val reading = ProbeInterpreter.readingOf(pageBody(listOf(1_787_828_597L)), TARGET_RPID)
        assertEquals(122, reading.totalCount)
        assertEquals(20, reading.pageSize)
    }

    @Test
    fun `page 缺失时回落到 data_root 的计数与默认页大小`() {
        val body = """{"code":0,"data":{"replies":[],"root":{"rcount":57,"count":57}}}"""
        val reading = ProbeInterpreter.readingOf(body, TARGET_RPID)
        assertEquals(57, reading.totalCount)
        assertEquals(20, reading.pageSize)
    }

    @Test
    fun `页数计算`() {
        assertEquals(1, ProbeInterpreter.lastPage(0, 20))
        assertEquals(1, ProbeInterpreter.lastPage(20, 20))
        assertEquals(2, ProbeInterpreter.lastPage(21, 20))
        assertEquals(7, ProbeInterpreter.lastPage(122, 20))
        assertEquals(7, ProbeInterpreter.lastPage(139, 20))
        // 页大小异常时回落到默认 20，而不是把整个楼层当成一页
        assertEquals(7, ProbeInterpreter.lastPage(122, 0))
    }

    // ---------- 命中判定 ----------

    @Test
    fun `rpid 命中不得匹配到更长的数字`() {
        val body = """{"code":0,"data":{"replies":[{"rpid":${TARGET_RPID}1,"ctime":1}]}}"""
        assertFalse(ProbeInterpreter.hasRpid(body, TARGET_RPID))
        assertTrue(ProbeInterpreter.hasRpid(body, TARGET_RPID * 10 + 1))
    }

    @Test
    fun `invisible 标记透出`() {
        val body = """{"code":0,"data":{"replies":[""" +
            subReplyJson(TARGET_RPID, 1_787_828_597L, invisible = true) + """]}}"""
        val reading = ProbeInterpreter.readingOf(body, TARGET_RPID)
        assertTrue(reading.hit)
        assertTrue(reading.invisible)
    }

    @Test
    fun `非 0 码时不做命中判定`() {
        val body = """{"code":-352,"message":"风控","data":{"replies":[{"rpid":$TARGET_RPID,"ctime":1}]}}"""
        val reading = ProbeInterpreter.readingOf(body, TARGET_RPID)
        assertEquals(ProbeKind.UNAVAILABLE, reading.kind)
        assertFalse(reading.hit)
        assertNull(reading.ctimeRange)
    }

    // ---------- 片段抽取 ----------

    @Test
    fun `blockOf 跳过同名的非对象与非数组值`() {
        val text = """{"a":1,"root":123,"b":{"root":{"rcount":7}}}"""
        assertEquals("{\"rcount\":7}", ProbeInterpreter.blockOf(text, "root", '{', '}'))
        val arr = """{"replies":null,"x":0,"replies":[1,2]}"""
        assertEquals("[1,2]", ProbeInterpreter.blockOf(arr, "replies", '[', ']'))
    }

    @Test
    fun `blockOf 跳过字符串字面量里的括号`() {
        val text = """{"content":"a}b]c","replies":[1]}"""
        assertEquals("[1]", ProbeInterpreter.blockOf(text, "replies", '[', ']'))
        assertNull(ProbeInterpreter.blockOf(text, "missing", '[', ']'))
    }

    @Test
    fun `firstInt 不会被相邻键名误匹配`() {
        assertEquals(139, ProbeInterpreter.firstInt("""{"rcount":7,"count":139}""", "count"))
        assertEquals(7, ProbeInterpreter.firstInt("""{"rcount":7,"count":139}""", "rcount"))
        assertNull(ProbeInterpreter.firstInt("""{"rcount":7}""", "size"))
    }

    // ---------- 双视角裁定 ----------

    private val anonHit = ProbeReading(ProbeKind.OK, hit = true)
    private val anonMiss = ProbeReading(ProbeKind.OK)
    private val anonGone = ProbeReading(ProbeKind.GONE, code = -404)
    private val anonDown = ProbeReading(ProbeKind.UNAVAILABLE, code = -352)
    private val authHit = ProbeReading(ProbeKind.OK, hit = true)
    private val authMiss = ProbeReading(ProbeKind.OK)
    private val authDown = ProbeReading(ProbeKind.UNAVAILABLE, code = -412)

    @Test
    fun `裁定 路人可见即正常`() {
        assertEquals(ProbeVerdict.NORMAL, ProbeInterpreter.verdictOf(anonHit, null))
        assertEquals(ProbeVerdict.INVISIBLE, ProbeInterpreter.verdictOf(anonHit.copy(invisible = true), null))
    }

    @Test
    fun `裁定 路人请求失败一律不落锤（回归）`() {
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(anonDown, authHit))
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(anonDown, null))
    }

    @Test
    fun `裁定 作者视角不可用时不判已删除（回归）`() {
        // 曾经：作者视角请求失败 → 直接判 DELETED。实际是"拿不到作者视角"，无法区分仅自己可见
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(anonMiss, authDown))
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(anonMiss, null))
    }

    @Test
    fun `裁定 双视角皆无才判已删除`() {
        assertEquals(ProbeVerdict.DELETED, ProbeInterpreter.verdictOf(anonMiss, authMiss))
    }

    @Test
    fun `裁定 评论区不存在直接判已删除`() {
        assertEquals(ProbeVerdict.DELETED, ProbeInterpreter.verdictOf(anonGone, anonGone))
        assertEquals(ProbeVerdict.DELETED, ProbeInterpreter.verdictOf(anonGone, null))
    }

    @Test
    fun `裁定 根评论消失落到根评论已消失而不是已删除（回归）`() {
        // 12006 只说明这条子评论挂靠的根评论不见了，子评论自身是否还在【无法核查】
        val rootGone = ProbeReading(ProbeKind.GONE, code = 12006)
        assertEquals(ProbeVerdict.ROOT_GONE, ProbeInterpreter.verdictOf(rootGone, rootGone))
        assertEquals(ProbeVerdict.ROOT_GONE, ProbeInterpreter.verdictOf(rootGone, null))
        // 作者视角仍能看到 → 依然是"仅自己可见"，不能因为根评论消失就判这条评论没了
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(rootGone, authHit))
        // -404 与 12006 必须落不同结论
        assertEquals(ProbeVerdict.DELETED, ProbeInterpreter.verdictOf(anonGone, null))
    }

    @Test
    fun `裁定 作者可见只是候选 复验后落锤`() {
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(anonMiss, authHit))
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(anonMiss, authHit, anonMiss))
    }

    @Test
    fun `裁定 复验路人可见则纠正为正常（回归）`() {
        assertEquals(ProbeVerdict.NORMAL, ProbeInterpreter.verdictOf(anonMiss, authHit, anonHit))
        assertEquals(ProbeVerdict.INVISIBLE, ProbeInterpreter.verdictOf(anonMiss, authHit, anonHit.copy(invisible = true)))
    }

    @Test
    fun `裁定 复验不可用则退回待确定`() {
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(anonMiss, authHit, anonDown))
    }

    @Test
    fun `裁定 复验确定看不到 落 shadowban 而不是待确定（回归）`() {
        // 复验路人视角也报 GONE（确定看不到）→ 与"作者可见"合并 = 仅自己可见。
        // 曾用 !recheck.ok 判"没验成"，把 GONE 与 UNAVAILABLE 混为一谈（两者 ok 都为 false）。
        val recheckGone = ProbeReading(ProbeKind.GONE, code = -404)
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(anonMiss, authHit, recheckGone))
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(anonGone, authHit, recheckGone))
    }

    @Test
    fun `裁定 路人报 gone 而作者可见 同样要先复验（楼中楼早期 gone 分支）`() {
        // 楼中楼的早期 gone 分支（第一个探针就报 -404 / 12006）原先直接落锤，
        // 现在与根评论路径、完整扫描路径走同一套"延迟复验路人视角"：
        val rootGone = ProbeReading(ProbeKind.GONE, code = 12006)
        // 候选（尚未复验）—— 调用方必须去复验，不得直接落锤
        assertEquals(ProbeVerdict.SHADOW_BANNED, ProbeInterpreter.verdictOf(rootGone, authHit))
        // 复验路人可见 → 是缓存 / 审核延迟造成的假阳性 → 纠正为正常
        assertEquals(ProbeVerdict.NORMAL, ProbeInterpreter.verdictOf(rootGone, authHit, anonHit))
        // 复验不可用 → 退回待确定
        assertEquals(ProbeVerdict.UNKNOWN, ProbeInterpreter.verdictOf(rootGone, authHit, anonDown))
    }

    // ---------- 软屏蔽（invisible）读取 ----------

    @Test
    fun `软屏蔽 认两种字段顺序`() {
        // 真实布局：invisible 在 rpid 之后
        val after = """{"data":{"replies":[{"rpid":$TARGET_RPID,"ctime":1,"invisible":true}]}}"""
        // 顺序颠倒也必须认（字段顺序不是契约）
        val before = """{"data":{"replies":[{"invisible":true,"rpid":$TARGET_RPID,"ctime":1}]}}"""
        assertTrue(ProbeInterpreter.isInvisible(after, TARGET_RPID))
        assertTrue(ProbeInterpreter.isInvisible(before, TARGET_RPID))
    }

    @Test
    fun `软屏蔽 不会被相邻评论串味`() {
        // 目标自身 invisible=false，邻居为 true —— 一个都不能错
        val body = """{"replies":[""" +
            """{"rpid":$TARGET_RPID,"ctime":1,"invisible":false},""" +
            """{"rpid":${TARGET_RPID + 1},"ctime":2,"invisible":true}]}"""
        assertFalse(ProbeInterpreter.isInvisible(body, TARGET_RPID))
        assertTrue(ProbeInterpreter.isInvisible(body, TARGET_RPID + 1))
    }

    @Test
    fun `软屏蔽 缺字段按未隐藏处理`() {
        val body = """{"replies":[{"rpid":$TARGET_RPID,"ctime":1}]}"""
        assertFalse(ProbeInterpreter.isInvisible(body, TARGET_RPID))
    }

    @Test
    fun `软屏蔽 命中即落 INVISIBLE`() {
        assertTrue(ProbeInterpreter.readingOf(
            """{"code":0,"data":{"replies":[{"rpid":$TARGET_RPID,"ctime":1,"invisible":true}]}}""",
            TARGET_RPID
        ).invisible)
    }

    companion object {
        private const val ROOT_ID = 288_172_898_608L
        private const val RPID_BASE = 315_535_000_000L
        private const val TARGET_RPID = RPID_BASE + 1   // 对应 replyCtimes[1]
    }
}
