# Churn design

Churn is an Android phone app for the GL.iNet Mudi 7 (GL-E5800) travel
router. Every SIM swap done through the app also gives the router new IMEIs,
derived from the new SIM profile and a random secret stored on the router.
This document explains why Churn works the way it does. How to build on it
is in [development.md](development.md); why the derived IMEIs keep the
router's own TAC is in [tac.md](tac.md).

Status: draft; nothing described here is implemented yet. *Verified* means
we observed it on a Mudi 7 with GL firmware 4.10.0 and a Quectel RG650V-EU
modem; *inferred* means reasoned but not yet tested.

## 1. Goal and threat model

A mobile network records which SIM (IMSI) was used in which device (IMEI),
where (cell) and when. Without Churn the router keeps its IMEI, so a new SIM
in it is linked to every SIM used in it before. Churn's goal is that the
identity used before a SIM swap can't be linked to the one used after it.

- **Adversary:** the carrier, and anyone who later reads its records
  (retained data, lawful access), possibly joined with other records such
  as a service's IP address logs or a phone's location data.
- **Churn handles:** the IMEI as a link between SIMs. Each SIM profile gets
  its own IMEIs, which nobody without the router's secret can predict.
- **The user handles:** time and place. A swap done where the old SIM was
  last used, or a router that registers at home, links identities without
  any IMEI. Section 8 lists the rules the app teaches for this.
- **The VPN handles:** the SIM's IP address. Without one, every service
  used through the router logs the current SIM's address with the account,
  so a single account links all SIMs (rule 5).
- **Out of scope:** the location of a router while it is online; what is
  done online and the accounts used; who bought a SIM; radio fingerprinting
  of the transmitter by specialized equipment nearby; and the modem model,
  which the network learns from the radio capabilities the modem announces,
  whatever its IMEI.
- **Accepted trade-off:** the phone holds a root SSH credential for the
  router.

## 2. Architecture

Everything runs in a native Kotlin app on the phone. No package is installed
on the router; the app only keeps a few files there (section 5). blue-merle,
an existing IMEI tool for GL.iNet routers, is neither installed nor forked;
Churn reimplements what it needs.

Rejected alternatives:

- **GL.iNet's JSON-RPC API:** undocumented in English, without stability
  guarantees, and its modem module is read-only.
- **LuCI or ubus over HTTP:** needs a package on the router, and LuCI is set
  up over plain HTTP.
- **A GUI on the router's screen:** a raw framebuffer, no plugin API, and
  the source of GL's screen software isn't available.
- **Installing blue-merle:** see section 11.

Given up on purpose: blue-merle's rotation at boot and its trigger on the
router's touch screen. Every rotation is a guided procedure in the app
instead.

## 3. Router access

- **Cable, not Wi-Fi.** The phone is connected to the router's USB-C port
  for the whole procedure, so the router's Wi-Fi can stay off (rule 3). The
  router appears as a USB Ethernet adapter (verified). The router has no
  internet during a rotation, so Android doesn't use this network by
  default; the app binds its connections to it explicitly.
- **SSH as root.** SSH is on by default on GL.iNet firmware and documented
  by GL.iNet. The app logs in with the router's admin password.
- **Host key:** trusted on first use after showing its fingerprint. A new or
  changed key is never accepted silently.
- **Password:** stored with the Android Keystore.
- **Dependencies:** an SSH library and AndroidX, nothing else.
- **Modem access:** every AT channel of the modem is held open by a GL or
  Quectel daemon (verified), so the app sends AT commands through GL's own
  command-line tool, which arbitrates between them. Every modem operation
  runs under an exclusive lock, and a failed or unclear answer means the
  step is repeated, never assumed done.

## 4. IMEI derivation

An IMEI is 15 digits: an 8-digit type allocation code (TAC) identifying the
device model, a 6-digit serial, and a Luhn check digit. The Mudi 7 has two
IMEIs, expected to belong to SIM slot 1 and slot 2 (section 10). Churn
uses only slot 2 (section 7) but derives both IMEIs, so they keep looking
like a factory pair.

### TAC: the router's own

