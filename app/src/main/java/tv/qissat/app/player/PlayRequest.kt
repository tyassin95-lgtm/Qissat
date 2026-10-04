package tv.qissat.app.player

import android.content.Context
import android.content.Intent
import tv.qissat.app.web.MediaSniffer

/** Everything the native player needs to replay what the web player was about to play. */
data class PlayRequest(
    val url: String,
    val kind: MediaSniffer.Kind,
    val referer: String?,
    val userAgent: String,
    val cookie: String?,
    val title: String,
    val pageUrl: String?,
    val startMs: Long,
) {
    fun toIntent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putExtra(K_URL, url)
        .putExtra(K_KIND, kind.name)
        .putExtra(K_REFERER, referer)
        .putExtra(K_UA, userAgent)
        .putExtra(K_COOKIE, cookie)
        .putExtra(K_TITLE, title)
        .putExtra(K_PAGE, pageUrl)
        .putExtra(K_START, startMs)

    companion object {
        private const val K_URL = "url"
        private const val K_KIND = "kind"
        private const val K_REFERER = "referer"
        private const val K_UA = "ua"
        private const val K_COOKIE = "cookie"
        private const val K_TITLE = "title"
        private const val K_PAGE = "page"
        private const val K_START = "start"

        fun from(intent: Intent): PlayRequest? {
            val url = intent.getStringExtra(K_URL) ?: return null
            return PlayRequest(
                url = url,
                kind = runCatching { MediaSniffer.Kind.valueOf(intent.getStringExtra(K_KIND) ?: "") }
                    .getOrDefault(MediaSniffer.Kind.PROGRESSIVE),
                referer = intent.getStringExtra(K_REFERER),
                userAgent = intent.getStringExtra(K_UA) ?: "Mozilla/5.0",
                cookie = intent.getStringExtra(K_COOKIE),
                title = intent.getStringExtra(K_TITLE) ?: "",
                pageUrl = intent.getStringExtra(K_PAGE),
                startMs = intent.getLongExtra(K_START, 0L),
            )
        }
    }
}
