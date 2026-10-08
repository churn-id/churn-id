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
  `get_modem_bus` returns on the Mudi 7).
- Don't open the modem's device nodes directly. Every AT channel in use
  (`/dev/smd7`, `smd8`, `smd9`, `smd11`, `/dev/at_mdm0`) is held by a GL or
  Quectel daemon (verified).
- Run each modem operation under `flock` on `/var/lock/churn.lock`. The lock
  keeps Churn's own operations apart; `gl_modem` arbitrates against GL's
  daemons (inferred).
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
| Active subscription | `AT+QUIMSUB?`: slot 2 runs on `2,"SUB2"` | verified |
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
- Slot 2's choice between SIM 2 and the eSIM. `cellular_manager` keeps it,
  and GL's scripts fall back to `/tmp/run/dual_sim/<interface>/current_sim`
  when the modem doesn't answer `AT+QUIMSLOT?` (verified).

## Still to find out

- How to switch slot 2 between SIM 2 and the eSIM from a shell. GL's
  `cellular_manager` does it, and slot strings in it point to
  `/etc/config/cellular/slot_map.json` (verified strings, unknown
  mechanism).
- Where GL stores "Power On with Charger".
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
