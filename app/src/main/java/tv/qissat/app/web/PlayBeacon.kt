package tv.qissat.app.web

import tv.qissat.app.adblock.HostUtil

/** A "video started playing" report sent by the injected shield from inside a (nested) frame. */
data class PlayBeacon(
    val frameUrl: String?,
    val positionSec: Float,
    /** Duration in seconds; 0 = unknown, -1 = live/infinite. */
    val durationSec: Int,
    val widthPx: Int,
    val heightPx: Int,
    val muted: Boolean,
    val currentSrc: String?,
) {
    /** Real episodes only: ignore thumbnails, ad bumpers and tiny background clips. */
    val looksLikeMainVideo: Boolean
        get() = widthPx >= MIN_W && heightPx >= MIN_H && (durationSec <= 0 || durationSec >= MIN_DURATION_S)

    companion object {
        private const val MIN_W = 200
        private const val MIN_H = 100
        private const val MIN_DURATION_S = 90

        fun parse(url: String, nonce: String): PlayBeacon? {
            fun p(name: String) = HostUtil.queryParam(url, name)
            if (p("k") != nonce) return null
            if (p("e") != "play") return null
            return PlayBeacon(
                frameUrl = p("o"),
                positionSec = p("t")?.toFloatOrNull() ?: 0f,
                durationSec = p("d")?.toIntOrNull() ?: 0,
                widthPx = p("w")?.toIntOrNull() ?: 0,
                heightPx = p("h")?.toIntOrNull() ?: 0,
                muted = p("m") == "1",
                currentSrc = p("s")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
