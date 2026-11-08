# Router tests

Churn relies on many claims about the Mudi 7, its firmware and the phone.
This document lists them, one per row, with the test that is to confirm
each one on a real router, so that a new GL firmware, or a claim that was
wrong all along, shows up before a user depends on it. The claims come
from [design.md](design.md) and [development.md](development.md).

Status: being built. The app runs the tier 1 checks, and debug builds
have tier 2's dry run and removal and clearing tests, tier 3's power cycle
test and tier 4's leak watch; the rest of tier 4 is done by hand. The
app's unit tests run in CI against a
simulated Mudi 7 (`FakeRouter`) built from these claims, so a claim that
turns out wrong makes the simulation wrong too. *Verified* means we
observed it by hand on a Mudi 7 with GL firmware 4.10.0 and a Quectel
RG650V-EU modem; *inferred* means not yet tested; *open* means one of the
open questions in design.md or development.md.

Each claim has an ID, a letter for its section and a number, such as M3.
The app's checks, commit messages and pull requests name claims by their
IDs, and a unit test keeps the checks' claims, tiers and *blocks* marks in
step with this document. An ID stays with its claim: a new claim takes the
next number in its section, and the number of a removed claim isn't used
again.

## Tiers

The tests run in the app, over the same cable and SSH connection as a
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
- The app runs every tier 1 check once the router screen shows, so that
  connecting takes no longer, and the checks marked *blocks* again before
  steps 1 and 3. Router checks, on the router screen, lists each check as
  passed, failed or not applicable, with the IDs of its claims, and while
  the checks run, how many are done. While the debug log is on, the
  results are a section of it too. The app sends them nowhere by itself.
  The check of H6 keeps a note on the phone from one connection to the
  next: the size of GL's eSIM manager's log and an MD5 sum of its first
  64 bytes, which the app forgets when it clears the SIM history.
- A failed check shows a note on the router screen. A check marked
  *blocks* also stops steps 1 and 3 when it fails, before they change
  slot 2, because the app's commands can't be trusted then; the step's
  screen lists the checks that failed. On firmware we haven't tested, a
  swap goes ahead if every blocking check passes, with a note on the
  step's screen that the firmware is untested.
- Tier 1 runs for every user. Tiers 2 and 3, and tier 4's leak watch,
  start in debug builds; tier 2 may later be offered to users. Tiers 3 and
  4 are for the maintainer.
