package tv.qissat.app.adblock

/**
 * Decides what the embedded browser may load.
 *
 *  * Top-level navigation: qisat.tv only.
 *  * Frames: qisat.tv, known video-player providers, or an embed-looking frame placed directly
 *    by a page we already trust. Everything else (ad iframes) is refused.
 *  * Sub-resources (scripts, media, XHR, images…): allowed unless they hit the ad host list or an
 *    ad path signature, so CDNs/streaming hosts keep working without being enumerated.
 */
class UrlPolicy(private val rules: Rules) {

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
        // A frame placed straight into the site or its player wrapper may be a provider we have
        // not heard of yet, but only if it looks like an embed. Frames nested deeper (inside a
        // provider page) must be a known provider; that is where ad iframes live. When the
        // referer is unknown we fall back to the path heuristic rather than break playback.
        if (refererHost == null) return looksLikeEmbed(url)
        val trustedParent = isFirstPartyHost(refererHost) ||
            HostUtil.brandLabel(refererHost) in WRAPPER_LABELS
        return trustedParent && looksLikeEmbed(url)
    }

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
        private val WRAPPER_LABELS = setOf("anaplayer")

        private val EMBED_HINTS = listOf(
            "/embed", "embed-", "/player", "/iframe", "/e/", "/v/", "/f/", "/watch", "/stream", "/video",
        )
    }
}
