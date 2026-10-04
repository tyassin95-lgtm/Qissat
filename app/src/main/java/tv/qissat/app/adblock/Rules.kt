package tv.qissat.app.adblock

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream

/** Immutable rule set loaded from the text files in `assets/adblock/` (shared with the unit tests). */
class Rules(
    blockedHosts: Collection<String>,
    blockedPaths: Collection<String>,
    frameProviders: Collection<String>,
) {
    private val hostSet: Set<String> = blockedHosts.toHashSet()
    val blockedPaths: List<String> = blockedPaths.toList()
    private val providerLabels: Set<String> = frameProviders.filter { '.' !in it }.toHashSet()
    private val providerHosts: Set<String> = frameProviders.filter { '.' in it }.toHashSet()

    /** True if [host] or any parent domain is on the host block list. */
    fun isBlockedHost(host: String): Boolean {
        var h = host
        while (true) {
            if (h in hostSet) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun isBlockedPath(pathAndQueryLower: String): Boolean {
        for (p in blockedPaths) if (pathAndQueryLower.contains(p)) return true
        return false
    }

    /** True if [host] belongs to a known video-player / embed provider. */
    fun isFrameProvider(host: String): Boolean {
        if (providerHosts.any { HostUtil.isSameOrSubdomain(host, it) }) return true
        val label = HostUtil.brandLabel(host) ?: return false
        return label in providerLabels
    }

    companion object {
        private const val DIR = "adblock/"

        fun load(context: Context): Rules {
            val am = context.assets
            return Rules(
                blockedHosts = am.open(DIR + "blocked_hosts.txt").use(::lines),
                blockedPaths = am.open(DIR + "blocked_paths.txt").use(::lines),
                frameProviders = am.open(DIR + "frame_providers.txt").use(::lines),
            )
        }

        fun fromStreams(hosts: InputStream, paths: InputStream, providers: InputStream) =
            Rules(hosts.use(::lines), paths.use(::lines), providers.use(::lines))

        internal fun lines(stream: InputStream): List<String> =
            stream.bufferedReader().use(BufferedReader::readLines)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
