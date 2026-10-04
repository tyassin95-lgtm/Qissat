package tv.qissat.app.adblock

/**
 * Decides what the embedded browser may load.
 *
 *  * Top-level navigation: qisat.tv only.
 *  * Frames: qisat.tv, known video-player providers, the site's player-wrapper pages (known or
 *    recognised by their `/albaplayer/` URL shape) and whatever those wrappers frame. Frames
 *    placed into the site itself must look like players. Everything else (ad iframes) is refused.
 *  * Sub-resources (scripts, media, XHR, images…): allowed unless they hit the ad host list or an
 *    ad path signature, so CDNs/streaming hosts keep working without being enumerated.
 */
class UrlPolicy(private val rules: Rules) {

    /** Wrapper hosts discovered at runtime (requests arrive on several threads). */
    private val learnedWrappers: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    enum class Nav { ALLOW, UPGRADE_TO_HTTPS, BLOCK }

    fun isFirstPartyHost(host: String?): Boolean =
        host != null && HostUtil.isSameOrSubdomain(host, FIRST_PARTY)

    fun isFirstPartyUrl(url: String?): Boolean = isFirstPartyHost(HostUtil.hostOf(url))

    /** Top-level navigation check (links, redirects, location changes). */
    fun navigation(url: String): Nav {
        val scheme = HostUtil.schemeOf(url) ?: return Nav.BLOCK
        if (scheme == "about" && url == "about:blank") return Nav.ALLOW
        if (scheme != "http" && scheme != "https") return Nav.BLOCK
        val host = HostUtil.hostOf(url) ?: return Nav.BLOCK
        if (!isFirstPartyHost(host)) return Nav.BLOCK
        return if (scheme == "http") Nav.UPGRADE_TO_HTTPS else Nav.ALLOW
    }

    /**
     * Network-level filter for everything except top-level navigations.
     *
     * @param isDocument true when the request loads an HTML document (iframe).
     * @param refererHost host of the page that issued the request, if known.
     */
    fun allowRequest(url: String, isDocument: Boolean, refererHost: String?): Boolean {
        val scheme = HostUtil.schemeOf(url)
        if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return true
        val host = HostUtil.hostOf(url) ?: return false

        if (isFirstPartyHost(host)) return true
        if (rules.isBlockedHost(host)) return false
        if (rules.isBlockedPath(HostUtil.pathAndQuery(url))) return false

        if (!isDocument) return true
        return allowFrame(url, host, refererHost)
    }

    private fun allowFrame(url: String, host: String, refererHost: String?): Boolean {
        if (rules.isFrameProvider(host)) return true
        val wrapperPage = HostUtil.pathOf(url).contains(WRAPPER_PATH)

        // Referer unknown: fall back to what the URL looks like rather than break playback.
        if (refererHost == null) {
            return (looksLikeEmbed(url) || wrapperPage).also { if (it && wrapperPage) learnedWrappers += host }
        }
        // Pages of the site's own player plugin only ever contain the real provider frame, so
        // anything (not on the ad lists) placed by one of them is accepted.
        if (isWrapperHost(refererHost)) {
            if (wrapperPage) learnedWrappers += host
            return true
        }
        // The site itself places the player wrapper or a directly embedded provider; ad
        // scripts also inject frames into it, so those must at least look like players.
        if (isFirstPartyHost(refererHost)) {
            val ok = wrapperPage || looksLikeEmbed(url)
            if (ok && wrapperPage) learnedWrappers += host
            return ok
        }
        // Frames nested deeper, inside a provider page, are where ad iframes live.
        return false
    }

    /** A host serving the site's player-wrapper plugin (known by name, or learned from its URL shape). */
    private fun isWrapperHost(host: String): Boolean =
        host in learnedWrappers || HostUtil.brandLabel(host) in WRAPPER_LABELS

    private fun looksLikeEmbed(url: String): Boolean {
        val p = HostUtil.pathOf(url)
        return EMBED_HINTS.any { p.contains(it) }
    }

    /** True for navigations that must never leave the app (market:, intent:, mailto:, …). */
    fun isExternalScheme(url: String): Boolean {
        val scheme = HostUtil.schemeOf(url) ?: return false
        return scheme != "http" && scheme != "https" && scheme != "about" &&
            scheme != "javascript" && scheme != "data" && scheme != "blob"
    }

    companion object {
        const val FIRST_PARTY = "qisat.tv"

        /** Player wrappers the site itself embeds; they in turn embed the real providers. */
        private val WRAPPER_LABELS = setOf("anaplayer", "shooof")

        /** URL shape of the AlbaPlayer plugin pages; the wrapper domains rotate, the path does not. */
        private const val WRAPPER_PATH = "/albaplayer/"

        private val EMBED_HINTS = listOf(
            "/embed", "embed-", "/player", "/iframe", "/e/", "/v/", "/f/", "/watch", "/stream", "/video",
        )
    }
}
