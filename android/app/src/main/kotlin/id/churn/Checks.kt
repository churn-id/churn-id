package id.churn

import kotlin.math.abs
import org.json.JSONObject

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
    Check("K7", blocks = false, title = R.string.check_usb_port) { router, _ -> usbPortInLan(router) },
    Check("D1", blocks = false, title = R.string.check_dns_servers) { router, _ -> dnsServers(router) },
    Check("D2", blocks = false, title = R.string.check_dns_rules) { router, _ -> glDnsRules(router) },
    Check("D5", blocks = false, title = R.string.check_own_traffic_routes) { router, _ -> ownTrafficRoutes(router) },
    Check("D6", blocks = false, title = R.string.check_own_traffic_targets) { router, _ -> ownTrafficTargets(router) },
    Check("D7", blocks = true, title = R.string.check_firewall_loaded) { router, _ -> firewallLoaded(router) },
    Check("D8", blocks = true, title = R.string.check_sim_interfaces) { router, _ -> simInterfaces(router) },
    Check("D9", blocks = false, title = R.string.check_wireguard_mark) { router, _ -> wireguardMark(router) },
    Check("D10", blocks = false, title = R.string.check_dnsmasq_user) { router, _ -> dnsmasqUser(router) },
    Check("D13", blocks = false, title = R.string.check_masquerading) { router, _ -> tunnelMasquerading(router) },
    Check("S1", blocks = true, title = R.string.check_location_id) { router, _ -> locationId(router) },
    Check("E1", blocks = true, title = R.string.check_esim_answers) { router, _ -> esimAnswers(router) },
    Check("E2", blocks = false, title = R.string.check_iccid_padding) { router, _ -> iccidPadding(router) },
    Check("E3", blocks = false, title = R.string.check_esim_placeholder) { router, _ -> esimPlaceholder(router) },
    Check("E5", blocks = false, title = R.string.check_esim_manager) { router, _ -> esimManagerBlocked(router) },
    Check("B6", blocks = true, title = R.string.check_boot_id) { router, _ ->
        // An ID other than a UUID fails it.
        router.bootId()
        CheckResult.Passed
    },
    Check("B16", blocks = false, title = R.string.check_test_profile) { router, _ -> testProfile(router) },
    Check("H1", blocks = false, title = R.string.check_flash_log) { router, _ -> flashLogWritten(router) },
    Check("H2", blocks = false, title = R.string.check_flash_log_stopped) { router, _ -> flashLogStopped(router) },
    Check("H4", blocks = false, title = R.string.check_card_files) { router, _ -> cardFileFormats(router) },
    Check("H5", blocks = false, title = R.string.check_esim_log_time) { router, _ -> esimLogInUtc(router) },
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
    val rules = spacedRules(router)
    val tunnelUp = tunnels.any { router.interfaceStatus(it)?.optBoolean("up") == true }
    val missing = (KILL_SWITCH_RULES + listOfNotNull(TUNNEL_RULE.takeIf { tunnelUp })).filter { it !in rules }
    return if (missing.isEmpty()) CheckResult.Passed else CheckResult.Failed("Missing: ${missing.joinToString(", ")}.")
}

/** The routing rules in use, as `ip rule show` lists them, with single spaces between the words. */
private fun spacedRules(router: Router) = router.ipRules().map { it.replace(Regex("\\s+"), " ") }

