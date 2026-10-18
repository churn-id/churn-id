package id.churn

import org.json.JSONArray
import org.json.JSONObject

/** Runs commands with the router's shell. */
fun interface Shell {
    /** Runs [command] with [input] as its standard input and waits up to [timeoutSeconds] for it to end. */
    fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output
}

/** What a command printed, and its exit status (null if it reported none). */
class Output(val status: Int?, val stdout: ByteArray, val stderr: String) {
    val text get() = stdout.toString(Charsets.UTF_8)
}

/** Why a step stopped. Nothing it did after the last confirmed answer is assumed done. */
enum class Problem {
    UNEXPECTED_ANSWER, RADIO_ON, SCHEDULED_REBOOT, IMEIS_NOT_KEPT, SLOT_NOT_SWITCHED, PROFILE_STILL_ENABLED,
    NO_CARD, NOT_SET_UP, NOT_STARTED, KILL_SWITCH_OFF, NO_VPN, FIREWALL_NOT_LOADED,
}

/** [detail] is what the router said, for the log; it never holds the secret or a password. */
class RouterException(val problem: Problem, val detail: String? = null) :
    Exception(if (detail == null) problem.name else "${problem.name}: $detail")

/** The modem's answer to an AT command: the lines before the final `OK`. */
class AtAnswer(val lines: List<String>) {

    /** The rest of the first line that starts with [prefix], such as `+CFUN:`, trimmed. */
    fun after(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)?.trim()

    companion object {
        /** Null unless [output] ends with `OK`, as every accepted answer must. */
        fun parse(output: String): AtAnswer? {
            val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return if (lines.lastOrNull() == "OK") AtAnswer(lines.dropLast(1)) else null
        }
    }
}

/** A setting that slot 2 can take: its card is in the SIM 2 tray or the built-in eSIM. */
enum class Slot2(val type: Int) { SIM2(0), ESIM(1) }

/** Churn's files on the router; null where a file is missing. */
class ChurnFolder(val factory: ImeiPair?, val secret: ByteArray?) {
    val complete get() = factory != null && secret != null
}

/**
 * The router operations Churn needs, with the commands of development.md. Every answer is checked; anything
 * unexpected throws [RouterException].
 */
class Router(private val shell: Shell) {

    fun airplaneMode() = uci("glconfig.general.airplane_mode") == "1"

    /** Turns airplane mode [on] or off, also for later power-ons, and reads the setting back. */
    fun setAirplaneMode(on: Boolean) = uciSet("glconfig.general.airplane_mode", if (on) "1" else "0")

    /** The modem's radio state, `+CFUN:`: 1 on, 4 off. */
    fun radio() = at("AT+CFUN?").after("+CFUN:")?.toIntOrNull() ?: throw unexpected("AT+CFUN?")

    fun imeis() = ImeiPair(readImei(item = 7), readImei(item = 11))

    /** Writes both IMEIs, saves them to the modem's flash and reads them back. */
    fun writeImeis(pair: ImeiPair) {
        require(Imei.isValid(pair.imei1) && Imei.isValid(pair.imei2))
        at("AT+EGMR=1,7,\"${pair.imei1}\"")
        at("AT+EGMR=1,11,\"${pair.imei2}\"")
        // Without it, a power-off soon after the write brings the earlier IMEIs back.
        at("AT+QPRTPARA=1")
        if (imeis() != pair) throw RouterException(Problem.IMEIS_NOT_KEPT)
    }

    fun slot2(): Slot2 {
        val type = at("AT+QSIMTYPE=\"slot2\"").after("+QSIMTYPE:")?.substringAfter(',')?.toIntOrNull()
        return Slot2.entries.firstOrNull { it.type == type } ?: throw unexpected("AT+QSIMTYPE")
    }

    fun setSlot2(slot2: Slot2) = setFeatureConfig(
        """{"location_id":"${locationId()}","bus":"cpu","slot":2,"data":{"failover":{"slot_type":""" +
            """[{"type":0,"slot":1},{"type":${slot2.type},"slot":2}]}}}""",
    )

    /** The active SIM slot, as GL reports it. */
    fun activeSlot() = uci("network.modem_cpu.dds_id")

