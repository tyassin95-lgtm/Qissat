package tv.qissat.app.adblock

/**
 * Small allocation-light URL helpers. Deliberately independent of android.net.Uri and
 * java.net.URI so that they behave identically in unit tests and never throw on the
 * malformed URLs that ad scripts like to produce.
 */
object HostUtil {

    /** Lower-cased host of [url] without port/userinfo, or null if there is none. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 1) return null
        var start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        var authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.startsWith("[")) { // IPv6 literal
            val close = authority.indexOf(']')
            return if (close > 0) authority.substring(0, close + 1).lowercase() else null
        }
        val colon = authority.indexOf(':')
        if (colon >= 0) authority = authority.substring(0, colon)
        authority = authority.trimEnd('.')
        return authority.lowercase().ifEmpty { null }
    }

    fun schemeOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val i = url.indexOf(':')
        if (i < 1) return null
        return url.substring(0, i).lowercase()
    }

    /** Path plus query, lower-cased ("" if none). */
    fun pathAndQuery(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 1) return ""
        var i = schemeEnd + 3
        while (i < url.length && url[i] != '/' && url[i] != '?' && url[i] != '#') i++
        val hash = url.indexOf('#', i)
        val raw = if (hash >= 0) url.substring(i, hash) else url.substring(i)
        return raw.lowercase()
    }

    /** Path only (no query/fragment), lower-cased. */
    fun pathOf(url: String): String {
        val pq = pathAndQuery(url)
        val q = pq.indexOf('?')
        return if (q >= 0) pq.substring(0, q) else pq
    }

    /** Decoded value of query parameter [name], or null. */
    fun queryParam(url: String, name: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val hash = url.indexOf('#', q)
        val query = if (hash >= 0) url.substring(q + 1, hash) else url.substring(q + 1)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key == name) {
                val raw = if (eq >= 0) pair.substring(eq + 1) else ""
                return try {
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    raw
                }
            }
        }
        return null
    }

    /** True if [host] equals [domain] or is a subdomain of it. */
    fun isSameOrSubdomain(host: String, domain: String): Boolean {
        if (host == domain) return true
        return host.length > domain.length &&
            host.endsWith(domain) &&
            host[host.length - domain.length - 1] == '.'
    }

    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac")

    /**
     * Registrable "brand" label: the label directly left of the public suffix
     * (cdnplus in w.cdnplus.sbs / cdnplus.co.uk). Heuristic, good enough for allow-listing
     * rotating TLDs of a known brand.
     */
    fun brandLabel(host: String): String? {
        val parts = host.split('.')
        if (parts.size < 2) return null
        if (parts.last().all { it.isDigit() }) return null // IPv4
        var idx = parts.size - 2
        if (parts.size >= 3 && parts[idx] in SECOND_LEVEL && parts.last().length == 2) idx--
        return parts[idx].ifEmpty { null }
    }

    /** Registrable domain (brand + suffix) – used to compare "same site". */
    fun siteOf(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        var keep = 2
        if (parts[parts.size - 2] in SECOND_LEVEL && parts.last().length == 2) keep = 3
        return parts.takeLast(keep).joinToString(".")
    }
}
