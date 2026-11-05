package id.churn

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Mudi 7 as development.md describes it, answering the commands [Router] sends. It notes every change made
 * while the radio was on, and every modem command sent without Churn's lock.
 */
class FakeRouter : Shell {

    class Card(val iccid: String, val imsi: String, val pinLocked: Boolean = false)

    /** Milliseconds, advanced by the tests' sleep. */
    var clock = 0L

    /** The router's clock, in seconds since 1970, which [clock] advances. */
    val time get() = START_TIME + clock / 1000
    private var bootedAt = -600_000L
    val uptime get() = (clock - bootedAt) / 1000.0

    /** The ID Linux gives the current run, a new one at every power-on, in Linux's format. */
    var bootId = UUID.randomUUID().toString()

    var airplaneMode = false

    /** For the radio that never turns off. */
    var radioStuckOn = false

    /** For a `/proc/uptime` that doesn't parse. */
    var uptimeBroken = false

    /** For a modem that refuses IMEI writes. */
    var imeiWritesRefused = false
    var imei1 = "356090215000002"
    var imei2 = "356090215000077"

    /** The IMEIs as last saved to flash, while a later write isn't saved yet. Power-on brings them back. */
    private var savedImeis: ImeiPair? = null
    var slot2 = Slot2.SIM2
    var activeSlot = 2

    /** GL's uci setting naming the flash file its system log goes to; null once the app turns the log off. */
    var logFile: String? = "/etc/sys_log"

    /** The ICCID GL recorded for the active card (`network.modem_cpu.iccid`), as GL stores it, or null for none. */
    var modemCpuIccid: String? = "8941000000000000015F"

    /** Set once the app overwrites the overlay's free space. */
    var freeSpaceOverwritten = false

    /** The enabled profile's ICCID as `AT+ICCID` gives it, or null for none. */
    var esimProfile: String? = "8944000000000000123F"

    /** How long the chip doesn't answer after a disable, while the modem reloads it; GL's eSIM manager waits 5 s. */
    var esimReloadMillis = 5_000L
    private var esimReloadsUntil = 0L

    /** For GL's eSIM manager leaving the profile enabled at this many requests, as it did once at our step 1. */
    var esimRequestsIgnored = 0
    var trayCard: Card? = Card("8941000000000000015F", "228010000000001")

    /** The card in the SIM 1 tray, which Churn never uses. */
    var tray1Card: Card? = null
    var scheduledReboot = false

    /** GL's Enhanced Kill Switch in GL's settings, and in the routing rules in use, which follow at a reload. */
    var killSwitchSet = true
    var killSwitchInUse = true

    /** For a network reload that leaves the routing rules as they are. */
    var reloadIgnored = false

    /** For a radio that comes on at a reload of the network, the firewall or the log service. */
    var radioOnAtReload = false

    /** The network interface the VPN tunnel in GL's route policy uses, or null for no tunnel. */
    var vpnTunnel: String? = "wgclient1"

    /** The tunnel's type in GL's route policy. */
    var vpnType = "wireguard"
    var poweredOff = false
    val files = mutableMapOf<String, ByteArray>()
    var sysupgrade = "/etc/config/\n/etc/profile"

    /** The user ID of GL's main DNS server, 453 on our Mudi 7. */
    var dnsmasqUid = 453

    /** For a firewall that can't load Churn's rules, such as a later fw4 or nftables that rejects one of them. */
    var fw4Rejects = false

    /** The chains of Churn's firewall rules that fw4 loaded at its last reload. */
    val loadedChains = mutableSetOf<String>()

    val commands = mutableListOf<String>()
    val changes = mutableListOf<String>()
    val unsafe = mutableListOf<String>()
    val unlocked = mutableListOf<String>()

    /** The radio state the modem reports. For some seconds after power-on it is on, whatever the settings. */
    val radio
        get() = when {
            radioStuckOn || uptime < 58 -> 1
            airplaneMode -> 4
            else -> 1
        }

