# Development notes

Technical detail for writing Churn's code. Why things are done this way is
in [design.md](design.md); building, testing and the contribution process
are in [CONTRIBUTING.md](../CONTRIBUTING.md). *Verified* means we observed
it on a Mudi 7 with GL firmware 4.10.0 and a Quectel RG650V-EU modem;
*inferred* means not yet tested. [router-tests.md](router-tests.md) lists
these claims with the test, built or planned, for each on a real router.

## Connecting to the router

- Over USB-C the router is a CDC-ECM USB Ethernet device (USB ID
  `2c7c:0133`, no RNDIS) and hands out an address by DHCP (verified). The
  router is at `192.168.8.1` by default; the app lets the user change it.
- Android doesn't use a network without internet by default. The app finds
  the USB Ethernet network through `ConnectivityManager` and binds its
  sockets to it. A Pixel 9 Pro XL with GrapheneOS reached the router this way
  with airplane mode on and Wi-Fi off (verified); other phones are untested.
- The app requests that network (`requestNetwork` for Ethernet without
  internet) rather than only listening for it, since Android may bring up
  an Ethernet network only while a request needs it (inferred). The same
  request reports when the network goes (`onLost`), and the app then ends
  the connection over it and releases the request. A later connection
  makes a new request. While the app is on screen, a request without a
  timeout watches for the router. Android reports the network as a new
  `Network` each time the cable goes in (inferred). A router switched on
  or restarted with the cable already in has shown up only once the cable
  was plugged in again, for a cause we haven't found (C6).
- Every socket needs Android's network permission, the cable's included.
  GrapheneOS lets the user turn it off per app (the Network permission), and
  then opening a socket fails with `EPERM` (verified on a Pixel 8). The app
  checks for this before it connects and asks the user to allow it.
- From Android 17 on, the router's address is on the local network, which
  needs `ACCESS_LOCAL_NETWORK`, in the Nearby devices permission group.
  Apps that target SDK 36 or lower, as Churn does, get it with `INTERNET`
  (developer.android.com, "Local network permission"). GrapheneOS shows
  that grant as "Allowed to keep app working" under Nearby devices, and
  lets the user disable it. A blocked TCP connection doesn't fail with
  `EPERM` but times out, so the app can't tell it from a router that
  doesn't answer. Targeting SDK 37 means asking for the permission at
  runtime.
- SSH as `root` with the router's admin password, using sshj. Show the host
  key fingerprint on first use and refuse a changed key unless the user
  accepts it again. Keep the password in the Android Keystore.
