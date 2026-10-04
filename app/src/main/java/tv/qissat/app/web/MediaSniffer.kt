package tv.qissat.app.web

import android.os.SystemClock
import tv.qissat.app.adblock.HostUtil

/**
 * Remembers recent media URLs (HLS / DASH / progressive) that the page requested, so that when
 * the player frame reports "play" the app knows which stream to hand to the native Media3 player.
 */
class MediaSniffer {

    enum class Kind { HLS, DASH, PROGRESSIVE }

    data class Candidate(
        val url: String,
        val kind: Kind,
        /** Referer the web player used (or the frame URL), forwarded to the native player. */
        val referer: String?,
        val atMs: Long,
    )

    private val lock = Any()
    private val items = ArrayDeque<Candidate>()

    fun record(url: String, requestHeaders: Map<String, String>?) {
        val kind = classify(url) ?: return
        val referer = requestHeaders?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
        synchronized(lock) {
            if (items.any { it.url == url }) return
            items.addLast(Candidate(url, kind, referer, SystemClock.elapsedRealtime()))
            while (items.size > MAX_ITEMS) items.removeFirst()
        }
    }

    fun clear() = synchronized(lock) { items.clear() }

    /**
     * @param frameUrl URL of the frame that owns the playing <video>.
     * @param currentSrc the element's `currentSrc` when it is a plain http(s) URL (not a blob).
     */
    fun pick(frameUrl: String?, currentSrc: String?, now: Long = SystemClock.elapsedRealtime()): Candidate? {
        if (!currentSrc.isNullOrEmpty() && HostUtil.schemeOf(currentSrc)?.startsWith("http") == true) {
            return Candidate(currentSrc, classify(currentSrc) ?: Kind.PROGRESSIVE, frameUrl, now)
        }
        val snapshot = synchronized(lock) { items.filter { now - it.atMs <= MAX_AGE_MS } }
        if (snapshot.isEmpty()) return null

        val frameHost = HostUtil.hostOf(frameUrl)
        val sameFrame = if (frameHost == null) snapshot else
            snapshot.filter { HostUtil.hostOf(it.referer) == frameHost }.ifEmpty { snapshot }

        val hls = sameFrame.filter { it.kind == Kind.HLS }
        if (hls.isNotEmpty()) {
            return hls.lastOrNull { isMasterLike(it.url) } ?: hls.first()
        }
        return sameFrame.lastOrNull { it.kind == Kind.DASH }
            ?: sameFrame.lastOrNull { it.kind == Kind.PROGRESSIVE }
    }

    companion object {
        private const val MAX_ITEMS = 32
        private const val MAX_AGE_MS = 30 * 60 * 1000L

        fun classify(url: String): Kind? {
            val path = HostUtil.pathOf(url)
            return when {
                path.endsWith(".m3u8") -> Kind.HLS
                path.endsWith(".mpd") -> Kind.DASH
                path.endsWith(".mp4") || path.endsWith(".m4v") ||
                    path.endsWith(".webm") || path.endsWith(".mkv") -> Kind.PROGRESSIVE
                else -> null
            }
        }

        internal fun isMasterLike(url: String): Boolean {
            val path = HostUtil.pathOf(url)
            return MASTER_HINTS.any { path.contains(it) }
        }

        private val MASTER_HINTS = listOf("master", "urlset", "playlist.m3u8", "manifest")
    }
}
