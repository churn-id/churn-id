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
        val folder = ChurnFolder(FACTORY, card = null)
        assertEquals(CheckResult.NotApplicable(R.string.reason_no_record), run("F5", folder))
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

        /** The fake router's IMEIs, as setup records them. */
        val FACTORY = ImeiPair("356090215000002", "356090215000077")
        val CARD_IMEIS = ImeiPair("356090211234563", "356090211235313")

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
