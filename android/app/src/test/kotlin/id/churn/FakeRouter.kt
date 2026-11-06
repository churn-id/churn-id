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

    /** GL's firmware version, the OpenWrt release it is built on, and the modem's firmware revision. */
    var glVersion = "4.10.0"
    var openwrtRelease = "23.05.4"
    var modemRevision = "RG650VEU00ADR02A04G8G"

    /** For the modem repeating each AT command before its answer, as for a while after power-on (verified). */
    var modemEchoes = false

    /** Commands the firmware doesn't have. Our Mudi 7 has every one Churn runs. */
    val absentCommands = mutableSetOf<String>()

    /**
     * Answers to AT commands that replace the modem's own, such as another modem firmware might give, each without its
     * final `OK`. Only the router checks' tests use them.
     */
    val atAnswers = mutableMapOf<String, String>()

    /**
     * uci settings that replace the router's own, or remove them for null, such as another GL firmware might hold.
     * Only the router checks' tests use them.
     */
    val uciAnswers = mutableMapOf<String, String?>()

    /**
     * Answers to commands that replace the router's own, such as another GL firmware might give. Only the router
     * checks' tests use them.
     */
    val answers = mutableMapOf<String, String>()

    /** Commands that fail, such as on a firmware without what they read. Only the router checks' tests use them. */
    val failing = mutableSetOf<String>()

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

    /**
     * For an `AT+QPRTPARA=1` that doesn't save the written IMEIs, against M7 in testing.md. Only the power cycle test's
     * tests use it.
     */
    var imeiSaveIgnored = false
    var imei1 = "356090215000002"
    var imei2 = "356090215000077"

    /** The IMEIs as last saved to flash, while a later write isn't saved yet. Power-on brings them back. */
    private var savedImeis: ImeiPair? = null
    var slot2 = Slot2.SIM2
    var activeSlot = 2

    /** GL's uci setting naming the flash file its system log goes to; null once the app turns the log off. */
    var logFile: String? = "/etc/sys_log"

    /** For a log service that doesn't start the `logread` that writes [logFile]. */
    var logreadMissing = false

    /** For a `logread` that goes on writing `/etc/sys_log` once the setting is deleted and the log service reloaded. */
    var logreadStuck = false

    /**
     * For a `logread` that runs but doesn't open the file [logFile] names, against H3. Only the removal test's tests
     * use it.
     */
    var logreadOpensNoFile = false

    /** The ICCID GL recorded for the active card (`network.modem_cpu.iccid`), as GL stores it, or null for none. */
    var modemCpuIccid: String? = "8941000000000000015F"

    /** Set once the app overwrites the overlay's free space. */
    var freeSpaceOverwritten = false

    /**
     * For an overwrite that leaves ext4's reserve at 0, against H13 in testing.md. Only the removal test's tests use
     * it.
     */
    var overwriteLeavesReserveOff = false

    /**
     * For a GL that saves `sim_map.json` while the overwrite has filled the partition and leaves it cut short, against
     * H14. Only the removal test's tests use it.
     */
    var overwriteCutsSimMap = false

    /**
     * What files held before they were deleted or replaced, which stays on the overlay's raw partition until the
     * free-space overwrite fills the free space.
     */
    val freeSpace = mutableListOf<ByteArray>()

    /** The numbers the app last counted on the raw partition, which it sends as input. */
    var numbersCounted = emptyList<String>()

    /**
     * For a read of the raw partition that fails without an error and finds no number, against H10 and H11. Only the
     * removal test's tests use it.
     */
    var rawReadFindsNothing = false

    /**
     * GL's system log in RAM since the router started, which `logread` writes to the file [logFile] names when the log
     * service starts it: lines naming the card in the tray and an earlier card, by ICCID and IMSI, and the modem's
     * IMEIs.
     */
    var logBuffer = "user.info Diag_Lib[1]: iccid[8941000000000000015F] imsi[228010000000001]\n" +
        "user.info Diag_Lib[1]: iccid[8941000000000000023F] imsi[228010000000002]\n" +
        "user.info gl_modem[1]: imei[356090215000002] imei2[356090215000077]\n"

    /** The kB written to the overlay's partition since its file system was made, as ext4 counts them. */
    var lifetimeWrittenKb = 41_943_040L

    /** How fast the overwrite writes to the flash, in kB/s: on our Mudi 7, 2.9 GB took about 3 minutes. */
    var flashWriteKbPerSecond = 16_000L

    /** The free space the overwrite leaves, in kB: 512 MB, but for an overwrite broken against H15. */
    var overwriteKeepsFreeKb = 524_288L

    /** For an overwrite that leaves fill files, against H15. Only the removal test's tests use it. */
    var overwriteLeavesFillFiles = false

    /** The overlay's device and file system, or null for none, and the blocks that ext4 keeps back from it. */
    var overlayDevice = "/dev/mmcblk0p59"
    var overlayType: String? = "ext4"
    var reservedClusters = 4096L

    /** The files in `/etc` written in the last 2 minutes, as our Mudi 7 listed them with the SIM online. */
    var etcWritten = listOf(
        "/etc/config/cellular/slot_map.json",
        "/etc/gl_modem_traffic/cpu/total_5g",
        "/etc/gl_modem_traffic/cpu/total_sim2",
    )

    /** The flash's `life_time` and `pre_eol_info`, as our Mudi 7 showed them. */
    var flashLifeTime = "0x01 0x01"
    var flashPreEol = "0x01"

    /** The enabled profile's ICCID as `AT+ICCID` gives it, or null for none. */
    var esimProfile: String? = "8944000000000000123F"

    /** How long the chip doesn't answer after a disable, while the modem reloads it; GL's eSIM manager waits 5 s. */
    var esimReloadMillis = 5_000L
    private var esimReloadsUntil = 0L

    /** For GL's eSIM manager leaving the profile enabled at this many requests, as it did once at our step 1. */
    var esimRequestsIgnored = 0
    var trayCard: Card? = Card("8941000000000000015F", "228010000000001")

    /**
     * For a modem that lets the built-in eSIM answer in slot 2 with slot 2 on the SIM 2 tray, against E4 in
     * testing.md. Only the dry run's test uses it.
     */
    var esimAnswersOnTray = false

    /**
     * For a shell that stops reading restore.sh, without an error, once the script deleted Churn's folder and itself,
     * against F12 in testing.md. Only the removal test's test uses it.
     */
    var restoreEndsEarly = false

    /** For a restore.sh that leaves the IMEIs as they are, against F7. Only the removal test's tests use it. */
    var restoreKeepsImeis = false

    /** For a restore.sh that leaves GL's system log off, against F9. Only the removal test's tests use it. */
    var restoreKeepsLogOff = false

    /** For a restore.sh that leaves the zero files, against F10. Only the removal test's tests use it. */
    var restoreKeepsZeroFiles = false

    /** For a restore.sh that leaves the fill files, against F10. Only the removal test's tests use it. */
    var restoreKeepsFillFiles = false

    /**
     * For zero files that something holds open as restore.sh deletes them, so that their space doesn't come free,
     * against F10. Only a broken firmware gets here, and only the removal test's tests use it.
     */
    var zeroFilesHeldOpen = false

    /**
     * For a restore.sh that deletes Churn's firewall rules but leaves their chains, as a reload of GL's firewall does,
     * against F11. Only the removal test's tests use it.
     */
    var restoreKeepsChains = false

    /** For a restore.sh that leaves Churn's firewall rules' file, against F11. Only the removal test's tests use it. */
    var restoreKeepsFirewallFile = false

    /**
     * For a restore.sh that leaves the line of Churn's firewall rules in /etc/sysupgrade.conf, against F11. Only the
     * removal test's tests use it.
     */
    var restoreKeepsFirewallLine = false

    /** For a restore.sh that leaves Churn's folder, against F8 and F12. Only the removal test's tests use it. */
    var restoreKeepsFolder = false

    /**
     * For a restore.sh that leaves the folder's line in /etc/sysupgrade.conf, against F12. Only the removal test's
     * tests use it.
     */
    var restoreKeepsFolderLine = false

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

    /** Whether the tunnel's interface is up, as while GL's VPN client has the tunnel connected. */
    var tunnelUp = true

    /** The mark the tunnel's WireGuard interface sends with, as `wg show` gives it. */
    var wireguardMark = "0x8000"

    /** The network device GL's cellular interface uses while it is up. */
    var simDevice = "rmnet_data0"

    /** The bridge the USB-C port's network device `ecm0` is in, or null for none. */
    var usbBridge: String? = "br-lan"
    var poweredOff = false

    /**
     * For a `poweroff` that doesn't shut the router down, against B4 in testing.md. Only the power cycle test's tests
     * use it.
     */
    var poweroffIgnored = false
    val files = mutableMapOf<String, ByteArray>()

    /** The modes that the app gave [files], as chmod takes them, such as 600. */
    val modes = mutableMapOf<String, String>()

    /** Churn's folder's mode, as `ls` shows it, and the user ID that owns the folder and its files. */
    var churnFolderMode = "drwx------"
    var churnOwner = 0

    /** Where `/etc/churn/zeros` links to, once the free-space overwrite made the link. */
    var zerosLink: String? = null

    /** The kB that Churn's zero files take, once the free-space overwrite made them; the overlay's free space less. */
    var zeroFilesKb = 0L

    /** The kB of the fill files in /overlay that a clearing that stopped left. */
    var fillFilesKb = 0L

    /** The kB of deleted files that something still holds open, so that their space isn't free yet. */
    private var heldKb = 0L

    /** The kB that GL's files took on the overlay since the fake started. */
    private var writtenKb = 0L

    /** The overlay's free space, in kB. */
    private val overlayFreeKb get() = OVERLAY_FREE_KB - zeroFilesKb - fillFilesKb - heldKb - writtenKb

    /** What `du` counts in Churn's folder, in kB, and whether `sysupgrade -l` follows the link to the zero files. */
    var churnFolderKb = 12L
    var upgradeFollowsLinks = false
    var sysupgrade = "/etc/config/\n/etc/profile"

    /** The user ID of GL's main DNS server, 453 on our Mudi 7. */
    var dnsmasqUid = 453

    /**
     * What listens on UDP ports 53 and 2153 as [Router.dnsServers] reads it: port, program, user ID and the file the
     * program takes its servers from, as on our Mudi 7 with a WireGuard tunnel.
     */
    val dnsServers = mutableListOf(
        "2153|dnsmasq|456|/tmp/resolv.conf.d/resolv.conf.wgclient1",
        "53|dnsmasq|453|/tmp/resolv.conf.d/resolv.conf.auto",
    )

    /**
     * The lines of GL's firewall rules that hold `redirect to :2153` or `lan_drop_leaked_dns`, as our Mudi 7 listed
     * them with a WireGuard tunnel, without their indent: the redirect of devices' lookups marked for the tunnel, the
     * drop of those sent past the router, and GL's redirects for its own processes.
     */
    val glDnsRules = mutableListOf(
        "udp dport 53 meta mark & 0x0000f000 != 0x00008000 counter packets 0 bytes 0 drop " +
            "comment \"!fw4: lan_drop_leaked_dns\"",
        "meta l4proto udp meta mark & 0x0000f000 == 0x00001000 fib daddr type local redirect to :2153",
        "tcp dport 53 meta mark & 0x0000f000 == 0x00001000 meta skuid 453 redirect to :2153",
        "udp dport 53 meta mark & 0x0000f000 == 0x00001000 meta skgid 10000 redirect to :2153",
    )

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
            radioStuckOn || uptime < radioWindowEnd || radioBurst?.contains(uptime) == true -> 1
            airplaneMode -> 4
            else -> 1
        }

    /** The end of the radio-on window after power-on, in seconds of uptime: the latest we measured, as B2 says. */
    var radioWindowEnd = 58.0

    /**
     * For a radio that comes on again after the window, while the router starts, against B3 in testing.md. Only the
     * power cycle test's tests use it.
     */
    var radioBurst: ClosedFloatingPointRange<Double>? = null

    /** When the modem first answers after power-on, in seconds of uptime, for [modemAnswers]. */
    var modemAnswersFrom = 30.0

    /**
     * The modem doesn't answer for a while after power-on (development.md, "After power-on"); 30 s is our guess, not
     * a measurement. The swap sends no modem command before 120 s, so only a swap that sends one too early gets here,
     * or the power cycle test, which watches the modem from the moment it reaches the router.
     */
    private val modemAnswers get() = uptime >= modemAnswersFrom

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

    /** The time zone of the times in GL's eSIM manager's log: UTC (inferred). */
    var esimLogZone: ZoneOffset = ZoneOffset.UTC

    /** When GL's eSIM manager last wrote its log, by [time]. */
    private var esimLogWritten = 0L

    /** Adds [text] to GL's eSIM manager's log, after the time in [esimLogZone], as the eSIM manager writes it. */
    fun esimLogs(text: String) {
        val stamp = ESIM_TIME.format(Instant.ofEpochSecond(time).atOffset(esimLogZone))
        files[ESIM_LOG] = (files[ESIM_LOG] ?: ByteArray(0)) + "$stamp $text\r\n".toByteArray()
        esimLogWritten = time
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

    /**
     * For a power-on that leaves the routing rules without the Enhanced Kill Switch's, against K10 in testing.md. Only
     * the power cycle test's tests use it.
     */
    var bootDropsKillSwitchRules = false

    /**
     * For a power-on whose firewall doesn't load Churn's rules, against D14 in testing.md. Only the power cycle test's
     * tests use it.
     */
    var bootSkipsChurnRules = false

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
        // The network and the firewall start from GL's settings and Churn's rules file.
        killSwitchInUse = killSwitchSet && !bootDropsKillSwitchRules
        loadedChains.clear()
        if (!bootSkipsChurnRules) loadChurnChains()
    }

    override fun run(command: String, input: ByteArray?, timeoutSeconds: Long): Output {
        check(!poweredOff) { "The router is off" }
        commands += command
        val body = command.removePrefix(LOCK)
        if (body == command && MODEM.any { it in command }) unlocked += command
        answers[body]?.let { return ok(it) }
        if (body in failing) return Output(1, ByteArray(0), "")
        return answer(body, input)
    }

    private fun answer(command: String, input: ByteArray?): Output {
        Regex("""gl_modem -B cpu AT '(.*)' </dev/null""").matchEntire(command)?.let {
            val answer = at(it.groupValues[1])
            return if (modemEchoes && answer.status == 0) ok(it.groupValues[1] + "\r\r\n" + answer.text) else answer
        }
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
        // As our Mudi 7 listed them, with other addresses on the cellular network.
        if (command == "ip route show table 16800") return Output(2, ByteArray(0), "Dump terminated\n")
        if (command == "ip route show table main") return ok(mainRoutes())
        if (command == "wg show all fwmark") {
            return ok(vpnTunnel?.takeIf { tunnelUp }?.let { "$it\t$wireguardMark\n" } ?: "")
        }
        if (command == "ls /sys/class/net") {
            return ok((NETWORK_DEVICES + listOfNotNull(vpnTunnel?.takeIf { tunnelUp })).joinToString("") { "$it\n" })
        }
        Regex("""ifstatus (\w+)""").matchEntire(command)?.let { return interfaceStatus(it.groupValues[1]) }
        // As our Mudi 7 showed it, with another hardware address.
        if (command == "ip link show ecm0") {
            val master = usbBridge?.let { " master $it" } ?: ""
            return ok(
                "26: ecm0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast$master state UP mode DEFAULT " +
                    "group default qlen 1000\n    link/ether 02:00:00:00:00:01 brd ff:ff:ff:ff:ff:ff\n",
            )
        }
        if (command == "uci -q show route_policy") return ok(routePolicy())
        if (command == "uci -q show chrony") return ok(CHRONY)
        if (command == "uci -q show firewall.block_lpa_port") return ok(LPA_RULE)
        if (command == "netstat -lnt") return ok(TCP_PORTS)
        if (command == KILL_SWITCH_ON) {
            change("Enhanced Kill Switch")
            killSwitchSet = true
            if (!reloadIgnored) killSwitchInUse = true
            if (radioOnAtReload) radioStuckOn = true
            return ok("")
        }
        if (command == "cat /etc/glversion && grep '^DISTRIB_RELEASE=' /etc/openwrt_release") {
            return ok("$glVersion\nDISTRIB_RELEASE='$openwrtRelease'\n")
        }
        COMMAND_LOOKUP.matchEntire(command)?.let { lookup ->
            val names = lookup.groupValues[1].split(" ")
            return ok(names.filter { it in absentCommands }.joinToString("") { "$it\n" })
        }
        if (command == "cat /proc/uptime") return ok(if (uptimeBroken) "" else "%.2f 100.00\n".format(uptime))
        if (command == "cat /proc/sys/kernel/random/boot_id") return ok("$bootId\n")
        if (command == "poweroff") {
            if (!poweroffIgnored) poweredOff = true
            return ok("")
        }
        Regex("""\[ -e '([^']+)' ]""").matchEntire(command)?.let {
            val path = it.groupValues[1]
            val found = path in files || files.keys.any { key -> key.startsWith("$path/") }
            return Output(if (found) 0 else 1, ByteArray(0), "")
        }
        Regex("""if \[ -e (\S+) ]; then cat \S+; else exit 3; fi""").matchEntire(command)?.let {
            val file = files[it.groupValues[1]] ?: return Output(3, ByteArray(0), "")
            return Output(0, file, "")
        }
        Regex("""umask 077 && .* && cat > (\S+)\.new && chmod (\d+) .*""").matchEntire(command)?.let {
            freed(files.put(it.groupValues[1], input!!))
            modes[it.groupValues[1]] = it.groupValues[2]
            return ok("")
        }
        if (command == "[ -d /etc/churn ] || exit 3; ls -lnd /etc/churn && ls -lnA /etc/churn") return churnListing()
        if (command.startsWith("[ -L /etc/churn/zeros ] || exit 3\n")) {
            val target = zerosLink ?: return Output(3, ByteArray(0), "")
            // As find lists them for an upgrade, through the folder that sysupgrade.conf names.
            val kept = listOf("/etc/churn/zeros").filter { "/etc/churn/" in sysupgrade.lines() }
            val zeroFiles = kept.map { "$it/0" }.filter { upgradeFollowsLinks }
            return ok("$target\n$churnFolderKb\n" + (kept + zeroFiles).joinToString("") { "$it\n" })
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
        if (command == "id -u dnsmasq_vpn") return ok("456\n")
        if (DNS_SERVERS.matches(command)) return ok(dnsServers.joinToString("") { "$it\n" })
        Regex("""umask 022 && cat > (\S+)\.new && mv \S+ \S+""").matchEntire(command)?.let {
            freed(files.put(it.groupValues[1], input!!))
            return ok("")
        }
        if (command == "fw4 reload") return fw4Reload()
        if (command == "sh /etc/churn/restore.sh") return restore()
        if (command == "rm -f ${Router.FIREWALL} && fw4 reload") {
            freed(files.remove(Router.FIREWALL))
            return fw4Reload()
        }
        if (command == "nft list ruleset | grep -F -e 'redirect to :2153' -e 'lan_drop_leaked_dns'") {
            // Churn's own redirect, which holds no device's lookups, doesn't count.
            val churns = "meta skuid $dnsmasqUid meta mark & 0x0000f000 == 0x00001000 meta l4proto { tcp, udp } " +
                "th dport 53 counter packets 0 bytes 0 redirect to :2153"
            val lines = glDnsRules + listOfNotNull(churns.takeIf { "churn_dns" in loadedChains })
            return Output(if (lines.isEmpty()) 1 else 0, lines.joinToString("") { "$it\n" }.toByteArray(), "")
        }
        if (command == CHURN_CHAINS) {
            val chains = listOf("churn_sim", "churn_route", "churn_dns").filter { it in loadedChains }
            return ok(chains.joinToString("") { "$it\n" })
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
        if (LOG_FOLLOWERS.matches(command)) {
            // As OpenWrt's log service starts it (inferred).
            val logread = (logFile ?: "/etc/sys_log".takeIf { logreadStuck })?.takeIf { !logreadMissing }
                ?.let { "/sbin/logread -f -F $it -p /var/run/logread.0.pid" }
            return Output(if (logread == null) 1 else 0, (logread?.let { "$it\n" } ?: "").toByteArray(), "")
        }
        if (command == "[ -s $ESIM_LOG ] || exit 3; date -r $ESIM_LOG +%s; tail -n 20 $ESIM_LOG") {
            val log = files[ESIM_LOG]?.takeIf { it.isNotEmpty() } ?: return Output(3, ByteArray(0), "")
            val tail = log.toString(Charsets.UTF_8).split("\n").takeLast(21).joinToString("\n")
            return ok("$esimLogWritten\n$tail")
        }
        if (OVERLAY.matches(command)) {
            val reserve = reservedClusters.takeIf { overlayType == "ext4" } ?: ""
            return ok(overlayType?.let { "$overlayDevice $it $reserve\n" } ?: "\n")
        }
        if (command == "find /etc -type f -mmin -2 2>/dev/null") return ok(etcWritten.joinToString("") { "$it\n" })
        if (command.startsWith("sync\nset -- /overlay/churn-zeros /overlay/churn-fill.*")) {
            val count = listOf(zeroFilesKb, fillFilesKb).count { it > 0 }
            return ok("$count\n${zeroFilesKb + fillFilesKb}\n")
        }
        if (command.startsWith("sync; df -k /overlay")) return ok("$overlayFreeKb\n")
        if (command.startsWith("sync\ndev=") && command.endsWith("/lifetime_write_kbytes")) {
            return ok("$lifetimeWrittenKb\n")
        }
        if (command == "cat /sys/block/mmcblk0/device/life_time /sys/block/mmcblk0/device/pre_eol_info") {
            return ok("$flashLifeTime\n$flashPreEol\n")
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
            freed(files.put(it.groupValues[1], ByteArray(0)))
            return ok("")
        }
        // The free-space overwrite writes /overlay/churn-fill.* parts, kept as zero files; its effect is not in the files.
        if ("/overlay/churn-fill." in command) {
            freeSpaceOverwritten = true
            zerosLink = "/overlay/churn-zeros"
            // It deletes any fill files first, and keeps all but 512 MB of the free space in zero files. What deleted
            // files held is overwritten.
            fillFilesKb = 0
            // Writing all of the free space takes its time.
            lifetimeWrittenKb += overlayFreeKb
            clock += overlayFreeKb * 1000 / flashWriteKbPerSecond
            freeSpace.clear()
            zeroFilesKb = maxOf(0, zeroFilesKb + overlayFreeKb - overwriteKeepsFreeKb)
            // Only an overwrite broken against H15 gets here: 4 kB files it didn't delete.
            if (overwriteLeavesFillFiles) fillFilesKb = 1_200
            // Only an overwrite broken against H13 gets here.
            if (overwriteLeavesReserveOff) reservedClusters = 0
            // Only a firmware broken against H14 gets here.
            if (overwriteCutsSimMap) files[SIM_MAP]?.let { files[SIM_MAP] = it.copyOf(it.size / 2) }
            return ok("")
        }
        if (command.startsWith("t=/tmp/churn-numbers\n")) {
            numbersCounted = input!!.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
            val inFiles = files.values.toList()
            // Only a read broken against H10 and H11 gets here.
            val raw = if (rawReadFindsNothing) emptyList() else inFiles + freeSpace
            return ok(numbersCounted.joinToString("") { "${count(raw, it)} ${count(inFiles, it)}\n" })
        }
        Regex("""rm -f (.+)""").matchEntire(command)?.let {
            it.groupValues[1].split(" ").filter(String::isNotBlank).forEach { path -> freed(files.remove(path)) }
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
        if (Router.FIREWALL in files && fw4Rejects) return Output(1, ByteArray(0), "Error: syntax error")
        loadedChains.clear()
        loadChurnChains()
        return Output(0, ByteArray(0), "[!] Section glblock option 'reload' is not supported by fw4")
    }

    /** Adds the chains of Churn's rules file, if it is there, to [loadedChains]. */
    private fun loadChurnChains() {
        val file = files[Router.FIREWALL]?.toString(Charsets.UTF_8) ?: return
        loadedChains += Regex("""chain (\w+) \{""").findAll(file).map { it.groupValues[1] }
    }

    private fun at(command: String): Output {
        if (!modemAnswers) return Output(1, ByteArray(0), "modem not ready")
        atAnswers[command]?.let { return atOk(it) }
        val imeiWrite = Regex("""AT\+EGMR=1,(7|11),"(\d{15})"""").matchEntire(command)
        return when {
            command == "ATI" -> atOk("Quectel\r\nRG650V-EU\r\nRevision: $modemRevision")
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
                if (!imeiSaveIgnored) savedImeis = null
                atOk()
            }
            command == "AT+QSIMTYPE=\"slot2\"" -> atOk("+QSIMTYPE: \"slot2\",${slot2.type}")
            command == "AT+QUIMSUB?" -> atOk("+QUIMSUB: 2,\"SUB2\",\"L\"")
            // The test commands as our Mudi 7 answered them, shortened.
            command == "AT+CFUN=?" -> atOk("+CFUN: (0,1,4),(0,1)")
            command == "AT+QCFG=?" -> atOk("+QCFG: \"rrc\",(0-5)\r\n+QCFG: \"data_interface\",(0,1),(0,1)")
            command == "AT+QNWCFG=?" -> atOk("+QNWCFG: \"data_path\",(0,1)\r\n+QNWCFG: \"data_roaming\",(0,1)")
            else -> ok("\r\nERROR\r\n")
        }
    }

    private fun cardAt(slot: Int, command: String): Output {
        val card = when {
            // As if no card were in. For the SIM 1 tray that is the worst a swap that asks too early could get, and
            // step3StopsWhileTheCardIsOffIfTheSim1TrayHoldsACard then fails.
            !modemAnswers -> null
            slot == 1 -> tray1Card
            slot2 == Slot2.SIM2 && !esimAnswersOnTray -> trayCard
            clock < esimReloadsUntil -> null
            else -> esimProfile?.let { Card(it, "260010000000001") }
                ?: Card(Router.PLACEHOLDER_ICCID, Router.TEST_IMSI)
        }
        // GL's channel repeats each command before the card's answer (verified).
        val data = command + "\r\r\n" + when {
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

    private fun uciGet(key: String): Output = if (key in uciAnswers) {
        uciAnswers[key]?.let { ok("$it\n") } ?: Output(1, ByteArray(0), "")
    } else when (key) {
        "glconfig.general.airplane_mode" -> ok(if (airplaneMode) "1\n" else "0\n")
        "gl_timer.reboot.enable" -> ok(if (scheduledReboot) "1\n" else "0\n")
        "network.modem_cpu.dds_id" -> ok("$activeSlot\n")
        "system.@system[0].log_file" -> logFile?.let { ok("$it\n") } ?: Output(1, ByteArray(0), "")
        "network.modem_cpu.iccid" -> modemCpuIccid?.let { ok("$it\n") } ?: Output(1, ByteArray(0), "")
        "kmwan.modem_cpu.tracks" -> ok("ping,1.1.1.1 ping,8.8.8.8 ping,208.67.222.222 ping,208.67.220.220\n")
        "route_policy.@default[0]" -> ok("default\n")
        "route_policy.@default[0].enabled" -> ok(if (killSwitchSet) "0\n" else "1\n")
        "network.vpn_to_main" -> if (killSwitchSet) Output(1, ByteArray(0), "") else ok("rule\n")
        "network.wgclient1" -> ok("interface\n")
        // The tunnel's WireGuard peer and its IP Masquerading, as our Mudi 7 listed them.
        "network.wgclient1.config" -> ok("peer_2010\n")
        "wireguard.peer_2010.masq" -> ok("1\n")
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

    /**
     * As our Mudi 7 listed them with the Enhanced Kill Switch on and a WireGuard tunnel up, or with the tunnel down,
     * which drops the rule to its table 1001. With the switch off, GL adds the rule at priority 9000; that it drops
     * the blackholes then is a guess.
     */
    private fun ipRules() = buildString {
        append("0:\tfrom all lookup local\n1:\tfrom all iif lo lookup 16800\n")
        append("800:\tfrom all lookup 9910 suppress_prefixlength 0\n")
        if (vpnTunnel != null && tunnelUp) append("6000:\tfrom all fwmark 0x1000/0xf000 lookup 1001\n")
        append("6000:\tfrom all fwmark 0x8000/0xf000 lookup main\n")
        if (killSwitchInUse) {
            append("9910:\tnot from all fwmark 0/0xf000 blackhole\n9920:\tfrom all iif br-lan blackhole\n")
        } else {
            append("9000:\tnot from all fwmark 0/0xf000 lookup main\n")
        }
        append("32766:\tfrom all lookup main\n32767:\tfrom all lookup default\n")
    }

    /**
     * The main routing table as our Mudi 7 listed it while online over the SIM, with other addresses. In airplane
     * mode the cellular interface is down, and its routes are gone (inferred).
     */
    private fun mainRoutes(): String {
        val sim = "default via 10.0.0.1 dev rmnet_data0 proto static src 10.0.0.2 metric 1 mtu 1500\n" +
            "8.8.4.4 dev rmnet_data0 proto static scope link metric 1\n" +
            "8.8.8.8 dev rmnet_data0 proto static scope link metric 1\n" +
            "10.0.0.0/30 dev rmnet_data0 proto kernel scope link src 10.0.0.2\n"
        val lan = "169.254.21.0/24 dev ecm0 proto kernel scope link src 169.254.21.1\n" +
            "192.168.8.0/24 dev br-lan proto kernel scope link src 192.168.8.1\n"
        return if (airplaneMode) lan else sim + lan
    }

    /** `ifstatus` of the network interface [name], shortened, as OpenWrt's netifd gives it. */
    private fun interfaceStatus(name: String): Output {
        val (up, device) = when (name) {
            vpnTunnel -> tunnelUp to name
            "modem_cpu" -> !airplaneMode to simDevice
            else -> return Output(1, "Interface $name not found\n".toByteArray(), "")
        }
        val status = JSONObject().put("up", up).put("pending", false).put("available", true)
        if (up) status.put("l3_device", device)
        return ok(status.toString(1))
    }

    /** The MD5 sum of the first [count] bytes of [bytes], or of all of them if there are fewer. */
    private fun md5(bytes: ByteArray, count: Int) =
        MessageDigest.getInstance("MD5").digest(bytes.take(count).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun change(what: String) {
        changes += "$what at ${uptime.toInt()} s"
        if (!airplaneMode || radio != 4) unsafe += what
    }

    /**
     * restore.sh as [Router.restoreFactoryImeis] runs it, by its effects as the script in the app's assets has them:
     * airplane mode on, the factory IMEIs written and saved, Churn's record of the card deleted, GL's setting for the
     * system log put back, the zero files deleted, Churn's firewall rules removed and, last, Churn's folder deleted.
     */
    private fun restore(): Output {
        // Only an app that runs restore.sh on a router it didn't set up gets here.
        val factory = files["/etc/churn/factory"]?.toString(Charsets.UTF_8)?.lines()?.takeIf { it.size >= 2 }
            ?: return Output(1, ByteArray(0), "Can't read /etc/churn/factory.\n")
        airplaneMode = true
        // Only a modem whose radio stays on gets here.
        if (radio != 4) return Output(1, ByteArray(0), "The radio didn't turn off, so the IMEIs are unchanged.\n")
        if (!restoreKeepsImeis) {
            change("IMEI 7")
            imei1 = factory[0]
            change("IMEI 11")
            imei2 = factory[1]
            savedImeis = null
        }
        freed(files.remove("/etc/churn/card"))
        if (!restoreKeepsLogOff) {
            files.remove("/etc/churn/log_file")?.let {
                freed(it)
                logFile = it.toString(Charsets.UTF_8).trim()
            }
        }
        // The log service's logread opens the file, which the app deleted at its last clearing, and writes the log's
        // buffer to it: 64 kB, say.
        logFile?.takeIf { !logreadMissing && !logreadOpensNoFile }?.let {
            files[it] = (files[it] ?: ByteArray(0)) + logBuffer.toByteArray()
            writtenKb += 64
        }
        zerosLink = null
        if (!restoreKeepsZeroFiles) {
            if (zeroFilesHeldOpen) heldKb += zeroFilesKb
            zeroFilesKb = 0
        }
        if (!restoreKeepsFillFiles) fillFilesKb = 0
        if (!restoreKeepsFirewallFile) freed(files.remove(Router.FIREWALL))
        if (!restoreKeepsChains) loadedChains.clear()
        if (!restoreKeepsFirewallLine) dropUpgradeLine(Router.FIREWALL)
        if (!restoreKeepsFolderLine) dropUpgradeLine("/etc/churn/")
        if (!restoreKeepsFolder) files.keys.filter { it.startsWith("/etc/churn/") }.forEach { freed(files.remove(it)) }
        if (restoreEndsEarly) return ok("")
        return ok(
            "The factory IMEIs are back: IMEI 1 ${factory[0]}, IMEI 2 ${factory[1]}. GL's setting for saving the " +
                "system log is as before. Churn's zero files, firewall rules and folder are removed. " +
                "Airplane mode is on.\n",
        )
    }

    /** Keeps what a file held before it was deleted or replaced, [content], on the raw partition. */
    private fun freed(content: ByteArray?) {
        if (content != null && content.isNotEmpty()) freeSpace += content
    }

    /** How often [texts] hold [number], as a whole run of digits. */
    private fun count(texts: List<ByteArray>, number: String) =
        texts.sumOf { text -> Regex("[0-9]+").findAll(text.toString(Charsets.ISO_8859_1)).count { it.value == number } }

    /** Deletes [line] from /etc/sysupgrade.conf, as restore.sh's sed does. */
    private fun dropUpgradeLine(line: String) {
        sysupgrade = sysupgrade.lines().filter { it != line }.joinToString("\n")
    }

    private fun atOk(line: String? = null) = ok("\n\r\n" + (line?.let { "$it\r\n\r\n" } ?: "") + "OK\r\n\n")

    /** Churn's folder and its files as BusyBox's `ls -ln` lists them, with any time. */
    private fun churnListing(): Output {
        val names = files.keys.filter { it.startsWith("/etc/churn/") }.map { it.removePrefix("/etc/churn/") }
        if (names.isEmpty() && zerosLink == null) return Output(3, ByteArray(0), "")
        fun line(mode: String, size: Int, name: String) =
            "%-10s %4d %-8d %-8d %8d Nov  5 21:00 %s\n".format(mode, 1, churnOwner, 0, size, name)
        val modeNames = mapOf("600" to "-rw-------", "700" to "-rwx------")
        val text = line(churnFolderMode, 4096, "/etc/churn") + names.sorted().joinToString("") { name ->
            line(modeNames[modes["/etc/churn/$name"]] ?: "-rw-r--r--", files.getValue("/etc/churn/$name").size, name)
        } + (zerosLink?.let { line("lrwxrwxrwx", it.length, "zeros -> $it") } ?: "")
        return ok(text)
    }

    private fun ok(text: String) = Output(0, text.toByteArray(), "")

    companion object {
        const val LOCK = "exec 9>/var/lock/churn.lock && flock 9 && "
        const val LOCATION_ID = "bcpu_p0133_v2c7c"
        /** The overlay's free space without Churn's zero files, in kB: 2.9 GB, as on our router. */
        const val OVERLAY_FREE_KB = 2_900_000L

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
        /** How [Router.missingCommands] looks for commands. */
        private val COMMAND_LOOKUP =
            Regex("for c in ([a-z0-9_ ]+); do command -v \\\$c >/dev/null \\|\\| echo \\\$c; done")
        /** How [Router.dnsServers] reads what listens on the DNS servers' ports. */
        private val DNS_SERVERS =
            Regex("""netstat -lnup \| while .* udp\*:53 \| udp\*:2153\) .* \| sort -u""", RegexOption.DOT_MATCHES_ALL)
        /** How [Router.overlay] reads the overlay's line in `/proc/mounts` and its reserve. */
        private val OVERLAY = Regex("""set -- .* /proc/mounts\)\necho .*/reserved_clusters 2>/dev/null\)""")

        /** How [Router.logFollowers] lists the `logread -f` processes. */
        private val LOG_FOLLOWERS = Regex("""for c in /proc/\[0-9]\*/cmdline; .* \| grep -E '\^\[\^ ]\*logread .*""")
        private val MODEM = listOf("gl_modem", "get_result_AT", "set_feature_config", "curl")
        /** How [Router.churnChains] lists the chains of Churn's rules that GL's firewall has. */
        private val CHURN_CHAINS = listOf("churn_sim", "churn_route", "churn_dns")
            .joinToString("; ") { "nft list chain inet fw4 $it >/dev/null 2>&1 && echo $it" }
        private const val FIREWALL_LOADED = "nft list chain inet fw4 churn_sim >/dev/null && " +
            "nft list chain inet fw4 churn_route >/dev/null && nft list chain inet fw4 churn_dns >/dev/null"

        /**
         * The network devices of our Mudi 7, without the tunnel's, as `/sys/class/net` lists them: the SIM's data
         * interfaces are there in airplane mode too.
         */
        private val NETWORK_DEVICES = listOf(
            "br-lan", "ecm0", "eth0", "lo", "rmnet_data0", "rmnet_data1", "rmnet_data15", "rmnet_data16", "rmnet_data2",
            "rmnet_data3", "rmnet_data4", "rmnet_data5", "rmnet_ipa0", "wlan0",
        )

        /** Chrony's settings as our Mudi 7 listed them, shortened. */
        // cspell:disable
        private val CHRONY = """
            chrony.@pool[0]=pool
            chrony.@pool[0].hostname='time.cloudflare.com'
            chrony.@pool[0].iburst='yes'
            chrony.@pool[0].nts='yes'
            chrony.@dhcp_ntp_server[0]=dhcp_ntp_server
            chrony.@dhcp_ntp_server[0].disabled='no'
            chrony.time_nl=server
            chrony.time_nl.hostname='ntppool1.time.nl'
            chrony.netnod=server
            chrony.netnod.hostname='nts.netnod.se'
            chrony.ptb=server
            chrony.ptb.hostname='ptbtime1.ptb.de'
            chrony.dfm=server
            chrony.dfm.hostname='time.dfm.dk'
            chrony.cifelli=server
            chrony.cifelli.hostname='time.cifelli.xyz'
        """.trimIndent() + "\n"
        // cspell:enable

        /** GL's firewall rule for its eSIM manager's port, with the settings our Mudi 7 listed. */
        val LPA_RULE = """
            firewall.block_lpa_port=rule
            firewall.block_lpa_port.name='Block LPA External Port'
            firewall.block_lpa_port.src='lan'
            firewall.block_lpa_port.dest_port='3456'
            firewall.block_lpa_port.proto='tcp'
            firewall.block_lpa_port.target='DROP'
            firewall.block_lpa_port.enabled='1'
        """.trimIndent() + "\n"

        /** What listens on TCP ports, shortened to SSH's and the eSIM manager's, whose line our Mudi 7 listed. */
        val TCP_PORTS = """
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State
            tcp        0      0 0.0.0.0:22              0.0.0.0:*               LISTEN
            tcp        0      0 :::3456                 :::*                    LISTEN
        """.trimIndent() + "\n"

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
