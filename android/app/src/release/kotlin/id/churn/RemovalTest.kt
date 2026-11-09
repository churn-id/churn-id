package id.churn

/** Release builds have no removal test, which starts in debug builds (router-tests.md, "Tiers"). */
@Suppress("UNUSED_PARAMETER")
class RemovalTest(
    router: Router,
    report: (Progress) -> Unit,
    check: () -> List<String>,
    setUpFolder: (ImeiPair, String?) -> Unit,
) {

    val testing = emptyList<String>()

    fun run(version: Int, save: (RemovalStart?) -> Unit): Unit = throw UnsupportedOperationException()

    fun setUpAgain(start: RemovalStart, save: (RemovalStart?) -> Unit): Unit = throw UnsupportedOperationException()

    companion object {
        const val AVAILABLE = false
    }
}
