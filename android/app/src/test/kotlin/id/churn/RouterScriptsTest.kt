package id.churn

import java.io.File
import org.junit.Test

/**
 * Writes the scripts in [Router]'s companion, as the app sends them, to `build/router-scripts`, where CI lints the
 * shell scripts with ShellCheck and checks the firewall rules with `nft -c`. A script added there belongs here too.
 */
class RouterScriptsTest {

    @Test
    fun writesTheRouterScriptsForCi() {
        val scripts = mapOf(
            "default-route-devices.sh" to Router.DEFAULT_ROUTE_DEVICES,
            "dns-servers.sh" to Router.DNS_SERVERS,
            "zeros-link.sh" to Router.ZEROS_LINK,
            "zero-files.sh" to Router.ZERO_FILES,
            "overlay-free.sh" to Router.OVERLAY_FREE,
            "overlay-written.sh" to Router.OVERLAY_WRITTEN,
            "count-numbers.sh" to Router.COUNT_NUMBERS,
            "numbers-in-files.sh" to Router.NUMBERS_IN_FILES,
            "sim-traffic.sh" to Router.SIM_TRAFFIC,
            "tunnel-clock.sh" to Router.TUNNEL_CLOCK,
            "overlay.sh" to Router.OVERLAY,
            "log-followers.sh" to Router.LOG_FOLLOWERS,
            "overwrite-free-space.sh" to Router.OVERWRITE_FREE_SPACE,
            "leak-counters.nft" to Router.LEAK_COUNTERS,
            // GL's firewall includes the file in its own table.
            "firewall.nft" to "table inet fw4 {\n${Router.firewallRules(dnsmasqUid = 453)}}\n",
        )
        // From android/app, where Gradle runs the tests.
        val folder = File("build/router-scripts")
        folder.deleteRecursively()
        folder.mkdirs()
        for ((name, script) in scripts) File(folder, name).writeText(script)
    }
}
