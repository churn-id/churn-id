package id.churn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fake = FakeRouter()
    private var on = true
    private val file by lazy { File(folder.root, DebugLog.FILE_NAME) }
    private val log by lazy { DebugLog(file, on = { on }, now = { fake.clock }) }
    private val router by lazy { Router(log.record(fake)) }

    @Test
    fun recordsNothingWhileOff() {
        on = false
        log.beginSwap()
        log.start("Step 1")
        log.watchEsim(router)
        router.radio()
        log.add("The router's network is there.")
        log.copyEsim()
        assertFalse(file.exists())
        assertTrue(log.isEmpty)
    }

    @Test
    fun recordsCommandsAndAnswersWithoutLongNumbers() {
        log.start("Step 3")
        fake.clock += 1_500
        assertEquals("8941000000000000015F", router.slot2Iccid())
        router.writeImeis(ImeiPair("356090213059463", "356090213059539"))
        val text = file.readText()
        assertTrue(text, "\n== Step 3\n" in text)
        val request = """{"cmd":"AT+ICCID","sub_id":2,"source_flag":0,"timeout":30}"""
        assertTrue(text, "    1.5 $ ${FakeRouter.LOCK}ubus -t 35 call modem.CPU.AT get_result_AT '$request'\n" in text)
        assertTrue(text, "+ICCID: …\\r\\n" in text)
        assertTrue(text, "$ ${FakeRouter.LOCK}gl_modem -B cpu AT 'AT+EGMR=1,7,\"…\"' </dev/null\n" in text)
        for (number in listOf("8941000000000000015", "356090213059463", "356090213059539")) {
            assertFalse(text, number in text)
        }
    }

    @Test
    fun showsTheNumbersThatNameNoCard() {
        assertEquals(
            "+ICCID: 89000123456789012341, +CIMI: 001010123456063, 89111111111111111111, 111111111111111, …, …",
            DebugLog.mask(
                "+ICCID: 89000123456789012341, +CIMI: 001010123456063, 89111111111111111111, 111111111111111, " +
                    "8944000000000000123F, 260010000000001",
            ),
        )
    }

    @Test
    fun leavesOutFilesTheVpnSettingsAndTheClock() {
        fake.files[Router.FIREWALL] = "table inet churn { }\n".toByteArray()
        assertFalse(router.firewallInPlace())
        assertTrue(router.vpnSetUp())
        router.esimLog(from = 0, head = "", max = 0)
        val text = file.readText()
        assertTrue(text, "  exit 0, answer of 21 bytes left out\n" in text)
        assertFalse(text, "table inet" in text)
        assertFalse(text, "Tunnel 1" in text)
        // What the app made of the settings shows in the commands that follow.
        assertTrue(text, "$ uci -q get 'network.wgclient1'\n" in text)
        assertFalse(text, "${fake.time}" in text)
    }

    @Test
    fun masksNumbersBeforeItCutsACommandOrAnAnswer() {
        val iccid = "8944000000000000123F"
        // Without masking first, the cuts at 300 and 2,000 characters would leave the ICCID's first digits.
        val shell = log.record { _, _, _ -> Output(0, ("x".repeat(1_990) + iccid).toByteArray(), "") }
        shell.run("echo " + "x".repeat(290) + iccid, input = null, timeoutSeconds = 10)
        val text = file.readText()
        assertTrue(text, "$ echo ${"x".repeat(290)}…\n" in text)
        assertTrue(text, "| ${"x".repeat(1_990)}…\n" in text)
        assertFalse(text, "8944" in text)
    }

    @Test
    fun copiesWhatGlsEsimManagerLoggedDuringTheStepAtItsTimes() {
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen")
        fake.clock += 60_000
        log.start("Step 1")
        fake.clock += 2_000
        log.watchEsim(router)
        fake.clock += 5_000
        fake.esimLogs("Debug esim: (ipa:383) IpaDisableProfile:8944000000000000123F")
        fake.clock += 3_000
        log.copyEsim()
        var text = file.readText()
        assertFalse(text, "IpaReopen" in text)
        val disable = "    7.0 · Debug esim: (ipa:383) IpaDisableProfile:…\n"
        assertTrue(text, "   10.0 GL's eSIM manager logged:\n$disable" in text)
        fake.esimLogs("Debug esim: (ipa:419) disable.ret: 0")
        log.copyEsim()
        log.copyEsim()
        text = file.readText()
        assertEquals(text, 1, Regex("IpaDisableProfile").findAll(text).count())
        assertTrue(text, "   10.0 · Debug esim: (ipa:419) disable.ret: 0\n" in text)
        assertTrue(text, "   10.0 GL's eSIM manager logged nothing new.\n" in text)
    }

    @Test
    fun copiesGlsEsimManagerLogFromItsStartOnceTheClearingEmptiedIt() {
        repeat(3) { fake.esimLogs("Debug esim: (ipa:130) IpaReopen before the step") }
        log.start("Step 3")
        log.watchEsim(router)
        fake.esimLogs("Debug esim: (ipa:130) IpaReopen before the clearing")
        log.copyEsim()
        fake.files[FakeRouter.ESIM_LOG] = ByteArray(0)
        // Longer than the log was before the clearing.
        repeat(5) { fake.esimLogs("Debug esim: (ipa:130) IpaReopen after the clearing") }
        log.copyEsim()
        val text = file.readText()
        assertFalse(text, "before the step" in text)
        assertEquals(text, 1, Regex("before the clearing").findAll(text).count())
        assertEquals(text, 5, Regex("after the clearing").findAll(text).count())
    }

    @Test
    fun copiesOnlyTheWholeLinesAtTheEndOfALongEsimLog() {
        log.start("Step 1")
        log.watchEsim(router)
        // 2,000 lines of 63 bytes, more than the 100,000 bytes a copy takes.
        repeat(2_000) { fake.esimLogs("Debug esim: (ipa:130) IpaReopen %04d".format(it)) }
        log.copyEsim()
        val copied = file.readText().lines().filter { "IpaReopen" in it }
        assertTrue(copied.all { it.matches(Regex(" +0\\.0 · Debug esim: \\(ipa:130\\) IpaReopen \\d{4}")) })
        assertEquals("0413", copied.first().takeLast(4))
        assertEquals("1999", copied.last().takeLast(4))
    }

    @Test
    fun keepsTheLastThreeSwaps() {
        log.start("Setting up")
        log.add("set up")
        for (swap in 1..4) {
            log.beginSwap()
            log.start("Step 1")
            log.add("swap $swap")
        }
        val text = file.readText()
        assertTrue(text, text.startsWith("== New swap\n"))
        assertEquals(listOf("swap 2", "swap 3", "swap 4"), Regex("swap \\d").findAll(text).map { it.value }.toList())
    }

    @Test
    fun addsWhyAStepStoppedAndWhereInTheApp() {
        log.start("Step 1")
        log.error("No WireGuard VPN is set up on the router.", RouterException(Problem.NO_VPN))
        val text = file.readText()
        assertTrue(text, "    0.0 ! No WireGuard VPN is set up on the router.\n" in text)
        assertTrue(text, "    0.0   id.churn.RouterException: NO_VPN\n" in text)
        assertTrue(text, "    0.0     at id.churn.DebugLogTest.addsWhyAStepStoppedAndWhereInTheApp(" in text)
    }

    @Test
    fun deletesTheLog() {
        log.start("Step 1")
        assertFalse(log.isEmpty)
        log.delete()
        assertTrue(log.isEmpty)
    }
}
