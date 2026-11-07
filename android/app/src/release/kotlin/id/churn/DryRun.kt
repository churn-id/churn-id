package id.churn

import java.security.SecureRandom

/** Release builds have no dry run, which starts in debug builds (docs/testing.md, "Tiers"). */
@Suppress("UNUSED_PARAMETER")
class DryRun(router: Router, report: (Progress) -> Unit, check: () -> List<String>) {

    val testing = emptyList<String>()

    fun run(factory: ImeiPair, random: SecureRandom, save: (DryRunStart?) -> Unit): Unit =
        throw UnsupportedOperationException()

    fun putBack(start: DryRunStart, save: (DryRunStart?) -> Unit): Unit = throw UnsupportedOperationException()

    companion object {
        const val AVAILABLE = false
    }
}
