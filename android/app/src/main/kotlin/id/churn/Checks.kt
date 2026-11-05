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

/** The firmware and the modem we tested Churn with, as [Router.platform] reads them. */
val TESTED_PLATFORMS = listOf(
    Platform(gl = "4.10.0", openwrt = "23.05.4", modem = "Quectel RG650V-EU", revision = "RG650VEU00ADR02A04G8G"),
)

/** The checks, in the order of their claims in docs/testing.md. */
val CHECKS = listOf(
    Check("P1", blocks = false, title = R.string.check_platform) { router, _ -> platformTested(router) },
    Check("D7", blocks = true, title = R.string.check_firewall_loaded) { router, _ -> firewallLoaded(router) },
    Check("D10", blocks = false, title = R.string.check_dnsmasq_user) { router, _ -> dnsmasqUser(router) },
    Check("F5", blocks = false, title = R.string.check_imeis_kept, confirm = ::imeisKept),
)

/** Whether the router runs a firmware and a modem we tested Churn with. */
private fun platformTested(router: Router): CheckResult {
    val platform = router.platform()
    if (platform in TESTED_PLATFORMS) return CheckResult.Passed
    return with(platform) {
        CheckResult.Failed("GL firmware $gl (OpenWrt $openwrt), modem $modem $revision, which we haven't tested.")
    }
}

/** Whether GL's firewall has loaded Churn's rules file, if it is there. */
private fun firewallLoaded(router: Router) = when {
    router.firewallFile() == null -> CheckResult.NotApplicable(R.string.reason_no_firewall)
    router.firewallLoaded() -> CheckResult.Passed
    else -> CheckResult.Failed("GL's firewall table lacks chains of Churn's rules.")
}

/** Whether Churn's rules file, if it is there, names the user ID of GL's main DNS server, as [Router.firewallRules]. */
private fun dnsmasqUser(router: Router): CheckResult {
    val uid = router.dnsmasqUid()
    val rules = router.firewallFile() ?: return CheckResult.NotApplicable(R.string.reason_no_firewall)
    val named = Regex("""meta skuid (\d+) """).find(rules)?.groupValues?.get(1)?.toIntOrNull()
    return when (named) {
        uid -> CheckResult.Passed
        null -> CheckResult.Failed("Churn's rules name no user ID.")
        else -> CheckResult.Failed("Churn's rules name user ID $named, but the user dnsmasq has $uid.")
    }
}

/**
 * Whether the card that [folder] records, while it answers in slot 2, still has the IMEIs Churn gave it. The router
 * screen warns about any other card, which has no pair from Churn.
 */
private fun imeisKept(router: Router, folder: ChurnFolder): CheckResult {
    val record = folder.card ?: return CheckResult.NotApplicable(R.string.reason_no_record)
    if (router.slot2() == Slot2.ESIM) return CheckResult.NotApplicable(R.string.reason_slot2_on_esim)
    val card = (router.slot2Card() as? Slot2Card.Subscription)?.iccid?.let(Imei::iccidDigits)
    if (card != record.iccid) return CheckResult.NotApplicable(R.string.reason_record_not_in_slot2)
    return when (router.imeis()) {
        record.imeis -> CheckResult.Passed
        folder.factory -> CheckResult.Failed("The router has its factory IMEIs again.")
        else -> CheckResult.Failed("The IMEIs aren't the pair Churn recorded for the card.")
    }
}
