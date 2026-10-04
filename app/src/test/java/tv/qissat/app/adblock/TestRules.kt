package tv.qissat.app.adblock

import java.io.File

/** Loads the real rule files that ship in the APK, so tests exercise production data. */
object TestRules {
    private fun asset(name: String): File {
        val candidates = listOf(
            File("src/main/assets/adblock/$name"),
            File("app/src/main/assets/adblock/$name"),
        )
        return candidates.first { it.exists() }
    }

    fun load(): Rules = Rules.fromStreams(
        asset("blocked_hosts.txt").inputStream(),
        asset("blocked_paths.txt").inputStream(),
        asset("frame_providers.txt").inputStream(),
    )

    fun policy() = UrlPolicy(load())
}
