package id.churn

import java.io.File
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {

    private val fake = FakeRouter()
    private val router = Router(fake)

    /** The notes the checks keep on the phone. */
    private val notes = object : CheckNotes {
        val map = mutableMapOf<String, String>()

        override fun get(key: String) = map[key]

        override fun set(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun everyCheckConfirmsTier1ClaimsOfTestingMd() {
        assertTrue("No claims read from testing.md", TIERS.isNotEmpty())
        val ids = CHECKS.flatMap { it.ids.toList() }
        assertEquals("A claim with two checks", ids.distinct(), ids)
        for (check in CHECKS) {
            assertTrue("A check without claims", check.ids.isNotEmpty())
            for (id in check.ids) {
                val tiers = TIERS[id] ?: throw AssertionError("$id isn't a claim in testing.md")
                assertTrue("$id isn't a tier 1 claim in testing.md", "1" in tiers)
                assertEquals("$id is marked otherwise in testing.md", check.blocks, "blocks" in tiers)
            }
        }
    }

    @Test
    fun aCheckFailsAtAnAnswerItDoesNotExpect() {
        val check = Check("M1", blocks = true, title = 0) { router, _ ->
            router.radio()
            CheckResult.Passed
        }
        // Just switched on, the modem doesn't answer yet.
        fake.boot()
        assertEquals(
            CheckResult.Failed("Unexpected answer: AT+CFUN?: modem not ready"),
            check.run(router, CheckContext(NO_FOLDER, notes)),
        )
    }

    @Test
    fun platformCheckPassesOnTheFirmwareAndModemWeTested() {
        assertEquals(CheckResult.Passed, run("P1"))
    }

    @Test
    fun platformCheckPassesWhileTheModemRepeatsEachCommand() {
        // As for a while after power-on.
        fake.modemEchoes = true
        assertEquals(CheckResult.Passed, run("P1"))
    }

    @Test
    fun everyCheckGivesTheSameResultWhileTheModemRepeatsEachCommand() {
        writeFolder(SWAPPED.card)
        router.writeImeis(CARD_IMEIS)
        fun results() = CHECKS.map { check ->
            notes.map.clear()
            check.run(router, CheckContext(SWAPPED, notes))
        }
        val withoutEcho = results()
        fake.modemEchoes = true
        assertEquals(withoutEcho, results())
    }

    @Test
    fun platformCheckFailsOnAnotherFirmware() {
        fake.glVersion = "4.11.0"
        assertEquals(
            CheckResult.Failed(
                "GL firmware 4.11.0 (OpenWrt 23.05.4), modem Quectel RG650V-EU RG650VEU00ADR02A04G8G, " +
                    "which we haven't tested.",
            ),
            run("P1"),
        )
    }

    @Test
    fun platformCheckFailsOnAnotherModemFirmware() {
        fake.modemRevision = "RG650VEU00ADR02A05G8G"
        assertTrue(run("P1") is CheckResult.Failed)
    }

    @Test
    fun platformCheckFailsOnAnotherOpenWrtRelease() {
        fake.openwrtRelease = "24.10.0"
        assertTrue(run("P1") is CheckResult.Failed)
    }

    @Test
    fun commandCheckPassesWithEveryCommandChurnRuns() {
        assertEquals(CheckResult.Passed, run("P2"))
    }

    @Test
    fun commandCheckFailsWithoutACommandChurnRuns() {
        fake.absentCommands += listOf("curl", "nft")
        assertEquals(CheckResult.Failed("Missing: curl, nft."), run("P2"))
    }

    @Test
    fun md5sumCheckPassesWithMd5sum() {
        assertEquals(CheckResult.Passed, run("P3"))
    }

    @Test
    fun md5sumCheckFailsWithoutMd5sum() {
        fake.absentCommands += "md5sum"
        assertEquals(CheckResult.Failed("Missing: md5sum."), run("P3"))
    }

    @Test
    fun radioCheckPassesWithTheRadioOn() {
        assertEquals(CheckResult.Passed, run("P4"))
    }

    @Test
    fun radioCheckPassesWithTheRadioOff() {
        fake.airplaneMode = true
        assertEquals(CheckResult.Passed, run("M1"))
    }

    @Test
    fun radioCheckFailsAtAnotherRadioState() {
        // Minimum functionality, which Churn never sets.
        fake.atAnswers["AT+CFUN?"] = "+CFUN: 0"
        assertEquals(CheckResult.Failed("AT+CFUN? answers 0."), run("M1"))
    }

    @Test
    fun imeiReadCheckPassesWithTheFactoryImeis() {
        assertEquals(CheckResult.Passed, run("M2", SET_UP))
    }

    @Test
    fun imeiReadCheckPassesWithACardsPair() {
        fake.imei1 = CARD_IMEIS.imei1
        fake.imei2 = CARD_IMEIS.imei2
        assertEquals(CheckResult.Passed, run("M2", SWAPPED))
    }

    @Test
    fun imeiReadCheckFailsAtAWrongCheckDigit() {
        fake.imei2 = "356090215000078"
        assertEquals(CheckResult.Failed("IMEI 2 has a wrong check digit."), run("M2", SET_UP))
    }

    @Test
    fun imeiReadCheckFailsAtAnotherTac() {
        fake.imei1 = "350000001234562"
        assertEquals(CheckResult.Failed("IMEI 1 has another TAC than the factory IMEI 1."), run("M2", SET_UP))
    }

    @Test
    fun imeiReadCheckDoesNotApplyBeforeSetup() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_not_set_up), run("M2"))
    }

    @Test
    fun slot2CheckPassesOnTheTray() {
        assertEquals(CheckResult.Passed, run("M3"))
    }

    @Test
    fun slot2CheckPassesOnTheEsim() {
        fake.slot2 = Slot2.ESIM
        assertEquals(CheckResult.Passed, run("M3"))
    }

    @Test
    fun slot2CheckFailsAtAnotherAnswer() {
        fake.atAnswers["AT+QSIMTYPE=\"slot2\""] = "+QSIMTYPE: \"slot2\",2"
        assertEquals(CheckResult.Failed("Unexpected answer: AT+QSIMTYPE"), run("M3"))
    }

    @Test
    fun subscriptionCheckPassesWithSub2() {
        assertEquals(CheckResult.Passed, run("M4"))
    }

    @Test
    fun subscriptionCheckFailsWithAnotherSubscription() {
        fake.atAnswers["AT+QUIMSUB?"] = "+QUIMSUB: 1,\"SUB1\",\"L\""
        assertEquals(CheckResult.Failed("AT+QUIMSUB? answers 1,\"SUB1\",\"L\"."), run("M4"))
    }

    @Test
    fun settingsCheckPassesWithTheSettingsWeSearched() {
        assertEquals(CheckResult.Passed, run("M5"))
    }

    @Test
    fun settingsCheckFailsAtSettingsWeHaveNotSearched() {
        fake.atAnswers["AT+QCFG=?"] = "+QCFG: \"rrc\",(0-5)\r\n+QCFG: \"boot_radio\",(0,1)"
        fake.atAnswers["AT+QNWCFG=?"] = "+QNWCFG: \"data_path\",(0,1)\r\n+QNWCFG: \"radio_at_boot\",(0,1)"
        assertEquals(
            CheckResult.Failed("Settings we haven't searched: AT+QCFG \"boot_radio\", AT+QNWCFG \"radio_at_boot\"."),
            run("M5"),
        )
    }

    @Test
    fun settingsCheckFailsAtAnotherRadioStateList() {
        fake.atAnswers["AT+CFUN=?"] = "+CFUN: (0,1,4,7),(0,1)"
        assertEquals(CheckResult.Failed("AT+CFUN=? answers (0,1,4,7),(0,1), not (0,1,4),(0,1)."), run("M5"))
    }

    @Test
    fun airplaneModeCheckPassesWithAirplaneModeOff() {
        assertEquals(CheckResult.Passed, run("U1"))
    }

    @Test
    fun airplaneModeCheckPassesWithTheRadioOff() {
        fake.airplaneMode = true
        assertEquals(CheckResult.Passed, run("U1"))
    }

    @Test
    fun airplaneModeCheckFailsWithTheRadioOn() {
        fake.airplaneMode = true
        fake.radioStuckOn = true
        assertEquals(CheckResult.Failed("Airplane mode is on, but AT+CFUN? answers 1."), run("U1"))
    }

    @Test
    fun airplaneModeCheckDoesNotApplyWhileTheRouterStarts() {
        fake.airplaneMode = true
        fake.boot()
        // Past the radio-on window after power-on that we measured, but not yet past the swap's wait.
        fake.clock += 90_000
        assertEquals(CheckResult.NotApplicable(R.string.reason_starting), run("U1"))
    }

    @Test
    fun airplaneModeCheckFailsAtAnotherValue() {
        fake.uciAnswers[Router.AIRPLANE_MODE] = "2"
        assertEquals(CheckResult.Failed("The setting is \"2\"."), run("U1"))
    }

    @Test
    fun rebootCheckPassesWithTheRebootOff() {
        assertEquals(CheckResult.Passed, run("U4"))
    }

    @Test
    fun rebootCheckPassesWithTheRebootOn() {
        fake.scheduledReboot = true
        assertEquals(CheckResult.Passed, run("U4"))
    }

    @Test
    fun rebootCheckFailsWithoutTheSetting() {
        // As if a firmware upgrade had moved GL's scheduled reboot, which steps 1 and 3 would then miss.
        fake.uciAnswers[Router.SCHEDULED_REBOOT] = null
        assertEquals(CheckResult.Failed("The setting is missing."), run("U4"))
    }

    @Test
    fun activeCardCheckPassesWithAnIccid() {
        assertEquals(CheckResult.Passed, run("U5"))
    }

    @Test
    fun activeCardCheckDoesNotApplyWithoutAnActiveCard() {
        fake.modemCpuIccid = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_active_card), run("U5"))
    }

    @Test
    fun activeCardCheckFailsAtSomethingElse() {
        fake.modemCpuIccid = "slot2"
        assertEquals(CheckResult.Failed("The setting holds 5 characters that aren't an ICCID."), run("U5"))
    }

    @Test
    fun activeSlotCheckPassesWithSlot2() {
        assertEquals(CheckResult.Passed, run("U6"))
    }

    @Test
    fun activeSlotCheckPassesWithSlot1() {
        fake.activeSlot = 1
        assertEquals(CheckResult.Passed, run("U6"))
    }

    @Test
    fun activeSlotCheckFailsAtAnotherValue() {
        fake.activeSlot = 0
        assertEquals(CheckResult.Failed("The setting is \"0\"."), run("U6"))
    }

    @Test
    fun vpnPolicyCheckPassesWithAWireGuardTunnel() {
        assertEquals(CheckResult.Passed, run("K1"))
    }

    @Test
    fun vpnPolicyCheckFailsWithoutTheDefaultPolicy() {
        fake.uciAnswers["route_policy.@default[0]"] = null
        assertEquals(CheckResult.Failed("GL's route policy has no default section."), run("K1"))
    }

    @Test
    fun vpnPolicyCheckDoesNotApplyWithoutATunnel() {
        fake.vpnTunnel = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_vpn), run("K1"))
    }

    @Test
    fun killSwitchCheckPassesWithTheSwitchOn() {
        assertEquals(CheckResult.Passed, run("K5"))
    }

    @Test
    fun killSwitchCheckPassesWithTheSwitchOff() {
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        assertEquals(CheckResult.Passed, run("K5"))
    }

    @Test
    fun killSwitchCheckFailsWhileTheRulesDoNotFollowTheSettings() {
        fake.killSwitchInUse = false
        assertEquals(
            CheckResult.Failed(
                "route_policy.@default[0].enabled is \"0\", network.vpn_to_main is absent, and ip rule show lists " +
                    "a rule at priority 9000.",
            ),
            run("K5"),
        )
    }

    @Test
    fun killSwitchCheckFailsWhileTheSettingsDisagree() {
        fake.uciAnswers["network.vpn_to_main"] = "rule"
        assertTrue(run("K5") is CheckResult.Failed)
    }

    @Test
    fun killSwitchRuleCheckPassesWithTheTunnelUp() {
        assertEquals(CheckResult.Passed, run("K6"))
    }

    @Test
    fun killSwitchRuleCheckPassesWithTheTunnelDown() {
        fake.tunnelUp = false
        assertEquals(CheckResult.Passed, run("K6"))
    }

    @Test
    fun killSwitchRuleCheckFailsWithoutABlackhole() {
        fake.answers["ip rule show"] = RULES.lines().filterNot { it.startsWith("9920:") }.joinToString("\n")
        assertEquals(CheckResult.Failed("Missing: 9920: from all iif br-lan blackhole."), run("K6"))
    }

    @Test
    fun killSwitchRuleCheckFailsWithoutTheTunnelsRuleWhileItIsUp() {
        fake.answers["ip rule show"] = RULES.lines().filterNot { "lookup 1001" in it }.joinToString("\n")
        assertEquals(CheckResult.Failed("Missing: 6000: from all fwmark 0x1000/0xf000 lookup 1001."), run("K6"))
    }

    @Test
    fun killSwitchRuleCheckDoesNotApplyWithTheSwitchOff() {
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        assertEquals(CheckResult.NotApplicable(R.string.reason_kill_switch_off), run("K6"))
    }

    @Test
    fun killSwitchRuleCheckDoesNotApplyWithoutATunnel() {
        fake.vpnTunnel = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_vpn), run("K6"))
    }

    @Test
    fun usbPortCheckPassesWithTheUsbPortInTheLan() {
        assertEquals(CheckResult.Passed, run("K7"))
    }

    @Test
    fun usbPortCheckFailsWithTheUsbPortOutsideTheLan() {
        fake.usbBridge = null
        assertEquals(CheckResult.Failed("ecm0 is in no bridge."), run("K7"))
    }

    @Test
    fun usbPortCheckFailsWithTheUsbPortInAnotherBridge() {
        fake.usbBridge = "br-guest"
        assertEquals(CheckResult.Failed("ecm0 is in br-guest, not br-lan."), run("K7"))
    }

    @Test
    fun usbPortCheckFailsWithoutTheUsbPortsDevice() {
        fake.failing += "ip link show ecm0"
        assertEquals(CheckResult.Failed("The router has no network device ecm0."), run("K7"))
    }

    @Test
    fun dnsServerCheckPassesWithGlsTwoDnsServers() {
        assertEquals(CheckResult.Passed, run("D1"))
    }

    @Test
    fun dnsServerCheckFailsWithoutTheTunnelsDnsServer() {
        fake.dnsServers.removeAll { it.startsWith("2153|") }
        assertEquals(CheckResult.Failed("Nothing listens on UDP port 2153."), run("D1"))
    }

    @Test
    fun dnsServerCheckFailsIfTheMainDnsServerRunsAsAnotherUser() {
        fake.dnsServers[1] = "53|dnsmasq|0|/tmp/resolv.conf.d/resolv.conf.auto"
        assertEquals(
            CheckResult.Failed(
                "On UDP port 53: dnsmasq as user ID 0, servers from /tmp/resolv.conf.d/resolv.conf.auto.",
            ),
            run("D1"),
        )
    }

    @Test
    fun dnsServerCheckFailsIfTheTunnelsDnsServerAsksTheSimsServers() {
        fake.dnsServers[0] = "2153|dnsmasq|456|/tmp/resolv.conf.d/resolv.conf.auto"
        assertTrue(run("D1") is CheckResult.Failed)
    }

    @Test
    fun dnsServerCheckDoesNotApplyWithoutATunnel() {
        fake.vpnTunnel = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_vpn), run("D1"))
    }

    @Test
    fun dnsRuleCheckPassesWithGlsRules() {
        assertEquals(CheckResult.Passed, run("D2"))
    }

    @Test
    fun dnsRuleCheckFailsWithoutTheRedirectOfDevicesLookups() {
        // Churn's own redirect is in place, and must not count.
        router.installFirewall()
        fake.glDnsRules.removeAll { "fib daddr type local" in it }
        assertEquals(
            CheckResult.Failed("No rule redirects devices' lookups marked for the tunnel to port 2153."),
            run("D2"),
        )
    }

    @Test
    fun dnsRuleCheckFailsWithoutTheDropOfLeakedLookups() {
        fake.glDnsRules.removeAll { "lan_drop_leaked_dns" in it }
        assertEquals(
            CheckResult.Failed("No rule lan_drop_leaked_dns drops lookups past the router without the mark 0x8000."),
            run("D2"),
        )
    }

    @Test
    fun dnsRuleCheckDoesNotApplyWithoutATunnel() {
        fake.vpnTunnel = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_vpn), run("D2"))
    }

    @Test
    fun ownTrafficCheckPassesOnlineOverTheSim() {
        assertEquals(CheckResult.Passed, run("D5"))
    }

    @Test
    fun ownTrafficCheckPassesInAirplaneMode() {
        fake.airplaneMode = true
        assertEquals(CheckResult.Passed, run("D5"))
    }

    @Test
    fun ownTrafficCheckFailsWithoutTheRuleAtPriority1() {
        fake.answers["ip rule show"] = RULES.lines().filterNot { it.startsWith("1:") }.joinToString("\n")
        assertEquals(CheckResult.Failed("ip rule show lists no rule at priority 1 to table 16800."), run("D5"))
    }

    @Test
    fun ownTrafficCheckFailsWithRoutesInTable16800() {
        fake.answers["ip route show table 16800"] = "default dev wgclient1 scope link\n"
        assertEquals(CheckResult.Failed("Table 16800 has routes."), run("D5"))
    }

    @Test
    fun ownTrafficCheckDoesNotApplyOverAnotherUplink() {
        fake.answers["ip route show table main"] = "default via 192.168.1.1 dev eth0 proto static metric 3\n"
        assertEquals(CheckResult.NotApplicable(R.string.reason_other_uplink), run("D5"))
    }

    @Test
    fun ownTrafficTargetCheckPassesWithTheTargetsWeWatched() {
        assertEquals(CheckResult.Passed, run("D6"))
    }

    @Test
    fun ownTrafficTargetCheckFailsWithAnotherTimeServer() {
        fake.answers["uci -q show chrony"] = "chrony.@pool[0]=pool\n" + "chrony.@pool[0].hostname='pool.ntp.org'\n"
        assertEquals(CheckResult.Failed("Chrony's time servers are pool.ntp.org."), run("D6"))
    }

    @Test
    fun ownTrafficTargetCheckFailsWithAnotherConnectionCheck() {
        fake.uciAnswers["kmwan.modem_cpu.tracks"] = null
        assertEquals(CheckResult.Failed("GL's connection check over the SIM pings nothing."), run("D6"))
    }

    @Test
    fun firewallCheckPassesWithChurnsRulesLoaded() {
        router.installFirewall()
        assertEquals(CheckResult.Passed, run("D7"))
    }

    @Test
    fun firewallCheckFailsWithChurnsRulesNotLoaded() {
        router.installFirewall()
        // As if GL's firewall hadn't loaded the file at power-on.
        fake.loadedChains.clear()
        assertTrue(run("D7") is CheckResult.Failed)
    }

    @Test
    fun firewallCheckDoesNotApplyWithoutChurnsRules() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_firewall), run("D7"))
    }

    @Test
    fun simInterfaceCheckPassesOnline() {
        assertEquals(CheckResult.Passed, run("D8"))
    }

    @Test
    fun simInterfaceCheckPassesInAirplaneMode() {
        fake.airplaneMode = true
        assertEquals(CheckResult.Passed, run("D8"))
    }

    @Test
    fun simInterfaceCheckFailsWithoutTheSimsDataInterfaces() {
        fake.answers["ls /sys/class/net"] = listOf("br-lan", "ecm0", "eth0", "lo", "usb0").joinToString("") { "$it\n" }
        assertEquals(CheckResult.Failed("No network device is named rmnet_data and a number."), run("D8"))
    }

    @Test
    fun simInterfaceCheckFailsIfGlsCellularInterfaceUsesAnotherDevice() {
        fake.simDevice = "usb0"
        assertEquals(CheckResult.Failed("GL's cellular interface uses usb0."), run("D8"))
    }

    @Test
    fun simInterfaceCheckFailsWithoutGlsCellularInterface() {
        fake.failing += "ifstatus modem_cpu"
        assertEquals(CheckResult.Failed("GL has no network interface modem_cpu."), run("D8"))
    }

    @Test
    fun wireguardMarkCheckPassesWithTheMarkOfGlsFallbackPolicy() {
        assertEquals(CheckResult.Passed, run("D9"))
    }

    @Test
    fun wireguardMarkCheckFailsWithAnotherMark() {
        fake.wireguardMark = "off"
        assertEquals(CheckResult.Failed("wgclient1 sends with off."), run("D9"))
    }

    @Test
    fun wireguardMarkCheckDoesNotApplyWithTheTunnelDown() {
        fake.tunnelUp = false
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_tunnel_up), run("D9"))
    }

    @Test
    fun dnsmasqCheckPassesWithItsUserIdInChurnsRules() {
        router.installFirewall()
        assertEquals(CheckResult.Passed, run("D10"))
    }

    @Test
    fun dnsmasqCheckFailsIfChurnsRulesNameAnotherUserId() {
        router.installFirewall()
        // As if a firmware upgrade had given GL's DNS server another user ID.
        fake.dnsmasqUid = 454
        assertEquals(
            CheckResult.Failed("Churn's rules name user ID 453, but the user dnsmasq has 454."),
            run("D10"),
        )
    }

    @Test
    fun dnsmasqCheckFailsIfChurnsRulesNameNoUserId() {
        val rules = Router.firewallRules(dnsmasqUid = 453).replace("meta skuid 453 ", "")
        fake.files[Router.FIREWALL] = rules.toByteArray()
        assertEquals(CheckResult.Failed("Churn's rules name no user ID."), run("D10"))
    }

    @Test
    fun dnsmasqCheckDoesNotApplyWithoutChurnsRules() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_firewall), run("D10"))
    }

    @Test
    fun masqueradingCheckPassesWithMasqueradingOn() {
        assertEquals(CheckResult.Passed, run("D13"))
    }

    @Test
    fun masqueradingCheckFailsWithMasqueradingOff() {
        fake.uciAnswers["wireguard.peer_2010.masq"] = "0"
        assertEquals(CheckResult.Failed("wireguard.peer_2010.masq is \"0\"."), run("D13"))
    }

    @Test
    fun masqueradingCheckFailsWithoutTheSetting() {
        fake.uciAnswers["wireguard.peer_2010.masq"] = null
        assertEquals(CheckResult.Failed("wireguard.peer_2010.masq is missing."), run("D13"))
    }

    @Test
    fun masqueradingCheckFailsWithoutTheTunnelsPeer() {
        fake.uciAnswers["network.wgclient1.config"] = null
        assertEquals(CheckResult.Failed("network.wgclient1.config is missing."), run("D13"))
    }

    @Test
    fun masqueradingCheckDoesNotApplyWithoutATunnel() {
        fake.vpnTunnel = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_vpn), run("D13"))
    }

    @Test
    fun locationIdCheckPassesWithOneKeyHoldingFailover() {
        assertEquals(CheckResult.Passed, run("S1"))
    }

    @Test
    fun locationIdCheckFailsWithoutAKeyHoldingFailover() {
        fake.answers[FEATURE_CONFIG_CALL] = FakeRouter.FEATURE_CONFIG.replace("\"failover\"", "\"fallback\"")
        assertEquals(CheckResult.Failed("No key of get_feature_config holds failover."), run("S1"))
    }

    @Test
    fun locationIdCheckFailsWithTwoKeysHoldingFailover() {
        val config = JSONObject(FakeRouter.FEATURE_CONFIG)
        config.put("bcpu_p0134_v2c7c", config.getJSONObject(FakeRouter.LOCATION_ID))
        fake.answers[FEATURE_CONFIG_CALL] = config.toString()
        assertEquals(
            CheckResult.Failed("2 keys of get_feature_config hold failover: bcpu_p0133_v2c7c, bcpu_p0134_v2c7c."),
            run("S1"),
        )
    }

    @Test
    fun locationIdCheckFailsAtAKeyTheSlotSwitchesCannotName() {
        fake.answers[FEATURE_CONFIG_CALL] = FakeRouter.FEATURE_CONFIG.replace("\"bcpu_p0133_v2c7c\"", "\"bcpu p0133\"")
        assertEquals(
            CheckResult.Failed("The key that holds failover has characters other than letters, digits and _."),
            run("S1"),
        )
    }

    @Test
    fun esimAnswerCheckPassesWithAProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        assertEquals(CheckResult.Passed, run("E1"))
    }

    @Test
    fun esimAnswerCheckPassesWithNoProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        assertEquals(CheckResult.Passed, run("E1"))
    }

    @Test
    fun esimAnswerCheckFailsWithoutAnIccid() {
        fake.slot2 = Slot2.ESIM
        fake.answers[cardCommand("AT+ICCID")] = cardAnswer("\r\nERROR\r\n")
        assertEquals(CheckResult.Failed("AT+ICCID gave no ICCID."), run("E1"))
    }

    @Test
    fun esimAnswerCheckFailsWithoutAnImsi() {
        fake.slot2 = Slot2.ESIM
        fake.answers[cardCommand("AT+CIMI")] = cardAnswer("\r\n+CME ERROR: 10\r\n")
        assertEquals(CheckResult.Failed("AT+CIMI gave no IMSI."), run("E1"))
    }

    @Test
    fun esimAnswerCheckDoesNotApplyOnTheTray() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_slot2_on_tray), run("E1"))
    }

    @Test
    fun iccidCheckPassesWithATrailingF() {
        assertEquals(CheckResult.Passed, run("E2"))
    }

    @Test
    fun iccidCheckFailsWithoutTheTrailingF() {
        fake.trayCard = FakeRouter.Card("8941000000000000015", "228010000000001")
        assertEquals(CheckResult.Failed("Slot 2's card gave its 19-digit ICCID without a trailing F."), run("E2"))
    }

    @Test
    fun iccidCheckDoesNotApplyToA20DigitIccid() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_19_digit_iccid), run("E2"))
    }

    @Test
    fun iccidCheckDoesNotApplyWithoutACard() {
        fake.trayCard = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_slot2_card), run("E2"))
    }

    @Test
    fun placeholderCheckPassesWithNoProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        assertEquals(CheckResult.Passed, run("E3"))
    }

    @Test
    fun placeholderCheckFailsAtAnotherNetworksImsi() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        fake.answers[cardCommand("AT+CIMI")] = cardAnswer("\r\n260010000000001\r\n\r\nOK\r\n")
        assertEquals(
            CheckResult.Failed("The placeholder ICCID came with an IMSI outside the test network 001/01."),
            run("E3"),
        )
    }

    @Test
    fun placeholderCheckFailsAtAnotherPlaceholder() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        fake.answers[cardCommand("AT+ICCID")] = cardAnswer("\r\n+ICCID: 89000123456789012358\r\n\r\nOK\r\n")
        assertEquals(
            CheckResult.Failed("An IMSI of the test network 001/01 came with another ICCID than the placeholder."),
            run("E3"),
        )
    }

    @Test
    fun placeholderCheckDoesNotApplyWithAProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        assertEquals(CheckResult.NotApplicable(R.string.reason_profile_enabled), run("E3"))
    }

    @Test
    fun placeholderCheckDoesNotApplyWhileTheChipDoesNotAnswer() {
        fake.slot2 = Slot2.ESIM
        fake.answers[cardCommand("AT+ICCID")] = cardAnswer("\r\n+CME ERROR: 10\r\n")
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_slot2_card), run("E3"))
    }

    @Test
    fun placeholderCheckDoesNotApplyOnTheTray() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_slot2_on_tray), run("E3"))
    }

    @Test
    fun esimManagerCheckPassesWithGlsRule() {
        assertEquals(CheckResult.Passed, run("E5"))
    }

    @Test
    fun esimManagerCheckFailsIfNothingListensOnItsPort() {
        fake.answers["netstat -lnt"] = FakeRouter.TCP_PORTS.lines().filter { ":3456" !in it }.joinToString("\n")
        assertEquals(CheckResult.Failed("Nothing listens on TCP port 3456."), run("E5"))
    }

    @Test
    fun esimManagerCheckFailsWithoutGlsRule() {
        fake.answers["uci -q show firewall.block_lpa_port"] = ""
        assertEquals(CheckResult.Failed("GL's firewall has no rule block_lpa_port."), run("E5"))
    }

    @Test
    fun esimManagerCheckFailsWithTheRuleOff() {
        fake.answers["uci -q show firewall.block_lpa_port"] = FakeRouter.LPA_RULE.replace("enabled='1'", "enabled='0'")
        assertEquals(CheckResult.Failed("The rule is off."), run("E5"))
    }

    @Test
    fun esimManagerCheckFailsWithAnotherRule() {
        // A rule for traffic through the router, for any protocol, that lets it through.
        fake.answers["uci -q show firewall.block_lpa_port"] = FakeRouter.LPA_RULE.lines()
            .filter { "proto" !in it }.map { it.replace("target='DROP'", "target='ACCEPT'") }
            .joinToString("\n") + "firewall.block_lpa_port.dest='wan'\n"
        assertEquals(
            CheckResult.Failed(
                "firewall.block_lpa_port.dest is set. firewall.block_lpa_port.proto is missing. " +
                    "firewall.block_lpa_port.target is \"ACCEPT\".",
            ),
            run("E5"),
        )
    }

    @Test
    fun bootIdCheckPassesWithAUuid() {
        assertEquals(CheckResult.Passed, run("B6"))
    }

    @Test
    fun bootIdCheckFailsWithoutAUuid() {
        fake.bootId = "0"
        assertEquals(CheckResult.Failed("Unexpected answer: /proc/sys/kernel/random/boot_id"), run("B6"))
    }

    @Test
    fun testProfileCheckPassesWithNoProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        assertEquals(CheckResult.Passed, run("B16"))
    }

    @Test
    fun testProfileCheckFailsAtAnotherTestImsi() {
        fake.slot2 = Slot2.ESIM
        fake.esimProfile = null
        fake.answers[cardCommand("AT+CIMI")] = cardAnswer("\r\n001010123456789\r\n\r\nOK\r\n")
        assertEquals(
            CheckResult.Failed("The placeholder ICCID came with another IMSI than TS.48's generic test profile's."),
            run("B16"),
        )
    }

    @Test
    fun testProfileCheckDoesNotApplyWithAProfileEnabled() {
        fake.slot2 = Slot2.ESIM
        assertEquals(CheckResult.NotApplicable(R.string.reason_profile_enabled), run("B16"))
    }

    @Test
    fun testProfileCheckDoesNotApplyWhileTheChipDoesNotAnswer() {
        fake.slot2 = Slot2.ESIM
        fake.answers[cardCommand("AT+ICCID")] = cardAnswer("\r\n+CME ERROR: 10\r\n")
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_slot2_card), run("B16"))
    }

    @Test
    fun testProfileCheckDoesNotApplyOnTheTray() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_slot2_on_tray), run("B16"))
    }

    @Test
    fun flashLogCheckPassesWhileLogreadWritesTheFile() {
        assertEquals(CheckResult.Passed, run("H1"))
    }

    @Test
    fun flashLogCheckFailsWithoutLogread() {
        fake.logreadMissing = true
        assertEquals(CheckResult.Failed("No logread -f -F writes /etc/sys_log."), run("H1"))
    }

    @Test
    fun flashLogCheckDoesNotApplyWithTheLogInRam() {
        fake.logFile = null
        assertEquals(CheckResult.NotApplicable(R.string.reason_flash_log_off), run("H1"))
    }

    @Test
    fun logreadCheckPassesWithTheLogInRam() {
        fake.logFile = null
        assertEquals(CheckResult.Passed, run("H2"))
    }

    @Test
    fun logreadCheckFailsIfLogreadStillWritesTheFile() {
        fake.logFile = null
        fake.logreadStuck = true
        assertEquals(CheckResult.Failed("logread still writes /etc/sys_log."), run("H2"))
    }

    @Test
    fun logreadCheckDoesNotApplyWithTheLogOnTheFlash() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_flash_log_on), run("H2"))
    }

    @Test
    fun cardFileCheckPassesWithGlsFormats() {
        fake.recordCards("8941000000000000015F", Router.PLACEHOLDER_ICCID)
        assertEquals(CheckResult.Passed, run("H4"))
    }

    @Test
    fun cardFileCheckFailsAtAFileThatDoesNotParse() {
        fake.recordCards("8941000000000000015F")
        fake.files[Router.SIM_MAP] = "{".toByteArray()
        assertEquals(CheckResult.Failed("Not in the format development.md lists: sim_map.json."), run("H4"))
    }

    @Test
    fun cardFileCheckFailsAtACardFileMemberThatIsNotAnIccid() {
        fake.recordCards("8941000000000000015F")
        fake.files[Router.SIM_MAP] = """{"default": {"manual": false}}""".toByteArray()
        fake.files[Router.APN_HISTORY] = """{"apns": [{"iccid": "", "apn": "internet"}]}""".toByteArray()
        assertEquals(
            CheckResult.Failed("Not in the format development.md lists: sim_map.json, history_apn_database.json."),
            run("H4"),
        )
    }

    @Test
    fun cardFileCheckFailsAtAnEsimRecordInAnotherFormat() {
        fake.recordCards("8941000000000000015F", Router.PLACEHOLDER_ICCID)
        val record = fake.files.keys.first { it.startsWith(Router.ESIM_RECORDS) }
        fake.files[record] = """{"records": {"8941000000000000015F": {"apn": ""}}}""".toByteArray()
        assertEquals(
            CheckResult.Failed("Not in the format development.md lists: 1 of the eSIM manager's records."),
            run("H4"),
        )
    }

    @Test
    fun cardFileCheckDoesNotApplyWithoutCardFiles() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_card_files), run("H4"))
    }

    @Test
    fun esimLogCheckPassesWithTimesInUtc() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        assertEquals(CheckResult.Passed, run("H5"))
    }

    @Test
    fun esimLogCheckFailsWithLocalTimes() {
        fake.esimLogZone = ZoneOffset.ofHours(2)
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        assertEquals(CheckResult.Failed("The log's last line is timed 120 minutes ahead of UTC."), run("H5"))
    }

    @Test
    fun esimLogCheckFailsWithoutTimes() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        fake.files[FakeRouter.ESIM_LOG] = "IpaReopen\r\n".toByteArray()
        assertEquals(CheckResult.Failed("None of the log's last 20 lines begins with a time."), run("H5"))
    }

    @Test
    fun esimLogCheckDoesNotApplyToAnEmptyLog() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_esim_log_empty), run("H5"))
    }

    @Test
    fun esimLogGrowthCheckNotesTheLogAtTheFirstConnection() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        assertEquals(CheckResult.NotApplicable(R.string.reason_first_note), run("H6"))
        assertTrue(ESIM_LOG_NOTE in notes.map)
    }

    @Test
    fun esimLogGrowthCheckPassesWhileTheLogGrows() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        run("H6")
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        assertEquals(CheckResult.Passed, run("H6"))
    }

    @Test
    fun esimLogGrowthCheckPassesWithoutALog() {
        run("H6")
        assertEquals(CheckResult.Passed, run("H6"))
    }

    @Test
    fun esimLogGrowthCheckNotesTheLogAnewAfterABrokenNote() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        notes.map[ESIM_LOG_NOTE] = "0"
        assertEquals(CheckResult.NotApplicable(R.string.reason_first_note), run("H6"))
    }

    @Test
    fun esimLogGrowthCheckFailsIfTheLogWasEmptied() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        run("H6")
        fake.files.remove(FakeRouter.ESIM_LOG)
        fake.esimLogs("Debug esim: (ipa:383) IpaDisableProfile")
        val emptied = "Since the last connection, the log was emptied or cut, though the app didn't clear it."
        assertEquals(CheckResult.Failed(emptied), run("H6"))
    }

    @Test
    fun overlayCheckPassesOnExt4WithItsReserve() {
        assertEquals(CheckResult.Passed, run("H12"))
    }

    @Test
    fun overlayCheckFailsWithTheReserveAt0() {
        fake.reservedClusters = 0
        assertEquals(CheckResult.Failed("ext4 keeps 0 blocks back from it, not 4096."), run("H12"))
    }

    @Test
    fun overlayCheckFailsOnAnotherPartition() {
        fake.overlayDevice = "/dev/mmcblk0p60"
        assertEquals(CheckResult.Failed("The overlay is on /dev/mmcblk0p60."), run("H12"))
    }

    @Test
    fun overlayCheckFailsOnAnotherFileSystem() {
        fake.overlayType = "f2fs"
        assertEquals(
            CheckResult.Failed("The overlay is f2fs, not ext4. ext4 has no reserved_clusters file for it."),
            run("H12"),
        )
    }

    @Test
    fun overlayCheckFailsWithoutAnOverlay() {
        fake.overlayType = null
        assertEquals(CheckResult.Failed("Nothing is mounted on /overlay."), run("H12"))
    }

    @Test
    fun flashWearCheckPassesWithTheFlashRatingItsWear() {
        fake.flashLifeTime = "0x02 0x0a"
        assertEquals(CheckResult.Passed, run("H18"))
    }

    @Test
    fun flashWearCheckFailsWithoutAnEstimateOfItsLife() {
        fake.flashLifeTime = "0x00 0x01"
        assertEquals(
            CheckResult.Failed("The flash gives no estimate of its wear: life_time 0x00 0x01, pre_eol_info 0x01."),
            run("H18"),
        )
        fake.flashLifeTime = "0x01 0x0c"
        assertTrue(run("H18").toString().contains("no estimate"))
    }

    @Test
    fun flashWearCheckFailsWithoutAStateOfItsSpareBlocks() {
        fake.flashPreEol = "0x00"
        assertTrue(run("H18").toString().contains("no estimate"))
        fake.flashPreEol = "0x04"
        assertTrue(run("H18").toString().contains("no estimate"))
    }

    @Test
    fun flashWearCheckFailsWithTheFlashPastItsLife() {
        fake.flashLifeTime = "0x0b 0x03"
        assertEquals(CheckResult.Failed("The flash reports its life used up."), run("H18"))
    }

    @Test
    fun flashWearCheckFailsWithTheSpareBlocksRunningOut() {
        fake.flashPreEol = "0x02"
        assertEquals(CheckResult.Failed("The flash reports 80% of its spare blocks used."), run("H18"))
        fake.flashPreEol = "0x03"
        assertEquals(CheckResult.Failed("The flash reports 90% of its spare blocks used."), run("H18"))
    }

    @Test
    fun flashWearCheckFailsAtAnAnswerItCannotRead() {
        fake.flashLifeTime = "0x01"
        assertTrue(run("H18").toString().contains("Unexpected answer: The flash's wear"))
    }

    @Test
    fun etcCheckPassesWithGlWritingFiles() {
        assertEquals(CheckResult.Passed, run("H19"))
    }

    @Test
    fun etcCheckFailsWithOnlyChurnWritingFiles() {
        fake.etcWritten = listOf(
            "/etc/churn/card",
            "/etc/nftables.d/churn.nft",
            "/etc/sysupgrade.conf",
            "/etc/config/cellular/sim_map.json",
            "/etc/config/cellular/history_apn_database.json",
            "/etc/config/glconfig",
            "/etc/config/network",
            "/etc/config/route_policy",
            "/etc/config/system",
        )
        assertEquals(CheckResult.Failed("Nothing but Churn wrote a file in /etc in the last 2 minutes."), run("H19"))
        fake.etcWritten = emptyList()
        assertTrue(run("H19") is CheckResult.Failed)
    }

    @Test
    fun etcCheckWaitsFor2MinutesAfterAPowerOn() {
        fake.boot()
        fake.clock += 119_000
        assertEquals(CheckResult.NotApplicable(R.string.reason_just_started), run("H19"))
        fake.clock += 2_000
        assertEquals(CheckResult.Passed, run("H19"))
    }

    /** Writes Churn's folder as setup does, and with [card] as step 3 records it. */
    private fun writeFolder(card: Card? = null) {
        router.writeChurnFile("README.txt", "Churn's folder\n".toByteArray())
        router.writeChurnFile("factory", "${FACTORY.imei1}\n${FACTORY.imei2}\n".toByteArray())
        router.writeChurnFile("restore.sh", "#!/bin/sh\n".toByteArray(), executable = true)
        card?.let(router::recordCard)
    }

    @Test
    fun churnFolderCheckPassesWithTheFolderAsChurnWritesIt() {
        writeFolder()
        assertEquals(CheckResult.Passed, run("F1", SET_UP))
        // After a swap with the overwrite, and with what earlier versions of the app left.
        writeFolder(SWAPPED.card)
        router.writeChurnFile("log_file", "/etc/sys_log\n".toByteArray())
        router.writeChurnFile("secret", ByteArray(32))
        fake.zerosLink = "/overlay/churn-zeros"
        assertEquals(CheckResult.Passed, run("F1", SWAPPED))
    }

    @Test
    fun churnFolderCheckPassesWithAnIccidOf18To20Digits() {
        writeFolder(Card("894100000000000001", CARD_IMEIS))
        assertEquals(CheckResult.Passed, run("F1", SWAPPED))
        writeFolder(Card("89410000000000000015", CARD_IMEIS))
        assertEquals(CheckResult.Passed, run("F1", SWAPPED))
    }

    @Test
    fun churnFolderCheckDoesNotApplyBeforeSetup() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_not_set_up), run("F1"))
    }

    @Test
    fun churnFolderCheckFailsWithoutTheFolder() {
        assertEquals(CheckResult.Failed("Churn's folder is missing."), run("F1", SET_UP))
    }

    @Test
    fun churnFolderCheckFailsWithAFolderOthersCanRead() {
        writeFolder()
        fake.churnFolderMode = "drwxr-xr-x"
        assertEquals(CheckResult.Failed("The folder has mode drwxr-xr-x, not drwx------."), run("F1", SET_UP))
    }

    @Test
    fun churnFolderCheckFailsWithAFileOthersCanRead() {
        writeFolder(SWAPPED.card)
        fake.modes["/etc/churn/card"] = "644"
        assertEquals(CheckResult.Failed("card has mode -rw-r--r--, not -rw-------."), run("F1", SWAPPED))
        fake.modes["/etc/churn/card"] = "600"
        fake.modes["/etc/churn/restore.sh"] = "600"
        assertEquals(CheckResult.Failed("restore.sh has mode -rw-------, not -rwx------."), run("F1", SWAPPED))
        writeFolder()
        router.writeChurnFile("log_file", "/etc/sys_log\n".toByteArray(), executable = true)
        router.writeChurnFile("README.txt", "Churn's folder\n".toByteArray(), executable = true)
        assertEquals(
            CheckResult.Failed(
                "README.txt has mode -rwx------, not -rw-------. log_file has mode -rwx------, not -rw-------.",
            ),
            run("F1", SET_UP),
        )
    }

    @Test
    fun churnFolderCheckFailsWithAnotherFileOthersCanRead() {
        writeFolder()
        fake.files["/etc/churn/notes"] = "x\n".toByteArray()
        assertEquals(CheckResult.Failed("notes has mode -rw-r--r--."), run("F1", SET_UP))
        fake.modes["/etc/churn/notes"] = "700"
        assertEquals(CheckResult.Passed, run("F1", SET_UP))
    }

    @Test
    fun churnFolderCheckFailsWithAMissingFile() {
        writeFolder()
        fake.files.remove("/etc/churn/restore.sh")
        assertEquals(CheckResult.Failed("restore.sh is missing."), run("F1", SET_UP))
        writeFolder()
        fake.files.remove("/etc/churn/README.txt")
        assertEquals(CheckResult.Failed("README.txt is missing."), run("F1", SET_UP))
        writeFolder()
        fake.files.remove("/etc/churn/factory")
        assertEquals(CheckResult.Failed("factory is missing."), run("F1", SET_UP))
    }

    @Test
    fun churnFolderCheckFailsAtAListingItCannotRead() {
        writeFolder()
        val listing = "[ -d /etc/churn ] || exit 3; ls -lnd /etc/churn && ls -lnA /etc/churn"
        fake.answers[listing] = "ls: unknown option\n"
        assertEquals(CheckResult.Failed("Unexpected answer: /etc/churn"), run("F1", SET_UP))
        // Without the folder's own line first.
        fake.answers[listing] = "-rw-------    1 0        0              32 Nov  5 21:00 factory\n"
        assertEquals(CheckResult.Failed("Unexpected answer: /etc/churn"), run("F1", SET_UP))
    }

    @Test
    fun churnFolderCheckFailsWithAnotherOwner() {
        writeFolder()
        fake.churnOwner = 1000
        assertEquals(
            CheckResult.Failed(
                "/etc/churn belongs to user ID 1000. README.txt belongs to user ID 1000. " +
                    "factory belongs to user ID 1000. restore.sh belongs to user ID 1000.",
            ),
            run("F1", SET_UP),
        )
    }

    @Test
    fun churnFolderCheckFailsWithRecordsOfOtherSizes() {
        writeFolder()
        fake.files["/etc/churn/factory"] = "${FACTORY.imei1}\n${FACTORY.imei2}\n\n".toByteArray()
        assertEquals(CheckResult.Failed("factory is 33 bytes, not 32."), run("F1", SET_UP))
        fake.files["/etc/churn/factory"] = "${FACTORY.imei1}\n${FACTORY.imei2}".toByteArray()
        assertEquals(CheckResult.Failed("factory is 31 bytes, not 32."), run("F1", SET_UP))
        writeFolder(SWAPPED.card)
        fake.files["/etc/churn/card"] = "894100000000000001\n${CARD_IMEIS.imei1}\n${CARD_IMEIS.imei2}".toByteArray()
        assertEquals(CheckResult.Failed("card is 50 bytes, not 51 to 53."), run("F1", SWAPPED))
        fake.files["/etc/churn/card"] =
            "89410000000000000015\n${CARD_IMEIS.imei1}\n${CARD_IMEIS.imei2}\n\n".toByteArray()
        assertEquals(CheckResult.Failed("card is 54 bytes, not 51 to 53."), run("F1", SWAPPED))
    }

    @Test
    fun folderKeptCheckPassesWithTheFolderListed() {
        router.keepChurnFolder()
        assertEquals(CheckResult.Passed, run("F2", SET_UP))
    }

    @Test
    fun folderKeptCheckFailsWithoutTheFolderListed() {
        assertEquals(CheckResult.Failed("/etc/sysupgrade.conf doesn't list /etc/churn/."), run("F2", SET_UP))
        // Only the exact line counts.
        fake.sysupgrade += "\n/etc/churn\n"
        assertTrue(run("F2", SET_UP) is CheckResult.Failed)
    }

    @Test
    fun folderKeptCheckDoesNotApplyBeforeSetup() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_not_set_up), run("F2"))
    }

    @Test
    fun firewallKeptCheckPassesWithTheRulesListed() {
        router.installFirewall()
        assertEquals(CheckResult.Passed, run("F3"))
    }

    @Test
    fun firewallKeptCheckFailsWithoutTheRulesListed() {
        router.installFirewall()
        fake.sysupgrade = "/etc/config/\n/etc/profile"
        assertEquals(CheckResult.Failed("/etc/sysupgrade.conf doesn't list /etc/nftables.d/churn.nft."), run("F3"))
    }

    @Test
    fun firewallKeptCheckDoesNotApplyWithoutTheRules() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_firewall), run("F3"))
    }

    @Test
    fun zerosLinkCheckPassesWithOnlyTheLinkTaken() {
        router.keepChurnFolder()
        fake.zerosLink = "/overlay/churn-zeros"
        assertEquals(CheckResult.Passed, run("F4"))
        fake.churnFolderKb = 1023
        assertEquals(CheckResult.Passed, run("F4"))
    }

    @Test
    fun zerosLinkCheckDoesNotApplyWithoutTheLink() {
        router.keepChurnFolder()
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_zeros), run("F4"))
    }

    @Test
    fun zerosLinkCheckFailsWithTheLinkLeadingElsewhere() {
        router.keepChurnFolder()
        fake.zerosLink = "/tmp/churn-zeros"
        assertEquals(CheckResult.Failed("The link leads to /tmp/churn-zeros."), run("F4"))
    }

    @Test
    fun zerosLinkCheckFailsWithDuFollowingTheLink() {
        router.keepChurnFolder()
        fake.zerosLink = "/overlay/churn-zeros"
        fake.churnFolderKb = 1024
        assertEquals(CheckResult.Failed("du counts 1024 kB in Churn's folder."), run("F4"))
    }

    @Test
    fun zerosLinkCheckFailsWithAnUpgradeKeepingZeroFiles() {
        router.keepChurnFolder()
        fake.zerosLink = "/overlay/churn-zeros"
        fake.upgradeFollowsLinks = true
        assertEquals(CheckResult.Failed("sysupgrade -l lists zero files too."), run("F4"))
    }

    @Test
    fun zerosLinkCheckFailsWithAnUpgradeLeavingOutTheLink() {
        fake.zerosLink = "/overlay/churn-zeros"
        assertEquals(CheckResult.Failed("sysupgrade -l doesn't list the link."), run("F4"))
    }

    @Test
    fun zerosLinkCheckFailsAtAnAnswerItCannotRead() {
        fake.zerosLink = "/overlay/churn-zeros"
        val command = listOf(
            "[ -L /etc/churn/zeros ] || exit 3",
            "readlink /etc/churn/zeros || exit 1",
            "du -sk /etc/churn 2>/dev/null | cut -f 1",
            "sysupgrade -l 2>/dev/null | grep -e '^/etc/churn/zeros' -e '^/overlay/churn-zeros'",
            "true",
        ).joinToString("\n")
        fake.answers[command] = "/overlay/churn-zeros\n"
        assertTrue(run("F4").toString().contains("Unexpected answer: The link to the zero files"))
    }

    @Test
    fun imeiCheckPassesWithTheRecordedCardsPair() {
        fake.imei1 = CARD_IMEIS.imei1
        fake.imei2 = CARD_IMEIS.imei2
        assertEquals(CheckResult.Passed, run("F5", SWAPPED))
    }

    @Test
    fun imeiCheckFailsIfTheRecordedCardsPairChanged() {
        fake.imei1 = CARD_IMEIS.imei1
        assertEquals(CheckResult.Failed("The IMEIs aren't the pair Churn recorded for the card."), run("F5", SWAPPED))
    }

    @Test
    fun imeiCheckFailsIfTheFactoryImeisAreBack() {
        assertEquals(CheckResult.Failed("The router has its factory IMEIs again."), run("F5", SWAPPED))
    }

    @Test
    fun imeiCheckDoesNotApplyBeforeTheFirstSwap() {
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_record), run("F5", SET_UP))
    }

    @Test
    fun imeiCheckDoesNotApplyWhileSlot2IsOnTheEsim() {
        fake.slot2 = Slot2.ESIM
        assertEquals(CheckResult.NotApplicable(R.string.reason_slot2_on_esim), run("F5", SWAPPED))
    }

    @Test
    fun imeiCheckDoesNotApplyToAnotherCard() {
        fake.trayCard = FakeRouter.Card("8949000077777777777F", "228010000000002")
        assertEquals(CheckResult.NotApplicable(R.string.reason_record_not_in_slot2), run("F5", SWAPPED))
    }

    /** Runs the check of the claim with [id] on the router, whose Churn folder is [folder]. */
    private fun run(id: String, folder: ChurnFolder = NO_FOLDER) =
        CHECKS.single { id in it.ids }.run(router, CheckContext(folder, notes))

    /** How the router sends the AT [command] to slot 2's card. */
    private fun cardCommand(command: String) = "ubus -t 35 call modem.CPU.AT get_result_AT " +
        "'{\"cmd\":\"$command\",\"sub_id\":2,\"source_flag\":0,\"timeout\":30}'"

    /** GL's answer to [cardCommand], with the card's answer [data]. */
    private fun cardAnswer(data: String) = JSONObject().put("data", data).put("channel_status", true).toString()

    companion object {
        val NO_FOLDER = ChurnFolder(factory = null, card = null)

        /** How the router reads GL's settings for the modem and its slots. */
        const val FEATURE_CONFIG_CALL = "ubus -t 30 call cellular.modem get_feature_config '{}'"

        /** `ip rule show` as our Mudi 7 answered it with the Enhanced Kill Switch on and a WireGuard tunnel up. */
        val RULES = """
            0:	from all lookup local
            1:	from all iif lo lookup 16800
            800:	from all lookup 9910 suppress_prefixlength 0
            6000:	from all fwmark 0x1000/0xf000 lookup 1001
            6000:	from all fwmark 0x8000/0xf000 lookup main
            9910:	not from all fwmark 0/0xf000 blackhole
            9920:	from all iif br-lan blackhole
            32766:	from all lookup main
            32767:	from all lookup default
        """.trimIndent()

        /** The fake router's IMEIs, as setup records them. */
        val FACTORY = ImeiPair("356090215000002", "356090215000077")
        val CARD_IMEIS = ImeiPair("356090211234563", "356090211235313")

        /** Churn's folder once setup has recorded the fake router's IMEIs. */
        val SET_UP = ChurnFolder(FACTORY, card = null)

        /** Churn's folder once a swap has given the card in the fake router's tray [CARD_IMEIS]. */
        val SWAPPED = ChurnFolder(FACTORY, Card("8941000000000000015", CARD_IMEIS))

        /**
         * The tiers of each claim in docs/testing.md, by its ID, with `blocks` for a blocking tier 1 check: `1`,
         * `blocks` and `2` for "1, blocks; 2". The tests run in android/app.
         */
        val TIERS = File("../../docs/testing.md").readLines()
            .map { line -> line.split('|').map { it.trim() } }
            // | ID | Claim | Status | Check | Tier |
            .filter { it.size == 7 && it[1].matches(Regex("[A-Z][0-9]+")) }
            .associate { it[1] to it[5].split(',', ';').map(String::trim) }
    }
}
