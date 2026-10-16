package id.churn

import org.json.JSONObject
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
        assertFalse(router.firewallInPlace())
        assertEquals("8941000000000000015F", router.slot2Iccid())
        assertEquals("228010000000001", router.slot2Imsi())
        assertEquals("8941000000000000015F", router.currentCardIccid())
        assertFalse(router.flashLogOff())
    }

    @Test
    fun takesOnlyAnIccidForTheCurrentCard() {
        fake.modemCpuIccid = "unknown"
        assertNull(router.currentCardIccid())
        fake.modemCpuIccid = null
        assertNull(router.currentCardIccid())
    }

    @Test
    fun clearsTheSimHistoryButTheCurrentCard() {
        fake.recordCards(OLD_CARD, OLD_CARD_20, CURRENT_CARD)
        fake.files["/etc/sys_log.old"] = "user.info Diag_Lib[1]: iccid[$OLD_CARD]\n".toByteArray()
        router.clearHistory(CURRENT_CARD)
        assertTrue(router.flashLogOff())
        assertNull(fake.files["/etc/sys_log"])
        assertNull(fake.files["/etc/sys_log.old"])
        assertEquals(setOf(CURRENT_CARD), fake.cardsInSimMap)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInApnHistory)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInEsimRecords)
        assertEquals(0, fake.files["/root/esim/log.txt"]!!.size)
        // The current card keeps its settings.
        val settings = JSONObject(fake.files[FakeRouter.SIM_MAP]!!.toString(Charsets.UTF_8))
        assertTrue(settings.getJSONObject(CURRENT_CARD).getBoolean("roaming"))
        assertTrue(fake.freeSpaceOverwritten)
    }

    @Test
    fun turnsTheLogOffBeforeDeletingIt() {
        // An open file keeps its data on the flash after it is deleted.
        router.clearHistory(CURRENT_CARD)
        val off = fake.commands.indexOfFirst { it.startsWith("uci delete 'system.@system[0].log_file'") }
        val reload = fake.commands.indexOf("/etc/init.d/log reload")
        val delete = fake.commands.indexOf("rm -f /etc/sys_log /etc/sys_log.old")
        assertTrue(off in 0 until reload && reload < delete)
    }

    @Test
    fun overwritesTheFreeSpaceLast() {
        // Only then is everything it deleted in the free space.
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(CURRENT_CARD)
        assertTrue("/overlay/churn-fill." in fake.commands.last())
    }

    @Test
    fun matchesTheCurrentCardWithOrWithoutItsTrailingF() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(CURRENT_CARD.removeSuffix("F"))
        assertEquals(setOf(CURRENT_CARD), fake.cardsInSimMap)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInApnHistory)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInEsimRecords)
        fake.recordCards(OLD_CARD, CURRENT_CARD.removeSuffix("F"))
        router.clearHistory(CURRENT_CARD)
        assertEquals(setOf(CURRENT_CARD.removeSuffix("F")), fake.cardsInSimMap)
    }

    @Test
    fun clearsEveryCardWithoutACurrentOne() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(null)
        assertEquals(emptySet<String>(), fake.cardsInSimMap)
        assertEquals(emptySet<String>(), fake.cardsInApnHistory)
        assertEquals(emptySet<String>(), fake.cardsInEsimRecords)
    }

    @Test
    fun savesTheLogSettingOnlyOnce() {
        router.clearHistory(CURRENT_CARD)
        assertArrayEquals("/etc/sys_log\n".toByteArray(), fake.files["/etc/churn/log_file"])
        // As after a firmware upgrade that turned the log back on: restore.sh still puts back what came before Churn.
        fake.logFile = "/etc/messages"
        router.clearHistory(CURRENT_CARD)
        assertTrue(router.flashLogOff())
        assertArrayEquals("/etc/sys_log\n".toByteArray(), fake.files["/etc/churn/log_file"])
    }

    @Test
    fun leavesALogThatIsOffAsItIs() {
        fake.logFile = null
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(CURRENT_CARD)
        assertNull(fake.files["/etc/churn/log_file"])
        assertTrue(fake.commands.none { "uci delete" in it || "log reload" in it })
        assertEquals(setOf(CURRENT_CARD), fake.cardsInSimMap)
        assertTrue(fake.freeSpaceOverwritten)
    }

    @Test
    fun rewritesOnlyCardFilesThatChange() {
        fake.recordCards(CURRENT_CARD)
        router.clearHistory(CURRENT_CARD)
        assertTrue(fake.commands.none { "cat > /etc/config/cellular/" in it })
    }

    @Test
    fun leavesACardFileItCannotReadAsItIs() {
        fake.files[FakeRouter.SIM_MAP] = "{ \"$OLD_CARD\": ".toByteArray()
        router.clearHistory(CURRENT_CARD)
        assertArrayEquals("{ \"$OLD_CARD\": ".toByteArray(), fake.files[FakeRouter.SIM_MAP])
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
    fun ignoresATunnelThatIsNotWireGuard() {
        fake.vpnTunnel = "ovpnclient1"
        fake.vpnType = "openvpn"
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
    fun putsChurnsFirewallRulesInPlace() {
        router.installFirewall()
        assertTrue(router.firewallInPlace())
        assertEquals(setOf("churn_sim", "churn_route", "churn_dns"), fake.loadedChains)
        assertTrue("meta skuid 453 " in fake.files[Router.FIREWALL]!!.toString(Charsets.UTF_8))
        assertEquals("/etc/config/\n/etc/profile\n${Router.FIREWALL}\n", fake.sysupgrade)
        router.installFirewall()
        assertEquals("/etc/config/\n/etc/profile\n${Router.FIREWALL}\n", fake.sysupgrade)
    }

    @Test
    fun redirectsTheDnsServerByItsUserId() {
        fake.dnsmasqUid = 460
        router.installFirewall()
        assertTrue("meta skuid 460 " in fake.files[Router.FIREWALL]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun seesRulesForAnotherUserId() {
        // As after a firmware upgrade that kept the rules but gave the DNS server another user ID.
        router.installFirewall()
        fake.dnsmasqUid = 460
        assertFalse(router.firewallInPlace())
    }

    @Test
    fun seesRulesThatAreNotLoaded() {
        fake.files[Router.FIREWALL] = Router.firewallRules(453).toByteArray()
        assertFalse(router.firewallInPlace())
    }

    @Test
    fun removesRulesTheFirewallDoesNotLoad() {
        fake.fw4Rejects = true
        try {
            router.installFirewall()
            throw AssertionError("Rules that didn't load were taken for in place")
        } catch (e: RouterException) {
            assertEquals(Problem.FIREWALL_NOT_LOADED, e.problem)
        }
        assertNull(fake.files[Router.FIREWALL])
        assertFalse(router.firewallInPlace())
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

    @Test
    fun rewritesOnlyChurnFilesThatChanged() {
        fun writes() = fake.commands.count { "cat > /etc/churn/restore.sh.new" in it }
        router.updateChurnFile("restore.sh", "old".toByteArray(), executable = true)
        router.updateChurnFile("restore.sh", "new".toByteArray(), executable = true)
        assertArrayEquals("new".toByteArray(), fake.files["/etc/churn/restore.sh"])
        assertEquals(2, writes())
        router.updateChurnFile("restore.sh", "new".toByteArray(), executable = true)
        assertEquals(2, writes())
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

    private companion object {
        /** The card in the fake router's SIM 2 tray, as GL stores its 19-digit ICCID. */
        const val CURRENT_CARD = "8941000000000000015F"
        const val OLD_CARD = "8949000077777777777F"

        /** A card with a 20-digit ICCID, which GL stores without an `F`. */
        const val OLD_CARD_20 = "89320000555555555555"
    }
}