Derived IMEIs keep the router's factory TAC; only the serial changes. On the
Mudi 7 we tested, both IMEIs use `35609021`, which public TAC lists show as
the Mudi 7. A pool of other devices' TACs would not hide the modem, would
make Churn users recognizable, and would risk collisions with real phones.
The full reasoning is in [tac.md](tac.md).

### Input: the profile's ICCID

The IMEIs are derived from the ICCID of the SIM profile in use, not from its
IMSI.

- The ICCID identifies a profile: a physical SIM has one, and each profile
  on an eSIM has its own. The chip itself is identified by its EID, which
  Churn never uses.
- Some roaming profiles switch between several IMSIs, depending on the
  country, while their ICCID stays the same. With the IMSI as input, the
  same profile could get a new IMEI when inserted again, which would show
  its issuer that the IMEI was rewritten. With the ICCID, a profile always
  shows the same IMEI, like a SIM in an ordinary device.

### Keyed and deterministic

- **Keyed:** the IMEI is an HMAC of the ICCID under a random secret stored
  on the router. blue-merle's deterministic mode maps the IMSI to an IMEI
  without a key; anyone can compute that mapping and recognize its users.
  Without the secret, Churn's mapping can't be tested.
- **Deterministic:** the same profile always gets the same IMEIs, so
  repeating a step after an error or inserting a profile again gives the
  values it had before.

### Specification

- `secret`: 32 bytes from a cryptographically secure random generator,
  created at setup and stored on the router (section 5).
- `mac = HMAC-SHA256(secret, "churn-imei-v1:" || ICCID)`, with the ICCID as
  ASCII digits. The label versions the derivation, so a future change can't
  silently produce the old values.
- `d` = serial of factory IMEI 2 minus serial of factory IMEI 1, read at
  setup.
- `n = 1,000,000 − |d|`. Read `mac` as eight 32-bit big-endian words and
  take the first one below the largest multiple of `n` that fits in 32 bits;
  `s` is that word modulo `n`. Rejecting the other words avoids modulo bias.
  If all eight are rejected (probability below 10⁻²⁹), continue with the
  words of `HMAC-SHA256(secret, mac)`.
- Serial 1 = `s + max(0, −d)`, serial 2 = serial 1 + `d`. Both stay within
  000000 to 999999.
- IMEI 1 and IMEI 2 = the TAC of the respective factory IMEI + 6-digit
  serial + Luhn check digit.

Test vectors are in [development.md](development.md).

### Two IMEIs, factory spacing

A factory Mudi 7 has two different IMEIs whose serials are a small distance
apart: 7 on the unit we tested. Derived pairs keep the router's own distance,
so to anyone who sees both IMEIs they look like a factory pair. Equal IMEIs
would never occur on a factory unit. Since Churn uses only slot 2, the
network should see only IMEI 2 (section 10), so whether the distance varies
between units doesn't matter.

### Throwaway pair

Built the same way, with `s` drawn uniformly from the random generator
instead of the HMAC. It is written before every swap, because the first
boot after it emits the built-in eSIM's short bursts before the final IMEIs
are known (section 7), and those bursts must not carry the previous
profile's IMEIs.

## 5. Storage on the router

Everything Churn keeps on the router is in `/etc/churn/`, readable by root
only: a README explaining the folder, the secret, the factory IMEIs and a
restore script. The script writes the factory IMEIs back; the app runs it
on request, and it also works over a plain SSH session if the app is gone.
The script ships inside the app and is copied to the router at setup, so the
repository has no separate router component.

- **Kept across firmware upgrades.** OpenWrt's upgrade with "keep settings"
  keeps only listed paths, so the app adds `/etc/churn/` to
  `/etc/sysupgrade.conf`. Otherwise an upgrade would lose the secret and the
  factory IMEIs, and setup would run again and could record already rewritten
  IMEIs as the factory ones. A factory reset still deletes the folder; the
  README says to restore the factory IMEIs first.
- **Checked on every connection.** The app checks that the folder exists and
  that the IMEIs are the ones it last wrote. GL's firmware contains the
  command that writes IMEI 1, so an upgrade or reset might restore the
  factory IMEI (inferred), and the next boot with a SIM would pair it with
  that SIM.
- **What the secret reveals.** Anyone with root on the router can recompute
  the IMEIs for a known ICCID. Nothing else on the router links a past SIM
  to a past IMEI.
