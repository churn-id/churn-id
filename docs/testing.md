# Router tests

Churn relies on many claims about the Mudi 7, its firmware and the phone.
This document lists them, one per row, with the test that is to confirm
each one on a real router, so that a new GL firmware, or a claim that was
wrong all along, shows up before a user depends on it. The claims come
from [design.md](design.md) and [development.md](development.md).

Status: plan. No test on a real router exists yet. The app's unit tests
run in CI against a simulated Mudi 7 (`FakeRouter`) built from these
claims, so a claim that turns out wrong makes the simulation wrong too.
*Verified* means we observed it by hand on a Mudi 7 with GL firmware
4.10.0 and a Quectel RG650V-EU modem; *inferred* means not yet tested;
*open* means one of the open questions in design.md or development.md.

## Tiers

The tests will run in the app, over the same cable and SSH connection as a
swap, with the same code. They come in four tiers, by what a test may do
to the router:

| Tier | What a test may do | Where it runs |
| --- | --- | --- |
| 1 | Read only | Whenever the app is connected: at every connection and before steps 1 and 3 of a swap |
| 2 | Change settings and put them back, with the radio off and no reboot | At home, with no SIM in the router, airplane mode on and a WireGuard VPN set up, which steps 1 and 3 ask for |
| 3 | Also turn the radio on and reboot the router | At home, with both trays empty and slot 2 on the empty SIM 2 tray |
| 4 | Anything that needs a person: an RF meter, a real SIM, or a SIM online | At a place that doesn't matter (rule 1) |

- Every test keeps the swap's rules: it changes nothing unless airplane
  mode is on and the modem answers `+CFUN: 4`, checks the radio again after
  each change, and stops at anything unexpected. Tier 3 is the exception
  for the radio: with both trays empty and slot 2 on the empty SIM 2 tray,
  the router emitted nothing at power-on, with airplane mode on or off
  (design.md, section 6).
- A tier 2 or 3 test saves its progress like a swap, so an interrupted
  test is finished at the next connection, and it ends with the router as
  it was.
- A tier 1 check marked *blocks* stops a swap when it fails, because the
  app's commands can't be trusted then. The other checks show a note.
  On firmware we haven't tested, a swap goes ahead if every blocking check
  passes, with a note that the firmware is untested.
- Tier 1 runs for every user. Tiers 2 and 3 start in debug builds; tier 2
  may later be offered to users. Tiers 3 and 4 are for the maintainer.
