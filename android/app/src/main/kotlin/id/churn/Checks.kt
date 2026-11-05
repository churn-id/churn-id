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
    Check("P2", blocks = true, title = R.string.check_commands) { router, _ -> commandsFound(router, CHURN_COMMANDS) },
    Check("P3", blocks = false, title = R.string.check_md5sum) { router, _ -> commandsFound(router, listOf("md5sum")) },
    Check("P4", "M1", blocks = true, title = R.string.check_radio_answer) { router, _ -> radioAnswered(router) },
    Check("M2", blocks = true, title = R.string.check_imeis_read, confirm = ::imeisRead),
    Check("M3", blocks = true, title = R.string.check_slot2_answer) { router, _ ->
        // An answer other than the SIM 2 tray or the eSIM fails it.
        router.slot2()
        CheckResult.Passed
    },
    Check("M4", blocks = false, title = R.string.check_slot2_subscription) { router, _ -> slot2Subscription(router) },
    Check("M5", blocks = false, title = R.string.check_modem_settings) { router, _ -> modemSettingsSearched(router) },
    Check("U1", blocks = true, title = R.string.check_airplane_mode) { router, _ -> airplaneModeKept(router) },
    Check("U4", blocks = true, title = R.string.check_scheduled_reboot) { router, _ ->
        settingIn(router.setting(Router.SCHEDULED_REBOOT), "0", "1")
    },
    Check("U5", blocks = false, title = R.string.check_modem_iccid) { router, _ -> activeCardIccid(router) },
    Check("U6", blocks = true, title = R.string.check_active_slot) { router, _ ->
        settingIn(router.activeSlot(), "1", "2")
    },
    Check("K1", blocks = true, title = R.string.check_vpn_policy) { router, _ -> vpnPolicy(router) },
    Check("K5", blocks = false, title = R.string.check_kill_switch_settings) { router, _ -> killSwitchAgrees(router) },
    Check("K6", blocks = true, title = R.string.check_kill_switch_rules) { router, _ -> killSwitchRules(router) },
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

/** The commands Churn runs on the router besides the shell's own and BusyBox's usual ones. */
private val CHURN_COMMANDS = listOf("flock", "curl", "ubus", "fw4", "nft")

/** Whether the router's shell finds the commands [names]. */
private fun commandsFound(router: Router, names: List<String>): CheckResult {
    val missing = router.missingCommands(names)
    return if (missing.isEmpty()) CheckResult.Passed else CheckResult.Failed("Missing: ${missing.joinToString(", ")}.")
}

/** Whether the modem answers `AT+CFUN?` through `gl_modem`, ending with `OK`, with the radio on or off. */
private fun radioAnswered(router: Router) = when (val radio = router.radio()) {
    1, 4 -> CheckResult.Passed
    else -> CheckResult.Failed("AT+CFUN? answers $radio.")
}