    /** Makes slot 2 the active slot. [slot2] must be slot 2's current setting. */
    fun activateSlot2(slot2: Slot2) = setFeatureConfig(
        """{"location_id":"${locationId()}","bus":"cpu","data":{"failover":""" +
            """{"current_sim":2,"esim2_enable":${slot2 == Slot2.ESIM}}}}""",
    )

    /** Slot 2's ICCID as the modem gives it, with a trailing `F` for 19 digits; null if the card doesn't answer. */
    fun slot2Iccid() = slot2Answer("AT+ICCID")?.after("+ICCID:")?.takeIf { it.matches(Regex("[0-9A-Fa-f]{18,20}")) }

    /** Slot 2's IMSI; null if the card doesn't answer. */
    fun slot2Imsi() = slot2Answer("AT+CIMI")?.lines?.firstOrNull { it.matches(Regex("[0-9]{6,15}")) }

    /** Asks GL's eSIM manager to disable the profile with [iccid], given as [slot2Iccid] returns it. */
    fun disableProfile(iccid: String): String {
        require(iccid.matches(Regex("[0-9A-Fa-f]{18,20}")))
        val request = """{"txId":"1","method":"disable","iccid":"$iccid"}"""
        return run(
            locked("curl -sS -m 60 -H 'Content-Type: application/json' -d ${quote(request)} $ESIM_API"),
            timeoutSeconds = 70,
        ).text.trim()
    }

    fun uptimeSeconds() = run("cat /proc/uptime").text.substringBefore(' ').toDoubleOrNull()
        ?: throw unexpected("/proc/uptime")

    fun scheduledReboot() = uci("gl_timer.reboot.enable") == "1"

    /**
     * Whether GL's VPN client has a WireGuard tunnel set up, as development.md describes it: a rule in GL's route
     * policy that sends traffic through an existing WireGuard interface. Other tunnel types are untested, so they
     * don't count. Whether the tunnel is on or up doesn't count either.
     */
    fun vpnSetUp(): Boolean {
        val settings = run("uci -q show route_policy").text.lines()
            .associate { it.substringBefore('=') to it.substringAfter('=').removeSurrounding("'") }
        return settings.filterValues { it == "rule" }.keys
            .filter { settings["$it.via_type"] == "wireguard" }
            .mapNotNull { settings["$it.via"]?.takeIf { via -> via.matches(Regex("[A-Za-z0-9_]+")) } }
            .any { uci("network.$it") == "interface" }
    }

    /**
     * Whether GL's Enhanced Kill Switch is on, as development.md describes it: in GL's settings, and in the routing
     * rules in use, which follow the settings only at a network reload.
     */
    fun enhancedKillSwitch(): Boolean {
        val rules = run("ip rule show")
        rules.check("ip rule")
        // network.vpn_to_main is the rule at priority 9000.
        val ruleInUse = rules.text.lines().any { it.startsWith("9000:") }
        return uci(DEFAULT_POLICY) == "0" && uci(VPN_TO_MAIN) == null && !ruleInUse
    }

    /** Turns GL's Enhanced Kill Switch on, also for later power-ons, and checks that it is on. */
    fun turnEnhancedKillSwitchOn() {
        // Deleting the rule fails if it is already gone, which is fine: only the check afterwards counts.
        run(
            "uci set ${quote("$DEFAULT_POLICY=0")} && { uci -q delete ${quote(VPN_TO_MAIN)} || true; } && " +
                "uci commit route_policy && uci commit network && /etc/init.d/network reload",
            timeoutSeconds = 60,
        ).check("Enhanced Kill Switch")
        if (!enhancedKillSwitch()) throw RouterException(Problem.KILL_SWITCH_OFF)
    }

    /** Whether Churn's firewall rules are in place: the file as the app would write it now, and its chains loaded. */
    fun firewallInPlace() =
        readFile(FIREWALL).contentEquals(firewallRules(dnsmasqUid()).toByteArray()) && firewallLoaded()

    /**
     * Writes Churn's firewall rules, keeps them across firmware upgrades and has GL's firewall load them. Rules that
     * don't load are removed again, since at the next power-on GL's firewall might then load none of its own either.
     */
    fun installFirewall() {
        val rules = firewallRules(dnsmasqUid()).toByteArray()
        run("umask 022 && cat > $FIREWALL.new && mv $FIREWALL.new $FIREWALL", rules).check(FIREWALL)
        keep(FIREWALL)
        // fw4 warns about GL's own options at every reload, so only its exit status counts.
        val reload = run("fw4 reload", timeoutSeconds = 60)
        if (reload.status != 0 || !firewallLoaded()) {
            run("rm -f $FIREWALL && fw4 reload", timeoutSeconds = 60)
            throw RouterException(Problem.FIREWALL_NOT_LOADED, (reload.text + " " + reload.stderr).trim().take(300))
        }
        if (!firewallInPlace()) throw unexpected(FIREWALL)
    }

