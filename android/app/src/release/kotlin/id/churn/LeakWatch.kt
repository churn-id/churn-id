package id.churn

/** Release builds have no leak watch, which starts in debug builds (docs/testing.md, "Tiers"). */
@Suppress("UNUSED_PARAMETER")
class LeakWatch(
    router: Router,
    report: (Progress) -> Unit,
    measured: (Progress, List<Long>) -> Unit,
    check: () -> List<String>,
) {

    val testing = emptyList<String>()

    fun run(card: Card?): Unit = throw UnsupportedOperationException()

    companion object {
        const val AVAILABLE = false
    }
}
