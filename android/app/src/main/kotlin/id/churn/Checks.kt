package id.churn

/**
 * A check of the router that only reads, tier 1 in docs/testing.md. It confirms the claims there with the IDs [ids],
 * such as `D7`, and one that [blocks] confirms a claim that Churn's commands can't be trusted without. [title] says
 * what it confirms, as a string resource.
 */
class Check(
    vararg val ids: String,
    val blocks: Boolean,
    val title: Int,
    private val confirm: (Router, ChurnFolder) -> CheckResult,
) {

    /** Runs the check on [router], whose Churn folder is [folder]. An answer the check doesn't expect fails it. */
    fun run(router: Router, folder: ChurnFolder) = try {
        confirm(router, folder)
    } catch (e: RouterException) {
        CheckResult.Failed("Unexpected answer: ${e.detail ?: e.problem}")
    }
}

/** What a [Check] found. */
sealed interface CheckResult {
    data object Passed : CheckResult

    /** [detail] says what the router showed instead. */
    data class Failed(val detail: String) : CheckResult

    /** The router's state rules the check out, for the reason that the string resource [reason] gives. */
    data class NotApplicable(val reason: Int) : CheckResult
}

/** The checks, in the order of their claims in docs/testing.md. */
val CHECKS = listOf(
    Check("D7", blocks = true, title = R.string.check_firewall_loaded) { router, _ -> firewallLoaded(router) },
)

/** Whether GL's firewall has loaded Churn's rules file, if it is there. */
private fun firewallLoaded(router: Router) = when {
    router.firewallFile() == null -> CheckResult.NotApplicable(R.string.reason_no_firewall)
    router.firewallLoaded() -> CheckResult.Passed
    else -> CheckResult.Failed("GL's firewall table lacks chains of Churn's rules.")
}
