package tv.qissat.app

/** App-wide constants. */
object Config {
    const val HOME_URL = "https://www.qisat.tv/"

    /** Pseudo-host the injected shield script talks to; intercepted natively, never hits the network. */
    const val BRIDGE_HOST = "bridge.qissat.invalid"

    /** Seconds after which a main-frame load is treated as stalled. */
    const val PAGE_TIMEOUT_MS = 45_000L

    const val EXIT_CONFIRM_MS = 2_000L
}