    /**
     * The modem doesn't answer for a while after power-on (development.md, "After power-on"); 30 s is our guess, not
     * a measurement. The swap sends no modem command before 120 s, so only a swap that sends one too early gets here.
     */
    private val modemAnswers get() = uptime >= 30

    /**
     * GL's records of [cards], given by their ICCIDs as GL stores them, in the files and formats of our Mudi 7: the
     * system log, the two card files, and a record in the eSIM manager's folder for each, as if each card were an
     * eSIM adapter card. The eSIM manager's log names them too.
     */
    fun recordCards(vararg cards: String) {
        val simMap = JSONObject()
        val apns = JSONArray()
        cards.forEachIndexed { i, iccid ->
            simMap.put(iccid, JSONObject().put("manual", false).put("pincode", "").put("roaming", true).put("cid", 1))
            apns.put(JSONObject().put("iccid", iccid).put("apn", "internet").put("ip_type", "IPV4V6").put("cid", 1))
            val record = JSONObject().put("iccid", iccid).put("ac_code", "").put("apn", "").put("oplmn", "")
            files["$ESIM_DB/${"${i + 1}".repeat(32)}"] =
                JSONObject().put("records", JSONObject().put(iccid, record)).toString().toByteArray()
        }
        files[SIM_MAP] = simMap.toString(2).toByteArray()
        files[APN_HISTORY] = JSONObject().put("apns", apns).toString(2).toByteArray()
        files[ESIM_LOG] = cards.joinToString("") { "get profile list: $it\n" }.toByteArray()
        files["/etc/sys_log"] = cards.joinToString("") { "user.info Diag_Lib[1]: iccid[$it]\n" }.toByteArray()
    }

    /** Adds [text] to GL's eSIM manager's log, after the time in UTC, as the eSIM manager writes it (inferred). */
    fun esimLogs(text: String) {
        val stamp = ESIM_TIME.format(Instant.ofEpochSecond(time).atOffset(ZoneOffset.UTC))
        files[ESIM_LOG] = (files[ESIM_LOG] ?: ByteArray(0)) + "$stamp $text\r\n".toByteArray()
    }

    /** The cards GL's `sim_map.json` still names. */
    val cardsInSimMap: Set<String>
        get() = JSONObject(files[SIM_MAP]!!.toString(Charsets.UTF_8)).keys().asSequence().toSet()

    /** The cards GL's `history_apn_database.json` still names. */
    val cardsInApnHistory: Set<String>
        get() {
            val apns = JSONObject(files[APN_HISTORY]!!.toString(Charsets.UTF_8)).getJSONArray("apns")
            return (0 until apns.length()).map { apns.getJSONObject(it).getString("iccid") }.toSet()
        }

    /** The cards the eSIM manager's records still name. */
    val cardsInEsimRecords: Set<String>
        get() = files.filterKeys { it.startsWith("$ESIM_DB/") }.values
            .flatMap { JSONObject(it.toString(Charsets.UTF_8)).getJSONObject("records").keys().asSequence() }.toSet()

    /** Power-on with the settings kept. */
    fun boot() {
        savedImeis?.let {
            imei1 = it.imei1
            imei2 = it.imei2
        }
        savedImeis = null
        bootedAt = clock
        bootId = UUID.randomUUID().toString()
        poweredOff = false
    }

