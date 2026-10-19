# Development notes

Technical detail for writing Churn's code. Why things are done this way is
in [design.md](design.md); building, testing and the contribution process
are in [CONTRIBUTING.md](../CONTRIBUTING.md). *Verified* means we observed
it on a Mudi 7 with GL firmware 4.10.0 and a Quectel RG650V-EU modem;
*inferred* means not yet tested. [testing.md](testing.md) lists these claims
with the test planned for each on a real router.

## Connecting to the router

- Over USB-C the router is a CDC-ECM USB Ethernet device (USB ID
  `2c7c:0133`, no RNDIS) and hands out an address by DHCP (verified). The
  router is at `192.168.8.1` by default; the app lets the user change it.
- Android doesn't use a network without internet by default. The app finds
  the USB Ethernet network through `ConnectivityManager` and binds its
  sockets to it. A Pixel 9 Pro XL with GrapheneOS reached the router this way
  with airplane mode on and Wi-Fi off (verified); other phones are untested.
- Every socket needs Android's network permission, the cable's included.
  GrapheneOS lets the user turn it off per app (the Network permission), and
  then opening a socket fails with `EPERM` (verified on a Pixel 8). The app
  checks for this before it connects and asks the user to allow it.
- SSH as `root` with the router's admin password, using sshj. Show the host
  key fingerprint on first use and refuse a changed key unless the user
  accepts it again. Keep the password in the Android Keystore.
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
- Never send `AT+QUIMSUB` with a value, only `AT+QUIMSUB?`. blue-merle v2
  reports that setting it breaks the AT channel until reboot.

## AT commands (RG650V-EU)

| Purpose | Command | Status |
| --- | --- | --- |
| Radio state | `AT+CFUN?`: `1` on, `4` off | verified |
| Radio on | `AT+CFUN=1` | verified |
| Radio off | `AT+CFUN=4` | verified |
| SIM present | `AT+CPIN?`: `+CME ERROR: 10` = no SIM | verified |
| Subscription of slot 2's card | `AT+QUIMSUB?`: `2,"SUB2"`, also while slot 1 is the active slot | verified |
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

To shut the router down, the app runs `poweroff` over SSH (verified).

## uci settings

Read a value with `uci -q get <setting>`. A change needs `uci set` followed
by `uci commit <config>`, where `<config>` is the part before the first dot.
Before the commit, `uci get` already returns the new value, but
`/etc/config/<config>` still holds the old one (verified). GL's
`cellular_manager` switches airplane mode right after `uci set`, without a
commit (verified); for the other settings this is untested. The app still
commits every change, so it survives a reboot (inferred from OpenWrt's
`uci`, which keeps uncommitted changes in RAM).

| Setting | Value seen | Meaning | Status |
| --- | --- | --- | --- |
| `glconfig.general.airplane_mode` | `'0'` off, `'1'` on | GL's airplane mode. Writing it turns airplane mode on and off. Only `cellular_manager` and `gl_screen` read it | verified; after a boot with `'1'` the modem answers `+CFUN: 4` |
| `lpm.global.auto_shutdown_time` | `'30'`, set by us | Delay in seconds before GL's auto power-off. It fires only on battery with no cable or Wi-Fi client connected, and shutdown starts more than 20 s after the set delay, counted from when the display goes dark | verified |
| `gl_timer.reboot.enable` | `'0'` | GL's scheduled reboot, off | verified |
| `network.modem_cpu.dds_id` | `'1'` or `'2'` | The active SIM slot. GL rewrites it at every slot change, so it only reports the slot: writing it, even with a commit, switches nothing | verified |
| `network.modem_cpu.iccid` | The card's ICCID, a 19-digit one with a trailing `F` | The card GL uses | verified |
| `system.@system[0].log_file` | `'/etc/sys_log'` | The file that GL's `logread -f -F` writes the system log to, rotated to `/etc/sys_log.old` at `log_size` (`'128000'`, in KB). Without it, the log stays in `logd`'s buffer in RAM, `log_buffer_size` (`'64'`, in KB) | verified |
| `route_policy.@rule[<n>]` | `rule`, one section per tunnel, with `name='Tunnel 1'`, `via_type='wireguard'`, `enabled`, `killswitch` | A tunnel in GL's VPN dashboard | verified for one WireGuard tunnel |
| `route_policy.@rule[<n>].via` | `'wgclient1'` | The tunnel's network interface, `network.wgclient1` with `proto='wgclient'` | verified |
| `route_policy.@rule[0].killswitch` | `'1'` | Kill switch of the first VPN rule. Writing it turns it on and off | verified |
| `route_policy.@default[0].enabled` | `'0'` with Enhanced Kill Switch on, `'1'` off | GL's fallback policy, which sends traffic that matches no VPN rule past the VPN (`via='novpn'`, mark `0x8000`) | values verified, meaning inferred |
| `network.vpn_to_main` | absent with Enhanced Kill Switch on, present off | Routing rule that sends marked traffic (`mark='0x0/0xf000'`, `invert='1'`) to the main table, outside the VPN. `ip rule` lists it at priority 9000 only while the switch is off | values and `ip rule` verified, meaning inferred |

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
look like. Untested: a router without a tunnel, and whether turning a
tunnel off in GL's VPN dashboard changes its `enabled`.