- **Factory capture.** The IMEIs read at first setup may already be
  rewritten, for example by blue-merle. The app warns if their TAC isn't a
  known Mudi 7 TAC.

## 6. Measured hardware behavior

We measured one Mudi 7 with GL firmware 4.10.0 (OpenWrt 23.05.4) and a
Quectel RG650V-EU modem. Linux runs on the modem chip itself (Qualcomm
SDX75). Methods: a script on the router polling the modem's radio state once
a second from boot; a broadband RF meter next to the router during boots;
and AT commands sent over SSH.

- **Radio on at boot, despite airplane mode.** With airplane mode on, the
  modem reported its radio fully on for about 12 s before GL switched it to
  airplane mode: 46 to 58 s after power-on with no SIM, 38 to 51 s with an
  eSIM profile enabled. The modem didn't answer earlier, so the radio may
  have been on before that.
- **No SIM, no emission.** With slot 2 set to the physical SIM 2 and both
  trays empty, the meter showed no RF at boot, with airplane mode on or off.
- **A SIM registers at every boot.** With an eSIM profile enabled, the meter
  showed RF for several seconds on every boot, whatever GL's airplane mode
  and cellular switches said. Several seconds fit a network registration
  (inferred). Airplane mode therefore can't keep a router with a SIM silent
  at power-on.
- **The built-in eSIM emits even without a profile.** With slot 2 set to
  the eSIM and its profiles disabled, the meter showed two short bursts at
  boot. Without a profile the chip presents a test-network identity:
  IMSI `001010123456` plus three digits, and a placeholder ICCID. That
  identity is likely the same or nearly the same on every unit (inferred).
- **Slot selection doesn't keep a card off at boot.** With slot 1 selected
  in GL's interface and slot 2 on an enabled eSIM profile, the router still
  emitted at boot. The modem rejects Quectel's usual slot command, and GL
  switches slots only while running.
- **Switching slots while running is silent.** Switching slot 2 from SIM 2
  to an enabled eSIM profile in airplane mode emitted no RF.
- **A card in slot 2 stays off while slot 2 is on the eSIM.** With slot 2
  set to the built-in eSIM with its profiles disabled and airplane mode on,
  we switched the router off, put an eSIM adapter card into slot 2's tray
  and switched it on. The meter showed only the eSIM's two short bursts.
  Switching slot 2 to SIM 2 afterwards, still in airplane mode, emitted
  nothing, and the modem then reported the card's ICCID. A normal SIM card
  should behave the same (inferred).
- **A card in slot 1 always emits.** With a card in slot 1, every boot
  showed RF for about 10 s or longer, whatever the settings, airplane mode
  and GL's cellular switch included.
- **No setting starts the radio off.** None of the modem's configuration
  commands offers a power-up radio state, so nothing saved can make a boot
  with an enabled SIM profile silent.
- **Both IMEIs are writable.** IMEI 1 and IMEI 2 can be written, the new
  values read back at once and survive a reboot.
- **No automatic power-on.** With GL's "Power On with Charger" setting off,
  connecting a charger doesn't switch the router on.
- **Auto power-off is limited.** GL's automatic power-off fires only on
  battery and only while no cable or Wi-Fi client is connected.
- **The SIM tray is under the battery.** A physical SIM can't be swapped
  while the router runs on battery.

## 7. Guided SIM swap

The measurements set two constraints. A power-on with a SIM inside registers
where it happens, so the new SIM must not be powered until its own IMEIs are
written, and every power-on with a SIM inside must happen at a place that
doesn't matter. Swapping a SIM while the router runs would mean running
without the battery, which risks file-system corruption, so it isn't
offered.

The app therefore parks slot 2 on the built-in eSIM while the user swaps
the card, because a card in slot 2 stays off as long as slot 2 is set to
the eSIM (section 6). Slot 1 is never used, because a card there emits at
every boot:

1. With the cable connected, the app turns the radio off and verifies it,
   writes a throwaway pair (section 4) and verifies it, sets slot 2 to the
   built-in eSIM with no profile enabled, turns airplane mode on and
   shuts the router down.