/** Whether the modem reads both IMEIs as valid ones with the TACs of the factory IMEIs that setup recorded. */
private fun imeisRead(router: Router, folder: ChurnFolder): CheckResult {
    val factory = folder.factory ?: return CheckResult.NotApplicable(R.string.reason_not_set_up)
    val imeis = router.imeis()
    val problems = listOf(imeis.imei1 to factory.imei1, imeis.imei2 to factory.imei2).mapIndexedNotNull { i, pair ->
        val (imei, original) = pair
        when {
            !Imei.isValid(imei) -> "IMEI ${i + 1} has a wrong check digit."
            Imei.tac(imei) != Imei.tac(original) -> "IMEI ${i + 1} has another TAC than the factory IMEI ${i + 1}."
            else -> null
        }
    }
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/** Whether the modem names slot 2's card's subscription `SUB2`, as development.md says, whichever slot is active. */
private fun slot2Subscription(router: Router): CheckResult {
    val answer = router.at("AT+QUIMSUB?").after("+QUIMSUB:")
    if (answer?.startsWith("2,\"SUB2\"") == true) return CheckResult.Passed
    return CheckResult.Failed("AT+QUIMSUB? answers ${answer?.take(40) ?: "without +QUIMSUB:"}.")
}

/**
 * What the modem's test commands listed when we searched them for a setting that starts the radio off at power-on,
 * and found none: `AT+CFUN=?`'s answer, and the names of the settings that `AT+QCFG=?` and `AT+QNWCFG=?` list.
 */
private const val SEARCHED_CFUN = "(0,1,4),(0,1)"

// cspell:disable
private val SEARCHED_QCFG = setOf(
    "rrc", "hsdpacat", "hsupacat", "pdp/duplicatechk", "risignaltype", "lte/bandprior", "volte_disable",
    "diversity/config", "div_test_mode", "usbnet", "usbspeed", "data_interface", "pcie/mode", "sms_control",
    "call_control", "netmaskset", "clat", "enable_gea1", "dhcppktfltr",
)

private val SEARCHED_QNWCFG = setOf(
    "lte_cdrx", "nr5g_cdrx", "csi_ctrl", "lte_csi", "nr5g_csi", "lte_cell_id", "nr5g_cell_id", "lte_csi_ext",
    "nr5g_csi_ext", "lte_tx_pwr", "nr5g_tx_pwr", "wcdma_cqi", "up/down", "data_path", "dss_enable", "lte_dl_tx_mode",
    "nr5g_ulMCS", "nr5g_dlMCS", "nr5g_pusch_data", "lapi", "nr5g_meas_info", "lte_time_advance", "nr5g_time_advance",
    "clr_rplmn", "dis_rplmnact", "lte_ambr", "nr5g_ambr", "disable_lte_ca", "disable_nr_ca", "dis_4mimo_enable",
    "nr5g_4mimo_enable", "nr5g_ulTBsize", "encryp_alg_support", "integ_alg_support", "data_roaming", "nr5g_earfcn_lock",
    "lte_earfcn_lock", "event_a3_offset", "used_algo", "nr5g_pref_freq_list", "lte_pref_freq_list", "ehplmn_config",
    "nr5g_mimo", "ctrl_plane_dly", "rrc_state", "ssb_beam_id", "ul_data_path", "lte_ulMCS", "lte_mimo_layers",
    "nr5g_mimo_layers", "wcdma_tx_power", "lte_band_priority", "nr5g_band_priority", "3gpp_rel", "cause7_map_cause14",
    "nr5g_ul_256qam", "msearch_bscan_sep", "thin_ui_cfg", "freq_info", "msisdn", "lte_fgi_fdd", "lte_fgi_tdd",
    "sysmode", "nr5g_ulbw", "cops_auto_mode", "nr5g_pathloss", "nitz_ons", "clr_guti", "nr5g_mimo_info",
    "lte_mimo_info", "hplmn_search_timer", "center_arfcn_info", "eth_cfg", "lte_qos", "nr5g_qos", "rrc_inactive",
    "overheat_ind", "q8rxrssi", "c_rnti", "reg_time_info", "rrc_time_info", "pdu_time_info", "rrc_seg",
)
// cspell:enable

/** Whether the modem's test commands list no setting beyond those we searched for one that starts the radio off. */
private fun modemSettingsSearched(router: Router): CheckResult {
    val radioStates = router.at("AT+CFUN=?").after("+CFUN:")
    val newSettings = mapOf("QCFG" to SEARCHED_QCFG, "QNWCFG" to SEARCHED_QNWCFG).flatMap { (command, searched) ->
        router.at("AT+$command=?").lines
            .mapNotNull { Regex("""\+$command: "([^"]+)".*""").matchEntire(it)?.groupValues?.get(1) }
            .filter { it !in searched }
            .map { "AT+$command \"${it.take(30)}\"" }
    }
    val problems = listOfNotNull(
        "AT+CFUN=? answers ${radioStates?.take(40)}, not $SEARCHED_CFUN.".takeIf { radioStates != SEARCHED_CFUN },
        "Settings we haven't searched: ${newSettings.joinToString(", ")}.".takeIf { newSettings.isNotEmpty() },
    )
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/**
 * Whether GL's airplane mode setting is `0` or `1`, and with `1` the radio is off once the router has started, that is
 * once the radio is on for no more than the minute or so after power-on.
 */
private fun airplaneModeKept(router: Router): CheckResult {
    val setting = router.setting(Router.AIRPLANE_MODE)
    return when {
        setting == "0" -> CheckResult.Passed
        setting != "1" -> settingFailed(setting)
        router.uptimeSeconds() < Swap.SETTLED_UPTIME_SECONDS -> CheckResult.NotApplicable(R.string.reason_starting)
        else -> when (val radio = router.radio()) {
            4 -> CheckResult.Passed
            else -> CheckResult.Failed("Airplane mode is on, but AT+CFUN? answers $radio.")
        }
    }
}

/** Whether GL records the active card by an ICCID, as the modem gives it, while it records one. */
private fun activeCardIccid(router: Router): CheckResult {
    val value = router.setting(Router.MODEM_ICCID) ?: return CheckResult.NotApplicable(R.string.reason_no_active_card)
    if (Imei.iccidDigits(value) != null) return CheckResult.Passed
    // Not the value itself, which may hold a card's number all the same.
    return CheckResult.Failed("The setting holds ${value.length} characters that aren't an ICCID.")
}

/**
 * Whether GL's route policy has its default section, which holds the Enhanced Kill Switch's setting, and a WireGuard
 * tunnel in the form [Router.vpnSetUp] looks for.
 */
private fun vpnPolicy(router: Router) = when {
    router.setting("route_policy.@default[0]") != "default" ->
        CheckResult.Failed("GL's route policy has no default section.")
    router.vpnSetUp() -> CheckResult.Passed
    else -> CheckResult.NotApplicable(R.string.reason_no_vpn)
}

/**
 * Whether GL's two settings of the Enhanced Kill Switch and the routing rule at priority 9000 agree on whether it is
 * on, as [Router.enhancedKillSwitch] reads them: turning the switch on or off in GL's web interface changes all three.
 */
private fun killSwitchAgrees(router: Router): CheckResult {
    val policy = router.setting(Router.DEFAULT_POLICY)
    val vpnToMain = router.setting(Router.VPN_TO_MAIN) != null
    val rule = router.ipRules().any { it.startsWith("9000:") }
    if (policy == "0" && !vpnToMain && !rule || policy == "1" && vpnToMain && rule) return CheckResult.Passed
    val value = policy?.let { "\"${it.take(20)}\"" } ?: "missing"
    return CheckResult.Failed(
        "${Router.DEFAULT_POLICY} is $value, ${Router.VPN_TO_MAIN} is ${if (vpnToMain) "there" else "absent"}, " +
            "and ip rule show lists ${if (rule) "a" else "no"} rule at priority 9000.",
    )
}

/**
 * GL's routing rules with the Enhanced Kill Switch on and a WireGuard tunnel set up, as development.md lists them, in
 * `ip rule show`'s words with single spaces: traffic marked for GL's fallback policy goes to the main table, other
 * marked traffic and devices' unmarked traffic to a blackhole.
 */
private val KILL_SWITCH_RULES = listOf(
    "6000: from all fwmark 0x8000/0xf000 lookup main",
    "9910: not from all fwmark 0/0xf000 blackhole",
    "9920: from all iif br-lan blackhole",
)

/** GL's routing rule that sends traffic marked for the tunnel to the tunnel's table, while the tunnel is up. */
private const val TUNNEL_RULE = "6000: from all fwmark 0x1000/0xf000 lookup 1001"

/** Whether, with the Enhanced Kill Switch on, `ip rule show` lists GL's rules that send traffic through the tunnel. */
private fun killSwitchRules(router: Router): CheckResult {
    val tunnels = router.vpnInterfaces()
    if (tunnels.isEmpty()) return CheckResult.NotApplicable(R.string.reason_no_vpn)
    if (!router.enhancedKillSwitch()) return CheckResult.NotApplicable(R.string.reason_kill_switch_off)
    val rules = router.ipRules().map { it.replace(Regex("\\s+"), " ") }
    val tunnelUp = tunnels.any { router.interfaceStatus(it)?.optBoolean("up") == true }
    val missing = (KILL_SWITCH_RULES + listOfNotNull(TUNNEL_RULE.takeIf { tunnelUp })).filter { it !in rules }
    return if (missing.isEmpty()) CheckResult.Passed else CheckResult.Failed("Missing: ${missing.joinToString(", ")}.")
}

/** Whether a uci setting holds one of [expected], given its [value], or null if it isn't set. */
private fun settingIn(value: String?, vararg expected: String) =
    if (value in expected) CheckResult.Passed else settingFailed(value)

/** A failed check of a uci setting that holds [value], or that isn't set. */
private fun settingFailed(value: String?) =
    CheckResult.Failed(if (value == null) "The setting is missing." else "The setting is \"${value.take(20)}\".")

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