    /** The ICCID GL recorded for the active card, as GL stores it, or null if it recorded none. */
    fun currentCardIccid() = uci(MODEM_ICCID)?.takeIf { it.matches(Regex("[0-9A-Fa-f]{18,20}")) }

    /** Whether GL's system log to the flash is off, so no new SIM details are written to the flash. */
    fun flashLogOff() = uci(LOG_FILE) == null

    /**
     * Removes the record GL keeps of past SIMs on the flash: it turns GL's system log to the flash off (saving the
     * original setting first, so [restoreFactoryImeis] can put it back), deletes the log files, keeps only the card
     * with [keepIccid] in GL's card files and the eSIM manager's records, empties the eSIM log, and overwrites the
     * free space so that deleted copies can't be read from the raw partition. [keepIccid] is a card's ICCID as the
     * modem gives it, or null to keep none; GL records the current card again when it next goes online.
     * development.md, "Clearing the SIM history", explains what and why.
     */
    fun clearHistory(keepIccid: String?) {
        val keep = keepIccid?.let(::iccidKey)
        turnFlashLogOff()
        run("rm -f $SYS_LOG $SYS_LOG.old").check("delete the system log")
        pruneSimMap("$CELLULAR/sim_map.json", keep)
        pruneApnHistory("$CELLULAR/history_apn_database.json", keep)
        pruneEsimRecords(keep)
        overwriteFreeSpace()
    }

    /** Turns GL's flash system log off and saves its original setting, unless it is already off. */
    private fun turnFlashLogOff() {
        val current = uci(LOG_FILE) ?: return
        // Saved once, so restore.sh can turn the log back on as it was.
        if (readFile("$CHURN/log_file") == null) writeChurnFile("log_file", (current + "\n").toByteArray())
        run("uci delete ${quote(LOG_FILE)} && uci commit system").check("turn the system log off")
        // Stops GL's logread from writing the file; an open file would keep its data on the flash after deletion.
        run("/etc/init.d/log reload").check("reload the log service")
    }

    /** Keeps only the object named by [keep] in GL's `sim_map.json`, which holds one object per ICCID. */
    private fun pruneSimMap(path: String, keep: String?) {
        val root = readJson(path) ?: return
        var removed = false
        for (key in root.keys().asSequence().toList()) {
            if (keep == null || iccidKey(key) != keep) {
                root.remove(key)
                removed = true
            }
        }
        if (removed) writeFile(path, pretty(root))
    }

    /** Keeps only the entry named by [keep] in the `apns` list of GL's `history_apn_database.json`. */
    private fun pruneApnHistory(path: String, keep: String?) {
        val root = readJson(path) ?: return
        val apns = root.optJSONArray("apns") ?: return
        val kept = JSONArray()
        for (i in 0 until apns.length()) {
            val entry = apns.optJSONObject(i) ?: continue
            if (keep != null && iccidKey(entry.optString("iccid")) == keep) kept.put(entry)
        }
        if (kept.length() != apns.length()) {
            root.put("apns", kept)
            writeFile(path, pretty(root))
        }
    }

    /** Deletes the eSIM manager's record files that don't name [keep], and empties its log. */
    private fun pruneEsimRecords(keep: String?) {
        // GL names each file by the chip's 32-digit id; other names are left alone rather than passed to the shell.
        val files = run("find $ESIM/db -maxdepth 1 -type f 2>/dev/null").text.lines()
            .filter { it.matches(Regex("$ESIM/db/[A-Za-z0-9._-]+")) }
        for (file in files) {
            val content = readFile(file)?.toString(Charsets.UTF_8) ?: continue
            if (keep == null || !content.contains(keep)) run("rm -f $file").check("remove an eSIM record")
        }
        if (readFile("$ESIM/log.txt") != null) run(": > $ESIM/log.txt").check("empty the eSIM log")
    }

