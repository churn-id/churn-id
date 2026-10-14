package id.churn

import org.json.JSONObject

/**
 * A Mudi 7 as development.md describes it, answering the commands [Router] sends. It notes every change made
 * while the radio was on, and every modem command sent without Churn's lock.
 */
class FakeRouter : Shell {

    class Card(val iccid: String, val imsi: String)

    /** Milliseconds, advanced by the tests' sleep. */
    var clock = 0L
    private var bootedAt = -600_000L
    val uptime get() = (clock - bootedAt) / 1000.0

    var airplaneMode = false

    /** For the radio that never turns off. */
    var radioStuckOn = false

    /** For a radio that comes on again when the modem gets this AT command. */
    var radioOnAfter: String? = null

    /** For a `/proc/uptime` that doesn't parse. */
    var uptimeBroken = false
    var imei1 = "356090215000002"
    var imei2 = "356090215000077"

    /** The IMEIs as last saved to flash, while a later write isn't saved yet. Power-on brings them back. */
    private var savedImeis: ImeiPair? = null
    var slot2 = Slot2.SIM2
    var activeSlot = 2

    /** The enabled profile's ICCID as `AT+ICCID` gives it, or null for none. */
    var esimProfile: String? = "8944000000000000123F"
    private var esimReloadsUntil = 0L
    var trayCard: Card? = Card("8941000000000000015F", "228010000000001")
    var scheduledReboot = false
    var poweredOff = false
    val files = mutableMapOf<String, ByteArray>()
    var sysupgrade = "/etc/config/\n/etc/profile"

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

    private val modemAnswers get() = uptime >= 30

    /** Power-on with the settings kept. */
    fun boot() {
        savedImeis?.let {
            imei1 = it.imei1
            imei2 = it.imei2
        }
        savedImeis = null
        bootedAt = clock
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
            return slot2At(JSONObject(it.groupValues[1]).getString("cmd"))
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
        if (command == "cat /proc/uptime") return ok(if (uptimeBroken) "" else "%.2f 100.00\n".format(uptime))
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
        if (command.startsWith("grep -qxF /etc/churn/ /etc/sysupgrade.conf")) {
            val listed = "/etc/churn/" in sysupgrade.lines()
            if (command.contains("||") && !listed) {
                sysupgrade += (if (sysupgrade.endsWith("\n")) "" else "\n") + "/etc/churn/\n"
                return ok("")
            }
            return Output(if (listed) 0 else 1, ByteArray(0), "")
        }
        error("Unexpected command: $command")
    }

    private fun at(command: String): Output {
        if (!modemAnswers) return Output(1, ByteArray(0), "modem not ready")
        if (command == radioOnAfter) radioStuckOn = true
        val imeiWrite = Regex("""AT\+EGMR=1,(7|11),"(\d{15})"""").matchEntire(command)
        return when {
            command == "AT+CFUN?" -> atOk("+CFUN: $radio")
            command == "AT+EGMR=0,7" -> atOk("+EGMR: \"$imei1\"")
            command == "AT+EGMR=0,11" -> atOk("+EGMR: \"$imei2\"")
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

    private fun slot2At(command: String): Output {
        val card = when {
            !modemAnswers -> null
            slot2 == Slot2.SIM2 -> trayCard
            clock < esimReloadsUntil -> null
            else -> esimProfile?.let { Card(it, "260010000000001") }
                ?: Card(Router.PLACEHOLDER_ICCID, "001010123456789")
        }
        val data = when {
            card == null -> "\r\n+CME ERROR: 10\r\n"
            command == "AT+ICCID" -> "\r\n+ICCID: ${card.iccid}\r\n\r\nOK\r\n"
            command == "AT+CIMI" -> "\r\n${card.imsi}\r\n\r\nOK\r\n"
            else -> "\r\nERROR\r\n"
        }
        return ok(JSONObject().put("data", data).put("channel_status", true).toString(1))
    }

    private fun disable(request: JSONObject): Output {
        if (slot2 == Slot2.ESIM && request.getString("iccid") == esimProfile) {
            change("eSIM profile")
            esimProfile = null
            esimReloadsUntil = clock + 5_000
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
        "route_policy.@default[0].enabled" -> ok("0\n")
        else -> Output(1, ByteArray(0), "")
    }

    private fun change(what: String) {
        changes += "$what at ${uptime.toInt()} s"
        if (!airplaneMode || radio != 4) unsafe += what
    }

    private fun atOk(line: String? = null) = ok("\n\r\n" + (line?.let { "$it\r\n\r\n" } ?: "") + "OK\r\n\n")

    private fun ok(text: String) = Output(0, text.toByteArray(), "")

    companion object {
        const val LOCK = "exec 9>/var/lock/churn.lock && flock 9 && "
        const val LOCATION_ID = "bcpu_p0133_v2c7c"
        private val MODEM = listOf("gl_modem", "get_result_AT", "set_feature_config", "curl")

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
