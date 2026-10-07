# Development notes

Technical detail for writing Churn's code. Why things are done this way is
in [design.md](design.md); building, testing and the contribution process
are in [CONTRIBUTING.md](../CONTRIBUTING.md). *Verified* means we observed
it on a MUDI 7 with GL firmware 4.10.0 and a Quectel RG650V-EU modem;
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
  `get_modem_bus` returns on the MUDI 7).
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
| Radio state | `AT+CFUN?`: `1` on, `4` airplane | verified |
| Radio off | `AT+CFUN=4` | in GL's firmware, not yet sent by us |
| SIM present | `AT+CPIN?`: `+CME ERROR: 10` = no SIM | verified |
| Active subscription | `AT+QUIMSUB?`: slot 2 runs on `2,"SUB2"` | verified |
| Read IMEI 1 | `AT+EGMR=0,7` | verified |
| Read IMEI 2 | `AT+EGMR=0,11` | to test |
| Write IMEI 1 | `AT+EGMR=1,7,"<15 digits>"` | verified, survives reboot |
| Write IMEI 2 | `AT+EGMR=1,11,"<15 digits>"` | verified, survives reboot |
| IMEISV of IMEI 2 | `AT+EGMR=0,10`: 14 IMEI digits + 2-digit SVN | verified, rejects writes |
| Software version | item 9 (`AT+EGMR=0,9`); leave untouched | verified |
| ICCID | `AT+QCCID` | inferred |
| SIM hot-plug detection | `AT+QSIMDET?`: `1,0` = on | verified |
| Slot select | `AT+QUIMSLOT` | rejected by this modem |
| Persist modem settings | `AT+QPRTPARA=1` | sent by blue-merle v2 after writes; our writes persisted without it |

`AT+GSN` and `AT+CGSN` return one of the two IMEIs, switching irregularly,
so don't use them.

## Still to find out

- How to turn GL's airplane mode on and off and switch slot 2 between SIM 2
  and the eSIM from a shell. Both belong to GL's `cellular_manager`;
  airplane mode is stored as `glconfig.general.airplane_mode`, and slot
  strings in it point to `/etc/config/cellular/slot_map.json` (verified
  strings, unknown mechanism).
- Where GL stores "Power On with Charger"; it is not in `uci`.

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

- **ICCID read from the modem:** keep the digits only. Some modems pad a
  19-digit ICCID with a trailing `F` (inferred), which is dropped.
- **ICCID entered by the user:** 19 or 20 digits; reject it if the last
  digit isn't the Luhn check digit of the others.
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