    /**
     * Overwrites the overlay's free space with zeros, so that copies of deleted files left in it can't be read from
     * the raw partition. Writes in small parts, each flushed to the flash, so the partition is full only briefly.
     * On our Mudi 7 it took about 3 minutes; the timeout allows for a slower flash.
     */
    private fun overwriteFreeSpace() = run(OVERWRITE_FREE_SPACE, timeoutSeconds = 900).check("overwrite the free space")

    private fun readJson(path: String) =
        readFile(path)?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() }

    /** Writes [content] to [path], which only root can change. Used for GL's own files, not Churn's folder. */
    private fun writeFile(path: String, content: ByteArray) =
        run("umask 022 && cat > $path.new && mv $path.new $path", content).check(path)

    /** An ICCID without the trailing `F` the modem pads a 19-digit one with, for comparing the forms GL stores. */
    private fun iccidKey(iccid: String) = iccid.trim().trimEnd('F', 'f')

    private fun pretty(json: JSONObject) = (json.toString(2) + "\n").toByteArray()

    /** Starts the shutdown. The router may close the connection before it answers. */
    fun powerOff() {
        runCatching { shell.run("poweroff", input = null, timeoutSeconds = 15) }
    }

    fun readChurnFolder(): ChurnFolder {
        val factory = readFile("$CHURN/factory")?.toString(Charsets.US_ASCII)?.let { text ->
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.size != 2 || lines.any { !it.matches(Regex("[0-9]{15}")) }) {
                throw RouterException(Problem.NOT_SET_UP, "$CHURN/factory")
            }
            ImeiPair(lines[0], lines[1])
        }
        val secret = readFile("$CHURN/secret")?.also {
            if (it.size != 32) throw RouterException(Problem.NOT_SET_UP, "$CHURN/secret")
        }
        return ChurnFolder(factory, secret)
    }

    /** Writes [content] to [name] in Churn's folder, unless the file holds it already. */
    fun updateChurnFile(name: String, content: ByteArray, executable: Boolean = false) {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        if (!readFile("$CHURN/$name").contentEquals(content)) writeChurnFile(name, content, executable)
    }

    /** Writes [content] to [name] in Churn's folder, which only root can read. */
    fun writeChurnFile(name: String, content: ByteArray, executable: Boolean = false) {
        require(name.matches(Regex("[A-Za-z0-9._-]+")))
        val path = "$CHURN/$name"
        run(
            "umask 077 && mkdir -p $CHURN && chmod 700 $CHURN && cat > $path.new && " +
                "chmod ${if (executable) 700 else 600} $path.new && mv $path.new $path",
            content,
        ).check(path)
        if (!readFile(path).contentEquals(content)) throw unexpected(path)
    }

    /** Lists Churn's folder in `/etc/sysupgrade.conf`, so a firmware upgrade keeps it. */
    fun keepChurnFolder() = keep("$CHURN/")

    /** Lists [line], a path, in `/etc/sysupgrade.conf`, so a firmware upgrade that keeps settings keeps it. */
    private fun keep(line: String) {
        val file = SYSUPGRADE
        // A last line without a line break would otherwise run into Churn's.
        run(
            "grep -qxF $line $file || { [ ! -s $file ] || [ -z \"$(tail -c 1 $file)\" ] || echo >> $file; " +
                "echo $line >> $file; }",
        ).check(file)
        run("grep -qxF $line $file").check(file)
    }

    /** The user ID GL's main DNS server runs as. */
    private fun dnsmasqUid() = run("id -u dnsmasq").text.trim().toIntOrNull() ?: throw unexpected("id -u dnsmasq")

    private fun firewallLoaded() =
        run(FIREWALL_CHAINS.joinToString(" && ") { "nft list chain inet fw4 $it >/dev/null" }).status == 0

    /** Runs Churn's restore script; returns what it printed. */
    fun restoreFactoryImeis(): String {
        val output = run("sh $CHURN/restore.sh", timeoutSeconds = 120)
        if (output.status != 0) throw unexpected("restore.sh", output)
        return output.text.trim()
    }

    /** An AT command through GL's `gl_modem`, under Churn's lock. */
    fun at(command: String): AtAnswer {
        val output = run(locked("gl_modem -B cpu AT ${quote(command)} </dev/null"))
        return AtAnswer.parse(output.text) ?: throw unexpected(command, output)
    }

    /** An AT command to slot 2's card, sent the way GL's eSIM manager sends it. */
    private fun atSlot2(command: String): AtAnswer {
        require(command.matches(Regex("AT\\+[A-Z]+")))
        val request = """{"cmd":"$command","sub_id":2,"source_flag":0,"timeout":30}"""
        val output = run(locked("ubus -t 35 call modem.CPU.AT get_result_AT ${quote(request)}"), timeoutSeconds = 45)
        val data = runCatching { JSONObject(output.text).getString("data") }.getOrNull()
        return data?.let { AtAnswer.parse(it) } ?: throw unexpected(command, output)
    }

    /** Null if slot 2's card doesn't answer [command]. A broken connection still throws. */
    private fun slot2Answer(command: String) = try {
        atSlot2(command)
    } catch (_: RouterException) {
        null
    }

    /** The modem's `location_id`: the key of `get_feature_config` whose entry holds `failover`. */
    private fun locationId(): String {
        val output = run("ubus -t 30 call cellular.modem get_feature_config '{}'", timeoutSeconds = 40)
        val config = runCatching { JSONObject(output.text) }.getOrNull()
        return config?.keys()?.asSequence()
            ?.firstOrNull { config.optJSONObject(it)?.has("failover") == true }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
            ?: throw unexpected("get_feature_config", output)
    }

    private fun setFeatureConfig(data: String) {
        val command = "ubus -t 30 call cellular.modem set_feature_config ${quote(data)}"
        val output = run(locked(command), timeoutSeconds = 40)
        val ok = runCatching { JSONObject(output.text).getBoolean("ok") }.getOrDefault(false)
        if (!ok) throw unexpected("set_feature_config", output)
    }

    private fun uci(key: String): String? {
        val output = run("uci -q get ${quote(key)}")
        return if (output.status == 0) output.text.trim() else null
    }

    private fun uciSet(key: String, value: String) {
        val config = key.substringBefore('.')
        run("uci set ${quote("$key=$value")} && uci commit ${quote(config)}").check(key)
        if (uci(key) != value) throw unexpected(key)
    }

    private fun readFile(path: String): ByteArray? {
        val output = run("if [ -e $path ]; then cat $path; else exit 3; fi")
        return when (output.status) {
            0 -> output.stdout
            3 -> null
            // Only the error output: the file may be the secret.
            else -> throw RouterException(Problem.UNEXPECTED_ANSWER, "$path: ${output.stderr.trim().take(300)}")
        }
    }

    private fun run(command: String, input: ByteArray? = null, timeoutSeconds: Long = 30) =
        shell.run(command, input, timeoutSeconds)

    private fun Output.check(what: String) {
        if (status != 0) throw unexpected(what, this)
    }

    private fun readImei(item: Int): String {
        val command = "AT+EGMR=0,$item"
        return at(command).after("+EGMR:")?.removeSurrounding("\"")?.takeIf { it.matches(Regex("[0-9]{15}")) }
            ?: throw unexpected(command)
    }

    private fun unexpected(what: String, output: Output? = null) = RouterException(
        Problem.UNEXPECTED_ANSWER,
        if (output == null) what else "$what: ${(output.text + " " + output.stderr).trim().take(300)}",
    )

    companion object {
        /** The ICCID slot 2 shows while it is on the built-in eSIM with no profile enabled. */
        const val PLACEHOLDER_ICCID = "89000123456789012341"

        /** Churn's folder on the router. */
        const val CHURN = "/etc/churn"

        /** Churn's firewall rules. GL's firewall, fw4, loads the files in this folder into its own table. */
        const val FIREWALL = "/etc/nftables.d/churn.nft"
        private val FIREWALL_CHAINS = listOf("churn_sim", "churn_route", "churn_dns")

        private const val DEFAULT_POLICY = "route_policy.@default[0].enabled"
        private const val VPN_TO_MAIN = "network.vpn_to_main"
        private const val LOCK = "/var/lock/churn.lock"
        private const val SYSUPGRADE = "/etc/sysupgrade.conf"
        private const val ESIM_API = "http://127.0.0.1:3456/sdk/v1"

        /** GL's uci setting that names the flash file its system log is written to. */
        private const val LOG_FILE = "system.@system[0].log_file"

        /** GL's uci value for the ICCID of the active card. */
        private const val MODEM_ICCID = "network.modem_cpu.iccid"

        private const val SYS_LOG = "/etc/sys_log"
        private const val CELLULAR = "/etc/config/cellular"
        private const val ESIM = "/root/esim"

        /**
         * Fills the overlay's free space with zeros, then frees it again. ext4 keeps back free space that even root
         * can't fill, 16 MB on the Mudi 7, and copies of deleted files would stay in it, so the fill sets that reserve
         * to 0 and puts it back at the end, or when the run is stopped; a reboot also puts it back.
         *
         * 64 MB parts come first, each flushed to the flash before the next, so the partition is full only briefly. It
         * is 2.9 GB, so `dd` stops at a full partition after at most 47 parts. Files of 4 KB then take the single free
         * blocks between small files that the parts leave, where old versions of GL's config files are. Their
         * directories are made beforehand with at most 300 names each, so that they never need a new block on the
         * full partition. Fill files left by an earlier run that stopped are deleted first.
         */
        private val OVERWRITE_FREE_SPACE = """
            dev=${'$'}(awk '${'$'}2 == "/overlay" { print ${'$'}1; exit }' /proc/mounts)
            reserve=/sys/fs/ext4/${'$'}{dev##*/}/reserved_clusters
            size=${'$'}(cat ${'$'}reserve)
            rm -rf /overlay/churn-fill.*
            d=0
            while [ "${'$'}d" -lt 16 ]; do mkdir /overlay/churn-fill.d${'$'}d; d=${'$'}((d + 1)); done
            sync
            trap 'rm -rf /overlay/churn-fill.*; sync; echo ${'$'}size > ${'$'}reserve; exit 1' HUP INT TERM PIPE
            echo 0 > ${'$'}reserve
            i=0
            while [ "${'$'}i" -lt 256 ] && dd if=/dev/zero of=/overlay/churn-fill.${'$'}i bs=1M count=64 2>/dev/null; do
            	sync
            	i=${'$'}((i + 1))
            done
            d=0
            while [ "${'$'}d" -lt 16 ]; do
            	f=/overlay/churn-fill.d${'$'}d
            	i=0
            	while [ "${'$'}i" -lt 300 ] && dd if=/dev/zero of=${'$'}f/${'$'}i bs=4k count=1 2>/dev/null; do
            		i=${'$'}((i + 1))
            	done
            	[ "${'$'}i" -lt 300 ] && break
            	d=${'$'}((d + 1))
            done
            rm -rf /overlay/churn-fill.*
            sync
            echo ${'$'}size > ${'$'}reserve
        """.trimIndent()

        /**
         * Churn's firewall rules, for GL's main DNS server running as user [dnsmasqUid]. development.md, "Churn's
         * firewall rules", explains them.
         */
        fun firewallRules(dnsmasqUid: Int) = """
            # Churn's firewall rules: https://github.com/churn-id/churn-id
            # The Churn app writes this file, and /etc/churn/restore.sh removes it.
            # GL's firewall, fw4, loads it into its table inet fw4.

            # Over the SIM, only the router's WireGuard packets go out, which
            # carry the mark 0x8000. Postrouting sees the interface a packet
            # really leaves on, also after churn_route has re-routed it.
            chain churn_sim {
            	type filter hook postrouting priority 0; policy accept;
            	oifname "rmnet_data*" fib saddr type local meta mark 0x8000 meta l4proto udp counter accept
            	oifname "rmnet_data*" counter drop
            }

            # What the router itself starts toward the SIM gets GL's tunnel
            # mark, so it goes through the tunnel, or nowhere while it is down.
            chain churn_route {
            	type route hook output priority mangle; policy accept;
            	oifname "rmnet_data*" ct direction original meta mark & 0x0000f000 == 0x00000000 counter meta mark set meta mark & 0xffff1fff | 0x00001000
            }

            # GL's main DNS server asks the VPN's DNS server, through GL's DNS
            # server for the tunnel on port 2153.
            chain churn_dns {
            	type nat hook output priority -100; policy accept;
            	meta skuid $dnsmasqUid meta mark & 0x0000f000 == 0x00001000 meta l4proto { tcp, udp } th dport 53 counter redirect to :2153
            }
        """.trimIndent() + "\n"

        /** [s] as one single-quoted shell word. */
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** Runs [command] while holding Churn's lock, which keeps Churn's own modem operations apart. */
        private fun locked(command: String) = "exec 9>$LOCK && flock 9 && $command"
    }
}
