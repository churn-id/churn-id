package id.churn

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val fake = FakeRouter()
    private val router = Router(fake)

    @Test
    fun parsesTheModemsAnswers() {
        // As gl_modem printed them on our Mudi 7.
        assertEquals("1", AtAnswer.parse("AT+CFUN?", "\n\r\n+CFUN: 1\r\n\r\nOK\r\n\n")!!.after("+CFUN:"))
        val slot2 = AtAnswer.parse("AT+QSIMTYPE=\"slot2\"", "\r\n+QSIMTYPE: \"slot2\",0\r\n\r\nOK\r\n")
        assertEquals("\"slot2\",0", slot2!!.after("+QSIMTYPE:"))
        assertNull(AtAnswer.parse("AT+CPIN?", "\r\n+CME ERROR: 10\r\n"))
        assertNull(AtAnswer.parse("AT+CFUN?", "\r\nERROR\r\n"))
        assertNull(AtAnswer.parse("AT+CFUN?", ""))
    }

    @Test
    fun leavesTheModemsEchoOutOfItsAnswers() {
        // As gl_modem printed it on our Mudi 7 a minute after power-on, and GL's channel to the cards always does.
        val modem = listOf("Quectel", "RG650V-EU", "Revision: RG650VEU00ADR02A04G8G")
        val echoed = "ATI\r\r\nQuectel\r\nRG650V-EU\r\nRevision: RG650VEU00ADR02A04G8G\r\n\r\nOK\r\n"
        assertEquals(modem, AtAnswer.parse("ATI", echoed)!!.lines)
        assertEquals(modem, AtAnswer.parse("ATI", echoed.removePrefix("ATI\r\r\n"))!!.lines)
        // Only a line that is the command itself.
        assertEquals(listOf("ATI0", "Quectel"), AtAnswer.parse("ATI", "ATI0\r\nQuectel\r\nOK\r\n")!!.lines)
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
        assertFalse(router.simConnectionCheckOff())
        assertEquals("8941000000000000015F", router.slot2Iccid())
        assertEquals("228010000000001", router.slot2Imsi())
        assertEquals("8941000000000000015F", router.currentCardIccid())
        assertFalse(router.flashLogOff())
        assertEquals(fake.bootId, router.bootId())
    }

    @Test
    fun stopsAtASettingThatUciCannotRead() {
        // As after a power loss during a commit. None of these may take the setting for unset.
        fake.brokenConfigs += listOf("system", "gl_timer", "network")
        val error = FakeRouter.UCI_PARSE_ERROR
        assertStops("uci get system.@system[0].log_file: $error") { router.flashLogOff() }
        assertStops("uci get gl_timer.reboot.enable: $error") { router.scheduledReboot() }
        assertStops("uci get network.vpn_to_main: $error") { router.killSwitchSettings() }
        // Before it deletes anything, with the log perhaps still on.
        assertStops("uci get system.@system[0].log_file: $error") { router.clearHistory(CURRENT_CARD) }
        assertTrue(fake.commands.none { it.startsWith("rm ") })
    }

    @Test
    fun refusesAnEmptyBootId() {
        fake.bootId = ""
        try {
            router.bootId()
            throw AssertionError("An empty boot ID was accepted")
        } catch (e: RouterException) {
            assertEquals(Problem.UNEXPECTED_ANSWER, e.problem)
        }
    }

    @Test
    fun takesOnlySlot2sCardWithARealNetworksImsi() {
        assertEquals(Slot2Card.Subscription("8941000000000000015F"), router.slot2Card())
        // A card without an enabled profile: an ICCID of its own, but no real network's IMSI.
        fake.trayCard = FakeRouter.Card("89000123456789000001", "001010000000001")
        assertEquals(Slot2Card.NoProfile, router.slot2Card())
        fake.trayCard = FakeRouter.Card("89000123456789000001", "111111111111111")
        assertEquals(Slot2Card.NoProfile, router.slot2Card())
        // A 9eSIM adapter card without one, which counts by its IMSI, not by its placeholder ICCID.
        fake.trayCard = FakeRouter.Card("89111111111111111111", "111111111111111")
        assertEquals(Slot2Card.NoProfile, router.slot2Card())
        fake.trayCard = null
        assertNull(router.slot2Card())
        fake.slot2 = Slot2.ESIM
        assertEquals(Slot2Card.Subscription("8944000000000000123F"), router.slot2Card())
        fake.esimProfile = null
        assertEquals(Slot2Card.NoProfile, router.slot2Card())
    }

    @Test
    fun seesACardInTheSim1Tray() {
        assertFalse(router.cardInSim1Tray())
        fake.tray1Card = FakeRouter.Card("8949000077777777777F", "228010000000002")
        assertTrue(router.cardInSim1Tray())
        // A card locked with a PIN is in the tray all the same.
        fake.tray1Card = FakeRouter.Card("8949000077777777777F", "228010000000002", pinLocked = true)
        assertTrue(router.cardInSim1Tray())
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
    fun clearingGivesWhatTheJournalsTurnoverWrote() {
        // Which the removal test leaves out of what the fill wrote.
        assertEquals(fake.journalTurnoverKb, router.clearHistory(CURRENT_CARD))
        assertEquals(0L, router.clearHistory(CURRENT_CARD, overwrite = false))
    }

    @Test
    fun turnsTheLogOffBeforeDeletingIt() {
        // An open file keeps its data on the flash after it is deleted.
        router.clearHistory(CURRENT_CARD)
        val off = fake.commands.indexOfFirst { it.startsWith("uci delete 'system.@system[0].log_file'") }
        val reload = fake.commands.indexOf("/etc/init.d/log reload")
        val delete = fake.commands.indexOf("rm -f '/etc/sys_log' '/etc/sys_log.old'")
        assertTrue(off in 0 until reload && reload < delete)
    }

    @Test
    fun deletesTheLogWhereGlsSettingPutsIt() {
        // As after a firmware upgrade that turned the log back on, with another file than the one Churn's copy names.
        fake.files["/etc/churn/log_file"] = "/etc/sys_log\n".toByteArray()
        fake.logFile = "/etc/messages"
        fake.files["/etc/messages"] = LOG_LINES
        fake.files["/etc/messages.old"] = LOG_LINES
        router.clearHistory(CURRENT_CARD)
        assertNull(fake.files["/etc/messages"])
        assertNull(fake.files["/etc/messages.old"])
    }

    @Test
    fun deletesTheLogWhereChurnsCopyOfGlsSettingPutIt() {
        // With the log off and its file left, as after a clearing that stopped before it deleted the file.
        fake.logFile = null
        fake.files["/etc/churn/log_file"] = "/etc/messages\n".toByteArray()
        fake.files["/etc/messages"] = LOG_LINES
        router.clearHistory(CURRENT_CARD)
        assertNull(fake.files["/etc/messages"])
    }

    @Test
    fun deletesALogWhosePathHasASpaceAsOneFile() {
        fake.logFile = "/etc/sys_log /etc/config"
        fake.files["/etc/sys_log /etc/config"] = LOG_LINES
        fake.files["/etc/sys_log /etc/config.old"] = LOG_LINES
        // A file in place of GL's settings folder, which the path would name if it were split in two.
        fake.files["/etc/config"] = LOG_LINES
        router.clearHistory(CURRENT_CARD)
        assertNull(fake.files["/etc/sys_log /etc/config"])
        assertNull(fake.files["/etc/sys_log /etc/config.old"])
        assertNotNull(fake.files["/etc/config"])
    }

    @Test
    fun deletesALogWhosePathHasAQuoteAndASemicolon() {
        // Unquoted, the quote would begin a quoted word and the semicolon would end the command.
        fake.logFile = "/etc/it's;log"
        fake.files["/etc/it's;log"] = LOG_LINES
        fake.files["/etc/it's;log.old"] = LOG_LINES
        router.clearHistory(CURRENT_CARD)
        assertNull(fake.files["/etc/it's;log"])
        assertNull(fake.files["/etc/it's;log.old"])
    }

    @Test
    fun overwritesTheFreeSpaceLast() {
        // Only then is everything it deleted in the free space.
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(CURRENT_CARD)
        assertTrue("/overlay/churn-fill." in fake.commands.last())
    }

    @Test
    fun clearsTheSimHistoryWithoutTheOverwriteIfAsked() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        router.clearHistory(CURRENT_CARD, overwrite = false)
        assertTrue(router.flashLogOff())
        assertEquals(setOf(CURRENT_CARD), fake.cardsInSimMap)
        assertFalse(fake.freeSpaceOverwritten)
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
        router.clearHistory(keepIccid = null)
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
    fun keepsTheModeOwnerAndGroupOfTheCardFilesItRewrites() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        // As GL might keep them: sim_map.json holds SIM PINs.
        fake.cardFileModes[FakeRouter.SIM_MAP] = "-rw------- 0 0"
        fake.cardFileModes[FakeRouter.APN_HISTORY] = "-rw-r----- 0 50"
        val modes = mapOf(FakeRouter.SIM_MAP to "-rw------- 0 0", FakeRouter.APN_HISTORY to "-rw-r----- 0 50")
        assertEquals(modes, router.cardFileModes())
        router.clearHistory(CURRENT_CARD)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInSimMap)
        assertEquals(setOf(CURRENT_CARD), fake.cardsInApnHistory)
        assertEquals(modes, router.cardFileModes())
    }

    @Test
    fun rewritesOnlyCardFilesThatChange() {
        fake.recordCards(CURRENT_CARD)
        router.clearHistory(CURRENT_CARD)
        assertTrue(fake.commands.none { "cat > /etc/config/cellular/" in it })
    }

    @Test
    fun stopsAtACardFileThatDoesNotParse() {
        // As in a format of another firmware, which may still name earlier cards.
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        fake.files[FakeRouter.SIM_MAP] = "{ \"$OLD_CARD\": ".toByteArray()
        assertStops("/etc/config/cellular/sim_map.json doesn't parse") { router.clearHistory(CURRENT_CARD) }
        assertArrayEquals("{ \"$OLD_CARD\": ".toByteArray(), fake.files[FakeRouter.SIM_MAP])
        assertFalse(fake.freeSpaceOverwritten)
    }

    @Test
    fun stopsAtAnApnHistoryWithoutItsList() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        fake.files[FakeRouter.APN_HISTORY] = """{"history": [{"iccid": "$OLD_CARD"}]}""".toByteArray()
        assertStops("/etc/config/cellular/history_apn_database.json has no apns list") {
            router.clearHistory(CURRENT_CARD)
        }
        assertFalse(fake.freeSpaceOverwritten)
    }

    @Test
    fun stopsAtAnEsimRecordThatDoesNotParseWithoutNamingIt() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        val record = fake.files.keys.first { it.startsWith(FakeRouter.ESIM_DB) }
        fake.files[record] = "{\"records\": ".toByteArray()
        // Its name is the chip's EID.
        assertStops("A record in /root/esim/db doesn't parse") { router.clearHistory(CURRENT_CARD) }
        assertTrue(record in fake.files)
        assertFalse(fake.freeSpaceOverwritten)
    }

    @Test
    fun clearsWithoutCardFiles() {
        router.clearHistory(CURRENT_CARD)
        assertTrue(fake.freeSpaceOverwritten)
    }

    @Test
    fun picksTheNumbersOutOfGlsFilesAndLogsOnTheRouter() {
        fake.recordCards(OLD_CARD, CURRENT_CARD)
        // A log's file of almost 4 MB, of which each number comes back once. Runs of 16 or 21 digits are no card's.
        val lines = "user.info Diag_Lib[1]: iccid[$OLD_CARD]\n".repeat(80_000) +
            "user.info Diag_Lib[1]: imsi[228010000000002] 1234567890123456 123456789012345678901\n"
        fake.files["/etc/sys_log.old"] = lines.toByteArray()
        var largest = 0
        val router = Router { command, input, timeout ->
            fake.run(command, input, timeout).also { largest = maxOf(largest, it.stdout.size) }
        }
        val numbers = router.numbersInCardFilesAndLogs("/etc/sys_log")
        assertEquals(setOf(OLD_CARD.removeSuffix("F"), CURRENT_CARD.removeSuffix("F"), "228010000000002"), numbers)
        assertTrue(largest < 1000)
    }

    @Test
    fun picksTheNumbersOutOfALogWhosePathNeedsQuoting() {
        for (log in listOf("/etc/sys_log /etc/config", "/etc/it's;log")) {
            fake.files["$log.old"] = "user.info Diag_Lib[1]: imsi[228010000000002]\n".toByteArray()
            assertTrue(log, "228010000000002" in router.numbersInCardFilesAndLogs(log))
            fake.files.remove("$log.old")
        }
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
    fun takesRulesThatDifferForAnEarlierVersionsOnes() {
        router.installFirewall()
        // As the rules of an earlier version of the app, which counted no packets, with their chains loaded.
        fake.files[Router.FIREWALL] = Router.firewallRules(453).replace(" counter", "").toByteArray()
        assertFalse(router.firewallInPlace())
        assertTrue(router.firewallInPlace(earlierVersion = true))
        fake.loadedChains.clear()
        assertFalse(router.firewallInPlace(earlierVersion = true))
        fake.files.remove(Router.FIREWALL)
        assertFalse(router.firewallInPlace(earlierVersion = true))
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
    fun turnsGlsConnectionCheckOffForTheSim() {
        // As kmwan holds the SIM's member after the SIM was online.
        fake.kmwanHoldsSim = true
        router.turnSimConnectionCheckOff()
        assertTrue(router.simConnectionCheckOff())
        assertEquals("1", fake.kmwanSimDisabled)
        assertArrayEquals("0\n".toByteArray(), fake.files["/etc/churn/kmwan"])
    }

    @Test
    fun savesTheConnectionCheckSettingOnlyOnce() {
        router.turnSimConnectionCheckOff()
        // Again with the check off, as in the dry run: restore.sh still puts back GL's setting from before Churn.
        router.turnSimConnectionCheckOff()
        assertArrayEquals("0\n".toByteArray(), fake.files["/etc/churn/kmwan"])
    }

    @Test
    fun savesAnEmptyLineForAConnectionCheckWithoutTheSetting() {
        fake.kmwanSimDisabled = null
        router.turnSimConnectionCheckOff()
        assertArrayEquals("\n".toByteArray(), fake.files["/etc/churn/kmwan"])
    }

    @Test
    fun seesAConnectionCheckThatStillHoldsTheSim() {
        // Off in GL's settings, but the module took the SIM's member up before and kmwan hasn't restarted since.
        fake.kmwanSimDisabled = "1"
        fake.kmwanHoldsSim = true
        assertEquals(listOf("modem_cpu"), router.kmwanModemMembers())
        assertFalse(router.simConnectionCheckOff())
    }

    @Test
    fun countsOnlyTheModemsMembersOfTheConnectionCheck() {
        fake.kmwanSimDisabled = "1"
        fake.wiredUplink = true
        assertEquals(emptyList<String>(), router.kmwanModemMembers())
        assertTrue(router.simConnectionCheckOff())
    }

    @Test
    fun stopsIfTheConnectionCheckKeepsTheSim() {
        fake.kmwanHoldsSim = true
        fake.kmwanRestartIgnored = true
        try {
            router.turnSimConnectionCheckOff()
            throw AssertionError("The check was taken for off")
        } catch (e: RouterException) {
            assertEquals(Problem.CONNECTION_CHECK_ON, e.problem)
        }
    }

    @Test
    fun readsTheLocationIdFromGlsSettings() {
        router.setSlot2(Slot2.ESIM)
        assertTrue(fake.commands.any { "\"location_id\":\"${FakeRouter.LOCATION_ID}\"" in it })
        assertEquals(Slot2.ESIM, fake.slot2)
    }

    @Test
    fun tellsASwitchRefusedWhileGlIsBusyFromOtherRefusals() {
        fake.slot2SwitchesRefused = 2
        try {
            router.setSlot2(Slot2.ESIM)
            throw AssertionError("The refused switch was taken for done")
        } catch (e: RouterException) {
            assertEquals(Problem.LOCK_FAILED, e.problem)
        }
        fake.switchError = "invalid argument"
        try {
            router.setSlot2(Slot2.ESIM)
            throw AssertionError("The refused switch was taken for done")
        } catch (e: RouterException) {
            assertEquals(Problem.UNEXPECTED_ANSWER, e.problem)
            assertTrue(e.detail!!, "invalid argument" in e.detail!!)
        }
        assertEquals(Slot2.SIM2, fake.slot2)
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
        assertFalse(folder.complete)
        router.writeChurnFile("factory", "356090215000002\n356090215000077\n".toByteArray())
        router.keepChurnFolder()
        val written = router.readChurnFolder()
        assertTrue(written.complete)
        assertEquals(ImeiPair("356090215000002", "356090215000077"), written.factory)
        assertNull(written.card)
        assertEquals("/etc/config/\n/etc/profile\n/etc/churn/\n", fake.sysupgrade)
        router.keepChurnFolder()
        assertEquals("/etc/config/\n/etc/profile\n/etc/churn/\n", fake.sysupgrade)
    }

    @Test
    fun recordsTheCard() {
        val card = Card(CURRENT_CARD.removeSuffix("F"), PAIR)
        router.recordCard(card)
        assertEquals(card, router.readChurnFolder().card)
        assertEquals(
            "8941000000000000015\n356090213059463\n356090213059539\n",
            fake.files["/etc/churn/card"]!!.toString(Charsets.US_ASCII),
        )
        assertTrue(fake.commands.any { "chmod 600 /etc/churn/card.new" in it })
    }

    @Test
    fun recordsTheVersion() {
        assertNull(router.readChurnFolder().version)
        router.recordVersion(12)
        assertEquals(12, router.readChurnFolder().version)
        assertEquals("12\n", fake.files["/etc/churn/version"]!!.toString(Charsets.US_ASCII))
        assertTrue(fake.commands.any { "chmod 600 /etc/churn/version.new" in it })
    }

    @Test
    fun countsAFolderWithoutAVersionAsVersion1() {
        val factory = router.imeis()
        assertFalse(ChurnFolder(factory, card = null).olderThan(1))
        assertTrue(ChurnFolder(factory, card = null).olderThan(2))
        assertFalse(ChurnFolder(factory, card = null, version = 2).olderThan(2))
        assertTrue(ChurnFolder(factory, card = null, version = 2).olderThan(3))
    }

    @Test
    fun seesAFolderOfANewerVersion() {
        val factory = router.imeis()
        assertTrue(ChurnFolder(factory, card = null, version = 3).newerThan(2))
        assertFalse(ChurnFolder(factory, card = null, version = 2).newerThan(2))
        // Without a version, as before the first swap, the folder counts as version 1, which no app is older than.
        assertFalse(ChurnFolder(factory, card = null).newerThan(1))
    }

    @Test
    fun checksTheImeisAgainstTheRecord() {
        val iccid = CURRENT_CARD.removeSuffix("F")
        val folder = ChurnFolder(router.imeis(), Card(iccid, PAIR))
        assertTrue(folder.isRecorded(iccid, PAIR))
        assertFalse(ChurnFolder(router.imeis(), card = null).isRecorded(iccid, PAIR))
        assertFalse(folder.isRecorded(OLD_CARD.removeSuffix("F"), PAIR))
        assertFalse(folder.isRecorded(iccid, router.imeis()))
    }

    @Test
    fun setupWritesBackThePhonesCopyOfTheFactoryImeis() {
        val factory = router.imeis()
        // The folder was lost after Churn wrote PAIR, which the modem still has.
        assertEquals(factory, ChurnFolder.factoryAtSetup(PAIR, written = PAIR, copy = factory))
        // Written by an earlier version of the app, which kept no copy. The setup screen warns.
        assertEquals(PAIR, ChurnFolder.factoryAtSetup(PAIR, written = PAIR, copy = null))
        // A router Churn didn't write the IMEIs to, such as a new one, keeps its own.
        val otherRouter = ImeiPair("356090219876548", "356090219876613")
        assertEquals(factory, ChurnFolder.factoryAtSetup(factory, written = PAIR, copy = otherRouter))
        assertEquals(factory, ChurnFolder.factoryAtSetup(factory, written = null, copy = null))
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
    fun refusesADamagedRecord() {
        fake.files["/etc/churn/card"] = "8941000000000000015\n356090213059463\n".toByteArray()
        try {
            router.readChurnFolder()
            throw AssertionError("A damaged record was accepted")
        } catch (e: RouterException) {
            assertEquals(Problem.NOT_SET_UP, e.problem)
        }
    }

    @Test
    fun refusesADamagedVersion() {
        fake.files["/etc/churn/version"] = "0.0.1\n".toByteArray()
        try {
            router.readChurnFolder()
            throw AssertionError("A damaged version was accepted")
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

    @Test
    fun keepsTheEsimManagersAnswerWithoutTheIccid() {
        val iccid = "8944000000000000123F"
        // We never saw an answer that named the ICCID, but a step's log may end up in a screenshot.
        val answer = """{"tx_id":"","code":404,"message":"no profile $iccid"}"""
        val named = Router { _, _, _ -> Output(0, answer.toByteArray(), "") }
        assertEquals(
            """http://127.0.0.1:3456/sdk/v1: {"tx_id":"","code":404,"message":"no profile …"}""",
            named.disableProfile(iccid),
        )
        // curl's own errors are on its error output.
        val error = "curl: (7) Failed to connect to 127.0.0.1 port 3456 after 0 ms: Couldn't connect to server\n"
        val refused = Router { _, _, _ -> Output(7, ByteArray(0), error) }
        assertEquals("http://127.0.0.1:3456/sdk/v1: ${error.trim()}", refused.disableProfile(iccid))
    }

    @Test(expected = java.io.IOException::class)
    fun reportsABrokenConnectionAsSuch() {
        Router { _, _, _ -> throw java.io.IOException("broken") }.slot2Iccid()
    }

    @Test(expected = IllegalArgumentException::class)
    fun writesOnlyValidImeis() {
        router.writeImeis(ImeiPair("356090213059464", "356090213059539"))
    }

    /** Runs [run], which has to stop at an unexpected answer with the [detail]. */
    private fun assertStops(detail: String, run: () -> Unit) {
        try {
            run()
            throw AssertionError("It didn't stop")
        } catch (e: RouterException) {
            assertEquals(Problem.UNEXPECTED_ANSWER, e.problem)
            assertEquals(detail, e.detail)
        }
    }

    private companion object {
        /** The card in the fake router's SIM 2 tray, as GL stores its 19-digit ICCID. */
        const val CURRENT_CARD = "8941000000000000015F"
        const val OLD_CARD = "8949000077777777777F"

        /** A card with a 20-digit ICCID, which GL stores without an `F`. */
        const val OLD_CARD_20 = "89320000555555555555"

        /** Lines of GL's system log that name a card. */
        val LOG_LINES = "user.info Diag_Lib[1]: iccid[$OLD_CARD]\n".toByteArray()

        /** A valid pair with the fake router's TAC and spacing. */
        val PAIR = ImeiPair("356090213059463", "356090213059539")
    }
}