Two settings the app needs are not in `uci` (verified by searching
`uci show`):

- GL's "Power On with Charger".
- Slot 2's choice between SIM 2 and the eSIM. GL keeps it in
  `/etc/config/cellular/slot_map.json`, and the modem reports it with
  `AT+QSIMTYPE="slot2"` (verified). The next section shows how to change
  it.

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
(inferred from the rules).

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
`restore.sh` deletes the file and reloads if the file or the chain
`churn_sim` is still there, then deletes the line in
`/etc/sysupgrade.conf`.

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
| No profile enabled | placeholder `89000123456789012341` | `001010123456` plus three digits (test network 001/01) |
| A profile enabled | the profile's ICCID | the profile's network |

Step 3 of the swap reads the card in the SIM 2 tray the same way, once
slot 2 is set to it (inferred; verified only for the eSIM). It derives
IMEIs only for a card with a real network's IMSI, not one on test network
001/01: an eSIM adapter card without an enabled profile may show a
placeholder ICCID like the built-in eSIM's, the same on every card
(inferred).

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
so the app compares ICCIDs without the `F`. The current card is the one
`network.modem_cpu.iccid` names, or at step 3 the one in slot 2.
`Router.clearHistory` clears the files in this order:

1. If `system.@system[0].log_file` is set, the app saves its value to
   `/etc/churn/log_file`, unless that file exists, deletes the setting,
   commits and runs `/etc/init.d/log reload`. The reload ends the
   `logread -f -F` that writes the file, and `logd` goes on with its
   buffer in RAM (verified). A file still open would keep its data on the
   flash after it is deleted.
2. It deletes both log files.
3. It rewrites both card files with only the current card's entry,
   through a new file renamed over the old one, and leaves a file that
   doesn't parse as it is. It deletes each eSIM record file that doesn't
   name the current card, and empties `log.txt`, which the eSIM manager
   goes on writing to (verified).
4. It fills the free space of the overlay, ext4 on the eMMC partition
   `mmcblk0p59` (2.9 GB). It sets
   `/sys/fs/ext4/mmcblk0p59/reserved_clusters`, the 4096 blocks that ext4
   keeps free even from root, to 0, and makes the directory
   `/overlay/churn-zeros`, the link `/etc/churn/zeros` to it, and 16
   directories `/overlay/churn-fill.d<n>`.
   It writes files `/overlay/churn-fill.<n>` of 64 MB from `/dev/zero`,
   with a `sync` after each, until `dd` fails on the full partition, then
   files of 4 KB into the directories, at most 300 in each, until `dd`
   fails again. Then it syncs, because the zeros of a file deleted before
   they are written out never reach the flash (verified on a disk image).
   Only then does it delete the 4 KB files and move the 64 MB files into
   `/overlay/churn-zeros`, numbered on from the files there, so the folder
   gains only flushed parts, and none from a run that stops. It syncs again
   and sets the reserve back. Last, it deletes files in
   `/overlay/churn-zeros`, lowest number first, until 512 MB of the
   partition are free. When a signal stops the run, a trap deletes the fill
   files, syncs, sets the reserve back and frees the 512 MB the same way; a
   reboot sets the reserve back too.

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
file system clean (verified on the images).

## After power-on