- A test that clears the SIM history writes about 0.5 GB to the flash,
  or 2.9 GB without the zero files (development.md, "Clearing the SIM
  history"), and a test that runs `restore.sh` deletes the zero files and
  Churn's record of the card. So both run once for each firmware, not with
  every dry run, and the clearing comes after `restore.sh`. The dry run
  itself clears nothing and doesn't run `restore.sh`: step 3 with an empty
  tray stops before the clearing.

## Platform

From development.md, "Sending AT commands", and design.md, section 6.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| GL firmware 4.10.0 (OpenWrt 23.05.4), modem Quectel RG650V-EU | verified | Read `/etc/glversion`, `/etc/openwrt_release` and `ATI`, and compare with the versions we tested | 1 |
| The firmware has `flock`, `curl`, `ubus`, `fw4` and `nft` | verified | `command -v` for each | 1, blocks |
| AT commands work through `gl_modem -B cpu AT` | verified | `AT+CFUN?` answers, ending with `OK` | 1, blocks |
| `gl_modem` keeps Churn's commands apart from those of GL's daemons | inferred | Read the radio and both IMEIs many times in a row while GL's services run, and check every answer | 2 |

## Phone and cable

From development.md, "Connecting to the router", and design.md, sections 3
and 7.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| Over USB-C the router is a USB Ethernet device and hands out an address | verified | The app finds the cable's network and connects | 1 |
| SSH is on and accepts `root` with the admin password | verified | The app logs in | 1 |
| Phones other than the Pixels we used reach the router this way | open | A connection from such a phone answers it for that phone | 1 |
| On GrapheneOS, without the Network permission every socket fails with `EPERM`, the cable's included | verified | The app tries a socket before it connects | 1 |
| Android reports the router's network gone as soon as the cable comes out | inferred | Pull the cable while step 3 waits for the router to finish starting: the app says at once that the router left the cable | 4 |
| Android reports the router's network as a new one each time the cable goes in or the router starts, so the app connects by itself once each time | inferred | With the password saved, plug the cable in on the Connect screen, then pull it and plug it in again | 4 |
| On GrapheneOS, the uninstall dialog offers to keep the app's data, as `android:hasFragileUserData` asks | inferred | Uninstall the app, keeping its data, and reinstall it: the router screen still lists the place cards, and the app connects without asking for the password | 4 |

## Modem

From development.md, "AT commands (RG650V-EU)", and design.md, section 6.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| `AT+CFUN?` answers `1` with the radio on and `4` with it off | verified | The answer parses | 1, blocks |
| `AT+EGMR=0,7` and `AT+EGMR=0,11` return IMEI 1 and IMEI 2 | verified | Both are 15 digits with a valid check digit and the factory IMEIs' TAC | 1, blocks |
| `AT+QSIMTYPE="slot2"` answers `0` for the SIM 2 tray and `1` for the eSIM | verified | The answer parses | 1, blocks |
| `AT+QUIMSUB?` answers `2,"SUB2"` for slot 2's card | verified | The answer parses | 1 |
| No modem setting starts the radio off at power-on | verified | The test commands `AT+CFUN=?`, `AT+QCFG=?` and `AT+QNWCFG=?` list the same settings as when we searched | 1 |
| Both IMEIs can be written and read back at once | verified | Write a test pair and read it back, then write the earlier pair back | 2 |
| `AT+QPRTPARA=1` saves the written IMEIs; without it, a reboot 5 s after a write brought the earlier IMEIs back | verified | Write and save a test pair, reboot and read it; then the earlier pair the same way | 3 |
| `AT+QPRTPARA=1` also overwrites the modem's backup of its settings | inferred | None known: we know no command that reads the backup | – |

The app doesn't send `AT+CFUN=1`, `AT+CFUN=4`, `AT+CPIN?`, `AT+QCCID`,
`AT+GSN`, `AT+CGSN` or `AT+QUIMSLOT`, so development.md's claims about
them need no test. It never sends `AT+QUIMSUB` with a value, which
blue-merle v2 reports breaks the AT channel until a reboot.

## uci settings

From development.md, "uci settings".

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| `glconfig.general.airplane_mode` is `0` or `1`, and with `1` the modem answers `+CFUN: 4` once the router has started | verified | Read both once uptime is past 120 s | 1, blocks |
| Setting `glconfig.general.airplane_mode` turns airplane mode on or off at once, before the commit | verified | Turn airplane mode off and on again, and time how long the modem takes to answer `+CFUN: 4` | 3 |
| `uci commit` makes a change survive a reboot | inferred | Read airplane mode and the kill switch settings after a reboot | 3 |
| `gl_timer.reboot.enable` is GL's scheduled reboot | verified | The setting exists and is `0` or `1` | 1, blocks |
| `network.modem_cpu.iccid` holds the ICCID of the card GL uses, a 19-digit one with a trailing `F` | verified | It is an ICCID or absent | 1 |
| `network.modem_cpu.dds_id` reports the active slot, and writing it switches nothing | verified | It is `1` or `2` (tier 1) and follows a slot switch (tier 2) | 1, blocks; 2 |
| GL's auto power-off fires only on battery with nothing connected, more than 20 s after the set delay | verified | Unplug everything and time it | 4 |
| "Power On with Charger" is not in `uci`, and with it off a charger doesn't switch the router on | verified | Plug in a charger with the router off | 4 |
| Where GL keeps "Power On with Charger" | open | None until it's found; the user confirms the setting | – |

## Kill switch and VPN

From development.md, "uci settings", and design.md, sections 7 and 10.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| A WireGuard tunnel is a `route_policy` section of type `rule` with `via_type='wireguard'` whose `via` names a network interface | verified for one tunnel | `route_policy` has its `@default` section and such a `rule` section | 1, blocks |
| GL's `rule_process` sections are GL's policies for its own processes, not tunnels | inferred | None known | – |
| The VPN check also works on a router without a tunnel | open | Set up such a router | 4 |
| GL sets up an OpenVPN tunnel with routing and DNS rules like a WireGuard one's, so the app could accept it too | open | Set up an OpenVPN tunnel and compare its settings, rules and lookups | 4 |
| The Enhanced Kill Switch is on when `route_policy.@default[0].enabled` is `0`, `network.vpn_to_main` is absent and `ip rule show` lists no rule at priority 9000 | values verified, meaning inferred | Read both settings and the rules | 1 |
| With the switch on and a tunnel up, `ip rule show` lists the rules to table 1001 and to the main table, and the blackhole rules at priorities 9910 and 9920 | verified | Compare the rules with this list | 1, blocks |
| The USB-C port `ecm0` is in `br-lan`, so the blackhole at priority 9920 covers a device on it | port verified, coverage inferred | `ip link show ecm0` | 1 |
| Turning the switch on takes `/etc/init.d/network reload`, which neither drops the cable nor turns the radio on | open | The connection and `+CFUN: 4` across a reload (tier 2); the RF meter (tier 4) | 2, 4 |
| With the switch on and the tunnel disconnected, nothing gets through, also with the tunnel's own Kill Switch off | verified | Disconnect the tunnel and load a website on a device | 4 |
| The switch also blocks traffic while the router boots and when a tunnel fails on its own | open | The blocking rules are in place right after a boot (tier 3); traffic during a boot and through a failing tunnel (tier 4) | 3, 4 |

## DNS and the router's own traffic

From development.md, "uci settings" and "Churn's firewall rules", and
design.md, sections 7 and 10.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| The main dnsmasq listens on port 53 and asks the cellular interface's servers; the tunnel's own one listens on port 2153 and asks the VPN's DNS server | verified | The listening ports and the servers in their settings | 1 |
| GL's nftables rules redirect devices' lookups marked for the tunnel to port 2153 and drop lookups sent past the router without the mark `0x8000` | verified | The rules are present | 1 |
| With the tunnel up, devices' lookups go through the VPN; with it down, they get no answer | verified | A lookup of a made-up name from a device | 4 |
| Without Churn's firewall rules, for about 2 s after airplane mode goes off, the main dnsmasq passes devices' lookups on over the SIM | verified | Watch what leaves over the cellular interface after airplane mode goes off | 4 |
| Without Churn's firewall rules, what the router sends itself goes over the SIM, past both kill switches: the rule at priority 1 points to an empty table 16800, and the main table's default route goes over `rmnet_data0` | rules verified, traffic inferred | `ip rule show` and `ip route show` | 1 |
| Without Churn's firewall rules, the router itself reaches the VPN server and its time servers over the SIM, looks up one time server's name at Google when it goes online, and GL's connection check pings four public addresses | verified | The enabled services and their targets are unchanged (tier 1); watch the cellular interface (tier 4) | 1, 4 |
| GL's firewall loads `/etc/nftables.d/churn.nft` into its table `inet fw4`, and `fw4 reload` exits with 0 despite its warnings | verified, and checked by the app | All three chains are listed after a reload | 1, blocks |
| The SIM's data interfaces match `rmnet_data*`, online and in airplane mode | verified | The interfaces in `/sys/class/net` and the one `ifstatus modem_cpu` names | 1, blocks |
| WireGuard sends with the mark `0x8000`, and on output no other packets carry it | verified | `wg show all fwmark` (tier 1); the rules' counters across airplane mode off and on (tier 4) | 1, 4 |
| The main dnsmasq runs as the user ID the rules name | verified, and checked by the app | `id -u dnsmasq` | 1 |
| With the rules, nothing but WireGuard leaves over the SIM, from devices too, also with the Enhanced Kill Switch off and the tunnel down, and over IPv6 as over IPv4 | verified for devices over IPv4 with the switch off and the tunnel down; going online and IPv6 inferred | The drop counter grows and a device's website fails (tier 4); watch the cellular interface after airplane mode goes off (tier 4) | 4 |
| With the rules, the router's own traffic leaves through the tunnel from the VPN's address, and its lookups go to the VPN's DNS server | verified | A request and a lookup from the router, watched on the tunnel interface | 4 |
| The tunnel's IP Masquerading translates the router's own packets to the tunnel's address; without it, the VPN server wouldn't accept them | translation verified, without it inferred | The setting is on, once we know where GL keeps it (tier 1); a request from the router with it off (tier 4) | 1, 4 |
| GL's firewall loads Churn's rules at boot before the modem goes online | open | The chains are listed right after a boot (tier 3); what leaves over the SIM during a boot with a card (tier 4) | 3, 4 |
| A rules file that GL's firewall rejects might keep it from loading any rules at the next boot, which is why the app removes such a file | inferred | None: a test could leave the router without a firewall | – |
| With the rules, the tunnel comes up after a reboot and after airplane mode off and on, and the clock then syncs through it | verified | Reboot and turn airplane mode off and on; the handshake, the clock and a website | 4 |
| With the rules, GL still treats the cellular connection as connected, though its connection check can't ping over the SIM | verified with an earlier version of the rules | GL's connection status after airplane mode goes off | 4 |
| With the rules, devices work alike over Wi-Fi, the Ethernet port and USB-C, and an uplink over Ethernet or Wi-Fi works as without the rules | inferred | A website from a device on each; the router's own traffic with an Ethernet uplink | 4 |
| `fw4 reload` neither drops the cable nor turns the radio on | open | The connection and `+CFUN: 4` across a reload (tier 2); the RF meter (tier 4) | 2, 4 |

## Slot switching

From development.md, "Switching SIM slots", and design.md, section 6.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| `get_feature_config` has exactly one key whose entry holds `failover`, the modem's `location_id` | verified | Read it | 1, blocks |
| `set_feature_config` sets slot 2 to the tray or the eSIM and makes slot 2 the active slot, answers `"ok": true`, and the new state shows at once in `AT+QSIMTYPE="slot2"` and `dds_id` | verified | Switch slot 2 to the tray, make it the active slot, and switch it back to the eSIM | 2 |
| Both switches work in airplane mode, with the modem answering `+CFUN: 4` before and after | verified | `+CFUN: 4` after each switch | 2 |
| Both switches emit nothing | verified | RF meter | 4 |
| Changing the active slot moves only the data connection; a card in the other slot stays registered while the radio is on | inferred | Cards in both slots, radio on | 4 |

## Built-in eSIM

From development.md, "eSIM profiles", and design.md, section 6.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| Slot 2's card answers `AT+ICCID` and `AT+CIMI` through `ubus call modem.CPU.AT get_result_AT` with `sub_id` 2 | verified for the eSIM | Both answer while slot 2 is on the eSIM | 1, blocks |
| A 19-digit ICCID comes with a trailing `F` | verified | The answer's format | 1 |
| With no profile enabled, slot 2 shows the placeholder ICCID `89000123456789012341` and a 001/01 IMSI | verified | Read both after step 1 | 1 |
| The chip answers only while slot 2 is set to the eSIM | inferred | With slot 2 on the empty tray, the placeholder doesn't show | 2 |
| GL's eSIM manager listens on port 3456, and its firewall rule `block_lpa_port` keeps devices from reaching it, which would otherwise let them change profiles without a login | verified | The listening port and the rule | 1 |
| Posting `disable` to GL's eSIM manager from the router's shell disables the enabled profile without a login, answers `code` 200, and the placeholder shows within about 5 s, with airplane mode and `+CFUN: 4` holding | verified | Enable GL's pre-installed profile in airplane mode, then run step 1. Tier 4, because a power-on with a profile enabled registers wherever it happens | 4 |
| A request sent as soon as the radio is off can leave a profile that was online enabled | verified | Start step 1 several times with slot 2 on the eSIM and its profile online, and count the "still enabled" lines | 4 |
| A request at least 35 s after one that left the profile enabled disables it | verified | In those runs, step 1 passes after a "still enabled" line | 4 |
| The chip enables at most one profile at a time | inferred | Enable a second profile and check that the first one is disabled | 4 |
| A card in the SIM 2 tray answers `AT+ICCID` and `AT+CIMI` like the eSIM | verified with an eSIM adapter card | Step 3 with a card in the tray | 4 |
| A 9eSIM adapter card with no profile enabled shows the ICCID `89111111111111111111` and the IMSI `111111111111111` | verified | Read both with such a card in the tray and slot 2 on it | 4 |
| Every 9eSIM adapter card shows that ICCID with no profile enabled | inferred | A second 9eSIM adapter card | 4 |
| The router screen names such a card as an eSIM adapter card with no profile enabled, and step 3 stops at it as at an empty tray | inferred | The router screen with such a card in the tray and slot 2 on it, and step 3 with such a card | 4 |
| No real network's country code starts with 0 or 1 | inferred | The ITU's list of country codes | – |
| Deleting GL's pre-installed profile can't be undone without a factory reset, as blue-merle v2 reports | inferred | None: the app never deletes a profile | – |

## Power-on

From development.md, "After power-on", and design.md, section 6.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| The modem doesn't answer AT commands for a while after power-on | verified | Poll `/proc/uptime` and `AT+CFUN?` from the moment the app reconnects | 3 |
| At every power-on the modem reports its radio on (`+CFUN: 1`) for about 12 s whatever airplane mode says, ending 58 s after power-on at the latest | verified | The polling above: `+CFUN: 1` while the modem answers that early, then the first `+CFUN: 4` | 3 |
| 120 s after power-on, with `+CFUN: 4`, is a safe point for step 3 to change slot 2 | inferred | The end of the window above, on each firmware | 3 |
| `poweroff` over SSH shuts the router down | verified | Steps 1 and 3 of a swap, or a tier 3 test that ends with it | 3 |
| After `poweroff` the phone loses the router's network within 120 s, and only once the router is off | open | Steps 1 and 3 of a swap, watching the router's screen: the app shows the next step about when the screen goes dark | 3 |
| `/proc/sys/kernel/random/boot_id` holds a UUID that is new at every power-on | inferred | Read it (tier 1); read it again after a reboot (tier 3) | 1, blocks; 3 |
| The router emits nothing while it shuts down in airplane mode, with slot 2 on the eSIM or on a card in the tray | verified | RF meter | 4 |
| With both trays empty and slot 2 on the SIM 2 tray, a power-on emits nothing, with airplane mode on or off | verified | RF meter | 4 |
| A card in slot 2 emits for more than 8 s at every power-on while slot 2 is set to it, whatever airplane mode and GL's switches say, slot 1 selected included | verified with an eSIM profile | RF meter | 4 |
| The built-in eSIM emits one or two short bursts at power-on even with no profile enabled | verified | RF meter | 4 |
| An eSIM adapter card in the tray stays off at power-on while slot 2 is on the eSIM, and switching slot 2 to it afterwards in airplane mode emits nothing | verified | RF meter | 4 |
| A normal SIM card in the tray stays off at power-on the same way | verified | RF meter | 4 |
| Switching slot 2 to a normal SIM card afterwards in airplane mode emits nothing | inferred | RF meter during step 3 | 4 |
| A card in slot 1 emits for more than 8 s at every power-on | verified | RF meter | 4 |
| The modem tells whether the SIM 1 tray holds a card while slot 2 is the active slot, also in airplane mode | verified | Step 3 with a card in the SIM 1 tray stops before anything changes, and goes on once the card is out | 4 |
| With no profile enabled, the eSIM shows GSMA TS.48's generic test profile, IMSI `001010123456063` | verified | Read the IMSI after step 1 | 1 |
| Every unit shows that test profile | inferred | None: it needs a second router | – |
| The eSIM's short bursts carry no IMEI over LTE and 5G, where a device gives it only once the network has authenticated it, which no network can do for the test profile | inferred | None known: it needs the network's side | – |

## SIM history

From development.md, "Clearing the SIM history", and design.md, sections 7
and 10.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| GL writes its system log to the file `system.@system[0].log_file` names, and the log pairs cards' ICCIDs with their IMSIs and names the cells they used | verified | The setting and a running `logread -f -F` (tier 1); the log's lines (tier 4) | 1, 4 |
| Deleting `log_file`, committing and reloading the log service ends the `logread` that writes the file | verified | No `logread -f` process afterwards | 1 |
| Setting `log_file` back and reloading the log service makes `logread` write the file again | open | Run `restore.sh`, check the process and the file; setting the router up again turns the file off | 2 |
| GL's card files and the eSIM manager's records have the formats development.md lists | verified | Both card files parse, and every member name and `iccid` in them is an ICCID | 1 |
| GL doesn't add back pruned cards, also not when airplane mode goes off and on | verified | The card files after airplane mode off and on | 4 |
| GL copes with card files that name no card until it records the new one | inferred | Step 3 with a new card, then a power-on and airplane mode off | 4 |
| A card used again after a clearing gets GL's defaults, and GL's search finds its APN | verified with the built-in eSIM's profile | Use a cleared card again | 4 |
| After a clearing, the raw partition holds no IMSI of an earlier card | verified | Before the clearing, read the earlier cards' numbers from GL's files and log, keeping them only in memory; afterwards, count them on the raw partition and in its files | 2 |
| After a clearing, the raw partition holds no ICCID of an earlier card either | verified | Before the clearing, read the earlier cards' numbers from GL's files and log, keeping them only in memory; afterwards, count them on the raw partition and in its files | 2 |
| The overlay is ext4 on `mmcblk0p59`, 2.9 GB, and ext4 keeps 4096 blocks back from it in `reserved_clusters` | verified | The overlay's line in `/proc/mounts` and its `reserved_clusters` file | 1, blocks |
| The overwrite puts ext4's reserve back at its end, and a trap does when a signal stops it | verified; the trap on a disk image | Read `reserved_clusters` after a clearing | 2 |
| GL's files stay valid while the overwrite fills the partition | verified once | GL's JSON files parse after a clearing | 2 |
| The overwrite leaves all but 512 MB of the free space in `/overlay/churn-zeros`, so the next one fills only those 512 MB | verified on disk images | The free space and the folder after a clearing; `lifetime_write_kbytes` in `/sys/fs/ext4/mmcblk0p59/` before and after the next | 2 |
| With the zero files in place, an overwrite takes under a minute | inferred from the first overwrite's speed | Time a clearing from the router screen | 2 |
| The SSH connection stays open through the overwrite, which prints nothing for minutes | inferred | Clear the history from the router screen | 2 |
| The flash rates its own wear, and reported under 10% used on our router after our tests | verified | `life_time` and `pre_eol_info` in `/sys/block/mmcblk0/device/`, recorded at every connection | 1 |
| GL writes files in `/etc` every minute, so with the log's file off, the clock after a power-on still resumes from about the time of the power-off | verified with the SIM online | The files written in `/etc` within a minute, in airplane mode too | 1 |

## Churn's files and firmware upgrades

From development.md, "Files on the router", and design.md, section 5.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| `/etc/churn/` and its files have the modes and contents development.md lists | contents verified by the app, modes inferred | Modes and sizes; the app itself rewrites `README.txt` and `restore.sh` at every connection where they differ from its copies | 1, blocks |
| `/etc/churn/` is listed in `/etc/sysupgrade.conf` | verified by the app | The line is there | 1, blocks |
| With the firewall rules, `/etc/nftables.d/churn.nft` is listed in `/etc/sysupgrade.conf` | inferred | The line is there | 1 |
| `/etc/churn/zeros` links to `/overlay/churn-zeros`, and `du`, `grep -r`, `cp` and the `find` and `tar` that an upgrade runs take only the link | verified with BusyBox 1.36.1 on a PC | After a clearing, `ls -l /etc/churn` shows the link and `du -sh /etc` stays small; `sysupgrade -l` lists the link but no zero file | 1 |
| GL's firmware never writes the IMEIs itself, also not at an upgrade or a factory reset | open | At every connection, the IMEIs are the recorded pair of the card in slot 2 (tier 1, which the app already does); after an upgrade, they are the ones from before it (tier 3) | 1, 3 |
| A firmware upgrade with "keep settings" keeps `/etc/churn/`, the link `zeros` in it included, Churn's firewall rules and the log's file off | open | Upgrade, then check the folder, the link, the rules and `log_file` | 3 |
| `restore.sh` writes, reads back and saves the factory IMEIs, with the radio off | inferred | Run it, then write the earlier IMEIs back (tier 2); read the IMEIs after a reboot (tier 3) | 2, 3 |
| `restore.sh` then deletes Churn's record of the card | inferred | Run it, then check that `/etc/churn/card` is gone (tier 2) | 2 |
| `restore.sh` then sets GL's `log_file` back from Churn's copy, and deletes the copy | inferred | Run it, then check the setting and the copy (tier 2) | 2 |
| `restore.sh` then deletes `/overlay/churn-zeros`, the link to it and any fill files | inferred | Run it, then check that the folder and the link are gone and the free space is back (tier 2) | 2 |
| `restore.sh` then removes Churn's firewall rules and their line in `/etc/sysupgrade.conf` | inferred | Run it, then check that the chains, the file and the line are gone (tier 2) | 2 |
| `restore.sh` last deletes `/etc/churn/` and its line in `/etc/sysupgrade.conf`, and still runs to its end | running to its end verified with BusyBox 1.36.1 on a PC, the rest inferred | Run it, then check that the folder and the line are gone and that it printed its last message, and write the folder back (tier 2) | 2 |
| A factory reset deletes `/etc/churn/`, Churn's firewall rules and its zero files | inferred | Factory reset. It also resets GL's settings, so it is the last test to run | 4 |

## Swaps

From design.md, sections 4 and 7.

| Claim | Status | Check | Tier |
| --- | --- | --- | --- |
| While step 3 asks about a card, slot 2 is back on the built-in eSIM; after the answer it switches to the tray again and finds the same card, with `+CFUN: 4` throughout | inferred | Step 3 with a new card, answered after a minute | 4 |
| Canceling at step 3's question leaves slot 2 on the built-in eSIM, so the card stays off at the next power-on | inferred | Cancel, then switch the router on with an RF meter | 4 |
| A place card swapped back in gets the IMEIs it had at its earlier visits | inferred | Swap a place card out and back in, and compare the IMEIs on the router screen | 4 |
| Step 4 says to destroy the card taken out, unless it is a place card or still in SIM 2 | inferred | Swap a travel card for a place card and back, and once keep the card | 4 |
| Step 1 says that steps 1 to 3 can run at home or at work only while the router has its factory IMEIs | inferred | Begin a swap before the first swap and after it, and cancel it before Start | 4 |
| A swap runs as usual when it starts with the SIM 2 tray empty, as rule 7 asks once the router has been switched off | inferred | Take the card out with the router off, switch it on and swap in a new card | 4 |
| The router screen names the card in SIM 2: a place card by its name or digits, the recorded travel card by its digits, and with slot 2 on the built-in eSIM says the card is off | inferred | Open the router screen after step 4 with a place card and with a travel card, and after canceling at step 3's question | 4 |

## Beyond the router

Some claims no test on the router can confirm:

- Which IMEI the network sees for a card in slot 2 (design.md,
  section 10). Only the carrier's side shows it.
- Whether real Mudi 7 serials fall in a narrow range. It needs the IMEIs
  of many units.
- That a GrapheneOS phone with its eSIM profile disabled transmits
  nothing (rule 2). It is a measurement of the phone, not the router.
- Whether the VPN server, which is the same on every SIM, links the SIMs
  (design.md, section 10). It depends on what the carrier and the VPN
  provider can match, not on the router.
- What the flash chips keep beneath the partition after the overwrite
  (design.md, section 7). Only reading the chips themselves would show it.
- The legal and distribution questions in design.md, section 10.
