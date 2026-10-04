package tv.qissat.app.player

import android.content.Context
import tv.qissat.app.adblock.HostUtil

/** Remembers where each episode page was left off (bounded, oldest entries evicted). */
class PlaybackStore(context: Context) {

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun position(pageUrl: String?): Long {
        val raw = prefs.getString(key(pageUrl) ?: return 0L, null) ?: return 0L
        return raw.split(',').firstOrNull()?.toLongOrNull() ?: 0L
    }

    fun save(pageUrl: String?, positionMs: Long, durationMs: Long) {
        val k = key(pageUrl) ?: return
        val finished = durationMs > 0 && positionMs >= durationMs * 0.95
        val editor = prefs.edit()
        if (finished || positionMs < MIN_SAVE_MS) {
            editor.remove(k)
        } else {
            editor.putString(k, "$positionMs,$durationMs,${System.currentTimeMillis()}")
        }
        editor.apply()
        prune()
    }

    private fun prune() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        val oldest = all.entries
            .mapNotNull { e -> (e.value as? String)?.split(',')?.getOrNull(2)?.toLongOrNull()?.let { e.key to it } }
            .sortedBy { it.second }
            .take(all.size - MAX_ENTRIES)
        val editor = prefs.edit()
        oldest.forEach { editor.remove(it.first) }
        editor.apply()
    }

    private fun key(pageUrl: String?): String? {
        if (pageUrl.isNullOrEmpty()) return null
        val host = HostUtil.hostOf(pageUrl) ?: return null
        return "p_" + (host + HostUtil.pathOf(pageUrl)).hashCode().toString(16)
    }

    private companion object {
        const val MIN_SAVE_MS = 15_000L
        const val MAX_ENTRIES = 150
    }
}
