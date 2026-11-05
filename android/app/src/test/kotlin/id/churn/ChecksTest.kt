package id.churn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {

    private val fake = FakeRouter()
    private val router = Router(fake)

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
        assertEquals(CheckResult.Failed("Unexpected answer: AT+CFUN?: modem not ready"), check.run(router, NO_FOLDER))
    }

    @Test
    fun platformCheckPassesOnTheFirmwareAndModemWeTested() {
        assertEquals(CheckResult.Passed, run("P1"))
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
    private fun run(id: String, folder: ChurnFolder = NO_FOLDER) = CHECKS.single { id in it.ids }.run(router, folder)

    private companion object {
        val NO_FOLDER = ChurnFolder(factory = null, card = null)

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