2. With the router off, the user replaces the old SIM in slot 2's tray with
   the new one, goes to a place that doesn't matter, and switches the router
   on. Only the eSIM's short bursts go out, with the throwaway IMEI and the
   near-generic test identity.
3. The app switches slot 2 to SIM 2 in airplane mode, reads the ICCID,
   writes the derived pair and verifies it.
4. The user turns airplane mode off later, at another place and time.

### App behavior

- The step reached is saved, and a foreground service keeps the app alive,
  so the procedure resumes after the screen locks or Android stops the app.
- Each step says what to do, where, and why, so the rules don't depend on
  the user's memory.
- Before each rotation the app checks the slot 2 setting, airplane mode, and
  that "Power On with Charger" is off.

## 8. Usage rules

The app shows these rules in this wording:

Always follow these rules:

1. At home, at work and anywhere else you return to, keep the router
   switched off or without a SIM.
2. Before you get to such a place, unplug everything and switch the router
   off.
3. Connect to the router by cable only, and keep its Wi-Fi off.
4. Use normal SIM cards, or eSIM adapter cards with one profile each, and
   put them only into SIM slot 2. Don't use the router's built-in eSIM.
5. Send all traffic through the router's VPN, and don't use the router
   without it.
6. Do not run the router without the battery installed.
7. Before you leave a place you return to with the router, remove the SIM
   card or disable the eSIM profile in every phone you carry, and keep it
   that way until you're back.

For maximum privacy, also follow these rules where you can:

<!-- markdownlint-disable MD029 -->
8. Use SIM cards that aren't registered to your name. The same goes for
   eSIM profiles.
9. Get each new SIM card or eSIM profile from a different provider than
   the last one. If you can't, start using the new one somewhere else and
   some days after you stopped using the last one.
10. Don't reuse a card once you've swapped it out. If you run out of new
    cards, keep using the current one until you get a new one.
11. Vary when you swap SIMs, and where you switch the radio on and off.
12. Use a phone without Google services, or turn off location services on
    every device you use with the router.
13. Pay for the VPN in a way that isn't tied to your name.
14. Block SIM slot 1, for example with a drop of hot glue.
<!-- markdownlint-enable MD029 -->

Rules 1 to 7 allow no compromise: breaking one undoes what Churn does or
risks the router. Rules 8 to 14 make linking harder still, and some say
what to do when one can't be followed.

Why:

1. A router with a SIM registers at every power-on, before airplane mode
   takes effect; a router without one is silent (section 6). Registrations
   at a recurring place link every identity used there to that place.
2. Switching off before arriving means the router never registers there.
   Unplugging also lets GL's auto power-off step in if someone forgets to
   switch off, because it fires only with nothing connected. The app can
   offer to set it to a few minutes at setup; it is a safety net, not a
   replacement for the rule.
3. Wi-Fi broadcasts the router's network name and hardware address wherever
   it goes, which anyone nearby, and Wi-Fi location databases, can record
   across SIM swaps. Phones and laptops connected to it report it to those
   databases too. If the app could change both at every swap (section 10),
   Wi-Fi after a swap would link nothing across swaps, and this rule could
   allow it away from places the user returns to.
4. The built-in eSIM's EID never changes and is reported to the provider's
   server on every profile download, which links all its profiles. It also
   emits at boot whenever slot 2 is set to it. An eSIM adapter card has an
   EID too, so a second profile on it would be linked to the first. A card
   in slot 1 emits for about 10 s at every boot, whatever the settings,
   while a card in slot 2 stays off until the app has written its IMEIs
   (sections 6 and 7).
5. Without a VPN, every service a device behind the router uses sees the
   current SIM's IP address and logs it with the account, so one account
   links all SIMs, as the IMEI would. The carrier also sees the traffic,
   whose pattern can probably recognize the same devices across SIMs
   (inferred). A VPN on the router covers every device behind it. It must
   block traffic while it is down, or a boot or a dropped tunnel leaks
   (section 10).
6. Running without the battery risks file-system corruption on power loss.
7. A phone with an active SIM that travels with the router is seen at the
   same cells as each router SIM. Matching the two timelines links every
   router SIM to the phone, and usually to its owner. Airplane mode only
   while the router is on isn't enough: the phone's last cell before it
   goes quiet and its first after it comes back frame each router
   session. Removing the SIM card or disabling the eSIM profile is also
   safer than airplane mode, which one tap undoes. We measured that a
   GrapheneOS phone transmits nothing with its eSIM profile disabled, even
   with airplane mode off.