    override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
        check(!poweredOff) { "The router is off" }
        commands += command
        val body = command.removePrefix(LOCK)
        if (body == command && MODEM.any { it in command }) unlocked += command
        return answer(body, input)
    }

    private fun answer(command: String, input: ByteArray?): Output {
        Regex("""gl_modem -B cpu AT '(.*)' </dev/null""").matchEntire(command)?.let { return at(it.groupValues[1]) }
        Regex("""ubus -t 35 call modem.CPU.AT get_result_AT '(.*)'""").matchEntire(command)?.let {
            val request = JSONObject(it.groupValues[1])
            return cardAt(request.getInt("sub_id"), request.getString("cmd"))
        }
        Regex("""curl .* -d '(.*)' http://127.0.0.1:3456/sdk/v1""").matchEntire(command)?.let {
            return disable(JSONObject(it.groupValues[1]))
        }
        if (command == "ubus -t 30 call cellular.modem get_feature_config '{}'") return ok(FEATURE_CONFIG)
        Regex("""ubus -t 30 call cellular.modem set_feature_config '(.*)'""").matchEntire(command)?.let {
            return setFeatureConfig(JSONObject(it.groupValues[1]))
        }
        Regex("""uci -q get '(.*)'""").matchEntire(command)?.let { return uciGet(it.groupValues[1]) }
        val airplaneModeSet = Regex("""uci set 'glconfig.general.airplane_mode=([01])' && uci commit 'glconfig'""")
        airplaneModeSet.matchEntire(command)?.let {
            airplaneMode = it.groupValues[1] == "1"
            return ok("")
        }
        if (command == "ip rule show") return ok(ipRules())
        if (command == "uci -q show route_policy") return ok(routePolicy())
        if (command == KILL_SWITCH_ON) {
            change("Enhanced Kill Switch")
            killSwitchSet = true
            if (!reloadIgnored) killSwitchInUse = true
            if (radioOnAtReload) radioStuckOn = true
            return ok("")
        }
        if (command == "cat /proc/uptime") return ok(if (uptimeBroken) "" else "%.2f 100.00\n".format(uptime))
        if (command == "cat /proc/sys/kernel/random/boot_id") return ok("$bootId\n")
        if (command == "poweroff") {
            poweredOff = true
            return ok("")
        }
        Regex("""if \[ -e (\S+) ]; then cat \S+; else exit 3; fi""").matchEntire(command)?.let {
            val file = files[it.groupValues[1]] ?: return Output(3, ByteArray(0), "")
            return Output(0, file, "")
        }
        Regex("""umask 077 && .* && cat > (\S+)\.new && chmod (\d+) .*""").matchEntire(command)?.let {
            files[it.groupValues[1]] = input!!
            return ok("")
        }
        Regex("""grep -qxF (\S+) /etc/sysupgrade.conf.*""").matchEntire(command)?.let {
            val line = it.groupValues[1]
            val listed = line in sysupgrade.lines()
            if (command.contains("||") && !listed) {
                sysupgrade += (if (sysupgrade.endsWith("\n")) "" else "\n") + "$line\n"
                return ok("")
            }
            return Output(if (listed) 0 else 1, ByteArray(0), "")
        }
        if (command == "id -u dnsmasq") return ok("$dnsmasqUid\n")
        Regex("""umask 022 && cat > (\S+)\.new && mv \S+ \S+""").matchEntire(command)?.let {
            files[it.groupValues[1]] = input!!
            return ok("")
        }
        if (command == "fw4 reload") return fw4Reload()
        if (command == "rm -f ${Router.FIREWALL} && fw4 reload") {
            files.remove(Router.FIREWALL)
            return fw4Reload()
        }
        if (command == FIREWALL_LOADED) {
            val all = listOf("churn_sim", "churn_route", "churn_dns").all { it in loadedChains }
            return Output(if (all) 0 else 1, ByteArray(0), "")
        }
        // The app turns GL's flash system log off by deleting its uci setting, then reloads the log service.
        if (command == "uci delete 'system.@system[0].log_file' && uci commit system") {
            logFile = null
            return ok("")
        }
        if (command == "/etc/init.d/log reload") {
            if (radioOnAtReload) radioStuckOn = true
            return ok("")
        }
        // GL's eSIM manager keeps one record file per chip under /root/esim/db, named by a 32-digit id.
        Regex("""find (\S+/db) -maxdepth 1 -type f 2>/dev/null""").matchEntire(command)?.let {
            val dir = "${it.groupValues[1]}/"
            return ok(files.keys.filter { key -> key.startsWith(dir) }.joinToString("") { key -> "$key\n" })
        }
        // The debug log's read of GL's eSIM manager's log, as Router.esimLog describes it.
        ESIM_LOG_READ.matchEntire(command)?.let {
            val (count, from, head, max) = it.destructured
            val log = files[ESIM_LOG] ?: ByteArray(0)
            val start = if (md5(log, count.toInt()) == head && log.size >= from.toInt()) from.toInt() else 0
            val part = log.copyOfRange(log.size - minOf(log.size - start, max.toInt()), log.size)
            return Output(0, "$time\n${log.size} ${md5(log, 64)} $start\n".toByteArray() + part, "")
        }
        Regex(""": > (\S+)""").matchEntire(command)?.let {
            files[it.groupValues[1]] = ByteArray(0)
            return ok("")
        }
        // The free-space overwrite writes /overlay/churn-fill.* parts, kept as zero files; its effect is not in the files.
        if ("/overlay/churn-fill." in command) {
            freeSpaceOverwritten = true
            return ok("")
        }
        Regex("""rm -f (.+)""").matchEntire(command)?.let {
            it.groupValues[1].split(" ").filter(String::isNotBlank).forEach(files::remove)
            return ok("")
        }
        error("Unexpected command: $command")
    }

    /**
     * Loads the chains of Churn's rules file, all or, if [fw4Rejects], none: a reload that fails keeps the rules
     * loaded before. Like GL's fw4, it warns about GL's own options.
     */
    private fun fw4Reload(): Output {
        change("firewall")
        if (radioOnAtReload) radioStuckOn = true
        val file = files[Router.FIREWALL]?.toString(Charsets.UTF_8)
        if (file != null && fw4Rejects) return Output(1, ByteArray(0), "Error: syntax error")
        loadedChains.clear()
        file?.let { loadedChains += Regex("""chain (\w+) \{""").findAll(it).map { m -> m.groupValues[1] } }
        return Output(0, ByteArray(0), "[!] Section glblock option 'reload' is not supported by fw4")
    }

    private fun at(command: String): Output {
        if (!modemAnswers) return Output(1, ByteArray(0), "modem not ready")
        val imeiWrite = Regex("""AT\+EGMR=1,(7|11),"(\d{15})"""").matchEntire(command)
        return when {
            command == "AT+CFUN?" -> atOk("+CFUN: $radio")
            command == "AT+EGMR=0,7" -> atOk("+EGMR: \"$imei1\"")
            command == "AT+EGMR=0,11" -> atOk("+EGMR: \"$imei2\"")
            imeiWrite != null && imeiWritesRefused -> ok("\r\nERROR\r\n")
            imeiWrite != null -> {
                val (item, imei) = imeiWrite.destructured
                change("IMEI $item")
                if (savedImeis == null) savedImeis = ImeiPair(imei1, imei2)
                if (item == "7") imei1 = imei else imei2 = imei
                atOk()
            }
            command == "AT+QPRTPARA=1" -> {
                savedImeis = null
                atOk()
            }
            command == "AT+QSIMTYPE=\"slot2\"" -> atOk("+QSIMTYPE: \"slot2\",${slot2.type}")
            else -> ok("\r\nERROR\r\n")
        }
    }

    private fun cardAt(slot: Int, command: String): Output {
        val card = when {
            // As if no card were in. For the SIM 1 tray that is the worst a swap that asks too early could get, and
            // step3StopsWhileTheCardIsOffIfTheSim1TrayHoldsACard then fails.
            !modemAnswers -> null
            slot == 1 -> tray1Card
            slot2 == Slot2.SIM2 -> trayCard
            clock < esimReloadsUntil -> null
            else -> esimProfile?.let { Card(it, "260010000000001") }
                ?: Card(Router.PLACEHOLDER_ICCID, "001010123456789")
        }
        val data = when {
            card == null -> "\r\n+CME ERROR: 10\r\n"
            command == "AT+CPIN?" -> "\r\n+CPIN: ${if (card.pinLocked) "SIM PIN" else "READY"}\r\n\r\nOK\r\n"
            command == "AT+ICCID" -> "\r\n+ICCID: ${card.iccid}\r\n\r\nOK\r\n"
            command == "AT+CIMI" -> "\r\n${card.imsi}\r\n\r\nOK\r\n"
            else -> "\r\nERROR\r\n"
        }
        return ok(JSONObject().put("data", data).put("channel_status", true).toString(1))
    }

    /**
     * GL's eSIM manager disables only the enabled profile the request names, and only while slot 2 is on the eSIM
     * (inferred). The swap always meets both, so only a broken swap sends another request. That still gets `code` 200,
     * as GL answered even when it logged an error, and the swap notices only that the profile stays enabled. So does a
     * request GL's eSIM manager ignores.
     */
    private fun disable(request: JSONObject): Output {
        if (esimRequestsIgnored > 0) {
            esimRequestsIgnored--
        } else if (slot2 == Slot2.ESIM && request.getString("iccid") == esimProfile) {
            change("eSIM profile")
            esimProfile = null
            esimReloadsUntil = clock + esimReloadMillis
        }
        return ok("""{"tx_id":"","code":200,"message":"ok"}""")
    }

    private fun setFeatureConfig(request: JSONObject): Output {
        check(request.getString("location_id") == LOCATION_ID && request.getString("bus") == "cpu")
        val failover = request.getJSONObject("data").getJSONObject("failover")
        if (request.has("slot")) {
            val type = failover.getJSONArray("slot_type").getJSONObject(1).getInt("type")
            change("slot 2")
            slot2 = Slot2.entries.first { it.type == type }
        } else {
            check(failover.getBoolean("esim2_enable") == (slot2 == Slot2.ESIM))
            change("active slot")
            activeSlot = failover.getInt("current_sim")
        }
        return ok("{\n\t\"ok\": true,\n\t\"changed\": true\n}\n")
    }

    private fun uciGet(key: String): Output = when (key) {
        "glconfig.general.airplane_mode" -> ok(if (airplaneMode) "1\n" else "0\n")
        "gl_timer.reboot.enable" -> ok(if (scheduledReboot) "1\n" else "0\n")
        "network.modem_cpu.dds_id" -> ok("$activeSlot\n")
        "system.@system[0].log_file" -> logFile?.let { ok("$it\n") } ?: Output(1, ByteArray(0), "")
        "network.modem_cpu.iccid" -> modemCpuIccid?.let { ok("$it\n") } ?: Output(1, ByteArray(0), "")
        "route_policy.@default[0].enabled" -> ok(if (killSwitchSet) "0\n" else "1\n")
        "network.vpn_to_main" -> if (killSwitchSet) Output(1, ByteArray(0), "") else ok("rule\n")
        "network.wgclient1" -> ok("interface\n")
        // Only for ignoresATunnelThatIsNotWireGuard: with the interface present, only the WireGuard check can reject
        // that tunnel.
        "network.ovpnclient1" -> ok("interface\n")
        else -> Output(1, ByteArray(0), "")
    }

    /**
     * As our Mudi 7 showed it with one WireGuard tunnel, shortened. GL's process policies name the tunnel's interface
     * too; they stay here without a tunnel or with another interface, so that seesARouterWithoutVpn,
     * ignoresATunnelWithoutItsInterface and step1ChangesNothingWithoutAVpn fail if the app counts them as a tunnel.
     * As on our router, they have no `via_type`, so the app skips them both for their section type and as not
     * WireGuard; those tests fail only if both checks are gone.
     */
    private fun routePolicy() = buildString {
        appendLine("route_policy.global=policy")
        appendLine("route_policy.@default[0]=default")
        appendLine("route_policy.@default[0].via='novpn'")
        appendLine("route_policy.@default[0].enabled='${if (killSwitchSet) 0 else 1}'")
        vpnTunnel?.let {
            appendLine("route_policy.@rule[0]=rule")
            appendLine("route_policy.@rule[0].name='Tunnel 1'")
            appendLine("route_policy.@rule[0].via_type='$vpnType'")
            appendLine("route_policy.@rule[0].via='$it'")
        }
        appendLine("route_policy.gl_process=rule_process")
        appendLine("route_policy.gl_process.via='wgclient1'")
    }

    /** The rule at priority 9000 as our Mudi 7 printed it, among Linux's default rules. */
    private fun ipRules() = "0:\tfrom all lookup local\n" +
        (if (killSwitchInUse) "" else "9000:\tnot from all fwmark 0/0xf000 lookup main\n") +
        "32766:\tfrom all lookup main\n32767:\tfrom all lookup default\n"

    /** The MD5 sum of the first [count] bytes of [bytes], or of all of them if there are fewer. */
    private fun md5(bytes: ByteArray, count: Int) =
        MessageDigest.getInstance("MD5").digest(bytes.take(count).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun change(what: String) {
        changes += "$what at ${uptime.toInt()} s"
        if (!airplaneMode || radio != 4) unsafe += what
    }

    private fun atOk(line: String? = null) = ok("\n\r\n" + (line?.let { "$it\r\n\r\n" } ?: "") + "OK\r\n\n")

    private fun ok(text: String) = Output(0, text.toByteArray(), "")

    companion object {
        const val LOCK = "exec 9>/var/lock/churn.lock && flock 9 && "
        const val LOCATION_ID = "bcpu_p0133_v2c7c"
        const val SIM_MAP = "/etc/config/cellular/sim_map.json"
        const val APN_HISTORY = "/etc/config/cellular/history_apn_database.json"
        const val ESIM_DB = "/root/esim/db"
        const val ESIM_LOG = "/root/esim/log.txt"

        /** Sunday, October 11, 2026, 11:17:08 UTC: the router's clock at [clock] 0. */
        private const val START_TIME = 1_791_717_428L

        /** The time at the start of a line of GL's eSIM manager's log, such as `Sun Oct 11 11:17:08 2026`. */
        private val ESIM_TIME = DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ROOT)
        private val ESIM_LOG_READ = Regex(
            """date \+%s; s=.*? count=(\d+) .*? o=(\d+); .*? = '([0-9a-f]*)' ] .* n=(\d+); .* tail -c .n """ + ESIM_LOG,
        )
        private const val KILL_SWITCH_ON = "uci set 'route_policy.@default[0].enabled=0' && " +
            "{ uci -q delete 'network.vpn_to_main' || true; } && uci commit route_policy && uci commit network && " +
            "/etc/init.d/network reload"
        private val MODEM = listOf("gl_modem", "get_result_AT", "set_feature_config", "curl")
        private const val FIREWALL_LOADED = "nft list chain inet fw4 churn_sim >/dev/null && " +
            "nft list chain inet fw4 churn_route >/dev/null && nft list chain inet fw4 churn_dns >/dev/null"

        /** The answer recorded on our Mudi 7, shortened. */
        val FEATURE_CONFIG = """
            {
            	"s1_bcpu_p0133_v2c7c": {
            		"network_mode": "AUTO"
            	},
            	"s2_bcpu_p0133_v2c7c": {
            		"network_mode": "AUTO"
            	},
            	"bcpu_p0133_v2c7c": {
            		"failover": {
            			"slot_type": [
            				{
            					"type": 0,
            					"slot": 1
            				},
            				{
            					"type": 0,
            					"slot": 2
            				}
            			],
            			"esim2_enable": false,
            			"current_sim": 1
            		}
            	}
            }
        """.trimIndent()
    }
}
