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

    /** Runs the check of the claim with [id] on the router, whose Churn folder is [folder]. */
    private fun run(id: String, folder: ChurnFolder = NO_FOLDER) = CHECKS.single { id in it.ids }.run(router, folder)

    private companion object {
        val NO_FOLDER = ChurnFolder(factory = null, card = null)

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