/** Whether the USB-C port's network device is in `br-lan`, whose unmarked traffic the blackhole at 9920 stops. */
private fun usbPortInLan(router: Router): CheckResult {
    val link = router.link("ecm0") ?: return CheckResult.Failed("The router has no network device ecm0.")
    return when (val bridge = Regex(""" master (\S+) """).find(link.lineSequence().first())?.groupValues?.get(1)) {
        "br-lan" -> CheckResult.Passed
        null -> CheckResult.Failed("ecm0 is in no bridge.")
        else -> CheckResult.Failed("ecm0 is in ${bridge.take(20)}, not br-lan.")
    }
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

/**
 * Whether GL's main DNS server listens on UDP port 53, runs as user `dnsmasq` and takes its servers from the file
 * with those of the active uplink, such as the cellular interface, and the tunnel's own listens on UDP port 2153,
 * runs as `dnsmasq_vpn` and takes its servers from the tunnel's file.
 */
private fun dnsServers(router: Router): CheckResult {
    val tunnels = router.vpnInterfaces()
    if (tunnels.isEmpty()) return CheckResult.NotApplicable(R.string.reason_no_vpn)
    val servers = router.dnsServers()
    val main = DnsServer(53, "dnsmasq", router.dnsmasqUid(), "/tmp/resolv.conf.d/resolv.conf.auto")
    val tunnelUid = router.userId("dnsmasq_vpn")
    val tunnel = tunnels.map { DnsServer(2153, "dnsmasq", tunnelUid, "/tmp/resolv.conf.d/resolv.conf.$it") }
    val problems = listOf(53 to listOf(main), 2153 to tunnel).mapNotNull { (port, expected) ->
        val found = servers.filter { it.port == port }
        when {
            found.isEmpty() -> "Nothing listens on UDP port $port."
            found.all { it in expected } -> null
            else -> "On UDP port $port: " + found.joinToString(", ") {
                "${it.program.take(20)} as user ID ${it.uid}, servers from ${it.serversFile?.take(60)}"
            } + "."
        }
    }
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/** GL's rule that redirects devices' lookups marked for the tunnel to its DNS server, as nftables lists it. */
private const val GL_DNS_REDIRECT =
    "meta l4proto udp meta mark & 0x0000f000 == 0x00001000 fib daddr type local redirect to :2153"

/** GL's rule that drops lookups that devices send past the router without the mark of GL's fallback policy. */
private val GL_DNS_DROP = Regex(
    "udp dport 53 meta mark & 0x0000f000 != 0x00008000 (counter packets \\d+ bytes \\d+ )?drop " +
        "comment \"!fw4: lan_drop_leaked_dns\"",
)

/** Whether GL's firewall has its rules that keep devices' lookups in the tunnel, as development.md describes them. */
private fun glDnsRules(router: Router): CheckResult {
    if (!router.vpnSetUp()) return CheckResult.NotApplicable(R.string.reason_no_vpn)
    val rules = router.firewallLines("redirect to :2153", "lan_drop_leaked_dns")
    val problems = listOfNotNull(
        "No rule redirects devices' lookups marked for the tunnel to port 2153.".takeIf { GL_DNS_REDIRECT !in rules },
        "No rule lan_drop_leaked_dns drops lookups past the router without the mark 0x8000."
            .takeIf { rules.none(GL_DNS_DROP::matches) },
    )
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/** The SIM's data interfaces, as development.md names them. */
private val SIM_INTERFACE = Regex("rmnet_data[0-9]+")

/**
 * Whether, without Churn's rules, what the router sends itself would go over the SIM: the rule at priority 1 points to
 * the empty table 16800, so it falls through to the main table, whose default route goes over the SIM's interface.
 * Without a default route, such as in airplane mode, only the rule and the table count.
 */
private fun ownTrafficRoutes(router: Router): CheckResult {
    if ("1: from all iif lo lookup 16800" !in spacedRules(router)) {
        return CheckResult.Failed("ip rule show lists no rule at priority 1 to table 16800.")
    }
    if (router.routes("16800").isNotEmpty()) return CheckResult.Failed("Table 16800 has routes.")
    val route = router.routes("main").firstOrNull { it.startsWith("default ") } ?: return CheckResult.Passed
    val device = Regex(""" dev (\S+)""").find(route)?.groupValues?.get(1)
    if (device?.matches(SIM_INTERFACE) == true) return CheckResult.Passed
    return CheckResult.NotApplicable(R.string.reason_other_uplink)
}

/** The time servers chrony was set up with on our Mudi 7, which it reaches over the SIM without Churn's rules. */
// cspell:disable
private val WATCHED_TIME_SERVERS = setOf(
    "time.cloudflare.com", "ntppool1.time.nl", "nts.netnod.se", "ptbtime1.ptb.de", "time.dfm.dk", "time.cifelli.xyz",
)
// cspell:enable

/** What GL's connection check pings over the SIM on our Mudi 7, as `uci get` lists it. */
private const val WATCHED_CONNECTION_CHECK = "ping,1.1.1.1 ping,8.8.8.8 ping,208.67.222.222 ping,208.67.220.220"

/**
 * Whether the router would still reach the time servers and the addresses we watched it reach over the SIM, without
 * Churn's rules: chrony's servers, and those that GL's connection check pings over the SIM's interface.
 */
private fun ownTrafficTargets(router: Router): CheckResult {
    val timeServers = router.settings("chrony").filterKeys { it.endsWith(".hostname") }.values.toSet()
    val pings = router.setting("kmwan.modem_cpu.tracks")
    val problems = listOfNotNull(
        "Chrony's time servers are ${timeServers.sorted().joinToString(", ").take(200)}."
            .takeIf { timeServers != WATCHED_TIME_SERVERS },
        "GL's connection check over the SIM pings ${pings?.take(200) ?: "nothing"}."
            .takeIf { pings != WATCHED_CONNECTION_CHECK },
    )
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/**
 * Whether the SIM's data interfaces are those Churn's rules name: the router has network devices named so, in
 * airplane mode too, and GL's cellular interface uses one of them while it is up.
 */
private fun simInterfaces(router: Router): CheckResult {
    if (router.networkDevices().none(SIM_INTERFACE::matches)) {
        return CheckResult.Failed("No network device is named rmnet_data and a number.")
    }
    val status = router.interfaceStatus("modem_cpu")
        ?: return CheckResult.Failed("GL has no network interface modem_cpu.")
    if (!status.optBoolean("up")) return CheckResult.Passed
    val device = status.optString("l3_device")
    if (device.matches(SIM_INTERFACE)) return CheckResult.Passed
    return CheckResult.Failed("GL's cellular interface uses ${device.take(20).ifEmpty { "no device" }}.")
}

/** Whether WireGuard sends with the mark `0x8000`, the only traffic that Churn's rules let out over the SIM. */
private fun wireguardMark(router: Router): CheckResult {
    val marks = router.wireguardMarks()
    if (marks.isEmpty()) return CheckResult.NotApplicable(R.string.reason_no_tunnel_up)
    val others = marks.filterValues { it != "0x8000" }
    if (others.isEmpty()) return CheckResult.Passed
    return CheckResult.Failed(
        others.entries.joinToString(", ") { "${it.key.take(20)} sends with ${it.value.take(20)}" } + ".",
    )
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
 * Whether the IP Masquerading of each WireGuard tunnel is on, which translates the router's own packets to the
 * tunnel's address: the setting `masq` of the peer that the tunnel's network interface names, by its name the one
 * that GL's web interface calls IP Masquerading.
 */
private fun tunnelMasquerading(router: Router): CheckResult {
    val tunnels = router.vpnInterfaces()
    if (tunnels.isEmpty()) return CheckResult.NotApplicable(R.string.reason_no_vpn)
    val problems = tunnels.mapNotNull { tunnel ->
        val peer = router.setting("network.$tunnel.config") ?: return@mapNotNull "network.$tunnel.config is missing."
        val key = "wireguard.$peer.masq"
        when (val masq = router.setting(key)) {
            "1" -> null
            null -> "${key.take(60)} is missing."
            else -> "${key.take(60)} is \"${masq.take(20)}\"."
        }
    }
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/** Whether exactly one key of `get_feature_config` holds `failover`: the `location_id` that the slot switches name. */
private fun locationId(router: Router): CheckResult {
    val keys = router.failoverKeys().sorted()
    return when {
        keys.isEmpty() -> CheckResult.Failed("No key of get_feature_config holds failover.")
        keys.size > 1 -> CheckResult.Failed(
            "${keys.size} keys of get_feature_config hold failover: ${keys.joinToString(", ") { it.take(40) }}.",
        )
        keys[0].matches(Router.LOCATION_ID) -> CheckResult.Passed
        else -> CheckResult.Failed("The key that holds failover has characters other than letters, digits and _.")
    }
}

/**
 * Whether slot 2's card gives its ICCID and IMSI the way Churn reads them, while slot 2 is on the built-in eSIM, whose
 * chip answers with or without a profile. A card in the SIM 2 tray may be missing or locked with a PIN.
 */
private fun esimAnswers(router: Router): CheckResult {
    if (router.slot2() != Slot2.ESIM) return CheckResult.NotApplicable(R.string.reason_slot2_on_tray)
    val problems = listOfNotNull(
        "AT+ICCID gave no ICCID.".takeIf { router.slot2Iccid() == null },
        "AT+CIMI gave no IMSI.".takeIf { router.slot2Imsi() == null },
    )
    return if (problems.isEmpty()) CheckResult.Passed else CheckResult.Failed(problems.joinToString(" "))
}

/** Whether slot 2's card, if it has a 19-digit ICCID, gives it with a trailing `F`, as GL also keeps it. */
private fun iccidPadding(router: Router): CheckResult {
    val iccid = router.slot2Iccid() ?: return CheckResult.NotApplicable(R.string.reason_no_slot2_card)
    if (iccid.trimEnd('F', 'f').length != 19) return CheckResult.NotApplicable(R.string.reason_no_19_digit_iccid)
    if (iccid.length == 20) return CheckResult.Passed
    return CheckResult.Failed("Slot 2's card gave its 19-digit ICCID without a trailing F.")
}

/**
 * Whether the built-in eSIM shows the placeholder ICCID together with a test network's IMSI, by which step 1 tells that
 * no profile is enabled, and neither of them with another one.
 */
private fun esimPlaceholder(router: Router): CheckResult {
    if (router.slot2() != Slot2.ESIM) return CheckResult.NotApplicable(R.string.reason_slot2_on_tray)
    val iccid = router.slot2Iccid()
    val imsi = router.slot2Imsi()
    if (iccid == null || imsi == null) return CheckResult.NotApplicable(R.string.reason_no_slot2_card)
    val placeholder = iccid == Router.PLACEHOLDER_ICCID
    val testNetwork = imsi.startsWith(Router.TEST_NETWORK)
    return when {
        placeholder && testNetwork -> CheckResult.Passed
        placeholder -> CheckResult.Failed("The placeholder ICCID came with an IMSI outside the test network 001/01.")
        testNetwork -> CheckResult.Failed(
            "An IMSI of the test network 001/01 came with another ICCID than the placeholder.",
        )
        else -> CheckResult.NotApplicable(R.string.reason_profile_enabled)
    }
}

/**
 * GL's firewall rule that drops devices' connections to its eSIM manager, as our Mudi 7 listed it, by setting; null for
 * one that has to be absent: with `dest`, the rule would cover only traffic through the router.
 */
private val LPA_RULE = mapOf(
    "firewall.block_lpa_port" to "rule",
    "firewall.block_lpa_port.src" to "lan",
    "firewall.block_lpa_port.dest" to null,
    "firewall.block_lpa_port.dest_port" to "3456",
    "firewall.block_lpa_port.proto" to "tcp",
    "firewall.block_lpa_port.target" to "DROP",
)

/**
 * Whether GL's eSIM manager listens on TCP port 3456, and GL's firewall rule `block_lpa_port` keeps devices from
 * reaching it, which would let them change profiles without a login.
 */
private fun esimManagerBlocked(router: Router): CheckResult {
    val port = "Nothing listens on TCP port 3456.".takeIf { 3456 !in router.tcpPorts() }
    val rule = router.settings("firewall.block_lpa_port")
    val problems = if (rule.isEmpty()) {
        listOf("GL's firewall has no rule block_lpa_port.")
    } else {
        LPA_RULE.mapNotNull { (key, expected) ->
            val value = rule[key]
            when {
                value == expected -> null
                value == null -> "$key is missing."
                expected == null -> "$key is set."
                else -> "$key is \"${value.take(20)}\"."
            }
        } + listOfNotNull("The rule is off.".takeIf { rule["firewall.block_lpa_port.enabled"] == "0" })
    }
    val all = listOfNotNull(port) + problems
    return if (all.isEmpty()) CheckResult.Passed else CheckResult.Failed(all.joinToString(" "))
}

/** Whether the built-in eSIM shows the IMSI of GSMA TS.48's generic test profile with its placeholder ICCID. */
private fun testProfile(router: Router): CheckResult {
    if (router.slot2() != Slot2.ESIM) return CheckResult.NotApplicable(R.string.reason_slot2_on_tray)
    val iccid = router.slot2Iccid() ?: return CheckResult.NotApplicable(R.string.reason_no_slot2_card)
    if (iccid != Router.PLACEHOLDER_ICCID) return CheckResult.NotApplicable(R.string.reason_profile_enabled)
    val imsi = router.slot2Imsi() ?: return CheckResult.NotApplicable(R.string.reason_no_slot2_card)
    if (imsi == Router.TEST_IMSI) return CheckResult.Passed
    return CheckResult.Failed("The placeholder ICCID came with another IMSI than TS.48's generic test profile's.")
}

/** Whether GL's system log goes to the file that `log_file` names, through a `logread -f -F` that writes it. */
private fun flashLogWritten(router: Router): CheckResult {
    val file = router.setting(Router.LOG_FILE) ?: return CheckResult.NotApplicable(R.string.reason_flash_log_off)
    val writes = router.logFollowers().any { line -> "-F" to file in line.split(' ').zipWithNext() }
    return if (writes) CheckResult.Passed else CheckResult.Failed("No logread -f -F writes ${file.take(60)}.")
}

/** Whether, with `log_file` deleted, as Churn leaves it, no `logread -f -F` writes a file any more. */
private fun flashLogStopped(router: Router): CheckResult {
    if (!router.flashLogOff()) return CheckResult.NotApplicable(R.string.reason_flash_log_on)
    val files = router.logFollowers().mapNotNull { line ->
        line.split(' ').zipWithNext().firstOrNull { it.first == "-F" }?.second
    }
    if (files.isEmpty()) return CheckResult.Passed
    return CheckResult.Failed("logread still writes ${files.joinToString(", ") { it.take(60) }}.")
}

/**
 * Whether GL's card files and the eSIM manager's records parse and name each card by its ICCID, in the formats
 * development.md lists. The result names no card, and no record's file, whose name is the chip's ID.
 */
private fun cardFileFormats(router: Router): CheckResult {
    val files = router.cardFiles()
    if (files.isEmpty()) return CheckResult.NotApplicable(R.string.reason_no_card_files)
    val wrong = files.filter { (path, json) -> json == null || !inCardFileFormat(path, json) }.keys
    if (wrong.isEmpty()) return CheckResult.Passed
    val records = wrong.count { it.startsWith(Router.ESIM_RECORDS) }
    val names = wrong.filter { !it.startsWith(Router.ESIM_RECORDS) }.map { it.substringAfterLast('/') } +
        listOfNotNull("$records of the eSIM manager's records".takeIf { records > 0 })
    return CheckResult.Failed("Not in the format development.md lists: ${names.joinToString(", ")}.")
}

/** Whether [json], the card file or eSIM record at [path], is in the format development.md lists. */
private fun inCardFileFormat(path: String, json: JSONObject): Boolean {
    fun isIccid(value: Any?) = value is String && Imei.iccidDigits(value) != null
    fun membersByIccid(members: JSONObject?, entry: (JSONObject) -> Boolean) = members != null &&
        members.keys().asSequence().all { isIccid(it) && members.optJSONObject(it)?.let(entry) == true }
    return when (path) {
        Router.SIM_MAP -> membersByIccid(json) { true }
        Router.APN_HISTORY -> json.optJSONArray("apns")
            ?.let { apns -> (0 until apns.length()).all { isIccid(apns.optJSONObject(it)?.opt("iccid")) } } == true
        else -> membersByIccid(json.optJSONObject("records")) { isIccid(it.opt("iccid")) }
    }
}

/**
 * Whether GL's eSIM manager begins its log's lines with the time in UTC: the last line that begins with a time begins
 * with about the time the log was last written, by the router's clock. The result tells only how far apart they are.
 */
private fun esimLogInUtc(router: Router): CheckResult {
    val (lines, written) = router.esimLogEnd() ?: return CheckResult.NotApplicable(R.string.reason_esim_log_empty)
    val time = lines.asReversed().firstNotNullOfOrNull { EsimTime.LINE.matchEntire(it) }?.let(EsimTime::of)
        ?: return CheckResult.Failed("None of the log's last 20 lines begins with a time.")
    val minutes = (time - written) / 60
    // A line written over a few seconds, or just before a minute turns, is still on time.
    if (minutes in -2..2) return CheckResult.Passed
    val direction = if (minutes > 0) "ahead of" else "behind"
    return CheckResult.Failed("The log's last line is timed ${abs(minutes)} minutes $direction UTC.")
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
