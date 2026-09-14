package me.bili.unrestrict.hook

import java.net.URI
import java.net.URLDecoder

/** Pure text policy. Unknown parameters and fragments retain their original encoding. */
internal object ShareLinkFormatter {
    private val bv = Regex("BV1[1-9A-NP-Za-km-z]{9}")
    private val av = Regex("av[1-9][0-9]*")
    private val tracking = setOf(
        "buvid", "mid", "share_source", "share_medium", "share_plat", "share_session_id",
        "share_tag", "share_times", "timestamp", "ts", "unique_k", "vd_source", "bbid",
        "spm_id_from", "from_spmid", "share_from",
    )

    data class Content(val title: String, val url: String) {
        val text: String get() = "$title\n$url"
    }

    fun isTargetChannel(channel: String?) = channel == "COPY" || channel == "GENERIC"
    fun isBvid(value: String) = bv.matches(value)
    fun isAvid(value: String) = av.matches(value)

    fun format(title: String?, url: String?, bvidForAv: (String) -> String? = { null }): Content? {
        if (title.isNullOrBlank() || url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host != "www.bilibili.com" ||
            uri.rawUserInfo != null || uri.port != -1) return null
        val path = uri.rawPath ?: return null
        val id = Regex("/video/([^/]+)/?").matchEntire(path)?.groupValues?.get(1) ?: return null
        val bvid = when {
            isBvid(id) -> id
            isAvid(id) -> bvidForAv(id)?.takeIf(::isBvid) ?: return null
            else -> return null
        }
        val query = uri.rawQuery?.split('&')?.filterNot { parameter ->
            val key = runCatching {
                URLDecoder.decode(parameter.substringBefore('='), "UTF-8")
            }.getOrNull()
            key in tracking
        }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        val clean = buildString {
            append("https://www.bilibili.com/video/").append(bvid)
            if (query != null) append('?').append(query)
            uri.rawFragment?.let { append('#').append(it) }
        }
        val heading = title.trim().let {
            if (it.startsWith('【') && it.endsWith('】')) it else "【$it】"
        }
        return Content(heading, clean)
    }
}