- A test that clears the SIM history writes about 0.5 GB to the flash,
  or 2.9 GB without the zero files (development.md, "Clearing the SIM
  history"), and a test that runs `restore.sh` deletes the zero files and
  Churn's record of the card. So both run once for each firmware, not with
  every dry run, and the clearing comes after `restore.sh`. The dry run
  itself clears nothing and doesn't run `restore.sh`: step 3 with an empty
  tray stops before the clearing.

## New firmware

The firmware and modem versions we tested are a list in the app,
`TESTED_PLATFORMS` in `Checks.kt`, which the check of P1 and the note at
steps 1 and 3 compare with. A version joins the list, in a one-line
change, once it has passed tiers 1 to 3 and the tier 4 items the release
could affect. When GL releases a new firmware:

1. Before upgrading, connect with the app once, with the debug log on, so
   that it records the checks before the upgrade.
2. Upgrade with "keep settings", connect, and read Router checks, in
   particular those of Churn's folder, its firewall rules, the log's file
   and the IMEIs.
3. Run the dry run and tier 3, then the removal and clearing tests, then
   the tier 4 items the release could affect.
4. Add the version to the list.

## Dry run

Debug builds have a Dry run button on the router screen. It runs steps 1
and 3 of a swap with the swap's own code on a router with no SIM, both
trays empty and no profile enabled on the built-in eSIM, as far as the
steps go without one. With no SIM there is nothing to register, and the
dry run confirms the tier 2 claims that only a change of the router's
settings can:

1. It keeps slot 2's setting, the active slot and the IMEIs on the phone,
   to put them back at the end.
2. Step 1 up to the shutdown: the scheduled reboot, the VPN and the checks
   marked *blocks*, airplane mode on and the radio off, and slot 2 on the
   built-in eSIM. A profile enabled there stops the dry run, which doesn't
   disable it, since Churn couldn't enable it again.
3. Step 3 up to the card: the VPN again, the router started, the checks
   again, the radio off, the SIM 1 tray empty, and the Enhanced Kill
   Switch and Churn's firewall rules in place. Both reload even if the
   switch is on and the rules are in place, and the connection and the
   radio's state across each reload show whether it drops the cable or
   turns the radio on (K8, D19).
4. Slot 1 made the active slot, so that step 3 makes slot 2 the active
   slot again, as on a router new to Churn, and `dds_id` follows both
   switches (U6).
5. Slot 2 switched to the SIM 2 tray and made the active slot, where no
   card may answer, nor the built-in eSIM (E4). A card in the tray, an
   eSIM adapter card without an enabled profile too, stops the dry run.
6. Where step 3 writes a card's pair, a random test pair with the TACs
   and the spacing of the factory IMEIs written and read back, and then
   the earlier pair, each with the radio still off after it (M6).
7. Airplane mode, the radio and both IMEIs read 20 times in a row while
   GL's services run, every answer checked (P5).
8. Slot 2 switched back to the built-in eSIM, and its setting and the
   active slot put back (S2, S3).

Like a swap, the dry run leaves airplane mode and the Enhanced Kill Switch
on and Churn's firewall rules in place. A dry run that stops puts the
router back at once if the radio is still off. Otherwise its screen shows
at the next connection in place of the router screen, until Put the router
back has done so, once the router has started and its radio is off. The
screen lists what the dry run did and, if it stopped, the claims it was
confirming. The debug log keeps every command.

## Removal and clearing

Debug builds also have a Removal and clearing button on the router screen,
for the tier 2 claims that only running `restore.sh` and a clearing can
confirm. `restore.sh` deletes the zero files, so that the test's first
clearing fills all of the flash's free space, about 2.9 GB, and the test
runs once for each firmware, after the dry run. Like the dry run, it needs a
router with no SIM and no profile enabled on the built-in eSIM:

1. Step 1 up to the shutdown, as in the dry run, and the SIM 1 tray
   empty, so that a power-on before the router is set up again registers
   nowhere.
2. It keeps on the phone what setting the router up again takes: the
   factory IMEIs, Churn's record of the card, the IMEIs the router has,
   and whether Churn's firewall rules are in place.
3. It runs `restore.sh`, as Remove Churn does, which prints its last line
   only if it ran to its end (F12).
4. It checks what `restore.sh` did:
   - the factory IMEIs back, with the radio still off (F7);
   - Churn's folder, the card's record in it and the folder's line in
     `/etc/sysupgrade.conf` gone (F8, F12);
   - GL's `log_file` setting as Churn's copy had it (F9), with a `logread`
     writing that file again (H3);
   - the zero files and their link gone, and their space free (F10);
   - and Churn's firewall rules, their chains and their line in
     `/etc/sysupgrade.conf` gone (F11).
5. It reads the ICCIDs and IMSIs that GL's card files, the eSIM manager's
   records and log, and GL's system log name, with what `logread` wrote
   since `restore.sh`. It keeps them in memory only, out of the screen and
   the debug log.
6. It sets the router up again as the setup screen does, with the card's
   record, the IMEIs it had and Churn's firewall rules, and keeps the
   recorded card's SIM history (F12).
7. It clears the SIM history as step 3 does, keeping the recorded card's.
   With the zero files gone, the overwrite fills all of the free space and
   prints nothing for minutes, and the clearing has to end on the same SSH
   connection (H17). It then checks:
   - that ext4's reserve is as before the clearing (H13);
   - that GL's card files and the eSIM manager's records parse (H14);
   - that about 512 MB are free, with the zero files' folder holding the
     rest of the free space and no fill files left (H15);
   - and that the raw partition holds no copy of the IMSIs (H10) and ICCIDs
     (H11) from step 5 that no file holds any more, those of the earlier
     cards. It reads the partition from the flash, after dropping the
     router's block cache, which takes a few minutes, and a read that finds
     none of the numbers that files hold counts as failed. If files hold
     none, as on a router never swapped since setup, whose clearing keeps no
     card's numbers, H10 and H11 stay unconfirmed.
8. It clears the SIM history again, now with the zero files in place, as
   at every swap after the first. By ext4's `lifetime_write_kbytes`, the
   clearing has to write about as much as the free space it found, not
   the zero files' space again (H15), and it has to take under a minute
   (H16).

Like step 1, the test leaves airplane mode on and slot 2 on the built-in
eSIM. A removal test that stops before it set the router up again does so at
once if the radio is still off. Otherwise its screen shows at the next
connection in place of the router screen, until Set the router up again has
done so, once the router has started and its radio is off. The screen lists
what the test did and, if it stopped, the claims it was confirming. The
debug log keeps every command.

From `restore.sh` until the router is set up again, GL's log goes to the
flash, as before Churn, and names any card used since the router started.
Setting the router up again turns that off and deletes the file, and the
test's first clearing overwrites what was written.

## Power cycles

Debug builds also have a Power cycles button on the router screen, for
the tier 3 claims that only a power-on can confirm. The test shuts the
router down twice, and the user switches it on again each time. It runs
at home with both trays empty, and with slot 2 on the empty SIM 2 tray,
where the router emitted nothing at power-on (B8):

1. It keeps slot 2's setting, the active slot and the IMEIs on the phone,
   to put them back at the end.
2. Step 3 up to the card, as in the dry run but without its reloads: the
   VPN, the router started, the checks marked *blocks*, the radio off, the
   SIM 1 tray empty, and the Enhanced Kill Switch and Churn's firewall
   rules in place.
3. Slot 2 switched to the SIM 2 tray and made the active slot, where no
   card may answer. A card in the tray, an eSIM adapter card without an
   enabled profile too, stops the test before the router shuts down.
4. Right before the power-off, a random test pair with the TACs and the
   spacing of the factory IMEIs written, saved and read back, with the
   radio still off (M7).
5. It keeps the router's boot ID and the test pair on the phone and sends
   `poweroff`. The phone has to lose the router's network within 120 s,
   and the test times it from the command (B4, B5). Whether the router's
   screen had gone dark by then, the user sees.
6. Once the user has switched the router on again and it is on the cable,
   the boot ID has to be new (B4, B6), and GL's settings still have
   airplane mode and the Enhanced Kill Switch on (U3). From then until the
   router has started, 120 s after power-on, the test reads `/proc/uptime`
   and `AT+CFUN?` every 2 s, and the Enhanced Kill Switch's routing rules
   and Churn's firewall chains until they are in place. Both have to be in
   place by then, and the lines say when the test first saw them (K10,
   D14). The modem is to leave the first reads unanswered (B1), then
   report its radio on until 58 s after power-on at the latest (B2), and
   off from then on (B3). A modem that doesn't answer by then, or a radio
   on later, stops the test; if the phone reaches the router only once the
   modem answers, or once the radio is off, B1 or B2 stays unconfirmed.
   The router then has to have the test pair (M7).
7. It writes, saves and reads back the earlier pair, shuts the router down
   a second time, as in step 5, and checks the next power-on as in step 6,
   with the earlier pair.
8. With no card answering in either tray, it sets airplane mode off and on
   again without the commit, and times how long the modem takes to report
   its radio on and then off, at most 30 s each (U2). It then commits
   airplane mode on, as before.
9. It puts slot 2's setting and the active slot back.

Like a swap, the test leaves airplane mode and the Enhanced Kill Switch on
and Churn's firewall rules in place. While the router is off, the test's
screen waits in place of the router screen, and goes on once the router is
on the cable again, or puts the router back instead. A test that stops
puts the router back at once if the radio is still off. Otherwise its
screen shows at the next connection, until Put the router back has done
so, once the router has started and its radio is off. The screen lists
what the test did and, if it stopped, the claims it was confirming. The
debug log keeps every command.

## Leak watch

Debug builds also have a Leak watch button on the router screen, for the
tier 4 claims about going online. The test turns airplane mode off in the
user's place at step 4 of a swap, so it runs where step 4 may happen, once
the router has been switched on again there (rule 1):

1. It commits airplane mode on if it isn't, and checks the router as step
   3 does up to the card: the VPN, the router started, the radio off, the
   checks marked *blocks* and the SIM 1 tray empty. Unlike step 3, it
   waits for the radio before the checks: on a router that was online, the
   radio goes off a moment after the commit, and until then U1 fails.
2. The card that step 3 last gave its IMEIs, as Churn's folder records
   it, has to answer in the SIM 2 tray, with slot 2 on it as the active
   slot, and the router has to have the card's IMEIs. The Enhanced Kill
   Switch and Churn's firewall rules have to be in place, and the router
   may have no default route other than over the SIM: another uplink could
   carry WireGuard's packets instead. Anything else stops the test before
   airplane mode goes off.
3. It adds a table of its own to the router's firewall, `inet
   churn_watch`, in place of any that an earlier watch left, with counters
   of the packets that try to leave over the SIM, before Churn's rules, and
   of those that got past them, by IP version. A reload of GL's firewall
   changes only GL's table, so it keeps them (inferred), and a power-off
   deletes them.
4. Still in airplane mode, it reads the packets that each `rmnet_data*`
   interface sent, the counters, with the bytes that WireGuard sent to its
   peers right before and right after them, and the fragments that the
   router made of packets since it started, from `/proc/net/snmp` and
   `/proc/net/snmp6`. It also reads when WireGuard last completed a
   handshake and when chrony last updated the clock, which are times of
   day, so the debug log leaves them out.
5. It turns airplane mode off with GL's own call, as GL's screen does
   (U10), and reads the same every 2 s, until a minute after WireGuard's
   new handshake, however long the connection takes, and until the clock
   has synced. Every packet that got past Churn's rules passed the
   counters before it left, so the interfaces can't have sent more, but
   for the fragments that the router made of such packets, which they
   count one by one. More than that, packets that Churn's rules didn't
   see, stops the test (D11).
6. With each read it also compares the bytes that got past Churn's rules,
   without the IP and UDP headers of WireGuard's packets, with those that
   WireGuard sent. WireGuard counts a packet once the firewall has passed
   it on, so its counts before and after the counters bound what they can
   hold of its packets. More bytes than that got past the rules, packets
   with WireGuard's mark that aren't WireGuard's, and fewer than WireGuard
   sent, as its packets then didn't all get past the rules over the SIM,
   stop the test. When WireGuard sent nothing while a read was under way,
   the two have to match exactly (D9). A WireGuard interface made anew
   starts its count again, so the test then compares from the next such
   read on.
7. WireGuard has to complete a new handshake, and chrony has to update
   the clock from one of its servers, which with Churn's rules it reaches
   only through the tunnel, within 5 min of airplane mode going off (D16).
8. It deletes its table and leaves the router online, as step 4 does.

The lines say by how many seconds after airplane mode went off the test
saw the handshake and the clock's update, how many packets left over the
SIM while it watched, how many Churn's rules dropped, and how many bytes
WireGuard had sent by the last read during which it sent nothing. If the
router made fragments meanwhile, or one of the SIM's interfaces went away
or was made anew, D11 stays unconfirmed, and if no read matched
WireGuard's bytes exactly, D9; the lines say so. A test that stops once
airplane mode is off commits it on again and deletes its table. The
screen lists what the test did and, if it stopped, the claims it was
confirming. The debug log keeps every command. D16 after a reboot, a
device's website and the rest of tier 4 stay with the user.

## Platform

From development.md, "Sending AT commands", and design.md, section 6.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| P1 | GL firmware 4.10.0 (OpenWrt 23.05.4), modem Quectel RG650V-EU | verified | Read `/etc/glversion`, `/etc/openwrt_release` and `ATI`, and compare with the versions we tested | 1 |
| P2 | The firmware has `flock`, `curl`, `ubus`, `fw4` and `nft` | verified | `command -v` for each | 1, blocks |
| P3 | The firmware has `md5sum`, which the debug log uses | inferred | `command -v md5sum` | 1 |
| P4 | AT commands work through `gl_modem -B cpu AT` | verified | `AT+CFUN?` answers, ending with `OK` | 1, blocks |
| P5 | `gl_modem` keeps Churn's commands apart from those of GL's daemons | inferred | Read the radio and both IMEIs 20 times in a row while GL's services run, and check every answer | 2 |

## Phone and cable

From development.md, "Connecting to the router", and design.md, sections 3
and 7. The app's connection itself confirms C1 to C4 in tier 1, before any
check runs, so they have no check of their own.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| C1 | Over USB-C the router is a USB Ethernet device and hands out an address | verified | The app finds the cable's network and connects | 1 |
| C2 | SSH is on and accepts `root` with the admin password | verified | The app logs in | 1 |
| C3 | Phones other than the Pixels we used reach the router this way | open | A connection from such a phone answers it for that phone | 1 |
| C4 | On GrapheneOS, without the Network permission every socket fails with `EPERM`, the cable's included | verified | The app tries a socket before it connects | 1 |
| C5 | Android reports the router's network gone as soon as the cable comes out | inferred | Pull the cable while step 3 waits for the router to finish starting: the app says at once that the router left the cable | 4 |
| C6 | Android reports the router's network as a new one each time the cable goes in or the router starts, so the app connects by itself once each time | inferred | With the password saved, plug the cable in on the Connect screen, then pull it and plug it in again | 4 |
| C7 | On GrapheneOS, the uninstall dialog offers to keep the app's data, as `android:hasFragileUserData` asks | inferred | Uninstall the app, keeping its data, and reinstall it: the router screen still lists the place cards, and the app connects without asking for the password | 4 |

## Modem

From development.md, "AT commands (RG650V-EU)", and design.md, section 6.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| M1 | `AT+CFUN?` answers `1` with the radio on and `4` with it off | verified | The answer parses | 1, blocks |
| M2 | `AT+EGMR=0,7` and `AT+EGMR=0,11` return IMEI 1 and IMEI 2 | verified | Both are 15 digits with a valid check digit and the factory IMEIs' TAC | 1, blocks |
| M3 | `AT+QSIMTYPE="slot2"` answers `0` for the SIM 2 tray and `1` for the eSIM | verified | The answer parses | 1, blocks |
| M4 | `AT+QUIMSUB?` answers `2,"SUB2"` for slot 2's card | verified | The answer parses | 1 |
| M5 | No modem setting starts the radio off at power-on | verified | The test commands `AT+CFUN=?`, `AT+QCFG=?` and `AT+QNWCFG=?` list no setting beyond those we searched | 1 |
| M6 | Both IMEIs can be written and read back at once | verified | Write a test pair and read it back, then write the earlier pair back | 2 |
| M7 | `AT+QPRTPARA=1` saves the written IMEIs; without it, a reboot 5 s after a write brought the earlier IMEIs back | verified | Write and save a test pair, reboot and read it; then the earlier pair the same way | 3 |
| M8 | `AT+QPRTPARA=1` also overwrites the modem's backup of its settings | inferred | None known: we know no command that reads the backup | – |

The app doesn't send `AT+CFUN=1`, `AT+CFUN=4`, `AT+CPIN?`, `AT+QCCID`,
`AT+GSN`, `AT+CGSN` or `AT+QUIMSLOT`, so development.md's claims about
them need no test. It never sends `AT+QUIMSUB` with a value, which
blue-merle v2 reports breaks the AT channel until a reboot.

## uci settings

From development.md, "uci settings".

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| U1 | `glconfig.general.airplane_mode` is `0` or `1`, and with `1` the modem answers `+CFUN: 4` once the router has started | verified | Read both once uptime is past 120 s | 1, blocks |
| U2 | Setting `glconfig.general.airplane_mode` turns airplane mode on or off at once, before the commit | verified | Turn airplane mode off and on again, and time how long the modem takes to answer `+CFUN: 4` | 3 |
| U3 | `uci commit` makes a change survive a reboot | inferred | Read airplane mode and the kill switch settings after a reboot | 3 |
| U4 | `gl_timer.reboot.enable` is GL's scheduled reboot | verified | The setting exists and is `0` or `1` | 1, blocks |
| U5 | `network.modem_cpu.iccid` holds the ICCID of the card GL uses, a 19-digit one with a trailing `F` | verified | It is an ICCID or absent | 1 |
| U6 | `network.modem_cpu.dds_id` reports the active slot, and writing it switches nothing | verified | It is `1` or `2` (tier 1) and follows a slot switch (tier 2) | 1, blocks; 2 |
| U7 | GL's auto power-off fires only on battery with nothing connected, more than 20 s after the set delay | verified | Unplug everything and time it | 4 |
| U8 | "Power On with Charger" is not in `uci`, and with it off a charger doesn't switch the router on | verified | Plug in a charger with the router off | 4 |
| U9 | Where GL keeps "Power On with Charger" | open | None until it's found; the user confirms the setting | – |
| U10 | GL's screen turns airplane mode off with `ubus call cellular.modem set_airplane_mode '{"enable":false}'`, which commits the setting too, and the router then goes online sooner than after a commit of the setting alone, after which nothing tried to leave over the SIM for a minute | verified for the call and the commit; sooner seen by hand | `ubus monitor` and `uci changes glconfig` while turning airplane mode off on the screen; the leak watch's handshake time | 4 |

## Kill switch and VPN

From development.md, "uci settings", and design.md, sections 7 and 10.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| K1 | A WireGuard tunnel is a `route_policy` section of type `rule` with `via_type='wireguard'` whose `via` names a network interface | verified for one tunnel | `route_policy` has its `@default` section and such a `rule` section | 1, blocks |
| K2 | GL's `rule_process` sections are GL's policies for its own processes, not tunnels | inferred | None known | – |
| K3 | The VPN check also works on a router without a tunnel | open | Set up such a router | 4 |
| K4 | GL sets up an OpenVPN tunnel with routing and DNS rules like a WireGuard one's, so the app could accept it too | open | Set up an OpenVPN tunnel and compare its settings, rules and lookups | 4 |
| K5 | The Enhanced Kill Switch is on when `route_policy.@default[0].enabled` is `0`, `network.vpn_to_main` is absent and `ip rule show` lists no rule at priority 9000 | values verified, meaning inferred | Read both settings and the rules | 1 |
| K6 | With the switch on and a tunnel up, `ip rule show` lists the rules to table 1001 and to the main table, and the blackhole rules at priorities 9910 and 9920 | verified | Compare the rules with this list | 1, blocks |
| K7 | The USB-C port `ecm0` is in `br-lan`, so the blackhole at priority 9920 covers a device on it | port verified, coverage inferred | `ip link show ecm0` | 1 |
| K8 | Turning the switch on takes `/etc/init.d/network reload`, which neither drops the cable nor turns the radio on | open | The connection and `+CFUN: 4` across a reload (tier 2); the RF meter (tier 4) | 2, 4 |
| K9 | With the switch on and the tunnel disconnected, nothing gets through, also with the tunnel's own Kill Switch off | verified | Disconnect the tunnel and load a website on a device | 4 |
| K10 | The switch also blocks traffic while the router boots and when a tunnel fails on its own | open | The blocking rules are in place after a boot, and when the app first saw them (tier 3); traffic during a boot and through a failing tunnel (tier 4) | 3, 4 |

## DNS and the router's own traffic

From development.md, "uci settings" and "Churn's firewall rules", and
design.md, sections 7 and 10.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| D1 | The main dnsmasq listens on port 53 and asks the cellular interface's servers; the tunnel's own one listens on port 2153 and asks the VPN's DNS server | verified | The listening ports and the servers in their settings | 1 |
| D2 | GL's nftables rules redirect devices' lookups marked for the tunnel to port 2153 and drop lookups sent past the router without the mark `0x8000` | verified | The rules are present | 1 |
| D3 | With the tunnel up, devices' lookups go through the VPN; with it down, they get no answer | verified | A lookup of a made-up name from a device | 4 |
| D4 | Without Churn's firewall rules, for about 2 s after airplane mode goes off, the main dnsmasq passes devices' lookups on over the SIM | verified | Watch what leaves over the cellular interface after airplane mode goes off | 4 |
| D5 | Without Churn's firewall rules, what the router sends itself goes over the SIM, past both kill switches: the rule at priority 1 points to an empty table 16800, and the main table's default route goes over `rmnet_data0` | rules verified, traffic inferred | `ip rule show` and `ip route show` | 1 |
| D6 | Without Churn's firewall rules, the router itself reaches the VPN server and its time servers over the SIM, looks up one time server's name at Google when it goes online, and GL's connection check pings four public addresses | verified | Chrony's time servers and the addresses that GL's connection check pings over the SIM are those we watched (tier 1); watch the cellular interface (tier 4) | 1, 4 |
| D7 | GL's firewall loads `/etc/nftables.d/churn.nft` into its table `inet fw4`, and `fw4 reload` exits with 0 despite its warnings | verified, and checked by the app | With the file in place, all three chains are listed; the app checks the same, and the exit status, after each reload it starts | 1, blocks |
| D8 | The SIM's data interfaces match `rmnet_data*`, online and in airplane mode | verified | The interfaces in `/sys/class/net` and the one `ifstatus modem_cpu` names | 1, blocks |
| D9 | WireGuard sends with the mark `0x8000`, and on output no other packets carry it | verified | `wg show all fwmark` (tier 1); the leak watch's counts against WireGuard's (tier 4) | 1, 4 |
| D10 | The main dnsmasq runs as the user ID the rules name | verified, and checked by the app | `id -u dnsmasq` answers, and Churn's rules, while on the router, name the same user ID | 1 |
| D11 | With the rules, nothing but WireGuard leaves over the SIM, from devices too, also with the Enhanced Kill Switch off and the tunnel down, and over IPv6 as over IPv4 | verified for devices over IPv4 with the switch off and the tunnel down; going online and IPv6 inferred | The drop counter grows and a device's website fails (tier 4); the leak watch's counts after airplane mode goes off (tier 4) | 4 |
| D12 | With the rules, the router's own traffic leaves through the tunnel from the VPN's address, and its lookups go to the VPN's DNS server | verified | A request and a lookup from the router, watched on the tunnel interface | 4 |
| D13 | The tunnel's IP Masquerading translates the router's own packets to the tunnel's address; without it, the VPN server wouldn't accept them | translation verified, without it inferred | The setting `masq` of the tunnel's WireGuard peer, by its name the IP Masquerading, is on (tier 1); a request from the router with it off (tier 4) | 1, 4 |
| D14 | GL's firewall loads Churn's rules at boot before the modem goes online | open | The chains are listed after a boot, and when the app first saw them (tier 3); what leaves over the SIM during a boot with a card (tier 4) | 3, 4 |
| D15 | A rules file that GL's firewall rejects might keep it from loading any rules at the next boot, which is why the app removes such a file | inferred | None: a test could leave the router without a firewall | – |
| D16 | With the rules, the tunnel comes up after a reboot and after airplane mode off and on, and the clock then syncs through it | verified | Reboot and turn airplane mode off and on; the handshake, the clock and a website. The leak watch checks the first two after airplane mode goes off | 4 |
| D17 | With the rules, GL still treats the cellular connection as connected, though its connection check can't ping over the SIM | verified with an earlier version of the rules | GL's connection status after airplane mode goes off | 4 |
| D18 | With the rules, devices work alike over Wi-Fi, the Ethernet port and USB-C, and an uplink over Ethernet or Wi-Fi works as without the rules | inferred | A website from a device on each; the router's own traffic with an Ethernet uplink | 4 |
| D19 | `fw4 reload` neither drops the cable nor turns the radio on | open | The connection and `+CFUN: 4` across a reload (tier 2); the RF meter (tier 4) | 2, 4 |

## Slot switching

From development.md, "Switching SIM slots", and design.md, section 6.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| S1 | `get_feature_config` has exactly one key whose entry holds `failover`, the modem's `location_id` | verified | Exactly one key holds `failover`, with only letters, digits and `_`, which the app requires | 1, blocks |
| S2 | `set_feature_config` sets slot 2 to the tray or the eSIM and makes slot 2 the active slot, answers `"ok": true`, and the new state shows at once in `AT+QSIMTYPE="slot2"` and `dds_id` | verified | Switch slot 2 to the tray, make it the active slot, and switch it back to the eSIM | 2 |
| S3 | Both switches work in airplane mode, with the modem answering `+CFUN: 4` before and after | verified | `+CFUN: 4` after each switch | 2 |
| S4 | Both switches emit nothing | verified | RF meter | 4 |
| S5 | Changing the active slot moves only the data connection; a card in the other slot stays registered while the radio is on | inferred | Cards in both slots, radio on | 4 |

## Built-in eSIM

From development.md, "eSIM profiles", and design.md, section 6.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| E1 | Slot 2's card answers `AT+ICCID` and `AT+CIMI` through `ubus call modem.CPU.AT get_result_AT` with `sub_id` 2 | verified for the eSIM | Both answer while slot 2 is on the eSIM | 1, blocks |
| E2 | A 19-digit ICCID comes with a trailing `F` | verified | The answer's format | 1 |
| E3 | With no profile enabled, slot 2 shows the placeholder ICCID `89000123456789012341` and a 001/01 IMSI | verified | Read both while slot 2 is on the eSIM, as after step 1; neither comes with another | 1 |
| E4 | The chip answers only while slot 2 is set to the eSIM | inferred | With slot 2 on the empty tray, the placeholder doesn't show | 2 |
| E5 | GL's eSIM manager listens on port 3456, and its firewall rule `block_lpa_port` keeps devices from reaching it, which would otherwise let them change profiles without a login | verified | The listening port, and the rule's settings as we saw them | 1 |
| E6 | Posting `disable` to GL's eSIM manager from the router's shell disables the enabled profile without a login, answers `code` 200, and the placeholder shows within about 5 s, with airplane mode and `+CFUN: 4` holding | verified | Enable GL's pre-installed profile in airplane mode, then run step 1. Tier 4, because a power-on with a profile enabled registers wherever it happens | 4 |
| E7 | A request sent as soon as the radio is off can leave a profile that was online enabled | verified | Start step 1 several times with slot 2 on the eSIM and its profile online, and count the "still enabled" lines | 4 |
| E8 | A request at least 35 s after one that left the profile enabled disables it | verified | In those runs, step 1 passes after a "still enabled" line | 4 |
| E9 | The chip enables at most one profile at a time | inferred | Enable a second profile and check that the first one is disabled | 4 |
| E10 | A card in the SIM 2 tray answers `AT+ICCID` and `AT+CIMI` like the eSIM | verified with an eSIM adapter card | Step 3 with a card in the tray | 4 |
| E11 | A 9eSIM adapter card with no profile enabled shows the ICCID `89111111111111111111` and the IMSI `111111111111111` | verified | Read both with such a card in the tray and slot 2 on it | 4 |
| E12 | Other eSIM adapter cards with no profile enabled, other brands' included, also show an IMSI starting with 0 or 1. The app tells such a card by its IMSI alone, since adapter cards' placeholder ICCIDs can differ by brand | inferred | Read the IMSI of other adapter cards with no profile enabled, in the tray with slot 2 on them | 4 |
| E13 | The router screen names such a card as an eSIM adapter card with no profile enabled, and step 3 stops at it as at an empty tray | inferred | The router screen with such a card in the tray and slot 2 on it, and step 3 with such a card | 4 |
| E14 | No real network's country code starts with 0 or 1 | inferred | The ITU's list of country codes | – |
| E15 | Deleting GL's pre-installed profile can't be undone without a factory reset, as blue-merle v2 reports | inferred | None: the app never deletes a profile | – |

## Power-on

From development.md, "After power-on", and design.md, section 6.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| B1 | The modem doesn't answer AT commands for a while after power-on | verified | Poll `/proc/uptime` and `AT+CFUN?` from the moment the app reconnects | 3 |
| B2 | At every power-on the modem reports its radio on (`+CFUN: 1`) for about 12 s whatever airplane mode says, ending 58 s after power-on at the latest | verified | The polling above: `+CFUN: 1` while the modem answers that early, then the first `+CFUN: 4` | 3 |
| B3 | 120 s after power-on, with `+CFUN: 4`, is a safe point for step 3 to change slot 2 | inferred | The end of the window above, on each firmware | 3 |
| B4 | `poweroff` over SSH shuts the router down | verified | The power cycle test: the phone loses the router's network, and the boot ID is new once the router is on again | 3 |
| B5 | After `poweroff` the phone loses the router's network within 120 s, and only once the router is off | open | The power cycle test times it from the command, while the user watches the router's screen go dark | 3 |
| B6 | `/proc/sys/kernel/random/boot_id` holds a UUID that is new at every power-on | inferred | Read it: a UUID in Linux's format (tier 1); read it again after a reboot (tier 3) | 1, blocks; 3 |
| B7 | The router emits nothing while it shuts down in airplane mode, with slot 2 on the eSIM or on a card in the tray | verified | RF meter | 4 |
| B8 | With both trays empty and slot 2 on the SIM 2 tray, a power-on emits nothing, with airplane mode on or off | verified | RF meter | 4 |
| B9 | A card in slot 2 emits for more than 8 s at every power-on while slot 2 is set to it, whatever airplane mode and GL's switches say, slot 1 selected included | verified with an eSIM profile | RF meter | 4 |
| B10 | The built-in eSIM emits one or two short bursts at power-on even with no profile enabled | verified | RF meter | 4 |
| B11 | An eSIM adapter card in the tray stays off at power-on while slot 2 is on the eSIM, and switching slot 2 to it afterwards in airplane mode emits nothing | verified | RF meter | 4 |
| B12 | A normal SIM card in the tray stays off at power-on the same way | verified | RF meter | 4 |
| B13 | Switching slot 2 to a normal SIM card afterwards in airplane mode emits nothing | inferred | RF meter during step 3 | 4 |
| B14 | A card in slot 1 emits for more than 8 s at every power-on | verified | RF meter | 4 |
| B15 | The modem tells whether the SIM 1 tray holds a card while slot 2 is the active slot, also in airplane mode | verified | Step 3 with a card in the SIM 1 tray stops before anything changes, and goes on once the card is out | 4 |
| B16 | With no profile enabled, the eSIM shows GSMA TS.48's generic test profile, IMSI `001010123456063` | verified | Read the IMSI while slot 2 is on the eSIM with the placeholder ICCID, as after step 1 | 1 |
| B17 | Every unit shows that test profile | inferred | None: it needs a second router | – |
| B18 | The eSIM's short bursts carry no IMEI over LTE and 5G, where a device gives it only once the network has authenticated it, which no network can do for the test profile | inferred | None known: it needs the network's side | – |
| B19 | Once the modem answers after power-on, it repeats each AT command before its answer for a while | verified about 70 s after a power-on, and gone 146 s after it | None needed: the app leaves the repeated command out of every answer, and a unit test runs every check with and without it | – |

## SIM history

From development.md, "Clearing the SIM history", and design.md, sections 7
and 10.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| H1 | GL writes its system log to the file `system.@system[0].log_file` names, and the log pairs cards' ICCIDs with their IMSIs and names the cells they used | verified | The setting and a running `logread -f -F` (tier 1); the log's lines (tier 4) | 1, 4 |
| H2 | Deleting `log_file`, committing and reloading the log service ends the `logread` that writes the file | verified | No `logread -f` process afterwards | 1 |
| H3 | Setting `log_file` back and reloading the log service makes `logread` write the file again | open | Run `restore.sh`, check the process and the file; setting the router up again turns the file off | 2 |
| H4 | GL's card files and the eSIM manager's records have the formats development.md lists | verified | Both card files and the eSIM manager's records parse, and every member name and `iccid` in them is an ICCID | 1 |
| H5 | GL's eSIM manager writes its log to `/root/esim/log.txt`, beginning each line with the time in UTC | verified for the file; inferred for UTC | The time that the log's last line with a time begins with is within 2 minutes of when the log was last written (`date -r`) | 1 |
| H6 | The eSIM manager's log only grows until something empties it | inferred | At each connection, the log is at least as long as at the last one and begins as it did, unless the app cleared it since | 1 |
| H7 | GL doesn't add back pruned cards, also not when airplane mode goes off and on | verified | The card files after airplane mode off and on | 4 |
| H8 | GL copes with card files that name no card until it records the new one | inferred | Step 3 with a new card, then a power-on and airplane mode off | 4 |
| H9 | A card used again after a clearing gets GL's defaults, and GL's search finds its APN | verified with the built-in eSIM's profile | Use a cleared card again | 4 |
| H10 | After a clearing, the raw partition holds no IMSI of an earlier card | verified | Before the removal test's first clearing, read the earlier cards' numbers from GL's files and logs, keeping them only in memory; afterwards, count them on the raw partition and in its files | 2 |
| H11 | After a clearing, the raw partition holds no ICCID of an earlier card either | verified | Before the removal test's first clearing, read the earlier cards' numbers from GL's files and logs, keeping them only in memory; afterwards, count them on the raw partition and in its files | 2 |
| H12 | The overlay is ext4 on `mmcblk0p59`, 2.9 GB, and ext4 keeps 4096 blocks back from it in `reserved_clusters` | verified | The overlay's line in `/proc/mounts` and its `reserved_clusters` file | 1, blocks |
| H13 | The overwrite puts ext4's reserve back at its end, and a trap does when a signal stops it | verified; the trap on a disk image | `reserved_clusters` before and after the removal test's first clearing | 2 |
| H14 | GL's files stay valid while the overwrite fills the partition | verified once | GL's card files and the eSIM manager's records parse after the removal test's first clearing | 2 |
| H15 | The overwrite leaves all but 512 MB of the free space in `/overlay/churn-zeros`, so the next one fills only those 512 MB | verified on disk images | The free space and the folder after the removal test's first clearing; `lifetime_write_kbytes` in `/sys/fs/ext4/mmcblk0p59/` before and after its second | 2 |
| H16 | With the zero files in place, an overwrite takes under a minute | inferred from the first overwrite's speed | Time the removal test's second clearing | 2 |
| H17 | The SSH connection stays open through the overwrite, which prints nothing for minutes | inferred | The removal test's first clearing, which fills about 2.9 GB | 2 |
| H18 | The flash rates its own wear, and reported under 10% used on our router after our tests | verified | `life_time` and `pre_eol_info` in `/sys/block/mmcblk0/device/`, which the debug log records at every connection; the check also fails once the flash warns that its life or its spare blocks are running out | 1 |
| H19 | GL writes files in `/etc` every minute, so with the log's file off, the clock after a power-on still resumes from about the time of the power-off | verified with the SIM online | The files in `/etc` that something other than Churn wrote in the last 2 minutes, from 2 minutes after a power-on, in airplane mode too | 1 |

## Churn's files and firmware upgrades

From development.md, "Files on the router", and design.md, section 5.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| F1 | `/etc/churn/` and its files have the modes and contents development.md lists | contents verified by the app, modes inferred | Modes, owners and the records' sizes, and that only root may read any other file there; the app itself rewrites `README.txt` and `restore.sh` at every connection where they differ from its copies | 1, blocks |
| F2 | `/etc/churn/` is listed in `/etc/sysupgrade.conf` | verified by the app | The line is there | 1, blocks |
| F3 | With the firewall rules, `/etc/nftables.d/churn.nft` is listed in `/etc/sysupgrade.conf` | inferred | The line is there | 1 |
| F4 | `/etc/churn/zeros` links to `/overlay/churn-zeros`, and `du`, `grep -r`, `cp` and the `find` and `tar` that an upgrade runs take only the link | verified with BusyBox 1.36.1 on a PC | After a clearing, `readlink` gives the link's target, `du -sk /etc/churn` counts under 1 MB, and `sysupgrade -l` lists the link but no zero file | 1 |
| F5 | GL's firmware never writes the IMEIs itself, also not at an upgrade or a factory reset | open | At every connection, while the recorded card answers in slot 2, the IMEIs are its recorded pair (tier 1); after an upgrade, they are the ones from before it (tier 3) | 1, 3 |
| F6 | A firmware upgrade with "keep settings" keeps `/etc/churn/`, the link `zeros` in it included, Churn's firewall rules and the log's file off | open | Upgrade, then check the folder, the link, the rules and `log_file` | 3 |
| F7 | `restore.sh` writes, reads back and saves the factory IMEIs, with the radio off | inferred | Run it, then read the IMEIs and the radio, and write the earlier IMEIs back (tier 2); read the IMEIs after a reboot (tier 3) | 2, 3 |
| F8 | `restore.sh` then deletes Churn's record of the card | inferred | Run it, then check that `/etc/churn/card` is gone, with the folder (tier 2) | 2 |
| F9 | `restore.sh` then sets GL's `log_file` back from Churn's copy, and deletes the copy | inferred | Run it, then compare the setting with the copy read before; the copy goes with the folder (tier 2) | 2 |
| F10 | `restore.sh` then deletes `/overlay/churn-zeros`, the link to it and any fill files | inferred | Run it, then check that the folder, any fill files and the link are gone, and that the free space grew by what they took, less 16 MB for what GL writes meanwhile (tier 2) | 2 |
| F11 | `restore.sh` then removes Churn's firewall rules and their line in `/etc/sysupgrade.conf` | inferred | Run it, then check that the chains, the file and the line are gone (tier 2) | 2 |
| F12 | `restore.sh` last deletes `/etc/churn/` and its line in `/etc/sysupgrade.conf`, and still runs to its end | running to its end verified with BusyBox 1.36.1 on a PC, the rest inferred | Run it, then check that the folder and the line are gone and that it printed its last message, and write the folder back (tier 2) | 2 |
| F13 | A factory reset deletes `/etc/churn/`, Churn's firewall rules and its zero files | inferred | Factory reset. It also resets GL's settings, so it is the last test to run | 4 |

## Swaps

From design.md, sections 4 and 7.

| ID | Claim | Status | Check | Tier |
| --- | --- | --- | --- | --- |
| W1 | While step 3 asks about a card, slot 2 is back on the built-in eSIM; after the answer it switches to the tray again and finds the same card, with `+CFUN: 4` throughout | inferred | Step 3 with a new card, answered after a minute | 4 |
| W2 | Canceling at step 3's question leaves slot 2 on the built-in eSIM, so the card stays off at the next power-on | inferred | Cancel, then switch the router on with an RF meter | 4 |
| W3 | A place card swapped back in gets the IMEIs it had at its earlier visits | inferred | Swap a place card out and back in, and compare the IMEIs on the router screen | 4 |
| W4 | Step 4 says to destroy the card taken out, unless it is a place card or still in SIM 2 | inferred | Swap a travel card for a place card and back, and once keep the card | 4 |
| W5 | Step 1 says that steps 1 to 3 can run at home or at work only while the router has its factory IMEIs | inferred | Begin a swap before the first swap and after it, and cancel it before Start | 4 |
| W6 | A swap runs as usual when it starts with the SIM 2 tray empty, as rule 7 asks once the router has been switched off | inferred | Take the card out with the router off, switch it on and swap in a new card | 4 |
| W7 | The router screen names the card in SIM 2: a place card by its name or digits, the recorded travel card by its digits, and with slot 2 on the built-in eSIM says the card is off | inferred | Open the router screen after step 4 with a place card and with a travel card, and after canceling at step 3's question | 4 |

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
