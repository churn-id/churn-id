package id.churn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val fake = FakeRouter()
    private val router = Router(fake)

    @Test
    fun parsesTheModemsAnswers() {
        // As gl_modem printed them on our Mudi 7.
        assertEquals("1", AtAnswer.parse("\n\r\n+CFUN: 1\r\n\r\nOK\r\n\n")!!.after("+CFUN:"))
        assertEquals("\"slot2\",0", AtAnswer.parse("\r\n+QSIMTYPE: \"slot2\",0\r\n\r\nOK\r\n")!!.after("+QSIMTYPE:"))
        assertNull(AtAnswer.parse("\r\n+CME ERROR: 10\r\n"))
        assertNull(AtAnswer.parse("\r\nERROR\r\n"))
        assertNull(AtAnswer.parse(""))
    }

    @Test
    fun readsTheRoutersState() {
        assertEquals(ImeiPair("356090215000002", "356090215000077"), router.imeis())
        assertEquals(1, router.radio())
        assertEquals(Slot2.SIM2, router.slot2())
        assertEquals("2", router.activeSlot())
        assertFalse(router.airplaneMode())
        assertFalse(router.scheduledReboot())
        assertTrue(router.enhancedKillSwitch())
        assertTrue(router.vpnSetUp())
        assertEquals("8941000000000000015F", router.slot2Iccid())
        assertEquals("228010000000001", router.slot2Imsi())
    }

    @Test
    fun seesARouterWithoutVpn() {
        fake.vpnTunnel = null
        assertFalse(router.vpnSetUp())
    }

    @Test
    fun ignoresATunnelWithoutItsInterface() {
        fake.vpnTunnel = "wgclient2"
        assertFalse(router.vpnSetUp())
    }

    @Test
    fun turnsTheEnhancedKillSwitchOn() {
        fake.killSwitchSet = false
        fake.killSwitchInUse = false
        assertFalse(router.enhancedKillSwitch())
        router.turnEnhancedKillSwitchOn()
        assertTrue(router.enhancedKillSwitch())
    }

    @Test
    fun seesAKillSwitchThatIsNotInUseYet() {
        // Turned on in the settings, but without the network reload.
        fake.killSwitchInUse = false
        assertFalse(router.enhancedKillSwitch())
    }

    @Test
    fun stopsIfTheKillSwitchDoesNotComeOn() {
        fake.killSwitchInUse = false
        fake.reloadIgnored = true
        try {
            router.turnEnhancedKillSwitchOn()
            throw AssertionError("The switch was taken for on")
        } catch (e: RouterException) {
            assertEquals(Problem.KILL_SWITCH_OFF, e.problem)
        }
    }

    @Test
    fun readsTheLocationIdFromGlsSettings() {
        router.setSlot2(Slot2.ESIM)
        assertTrue(fake.commands.any { "\"location_id\":\"${FakeRouter.LOCATION_ID}\"" in it })
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun quotesShellWords() {
        assertEquals("'AT+EGMR=0,7'", Router.quote("AT+EGMR=0,7"))
        assertEquals("'it'\\''s'", Router.quote("it's"))
    }

    @Test
    fun setsUpChurnsFolder() {
        val folder = router.readChurnFolder()
        assertNull(folder.factory)
        assertNull(folder.secret)
        assertFalse(folder.complete)
        val secret = ByteArray(32) { (it * 7).toByte() }
        router.writeChurnFile("factory", "356090215000002\n356090215000077\n".toByteArray())
        router.writeChurnFile("secret", secret)
        router.keepChurnFolder()
        val written = router.readChurnFolder()
        assertTrue(written.complete)
        assertEquals(ImeiPair("356090215000002", "356090215000077"), written.factory)
        assertArrayEquals(secret, written.secret)
        assertEquals("/etc/config/\n/etc/profile\n/etc/churn/\n", fake.sysupgrade)
        router.keepChurnFolder()
        assertEquals("/etc/config/\n/etc/profile\n/etc/churn/\n", fake.sysupgrade)
    }

    @Test
    fun refusesADamagedFolder() {
        fake.files["/etc/churn/factory"] = "356090215000002\n".toByteArray()
        try {
            router.readChurnFolder()
            throw AssertionError("A damaged folder was accepted")
        } catch (e: RouterException) {
            assertEquals(Problem.NOT_SET_UP, e.problem)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun writesOnlyInsideChurnsFolder() {
        router.writeChurnFile("../config/glconfig", ByteArray(0))
    }

    @Test
    fun checksEveryImeiItWrites() {
        router.writeImeis(ImeiPair("356090213059463", "356090213059539"))
        assertEquals(ImeiPair("356090213059463", "356090213059539"), router.imeis())
    }

    @Test
    fun savesTheImeisItWrites() {
        val pair = ImeiPair("356090213059463", "356090213059539")
        router.writeImeis(pair)
        fake.boot()
        assertEquals(pair, ImeiPair(fake.imei1, fake.imei2))
    }

    @Test(expected = java.io.IOException::class)
    fun reportsABrokenConnectionAsSuch() {
        Router { _, _, _ -> throw java.io.IOException("broken") }.slot2Iccid()
    }

    @Test(expected = IllegalArgumentException::class)
    fun writesOnlyValidImeis() {
        router.writeImeis(ImeiPair("356090213059464", "356090213059539"))
    }
}