At every power-on the radio is on for about 12 s, whatever airplane mode
says, ending 58 s after power-on at the latest in our measurements
(design.md, section 6). The modem doesn't answer AT commands for a while
after power-on, so the app keeps asking. Step 3 of the swap changes nothing
before `/proc/uptime` shows 120 s and the modem answers `+CFUN: 4`.

## Still to find out

- Where GL stores "Power On with Charger". Until then the app asks the user
  to confirm that it is off.
- Whether `/etc/init.d/network reload` interrupts the cable connection or
  turns the radio on, and whether `fw4 reload` does.
- Whether the modem can tell that slot 1 holds a card, so the app can warn
  the user.
- Whether `logread` writes the log's file again once `restore.sh` has set
  `log_file` back and reloaded the log service.
- Whether GL copes with card files that name no card, as they can after a
  clearing at step 3, until it records the new card.

## Files on the router

| Path | Mode | Contents |
| --- | --- | --- |
| `/etc/churn/` | 700 | Churn's folder |
| `/etc/churn/README.txt` | 600 | What the folder is; how to restore the factory IMEIs by hand; to do so before a factory reset |
| `/etc/churn/secret` | 600 | 32 random bytes, the HMAC key |
| `/etc/churn/factory` | 600 | Factory IMEI 1 and IMEI 2, one per line |
| `/etc/churn/log_file` | 600 | GL's `system.@system[0].log_file` as it was before the app first deleted it. Only there after the app has deleted it |
| `/etc/churn/restore.sh` | 700 | Shipped in the app's assets. Takes the lock, turns airplane mode on and checks that the radio is off, writes the IMEIs from `factory`, reads them back and saves them, sets `log_file` back from Churn's copy and deletes the copy, deletes `/overlay/churn-zeros`, the link to it and any fill files, then removes Churn's firewall rules |
| `/etc/churn/zeros` | – | Link to `/overlay/churn-zeros`, made by the free-space overwrite |
| `/etc/nftables.d/churn.nft` | 644 | Churn's firewall rules, from `Router.firewallRules` |
| `/overlay/churn-zeros/` | – | Files of zeros from the free-space overwrite, up to 64 MB each and numbered, that take all but 512 MB of the free space. Beside the router's own files, so that upgrades and tools reading `/etc` skip them (design.md, section 5) |

At setup the app also adds the line `/etc/churn/` to `/etc/sysupgrade.conf`,
and with its firewall rules the line `/etc/nftables.d/churn.nft`. At every
connection it rewrites `README.txt` and `restore.sh` where they differ from
its assets.

## IMEI derivation

The algorithm is specified in design.md, section 4. Notes for implementing
it:

- **ICCID read from the modem:** keep the digits only. The modem pads a
  19-digit ICCID with a trailing `F` (verified), which is dropped.
- **Throwaway pair:** draw `s` with `SecureRandom.nextInt(n)`, which is
  unbiased, then build the serials as for a derived pair.
- **Luhn:** the standard algorithm. Example: `49015420323751` gets check
  digit `8`.

### Test vectors

All vectors use `secret` = bytes `00 01 02 … 1f` (32 bytes) and TAC
`35609021` for both IMEIs. The HMAC message is the ASCII string
`churn-imei-v1:` followed by the ICCID.

| ICCID | `d` | First words of `mac` | `s` | IMEI 1 | IMEI 2 |
| --- | --- | --- | --- | --- | --- |
| `8941000000000000015` | 7 | `27e4ba0f` = 669301263, accepted | 305946 | `356090213059463` | `356090213059539` |
| `8941000000000000015` | −7 | `27e4ba0f` = 669301263, accepted | 305946 | `356090213059539` | `356090213059463` |
| `8941000000000000015` | 0 | `27e4ba0f` = 669301263, accepted | 301263 | `356090213012637` | `356090213012637` |
| `8941000000000104627` | 7 | `ffff2de0` = 4294913504, rejected; `a8f98cb7` = 2834926775, accepted | 946613 | `356090219466134` | `356090219466209` |

For `d` = ±7 the rejection limit is 4,293,969,942 (`n` = 999,993); for
`d` = 0 it is 4,294,000,000. The full `mac` for the first ICCID is
`27e4ba0f49588b7f93391353f26f1e86cff6c7079bf5d1f1ebd9de4144bc63a4`.
