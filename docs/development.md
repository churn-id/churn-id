# Development notes

Technical detail for writing Churn's code. Why things are done this way is
in [design.md](design.md); building, testing and the contribution process
are in [CONTRIBUTING.md](../CONTRIBUTING.md). *Verified* means we observed
it on a Mudi 7 with GL firmware 4.10.0 and a Quectel RG650V-EU modem;
*inferred* means not yet tested.

## Connecting to the router

- Over USB-C the router is a CDC-ECM USB Ethernet device (USB ID
  `2c7c:0133`, no RNDIS) and hands out an address by DHCP (verified). The
  router is at `192.168.8.1` by default; the app lets the user change it.
- Android doesn't use a network without internet by default. The app finds
  the USB Ethernet network through `ConnectivityManager` and binds its
  sockets to it. A Pixel 9 Pro XL with GrapheneOS reached the router this way
  with airplane mode on and Wi-Fi off (verified); other phones are untested.
- SSH as `root` with the router's admin password, using sshj or a maintained
  JSch fork. Show the host key fingerprint on first use and refuse a changed
  key unless the user accepts it again. Keep the password in the Android
  Keystore.

## Sending AT commands

- Send every command through GL's tool, as GL's own scripts do:
  `gl_modem -B cpu AT '<command>'` (verified; `cpu` is what GL's
  `get_modem_bus` returns on the Mudi 7). Commands for the built-in eSIM
  are the exception; see [eSIM profiles](#esim-profiles).
- Don't open the modem's device nodes directly. Every AT channel in use
  (`/dev/smd7`, `smd8`, `smd9`, `smd11`, `/dev/at_mdm0`) is held by a GL or
  Quectel daemon (verified).
- Run each modem operation under `flock` on `/var/lock/churn.lock`:
  `exec 9>/var/lock/churn.lock && flock 9 && <command>`. The lock keeps
  Churn's own operations apart; `gl_modem` arbitrates against GL's daemons
  (inferred).
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
| Write IMEI 1 | `AT+EGMR=1,7,"<15 digits>"` | verified, survives reboot |
| Write IMEI 2 | `AT+EGMR=1,11,"<15 digits>"` | verified, survives reboot |
| ICCID | `AT+QCCID`: a 19-digit ICCID comes with a trailing `F` | verified |
| Slot select | `AT+QUIMSLOT` | rejected by this modem |
| Persist modem settings | `AT+QPRTPARA=1` | sent by blue-merle v2 after writes; our writes persisted without it |

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
| `route_policy.@rule[0].killswitch` | `'1'` | Kill switch of the first VPN rule. Writing it turns it on and off | verified |
| `route_policy.@default[0].enabled` | `'0'` with Enhanced Kill Switch on, `'1'` off | GL's fallback policy, which sends traffic that matches no VPN rule past the VPN (`via='novpn'`, mark `0x8000`) | values verified, meaning inferred |
| `network.vpn_to_main` | absent with Enhanced Kill Switch on, present off | Routing rule that sends marked traffic (`mark='0x0/0xf000'`, `invert='1'`) to the main table, outside the VPN. `ip rule` lists it at priority 9000 only while the switch is off | values and `ip rule` verified, meaning inferred |

The app needs the Enhanced Kill Switch on. To turn it on, it sets
`route_policy.@default[0].enabled='0'`, deletes `network.vpn_to_main`,
commits both configs and runs `/etc/init.d/network reload`; turning it off
reverses this. The priority 9000 rule changes only after the reload, and
GL's web interface shows the new state at once. With the switch turned on
this way and the tunnel down, a website no longer loaded (all verified).

Two settings the app needs are not in `uci` (verified by searching
`uci show`):

- GL's "Power On with Charger".
- Slot 2's choice between SIM 2 and the eSIM. GL keeps it in
  `/etc/config/cellular/slot_map.json`, and the modem reports it with
  `AT+QSIMTYPE="slot2"` (verified). The next section shows how to change
  it.

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

## After power-on

At every power-on the radio is on for about 12 s, whatever airplane mode
says, ending 58 s after power-on at the latest in our measurements
(design.md, section 6). The modem doesn't answer AT commands for a while
after power-on, so the app keeps asking. Step 3 of the swap changes nothing
before `/proc/uptime` shows 120 s and the modem answers `+CFUN: 4`.

## Still to find out

- Where GL stores "Power On with Charger".
- Whether GL's firmware has `flock`, which Churn's lock needs.
- Whether the modem can tell that slot 1 holds a card, so the app can warn
  the user.

## Files on the router

| Path | Mode | Contents |
| --- | --- | --- |
| `/etc/churn/` | 700 | Churn's folder |
| `/etc/churn/README.txt` | 600 | What the folder is; how to restore the factory IMEIs by hand; to do so before a factory reset |
| `/etc/churn/secret` | 600 | 32 random bytes, the HMAC key |
| `/etc/churn/factory` | 600 | Factory IMEI 1 and IMEI 2, one per line |
| `/etc/churn/restore.sh` | 700 | Shipped in the app's assets. Takes the lock, turns the radio off, writes the IMEIs from `factory`, reads them back |

At setup the app also adds the line `/etc/churn/` to `/etc/sysupgrade.conf`.

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
