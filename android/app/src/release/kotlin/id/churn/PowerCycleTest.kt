package id.churn

/** Release builds have no power cycle test, which starts in debug builds (docs/testing.md, "Tiers"). */
@Suppress("UNUSED_PARAMETER")
class PowerCycleTest(
    router: Router,
    report: (Progress) -> Unit,
    measured: (Progress, Int) -> Unit,
    check: () -> List<String>,
    awaitNetworkLost: (Long) -> Boolean,
) {

    val testing = emptyList<String>()

    fun run(save: (PowerCycleStart?) -> Unit): Unit = throw UnsupportedOperationException()

    fun resume(start: PowerCycleStart, save: (PowerCycleStart?) -> Unit): Unit = throw UnsupportedOperationException()

    fun putBack(start: PowerCycleStart, save: (PowerCycleStart?) -> Unit): Unit = throw UnsupportedOperationException()

    companion object {
        const val AVAILABLE = false
    }
}