8. A SIM or profile registered to a name ties every IMEI it is used with to
   that name. Churn can't undo that.
9. A provider that issues two consecutive profiles sees one Mudi 7 IMEI stop
   and another start, both with a rare TAC and possibly in the same area.
   Timing and place could link them (inferred). A different provider sees
   only one of them. Where the same provider can't be avoided, a different
   place and a gap of some days make the stop and the start harder to
   match.
10. A reused SIM brings back its old IMSI and ICCID. A reused eSIM adapter
    card brings back its EID, which links its profiles as in rule 4.
    Keeping the current card links nothing new, while swapping back to an
    old one links that card's earlier use to today. Done just as the current
    card goes quiet, the swap could also link the two cards by timing
    (inferred).
11. Regular habits, such as always swapping on the same day or at the same
    station, can link identities without any identifier.
12. A phone with location services on gives Google or Apple a continuous
    timeline of its account, built from GPS and the Wi-Fi networks and
    cells it sees. Matched against the carrier's records, it links every
    SIM the router used along the way (inferred).
13. The VPN provider sees each SIM's IP address in turn, all under one VPN
    account. If that account is tied to a name, so are all the SIMs.
14. A card put into slot 1 by mistake emits at the next boot, wherever
    that happens (rule 4). A blocked slot rules that mistake out.

The app also tells the user, before first use, that rewriting an IMEI is a
criminal offense in some countries, for example under the UK's Mobile
Telephones (Re-programming) Act 2002.

## 9. Distribution

[F-Droid](https://f-droid.org/) builds every app version on its own
servers before publishing it in its repository, which makes it the best
place to distribute Churn. Because of its reach, Google Play is an obvious
second choice. However, since Churn helps the user do something that is
illegal in some jurisdictions, Google might refuse the app outright or
shadow-ban it. Getting the app into Google Play is therefore a bonus rather
than a goal.

Churn reimplements rather than copies blue-merle, so it doesn't inherit
blue-merle's license.

## 10. Open questions

Hardware, needing real SIMs:

- Does the network see IMEI 2 for a card in slot 2, as expected?
- Does a normal SIM card in slot 2 stay off while slot 2 is on the eSIM,
  like the eSIM adapter card did?

Hardware, other:

- Does GL's firmware ever write the IMEIs itself, for example after an
  upgrade or a factory reset?
- Does GL's upgrade page keep the paths listed in `/etc/sysupgrade.conf`?
- Where is "Power On with Charger" stored, so the app can check it?
- Should a rotation clear the modem's cached network state? The modem offers
  commands for it.
- Do Android phones other than the one we tested accept the router's USB
  Ethernet?
- According to the documentation, GL's VPN client on the Mudi 7 blocks all
  traffic while the VPN is down if and only if the "Kill Switch" setting
  for all the tunnels and the "Enhanced Kill Switch" global setting are
  enabled. If this works as advertised is untested. Rule 5 depends on it.
- Can the app give the router's Wi-Fi a new network name and hardware
  address at every swap? OpenWrt has a setting for each; GL's firmware
  may override them.

Design:

- Do real Mudi 7 serials fall in a narrow range? A derived serial far
  outside it could stand out. Serials are uniform over all 6 digits until
  this is known.

Legal and distribution:

- Is rewriting an IMEI legal in Switzerland and other markets?
- Does Google Play's Device and Network Abuse policy allow an app that
  changes IMEIs?

## 11. Relation to blue-merle

[blue-merle](https://github.com/srlabs/blue-merle) and its fork
[blue-merle v2](https://github.com/WSchlesner/blue-merle-v2) rewrite the
IMEI on GL.iNet routers and were the starting point for Churn. Churn differs
in ways that follow from the sections above: nothing is installed on the
router, the derivation is keyed, derived IMEIs keep the router's own TAC
instead of a pool of other devices' TACs, and every swap is a guided
procedure that teaches the usage rules. blue-merle v2 was also the source
for the command that writes IMEI 2.