- The app reads a command's answer once the command has ended. In sshj
  0.41.2, a session channel's window is 2 MB, and sshj opens it again
  only as the app reads, so a router that has sent 2 MB would wait until
  the command's timeout. The app has sshj open the window as data comes
  in, and sshj then keeps up to 16 MB of each of the command's outputs
  (both verified in sshj's code). No command prints that much.
- sshj takes its algorithms from the security provider named `BC`.
  Android's own provider of that name is a reduced one, so the app replaces
  it with Bouncy Castle's full provider, last in line.

## Sending AT commands

- Send every command through GL's tool, as GL's own scripts do:
  `gl_modem -B cpu AT '<command>'` (verified; `cpu` is what GL's
  `get_modem_bus` returns on the Mudi 7). Commands for the built-in eSIM
  are the exception; see [eSIM profiles](#esim-profiles).
- Don't open the modem's device nodes directly. Every AT channel in use
  (`/dev/smd7`, `smd8`, `smd9`, `smd11`, `/dev/at_mdm0`) is held by a GL or
  Quectel daemon (verified).
- Run each modem operation under `flock` on `/var/lock/churn.lock`:
  `exec 9>/var/lock/churn.lock && flock 9 && <command>`. GL's firmware has
  `flock` (verified). The lock keeps Churn's own operations apart;
  `gl_modem` arbitrates against GL's daemons (inferred).
- Accept a step only when the expected answer and `OK` arrive. Anything else
  means the step is repeated, never assumed done. After every write, read
  the value back.

## AT commands (RG650V-EU)

| Purpose | Command | Status |
| --- | --- | --- |
| Radio state | `AT+CFUN?`: `1` on, `4` off | verified |
| Radio on | `AT+CFUN=1` | verified |
| Radio off | `AT+CFUN=4` | verified |
| SIM present | `AT+CPIN?`: `+CME ERROR: 10` = no SIM | verified |
| Slot 2's card | `AT+QSIMTYPE="slot2"`: `0` SIM 2 tray, `1` eSIM | verified |
| Read IMEI 1 | `AT+EGMR=0,7` | verified |
| Read IMEI 2 | `AT+EGMR=0,11` | verified |
| Write IMEI 1 | `AT+EGMR=1,7,"<15 digits>"` | verified, survives a reboot once saved |
| Write IMEI 2 | `AT+EGMR=1,11,"<15 digits>"` | verified, survives a reboot once saved |
| ICCID | `AT+QCCID`: a 19-digit ICCID comes with a trailing `F` | verified |
| Slot select | `AT+QUIMSLOT` | rejected by this modem |
| Save IMEI writes | `AT+QPRTPARA=1` | verified |

An IMEI write reads back at once, but without `AT+QPRTPARA=1` a reboot 5 s
later brought the earlier IMEI back; with it, the new one stayed (verified).
GL's own IMEI tool and blue-merle v2 send it after their writes too. It
also overwrites the modem's backup of its settings, so that backup holds
the IMEIs written last, not the factory ones (inferred).

`AT+GSN` and `AT+CGSN` return one of the two IMEIs, switching irregularly,
so don't use them.

To shut the router down, the app runs `poweroff` over SSH (verified), then
waits up to 120 s for the router's network to go. Before that it reads
`/proc/sys/kernel/random/boot_id`, a UUID that Linux makes new at every
boot (inferred for GL's firmware), and step 3 starts only once the router
shows another one.

## uci settings

Read a value with `uci get <setting>`. For an option, section or config
that isn't there, it prints `uci: Entry not found` and exits with 1
(verified for an option and a config), which the app reads as unset. Any
other failure, such as a config that doesn't load after a power loss
during a commit, stops the step, or fails the check, that reads the
setting: read as unset, it would have the app go on as if the flash log
were off already, or as if no reboot were scheduled.

A change needs `uci set` followed by `uci commit <config>`, where
`<config>` is the part before the first dot. Before the commit, `uci get`
already returns the new value, but
`/etc/config/<config>` still holds the old one (verified). GL's
`cellular_manager` switches airplane mode right after `uci set`, without a
commit (verified with a card in); for the other settings this is untested.
With both trays empty, the modem still answered `+CFUN: 4` 31 s after the
setting went to `'0'` without a commit, and whether GL turns the radio on
without a card at all, committed or not, is untested. The app still
commits every change, so it survives a reboot (inferred from OpenWrt's
`uci`, which keeps uncommitted changes in RAM).

GL's screen turns airplane mode on and off with a call of its own,
`ubus call cellular.modem set_airplane_mode '{"enable":false}'`, or
`true` (verified with `ubus monitor`). Turning it off on the screen also
writes the setting and commits it within 2 s (verified with
`uci changes glconfig` and a leak watch). The same call from a shell
answered `{"ok": true}` but changed neither the setting nor the screen,
in three leak watches; why is unknown. After a commit of `'0'` alone,
nothing tried to leave over the SIM in the minute that followed, in four
leak watches on our router, so it wasn't online yet (inferred). After
airplane mode off on the screen, the first packets left about 20 s later
(verified once). The app turns airplane mode on with the commit and then
waits for the modem to report the radio off, and leaves turning it off
to the user, as at step 4, the leak watch included.

| Setting | Value seen | Meaning | Status |
| --- | --- | --- | --- |
| `glconfig.general.airplane_mode` | `'0'` off, `'1'` on | GL's airplane mode. Writing it turns airplane mode on and off, though after `'0'` the router went online more slowly than after airplane mode off on GL's screen (above). Only `cellular_manager` and `gl_screen` read it. After a factory reset it was missing until airplane mode was first turned on, and Churn reads it missing as off | verified; after a boot with `'1'` the modem answers `+CFUN: 4`; missing after a reset seen once |
| `lpm.global.auto_shutdown_time` | `'30'`, set by us | Delay in seconds before GL's auto power-off. It fires only on battery with no cable or Wi-Fi client connected, and shutdown starts more than 20 s after the set delay, counted from when the display goes dark | verified |
| `gl_timer.reboot.enable` | `'0'` | GL's scheduled reboot, off | verified |
| `network.modem_cpu.dds_id` | `'1'` or `'2'` | The active SIM slot. GL rewrites it at every slot change, so it only reports the slot: writing it, even with a commit, switches nothing | verified |
| `network.modem_cpu.iccid` | The card's ICCID, a 19-digit one with a trailing `F` | The card GL uses | verified |
| `system.@system[0].log_file` | `'/etc/sys_log'` | The file that GL's `logread -f -F` writes the system log to, rotated to `/etc/sys_log.old` at `log_size` (`'128000'`, in KB). Without it, the log stays in `logd`'s buffer in RAM, `log_buffer_size` (`'64'`, in KB) | verified |
| `route_policy.@rule[<n>]` | `rule`, one section per tunnel, with `name='Tunnel 1'`, `via_type='wireguard'`, `enabled`, `killswitch` | A tunnel in GL's VPN dashboard | verified for one WireGuard tunnel |
| `route_policy.@rule[<n>].enabled` | `'1'` switched on, `'0'` off | Whether the tunnel is switched on in GL's VPN dashboard | verified for one WireGuard tunnel |
| `route_policy.@rule[<n>].via` | `'wgclient1'` | The tunnel's network interface, `network.wgclient1` with `proto='wgclient'` | verified |
| `route_policy.@rule[0].killswitch` | `'1'` | Kill switch of the first VPN rule. Writing it turns it on and off | verified |
| `route_policy.@default[0].enabled` | `'0'` with Enhanced Kill Switch on, `'1'` off | GL's fallback policy, which sends traffic that matches no VPN rule past the VPN (`via='novpn'`, mark `0x8000`) | values verified, meaning inferred |
| `network.vpn_to_main` | absent with Enhanced Kill Switch on, present off | Routing rule that sends marked traffic (`mark='0x0/0xf000'`, `invert='1'`) to the main table, outside the VPN. `ip rule` lists it at priority 9000 only while the switch is off | values and `ip rule` verified, meaning inferred |
| `network.wgclient1.config` | `'peer_2010'` | The tunnel's WireGuard peer, a section of `/etc/config/wireguard` | value verified, meaning inferred |
| `wireguard.<peer>.masq` | `'1'` | The tunnel's IP Masquerading, on | value verified, meaning inferred from its name |
| `kmwan.modem_cpu.disabled` | `'0'`; `'1'` set by Churn | Whether GL's connection check leaves out the SIM's interface `modem_cpu`. With `'0'`, it pings past Churn's firewall rules ("GL's connection check") | verified |
| `wireless.<network>.disabled` | `'1'` off, `'0'` on | Whether one of GL's Wi-Fi networks is on, a section of type `wifi-iface` with `mode='ap'`: `wifi2g`, `wifi5g`, `wifi6g` and the guest networks `guest2g`, `guest5g`, `guest6g`. GL's airplane mode leaves them as they are | verified |
| `wireless.<network>.device` | `'wifi0'`, `'wifi1'` or `'wifi2'` | The network's radio, a section of type `wifi-device`, whose own `disabled` stayed `'0'` with every network off | verified |

The app needs the Enhanced Kill Switch on. To turn it on, it sets
`route_policy.@default[0].enabled='0'`, deletes `network.vpn_to_main`,
commits both configs and runs `/etc/init.d/network reload`; turning it off
reverses this. The priority 9000 rule changes only after the reload, and
GL's web interface shows the new state at once. With the switch turned on
this way and the tunnel down, a website no longer loaded (all verified).
The app counts the switch as on only if both settings say so and
`ip rule show` lists no rule at priority 9000, so a switch turned on
without the reload counts as off. With the switch on and a WireGuard
tunnel up, `ip rule show` also lists rules that send traffic marked
`0x1000/0xf000` to the tunnel's table 1001 and traffic marked
`0x8000/0xf000` to the main table, and at priorities 9910 and 9920 send
other marked traffic and unmarked traffic from `br-lan` to a blackhole
(verified). With the tunnel's own Kill Switch off and the tunnel
disconnected, only the rule to table 1001 is gone, and a website no
longer loaded (verified). Ahead of all these, at priority 1, `ip rule show`
sends what the router sends itself to table 16800, which was empty, and
the main table's default route goes over the cellular interface
`rmnet_data0` (verified).

Without a WireGuard VPN, GL keeps neither `network.vpn_to_main` nor the
rule at priority 9000, while `route_policy.@default[0].enabled` is `'1'`,
the switch's off value. We saw this after deleting the VPN and after a
factory reset (verified), so the check of these three (router-tests.md,
K5) applies only with a WireGuard VPN set up.

GL runs two dnsmasq instances. The main one listens on port 53 and asks
the cellular interface's servers, 8.8.8.8 and 8.8.4.4. The tunnel's own
one, user `dnsmasq_vpn`, listens on port 2153 and asks the VPN's DNS
server (verified). GL's nftables rules redirect UDP lookups that a device
sends to the router and that carry the tunnel's mark `0x1000/0xf000` to
port 2153, and drop lookups that a device sends past the router unless
they carry the mark `0x8000/0xf000` (verified). With the tunnel up, the
VPN provider's connection check on a device saw no DNS leak; with the
tunnel down and the Enhanced Kill Switch on, a lookup of a made-up name
through the router timed out (verified). But for about 2 seconds after
the router came back from airplane mode, the main instance passed lookups
from devices on to its servers over the SIM (verified), presumably before
GL marked their traffic for the tunnel again (inferred). Churn's firewall
rules, in the next section, stop this, and send the router's own lookups
to the VPN's DNS server.

The app counts a VPN as set up if a section of type `rule` with
`via_type='wireguard'` names an existing network interface in `via`, as
on our Mudi 7 with one WireGuard tunnel (verified). GL's two sections of
type `rule_process` (`gl_process` and `gl_process_vpn`) name the same
interface but seem to be GL's policies for its own processes, so they
don't count (inferred). The app ignores other tunnel types until we have
seen how GL stores an OpenVPN tunnel and what its DNS and routing rules
look like. For a swap, one such tunnel must also be switched on:
switching it off in GL's VPN dashboard sets its `enabled` from `'1'` to
`'0'` (verified). Untested: a router without a tunnel.

Two settings the app needs are not in `uci` (verified by searching
`uci show`):

- GL's "Power On with Charger".
- Slot 2's choice between SIM 2 and the eSIM. GL keeps it in
  `/etc/config/cellular/slot_map.json`, and the modem reports it with
  `AT+QSIMTYPE="slot2"` (verified). The next section shows how to change
  it.

GL's airplane mode cuts only the cellular radio and leaves the router's
Wi-Fi as it was (verified once, after a factory reset). Turning Wi-Fi on
for all bands set `disabled` to `'0'` for `wifi2g` and `wifi5g`, while
`wifi6g` stayed `'1'` (verified). `iw dev` listed the wireless interface
`wlan0` with Wi-Fi off too, so the app goes by its state in
`/sys/class/net/wlan0/operstate`, which was `down` with Wi-Fi off and
`up` with it on (verified). `ubus call network.wireless status` printed
nothing. The router screen warns while a network is on in the settings,
on a radio that isn't off, or a wireless interface isn't down. The app
reads only the sections' types, radios and `disabled` settings and the
interfaces' names and states, so that the debug log gets no network
names, keys or hardware addresses.

## Churn's firewall rules

GL's firewall, fw4, includes every `*.nft` file in `/etc/nftables.d/` in
its table `inet fw4` at each reload, so the chains in Churn's file
`churn.nft` become chains of that table (verified). The folder also holds
OpenWrt's `10-custom-filter-chains.nft` and a README. `fw4 reload` prints
warnings starting with `[!]` about GL's own options and still exits with 0
(verified), so the app goes by the exit status. A reload resets the rules'
counters, and GL reloads the firewall itself whenever the Enhanced Kill
Switch is turned on or off (verified).

The file, as `Router.firewallRules` writes it, has three chains:

| Chain | Hook | What it does |
| --- | --- | --- |
| `churn_sim` | filter, postrouting, priority 0 | Lets the router's own UDP packets with mark `0x8000` out of `rmnet_data*`, and drops everything else that would leave there |
| `churn_route` | route, output, priority mangle | Gives packets that the router starts toward `rmnet_data*` without a mark in `0xf000` GL's tunnel mark `0x1000`, so the kernel routes them again, to the tunnel's table 1001, or to the blackhole at priority 9910 while the tunnel is down |
| `churn_dns` | nat, output, priority -100 | Redirects lookups that the main dnsmasq sends to port 53 with the tunnel mark to port 2153, the tunnel's dnsmasq |

The rules match only the SIM's interfaces. Devices reach the router as
before, however they connect, and an uplink over Ethernet or Wi-Fi works
as without the rules, so the router's own traffic goes past the VPN there
(inferred from the rules). GL's connection check gets past the rules over
the SIM too, so the app turns it off for the SIM (next section).

What the rules rely on, all verified on our Mudi 7:

- WireGuard sends with the mark `0x8000` (`wg show all fwmark`), the same
  mark as GL's `novpn` policy. Counted on output across airplane mode off
  and on, only WireGuard's packets carried it.
- The SIM's data interfaces are `rmnet_data0` to `rmnet_data5`,
  `rmnet_data15` and `rmnet_data16`, also in airplane mode, and
  `ifstatus modem_cpu` names `rmnet_data0` while online.
- The drop has to be in postrouting. After a route chain routes a packet
  again, later output chains still see the old interface in `oifname`;
  postrouting sees the one the packet leaves on. Our first version dropped
  in output and so dropped the time sync it had sent into the tunnel.
- The route hook at priority mangle, -150, runs before nat at -100, so the
  redirect sees the mark that `churn_route` set.
- The tunnel's IP Masquerading already translates the router's own packets
  to the tunnel's address, so the rules need no masquerade of their own.
- The main dnsmasq runs as user `dnsmasq`, ID 453; the tunnel's as
  `dnsmasq_vpn`, ID 456. The rule names the ID as a number, which the app
  reads with `id -u dnsmasq`, because a user name that nftables doesn't
  know would make the whole ruleset fail (inferred).

The app writes the file under a temporary name and renames it, adds it to
`/etc/sysupgrade.conf` and runs `fw4 reload`. It counts the rules as in
place only if the file is what the app would write now and
`nft list chain inet fw4 <chain>` finds all three chains. If the reload
fails or a chain is missing, it deletes the file and reloads again.
`restore.sh` deletes the file, then Churn's three chains with
`nft delete chain`, then the line in `/etc/sysupgrade.conf`. A reload
wouldn't remove the chains: fw4 starts it with `flush table inet fw4`,
which empties every chain of the table but deletes none, so the chains of
a file that is gone stay, empty, until the firewall or the router restarts
(verified with OpenWrt's fw4 template and nftables 1.0.9 in a network
namespace; GL's template on our Mudi 7 starts with the same flush).

## GL's connection check

GL checks whether an uplink reaches the internet with its multi-WAN
manager, kmwan. Its settings, `/etc/config/kmwan`, have a section of type
`member` for each uplink's interface. The SIM's is `kmwan.modem_cpu`, for
`modem_cpu`, whose `tracks` are pings to 1.1.1.1, 8.8.8.8, 208.67.222.222
and 208.67.220.220 (verified). kmwan is a kernel module: `lsmod` lists
`kmwan`, and no process runs for it. `/etc/init.d/kmwan` and
`/lib/functions/kmwan.sh` pass each member's settings to the module as
JSON, through `/proc/gl-kmwan/config`, and send no pings over the SIM
themselves (verified by reading them), so the module sends the pings
(inferred).

While the SIM is online, the module pings all four addresses together
once a second from the SIM's address (verified with `tcpdump`). Churn's
rules don't see the pings: in leak watches with GL's settings as they
were, 4 to 8 packets left over the SIM in the first read after going
online, before WireGuard had sent anything, and the counters before
Churn's rules never saw them (verified). So the pings presumably leave
without passing netfilter (inferred). Each ping carries an ICMP ID and
sequence number, which didn't start over when the router went online
(verified in a capture of 2026-10-14).

`kmwan.modem_cpu.check='0'` doesn't stop them: neither script reads it,
and the leak watch stopped with it as before (verified once).
`disabled='1'` does. `kmwan.sh` skips a disabled member wherever it adds
members to the module, and `/etc/init.d/kmwan restart` drops the module's
members and adds the enabled ones whose interface is up (inferred from
the scripts). After both, in airplane mode, `/proc/gl-kmwan/config` was
empty, and a leak watch counted nothing over the SIM but WireGuard's
packets through Churn's rules, with the tunnel and the clock's sync as
before (verified once). So the SIM's route and the tunnel don't need
kmwan. `kmwan.modem_cpu_6`, by its name the member for the SIM's IPv6,
was disabled already on our Mudi 7.

Step 3 turns the check off for the SIM after Churn's firewall rules, in
airplane mode. If `/etc/churn/kmwan` isn't there yet, it first saves
GL's value of `kmwan.modem_cpu.disabled` there, or an empty line for none.
It then sets the value to `'1'`, commits it and restarts kmwan. The init
script calls `gen_weight_route`, which `kmwan.sh` doesn't define, so the
app ignores the restart's exit status. It counts the check as off only if
the value is `'1'` and `/proc/gl-kmwan/config` names no member for a
modem's interface, found as GL's scripts find them: the lines holding
`modem`, up to the first `:`. The file was empty in airplane mode, so
that format is inferred. Otherwise the step stops. The restart also runs
`uci commit network` and reloads the network (from the scripts), so the
step then checks that the radio is still off. The dry run restarts kmwan
even with the check off, to see whether the restart drops the cable or
turns the radio on (router-tests.md, D23).

`restore.sh` sets `kmwan.modem_cpu.disabled` back from the copy, or
deletes it for an empty line, and commits it. It doesn't restart kmwan:
airplane mode keeps the SIM's interface down, and GL's scripts add the
member again once the interface comes up (inferred).

kmwan's events also set chrony's time servers. The firmware's default, in
`/rom/etc/config/chrony`, is the pool `2.openwrt.pool.ntp.org`. GL's hotplug
script `/etc/hotplug.d/kmwan/time_sync` runs when kmwan reports an uplink
online, and if the clock hasn't synced since power-on
(`/var/state/ntp-valid` is missing), it replaces that pool with
`time.cloudflare.com` and adds five more NTS servers, unless the router's
country code is CN, then commits and restarts chronyd (from the script). Our
Mudi 7 had those servers until a factory reset, then the pool, and those
servers again once it had been online (verified). Whether the script still
runs while Churn has the check off for the SIM is unknown. The clock synced
through the tunnel with those servers (D16), and with the pool it presumably
does too (inferred). router-tests.md's D6 accepts both sets. Another of GL's
components also writes `/var/state/ntp-valid`: after a sync with the check
off for the SIM, it held `chronyc sync`, while the script writes `kmwan set
success` (verified once).

## Switching SIM slots

Both switches go through GL's cellular manager, with the call GL's web
interface makes (verified by recording what the web interface does):

```sh
ubus call cellular.modem set_feature_config '<data>'
```

| Switch | `<data>` |
| --- | --- |
| Make slot `<n>` the active slot | `{"location_id":"<id>","bus":"cpu","data":{"failover":{"current_sim":<n>,"esim2_enable":<e>}}}`, where `<e>` is slot 2's current choice: `true` for the eSIM, `false` for the SIM 2 tray |
| Set slot 2 to the eSIM (`<t>` = `1`) or to the SIM 2 tray (`<t>` = `0`) | `{"location_id":"<id>","bus":"cpu","slot":2,"data":{"failover":{"slot_type":[{"type":0,"slot":1},{"type":<t>,"slot":2}]}}}` |

`<id>` is the modem's `location_id`, `bcpu_p0133_v2c7c` on our Mudi 7. It
seems to combine the bus with the modem's USB ID `2c7c:0133`, so another
modem or firmware may use a different value (inferred). The app reads it
from `ubus call cellular.modem get_feature_config '{}'`, which returns
GL's settings for the modem and for each slot. The key whose entry holds
`failover` is `<id>`, and the slots' keys are `<id>` with `s1_` or `s2_`
in front (verified).

GL answers `{"ok": true, "changed": true}`, and the new state shows at
once in `current_sim_slot` of `ubus call cellular.modem status`, in
`network.modem_cpu.dds_id` and in `AT+QSIMTYPE="slot2"`. `esim2_enable` in
`slot_map.json` follows later, so `<e>` is taken from
`AT+QSIMTYPE="slot2"`. Both calls work in airplane mode, and the modem
answered `+CFUN: 4` before and after each one, although GL's web interface
refuses to change the active slot in airplane mode. An RF meter showed no
emission during any of these switches (all verified).

Once in 27 calls in our tests, GL answered
`{"ok": false, "error": "lock failed"}` instead, right after 20 reads of the
radio and both IMEIs (router-tests.md, P5), and slot 2 stayed as it was
(verified). Which lock GL means, and what held it, is unknown. Churn sends
its commands one at a time, so most likely one of GL's own processes held it
(inferred). The app asks again 5 s later, and stops the step only at the
third such answer in a row.

Changing the active slot most likely moves only the data connection, and a
card in the other slot stays registered while the radio is on (inferred).
In a recording, GL logged a switch of the data subscription, and slot 2's
card reported full registration in the same second slot 2 became the
active slot.

## eSIM profiles

Step 1 of the swap needs slot 2 on the built-in eSIM with no profile
enabled (design.md, section 7). The chip enables at most one profile at a
time, so disabling the enabled one leaves none. The app checks this after
switching slot 2 to the eSIM in airplane mode, and disables the enabled
profile if there is one. It never deletes a profile: blue-merle v2 warns
that deleting GL's pre-installed one can't be undone without a factory
reset.

### Reading slot 2's card

The chip answers only while slot 2 is set to the eSIM. The app reads its
ICCID with `AT+ICCID` and its IMSI with `AT+CIMI`, sent to slot 2 the way
GL's eSIM manager sends them (verified):

```sh
ubus call modem.CPU.AT get_result_AT \
  '{"cmd":"AT+ICCID","sub_id":2,"source_flag":0,"timeout":30}'
```

The `data` field of the answer holds the modem's reply, `+ICCID: <ICCID>`
and `OK`. Like `AT+QCCID`, it pads a 19-digit ICCID with a trailing `F`
(verified). Whether `gl_modem` reaches slot 2's eSIM as well is untested.

| Slot 2 | ICCID | IMSI |
| --- | --- | --- |
| No profile enabled | placeholder `89000123456789012341` | `001010123456063` (GSMA TS.48's test profile, test network 001/01) |
| A profile enabled | the profile's ICCID | the profile's network |

Step 3 of the swap reads the card in the SIM 2 tray the same way, once
slot 2 is set to it (verified with an eSIM adapter card). A 9eSIM adapter
card with no profile enabled showed the placeholder ICCID
`89111111111111111111` and the IMSI `111111111111111` (verified). Every
such card probably shows the same ICCID, and other brands of adapter card
may show placeholders of their own (inferred). Step 3 records a pair only
for a card that has a real network's IMSI and doesn't show the built-in
eSIM's placeholder ICCID, since a pair recorded for a placeholder would go
to every card that shows it. No real network's country code starts with
0, which test networks such as 001/01 use, or 1, which no country has
(inferred from published lists of country codes), so an IMSI starting
with either digit counts as no real network's. That covers the 9eSIM
card's placeholder and those of other brands that use such an IMSI
(inferred). The app relies on no adapter card's placeholder ICCID, which
can differ by brand, only on the built-in eSIM's.

### Disabling the enabled profile

GL's eSIM manager is Quectel's LPA, `/usr/bin/lpa_rg650_v1.55`, started by
`/etc/init.d/esim_lpa`. It talks to the chip through the ubus call above
and serves a JSON API over HTTP on port 3456. GL's web interface reaches
the API through nginx at `/sdk/v1`, which requires a web login
(`/etc/nginx/gl-conf.d/esim.conf`), and GL's firewall rule
`block_lpa_port` drops port 3456 from the LAN (all verified).

To disable a profile, the web interface posts this request, where `txId`
is a request number (inferred) and `<ICCID>` the profile's ICCID as
`AT+ICCID` gives it, including a trailing `F` (copied from the browser):

```json
{"txId":"4","method":"disable","iccid":"<ICCID>"}
```

The answer is `{"tx_id":"","code":200,"message":"ok"}`; `code` 200 means
success. Over SSH no web login is needed: the app sends the same request
from the router's shell with `curl`, straight to the eSIM manager rather
than through nginx, and gets the same answer (all verified):

```sh
curl -sS -m 60 -H 'Content-Type: application/json' \
  -d '{"txId":"1","method":"disable","iccid":"<ICCID>"}' \
  http://127.0.0.1:3456/sdk/v1
```

The app doesn't rely on the answer. In both disables we recorded, one from
the web interface and one from the shell, the eSIM manager logged an error
(`disable.ret: -2`), yet the profile was disabled, and the shell request
still got `code` 200. After a disable or enable the eSIM manager waits about
5 s for the modem to reload the card (log `wait md reboot: 5s`), so the app
waits too, then reads ICCID and IMSI again until they show the placeholder
and 001/01. From the shell, the placeholder showed at the first read, 5 s
after the answer. Airplane mode stayed on and the modem answered `+CFUN: 4`
throughout the recorded disable and enable, and after the disable from the
shell (all verified).

If they don't show within 30 s, the app asks again, up to three requests in
all, and then stops step 1 with the eSIM manager's last answer in its
message, leaving out long numbers such as ICCIDs. Before the app asked
again, we saw step 1 leave the profile enabled after its one request only
when the profile had been online as step 1 began, and disabling it in GL's
web interface afterwards worked (verified). Step 1 sends the request as soon
as the modem reports the radio off, so most likely it came before the router
had finished taking the profile offline (inferred; the app kept no answer
then). The disables we recorded began with airplane mode already on. Since
the app asks again, several swaps have passed, and in one of them, begun
with the profile online, the first request again left the profile enabled
and the second disabled it (verified). Each request comes at least 35 s
after the last, which also lets the eSIM manager open a new channel to the
chip for it (inferred). It reused a channel it had opened up to 12 s before
(`reopen.ignore` in its log), and right after an enable, listing the
profiles failed twice on a channel it had opened 11 s after the enable
(`empty list, maybe bad esim channel`; both verified).

Not used:

- Quectel's `AT+QESIM` commands. The modem lists them, but
  `AT+QESIM="profile_brief"` answers `ERROR` while GL's eSIM manager runs
  (verified), probably because the modem's own LPA is off (inferred).
- The standard eSIM commands (GSMA SGP.22, ES10c), which the eSIM manager
  sends in `AT+CSIM` over the ubus call above: open a logical channel,
  select the ISD-R (AID `A0000005591010FFFFFFFF8900000100`), `BF2D` to
  list profiles and `BF32` to disable one by its ICCID (verified in the
  recordings). The eSIM manager polls the chip every few seconds on the
  same path and reopens the channel, so commands of our own could
  interleave with its own (inferred). They stay a fallback if the API
  changes.

## The SIM 1 tray

Churn never uses slot 1, but a card in the SIM 1 tray registers at every
power-on (design.md, section 6), so step 3 of the swap stops if the tray
holds one. While slot 2 is the active slot, the modem still answers
`AT+CPIN?` for the SIM 1 tray, sent the way the app reads slot 2's card
but with `sub_id` 1 (verified, with airplane mode on and off):

```sh
ubus call modem.CPU.AT get_result_AT \
  '{"cmd":"AT+CPIN?","sub_id":1,"source_flag":0,"timeout":30}'
```

| SIM 1 tray | `AT+CPIN?` | `AT+ICCID` |
| --- | --- | --- |
| Empty | `+CME ERROR: 10` (SIM not inserted) | `+CME ERROR: 13` |
| A card | `+CPIN: READY` and `OK` | the card's ICCID |

A card locked with a PIN answers `+CPIN: SIM PIN` instead
(3GPP TS 27.007; untested), so the app counts any `+CPIN:` answer as a
card. GL's `ubus call cellular.sim status` agreed, with an empty `iccid`
and `status` 0 for slot 1 while the tray was empty, and the card's ICCID
and `status` 5 while it held a card. `AT+QSIMSTAT?` answered `0,1` in
every case, so it can't tell (all verified).

## Clearing the SIM history

design.md, section 7, explains why the app clears what GL records of
earlier cards. On our Mudi 7, GL kept it in these files (verified):

| Path | Contents |
| --- | --- |
| `/etc/sys_log`, `/etc/sys_log.old` | The system log. `Diag_Lib` lines pair each card's ICCID with its IMSI, `ql_ril` lines give the network code, cell ID and tracking area of each registration, and GL logs the ICCID at each dial-up |
| `/etc/config/cellular/sim_map.json` | A JSON object with a member per card, named by its ICCID and holding the card's settings: `manual`, `pincode`, `auth`, `username`, `password`, `roaming` and `cid` |
| `/etc/config/cellular/history_apn_database.json` | `{"apns": [...]}`, with an object per card holding `iccid`, `apn`, `ip_type` and `cid`: the APN that worked |
| `/root/esim/db/<32 digits>` | The eSIM manager's record of a chip, one file per chip: `{"records": {...}}`, with a member per profile, named by its ICCID and holding `iccid`, `ac_code`, `apn` and `oplmn`, which were empty |
| `/root/esim/log.txt` | The eSIM manager's log, which names profiles by ICCID |

GL stores a 19-digit ICCID with a trailing `F` and a 20-digit one without,
so the app compares ICCIDs without the `F`s. The current card is the one
`network.modem_cpu.iccid` names, or at step 3 the one in slot 2.
`Router.clearHistory` clears the files in this order:

1. If `system.@system[0].log_file` is set, the app saves its value to
   `/etc/churn/log_file`, unless that file exists, deletes the setting,
   commits and runs `/etc/init.d/log reload`. The reload ends the
   `logread -f -F` that writes the file, and `logd` goes on with its
   buffer in RAM (verified). A file still open would keep its data on the
   flash after it is deleted.
2. It deletes the log's files, each with its `.old`: `/etc/sys_log`, the
   file that Churn's copy of the setting names, and the one the setting
   named until step 1, since another firmware may log to another file.
   Each path is quoted, so the log is deleted wherever the setting puts
   it.
3. It rewrites both card files with only the current card's entry,
   through a new file renamed over the old one. It makes the new file
   with `cp -p`, so that the file keeps its mode, owner and group:
   `sim_map.json` holds SIM PINs. On our Mudi 7 both are root's, with
   mode 644, as is GL's `slot_map.json`, which Churn never writes
   (verified, #153). It deletes each eSIM record file that doesn't name
   the current card, and empties `log.txt`, which the eSIM manager goes
   on writing to (verified). A card file or record that doesn't parse,
   or an APN history without its `apns` list, stops the clearing, since
   it may still name earlier cards. Steps 1 and 3 stop before that, when
   the check of H4 finds a file in another format, as after a firmware
   upgrade that changed it; the clearing's stop covers a file that
   changed since the check.
4. Except at setup, it fills the free space of the overlay, ext4 on the
   eMMC partition `mmcblk0p59` (2.9 GB). It holds the lock
   `/var/lock/churn-overwrite.lock` from start to end, so that a run that
   outlived the app's connection and the next one don't overlap;
   `restore.sh` takes it too before it deletes the zero files. It makes
   the directory `/overlay/churn-zeros` and the link `/etc/churn/zeros` to
   it, sets `/sys/fs/ext4/mmcblk0p59/reserved_clusters`, the 4096 blocks
   that ext4 keeps free even from root, to 0, and only then makes 16
   directories `/overlay/churn-fill.d<n>`, so that they fit on a partition
   that is full but for the reserve, as a power cut before the end of a
   run leaves it. It writes files `/overlay/churn-fill.<n>` of 64 MB from
   `/dev/zero`, with a `sync` after each, until `dd` fails on the full
   partition, then files of 4 KB into the directories, at most 300 in
   each, until `dd` fails again. Then it syncs, because the zeros of a
   file deleted before they are written out never reach the flash
   (verified on a disk image), and prints how much is still free, which
   should be nothing; more than 64 kB fails the run. Only then does it
   delete the 4 KB files and move the 64 MB files into
   `/overlay/churn-zeros`, numbered on from the files there, so the folder
   gains only flushed parts, and none from a run that stops. It syncs again
   and sets the reserve back. Then it deletes files in
   `/overlay/churn-zeros`, lowest number first, until 512 MB of the
   partition are free, and last turns ext4's journal over, as the end of
   this section explains. A run that fails once it has set the reserve
   still does all of this, and then exits with the status of its first
   failure: 5 if it couldn't set the reserve to 0, 6 for the free space
   left after the fill, 7 if it couldn't set the reserve back, and 8 if it
   couldn't turn the journal over. Without the lock it exits with 2, and
   without the folder or the link with 4. When a signal stops the run, a
   trap deletes the fill files and any folder of the turnover, syncs, sets
   the reserve back, frees the 512 MB the same way and exits with 1; a
   reboot sets the reserve back too. A run that finds the reserve at 0, as
   a run killed before it set the reserve back leaves it, exits with 3
   before it changes anything, since it would put back 0.

We ran these steps as a script on our router, with step 4 still without
the reserve and the 4 KB files. The router had seen two old cards, A and
B, the empty eSIM's placeholder and the current card, D (verified).
Afterwards GL's card files named only D, and still did after airplane
mode went off and on: GL doesn't add back cards it isn't using. The
overwrite took 46 parts and about 3 minutes, the last parts up to 9 s
each, and GL's JSON files stayed valid. We counted the cards' numbers on
the raw partition and in its files, after
`sync; echo 3 > /proc/sys/vm/drop_caches` so that the reads came from the
flash:

| Number | Raw partition, a day before | Files, a day before | Raw partition after | Files after |
| --- | --- | --- | --- | --- |
| The three IMSIs | 48 | 48 | 0 | 0 |
| ICCID of card A | 460 | 270 | 11 | 0 |
| ICCID of card B | 30 | 16 | 3 | 0 |
| The empty eSIM's placeholder ICCID | 543 | 485 | 0 | 0 |
| ICCID of card D | 502 | 199 | 58 | 5 |

The 14 copies of A and B that were left were in no file. Two days later,
after a switch to another card, a script that located each copy on the
partition found 8 copies of an old card's ICCID, all in free blocks, in
old versions of a settings file in uci's format that names the card in
use, and none in the ext4 journal (verified). The overwrite missed them
because of ext4's reserve, which root can't fill either, and because a
fill with large files takes the single free blocks between small files
last. Step 4 as it is now then took every free block with its 64 MB
files on our router, so the 4 KB files found none, and afterwards every
copy of a card's ICCID on the raw partition was in a file that names the
card (verified). On a disk image with 300 old versions of such a file in
the free space, the overwrite left 299 of them before step 4 had the
reserve and the 4 KB files, and none after; there the 64 MB files with
the reserve at 0 still left about 200 single blocks free, which the 4 KB
files took (verified on the image). Our script also remounted the
overlay with `discard` for the run; the app leaves that out, since the
overwrite already covers the free space.

On disk images with GL-like config rewrites, the second of two swaps in
a row overwrote only the 512 MB that the zero files left free: on an
image of the overlay's size, the first overwrite wrote 2.9 GB and the
second 0.5 GB. Neither left a copy of the outgoing card, a run stopped by
a signal left the zero files and the reserve as they were before it, a
run that found the partition full freed the 512 MB, and e2fsck found the
file system clean (verified on the images). CI repeats these runs on a
1 GiB image at every build (CONTRIBUTING.md, "Building").

A file's name can outlive the file. The eSIM manager names each record by
its chip's EID, which the eSIM provider's server learns when it downloads
a profile to an adapter card, so a deleted record's name would tie the
router to every profile that adapter held. Our router runs Linux
5.15.170 (#122), whose ext4 wipes a deleted file's entry in its
directory's block (inferred from the version). But ext4's journal,
64 MiB on the overlay (blocks 360448 to 376831), can keep an older copy
of the block, with the name, until newer metadata writes take its place.
The fill's own writes don't take it: on a disk image, the journal still
held the names of deleted files after the fill (verified on the image),
and on our router, after the second clearing, the removal test found a
deleted file's name on the raw partition, in no file (verified, #122),
so in the journal (inferred). jbd2, which writes ext4's journal, starts
again at the journal's beginning at every mount (verified in Linux
5.15's source), so a boot that writes little leaves the rest of the
journal as earlier boots wrote it. ext4 journals no file data by
default, so what Churn's record of a card held before step 3 wrote it
anew went to a block that is free again, which the fill overwrites,
unless the file system keeps small files in their inodes, as it does
with the `inline_data` feature (inferred). On our router, the removal
test found no copy of what a file held before it was written anew
(verified, #122).

So the overwrite ends by turning the journal over, once the 512 MB are free.
In five rounds, it makes as many folders in `/overlay/churn-turnover` as the
journal's largest transaction holds blocks, has ext4 commit them by writing
a file with `dd`'s `conv=fsync`, and deletes them. jbd2 caps a transaction
at a quarter of the journal in Linux 5.15 and at a third in Linux 6.12
(verified in their source), so the five rounds put at least 1.25 times the
journal's size in new folders' blocks into it, besides what ext4 writes to
keep track of them. On a disk image with a 64 MiB journal, whose blocks we
marked before, rounds of 4096 folders, a quarter of the journal as in
Linux 5.15, left 2903 of the 16,383 log blocks marked after three rounds and
none after four, and five rounds wrote 88 MiB. They left no deleted file's
name on the image, and e2fsck found it clean (verified on the image). Since
the folders are deleted before ext4 writes them to their own place, only the
journal gets them (inferred from what the rounds wrote): with a `sync` in
place of `dd`, which the overwrite falls back to, the five rounds wrote
174 MiB. The overwrite prints the folders a round took and the kB the
turnover wrote, which the removal test leaves out of what the fill wrote
(router-tests.md, H15). The removal test checks H22 and H23 after its second
clearing, with a probe of made-up digits. On our router, it found no copy of
the deleted file's name either, and the turnover wrote 89 and 93 MiB at its
two clearings (verified, #174). The second clearing took about 57 s, against
39 to 44 s before, so the turnover takes about 15 s there (inferred).

## After power-on

At every power-on the modem reports its radio on (`+CFUN: 1`) for about
12 s, whatever airplane mode says, ending 58 s after power-on at the latest
in our measurements (design.md, section 6). What the router emits then
depends on slot 2: only the eSIM's one or two short bursts while slot 2 is
on the built-in eSIM with no profile enabled, but more than 8 s of RF while
it is on an enabled eSIM profile or on a card in the SIM 2 tray. The modem
doesn't answer AT commands for a while after power-on, so the app keeps
asking. Once it answers, it repeats each command before its answer for a
while, as GL's channel to the cards always does (verified about 70 s after a
power-on, and gone 146 s after it), so the app leaves a first line that is
the command out of every answer. GL's DNS rules for the tunnel, its cellular
manager's `cellular.modem` and its eSIM manager can come up more than a
minute after power-on: all three were missing 56 to 62 s after one power-on,
`cellular.modem` was still missing 73 s after another, and all three were
there by 127 s after a third (#103). We take them to be up within 2 minutes
(inferred), so the router checks of D2, S1 and E5 don't apply before
`/proc/uptime` shows 120 s, and step 1, begun sooner, waits until then
before its checks. Step 3 of the swap changes nothing before `/proc/uptime`
shows 120 s and the modem answers `+CFUN: 4`.

## The debug log

design.md, section 7, says what the debug log holds and leaves out. The
app keeps it in `debug-log.txt` among its private files. The "Debug log"
menu item turns it on and off, deletes it, or shares it through the
app's own content provider, which lets only the app the user picks read
it.

Each job or step starts a section with a line `== <title>`, and each swap
with `== New swap`, after the lines of a job that was still running when
the swap began. Every line begins with the seconds since its section
began. A command shows as `$` and its first line, up to 300 characters,
followed by its exit status and its answer, up to 2,000 characters, with
`|` before each line of standard output and `!` before each line of
standard error. The commands that read files, GL's VPN settings and the
router's clock show only the size of their answer. Where an answer would
hold addresses, the router sends only what the app reads of it: of
`ifstatus`, only `up` and `l3_device`, through `jsonfilter`; of
`ip -o link show ecm0`, only the bridge after `master`; and of
`ip route show`, only how many routes table 16800 holds and the device of
each default route in the main table. A progress line starts
with `✓`, a question with `?`, followed by the user's answer, and the
reason a job or step stopped with `!`, followed by the exception and where
in the app it was thrown. Once the router screen shows, a section
`== Checking the router` holds the router checks, each with its commands
and then its result: `✓`, `✗` or `–` for passed, failed or not
applicable, the check and the IDs of its claims in router-tests.md, and on
the next line why it failed or doesn't apply. Before steps 1 and 3, the
checks a swap relies on go into the step's section the same way. Every run
of 12 or more hexadecimal digits shows as `…`, and so does every IPv4, IPv6
and MAC address, in commands as in answers: netstat's `0.0.0.0:22` and
`:::22` both show as `…:22`, and the address of GL's eSIM manager as
`http://…:3456`. The masking comes before the cuts, which could otherwise
leave a part of a number or an address. The ICCID and IMSI
that the built-in eSIM shows without an enabled profile (above) name no
card, so they show as `[built-in eSIM placeholder ICCID]` and
`[built-in eSIM test IMSI]`. Every other card's numbers show as `…`, an
adapter card's placeholders included. Before each section the app drops
the oldest sections, keeping the last three swaps and at most 1,000,000
characters, and in debug builds every swap and at most 20,000,000
characters. Without a swap limit, it reads the log for this only once the
file has more bytes than that.

GL's eSIM manager writes its log to `/root/esim/log.txt`, not to the
system log, and the clearing empties it (above). Once step 1 or 3 has
connected, the app notes the log's size, the MD5 sum of its first 64
bytes and the router's clock (`date +%s`). Just before step 3's
clearing, and a last time when the step ends, the app copies what the log
gained since the last note, at most its last 100,000 bytes from the start
of a line, and notes the log anew. A step that shuts the router down makes
its last copy just before the shutdown. The log only grows until it
is emptied (inferred), so if it has become shorter or no longer begins
with the bytes it began with, the app copies it from its start. The eSIM
manager begins each line with a time such as `Sun Oct 11 11:17:08 2026`,
which the app turns into seconds in the section by its distance from the
router's clock at the first note. It takes that time as UTC (inferred:
the eSIM manager's times were two hours behind the system log's, which
uses the router's time zone, CEST). If the times are local instead, the
copied lines are off by the time zone's offset. The reads use `dd`,
`md5sum`, `tail -c` and `wc -c`; whether GL's firmware has `md5sum` is
untested, and the router checks look for it.

## Still to find out

- Where GL stores "Power On with Charger". Until then the app asks the user
  to confirm that it is off.
- When during a shutdown the phone loses the router's network: whether
  only once the router is off, and how long after `poweroff`.
- Whether `/etc/init.d/network reload` interrupts the cable connection or
  turns the radio on, and whether `fw4 reload` or
  `/etc/init.d/kmwan restart` does.
- Whether GL turns its connection check back on for the SIM by itself, as
  at a slot switch, from its multi-WAN page or at a firmware upgrade
  (router-tests.md, D22), and whether kmwan pings over the SIM again,
  without a restart, once `restore.sh` has put GL's setting back.
- Whether `logread` writes the log's file again once `restore.sh` has set
  `log_file` back and reloaded the log service.
- Whether GL copes with card files that name no card, as they can after a
  clearing at step 3, until it records the new card.

## Files on the router

| Path | Mode | Contents |
| --- | --- | --- |
| `/etc/churn/` | 700 | Churn's folder |
| `/etc/churn/README.txt` | 600 | What the folder is; how to restore the factory IMEIs by hand; to do so before a factory reset |
| `/etc/churn/factory` | 600 | Factory IMEI 1 and IMEI 2, one per line |
| `/etc/churn/card` | 600 | The card step 3 last gave IMEIs: its ICCID's digits, IMEI 1 and IMEI 2, one per line. Written before the IMEIs |
| `/etc/churn/version` | 600 | The version code of the app whose step 3 last ran to its end, as a decimal number on one line. Written last, so only there after a swap; without it, the app counts the router as version 1 |
| `/etc/churn/log_file` | 600 | GL's `system.@system[0].log_file` as it was before the app first deleted it. Only there after the app has deleted it |
| `/etc/churn/kmwan` | 600 | GL's `kmwan.modem_cpu.disabled` as it was before step 3 first turned GL's connection check off for the SIM, or an empty line if it wasn't set. Only there after that |
| `/etc/churn/restore.sh` | 700 | Shipped in the app's assets. Takes the lock, turns airplane mode on and checks that the radio is off, writes the IMEIs from `factory`, reads them back and saves them, deletes `card`, sets `log_file` back from Churn's copy and deletes the copy, waits for a free-space overwrite still running, deletes `/overlay/churn-zeros`, the link to it and any fill files, sets `kmwan.modem_cpu.disabled` back from Churn's copy and deletes the copy, then removes Churn's firewall rules and, last, deletes `/etc/churn/` itself |
| `/etc/churn/zeros` | – | Link to `/overlay/churn-zeros`, made by the free-space overwrite |
| `/etc/nftables.d/churn.nft` | 644 | Churn's firewall rules, from `Router.firewallRules` |
| `/overlay/churn-zeros/` | – | Files of zeros from the free-space overwrite, up to 64 MB each and numbered, that take all but 512 MB of the free space. Beside the router's own files, so that upgrades and tools reading `/etc` skip them (design.md, section 5) |

At setup the app also adds the line `/etc/churn/` to `/etc/sysupgrade.conf`,
and with its firewall rules the line `/etc/nftables.d/churn.nft`. At every
connection it rewrites `README.txt` and `restore.sh` where they differ from
its assets. `restore.sh` takes both lines out again, the folder's just
before it deletes the folder.

## IMEI pairs

The pairs are specified in design.md, section 4. Notes for implementing
them:

- **ICCID read from the modem:** keep the digits only. The modem pads a
  19-digit ICCID with a trailing `F` (verified), and most likely an 18-digit
  one with two, since the card keeps it in 10 bytes, two digits to a byte
  (inferred: we have had no such card). The `F`s are dropped, and with them
  an ICCID has at most 20 characters.
- **Serial:** draw `s` with `SecureRandom.nextInt(n)`, which is unbiased.
- **Luhn:** the standard algorithm. Example: `49015420323751` gets check
  digit `8`.
- **Place list:** the app keeps it in its `SharedPreferences` as a JSON
  array of objects with `iccid` (digits only), `imei1`, `imei2` and, if the
  user gave the card one, `name`.
