package me.bili.unrestrict.hook

import java.util.Collections
import java.util.WeakHashMap

/** K is the host Bundle (identity equality). Values must not retain it or its Activity. */
internal class ShareLinkState<K : Any> {
    private data class Snapshot(val channel: String, val content: ShareLinkFormatter.Content)
    private val originals = Collections.synchronizedMap(WeakHashMap<K, Snapshot>())
    private val active = ThreadLocal<ShareLinkFormatter.Content?>()

    val current: ShareLinkFormatter.Content? get() = active.get()

    fun capture(key: K, channel: String?, content: ShareLinkFormatter.Content?) {
        synchronized(originals) {
            originals.remove(key)
            if (content != null && ShareLinkFormatter.isTargetChannel(channel)) {
                originals[key] = Snapshot(channel!!, content)
            }
        }
    }

    fun <R> within(key: K?, channel: String?, enabled: Boolean, action: () -> R): R {
        val previous = active.get()
        val snapshot = key?.let { originals[it] }
        active.set(snapshot?.takeIf {
            enabled && it.channel == channel && ShareLinkFormatter.isTargetChannel(channel)
        }?.content)
        try {
            return action()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
