# Churn design

Churn is an Android phone app for the GL.iNet Mudi 7 (GL-E5800) travel
router. Every SIM swap done through the app also gives the router new IMEIs,
drawn at random for the new SIM profile. This document explains why Churn
works the way it does. How to build on it is in
[development.md](development.md); why Churn's IMEIs keep the router's own
TAC is in [tac.md](tac.md).

Status: draft. A first prototype of the app implements sections 3 to 5, 7
and 8; it hasn't been tested on a router yet. *Verified* means we observed
it on a Mudi 7 with GL firmware 4.10.0 and a Quectel RG650V-EU modem;
*inferred* means reasoned but not yet tested.

## 1. Goal and threat model

A mobile network records which SIM (IMSI) was used in which device (IMEI),
where (cell) and when. Kept for months or years, these records form a
location history of each subscriber, coarser than but much like the one
Google and Apple collect from phones. Even without a name, such a history
points to its owner: in one carrier's records of 1.5 million people, four
places and hours told 95% of them apart
([study](https://www.nature.com/articles/srep01376)), and a person's
home and workplace are two such points that are easy to learn.

Changing SIMs splits the history only if the IMEI changes too, which
ordinary phones don't allow, so Churn moves the mobile connection to a
travel router whose modem does. Without Churn the router keeps its IMEI, so
a new SIM in it is linked to every SIM used in it before. Churn's goal is
that the identity used before a SIM swap can't be linked to the one used
after it.

- **Adversary:** the carrier, and anyone who later reads its records
  (retained data, lawful access), possibly joined with other records such
  as a service's IP address logs or a phone's location data.
- **Churn handles:** the IMEI as a link between SIMs. Each new SIM profile
  gets random IMEIs of its own, and Churn keeps nothing on the router that
  links it to earlier ones (section 5). A place card keeps its IMEIs for
  every visit to its place, and the phone, not the router, keeps them
  (section 4).
- **The user handles:** time and place. A new SIM that starts where and when
  the old one went quiet, or a router that registers at home, links
  identities without any IMEI. Section 8 lists the rules the app teaches for
  this.
- **The VPN handles:** the SIM's IP address. Without one, every service
  used through the router logs the current SIM's address with the account,
  so a single account links all SIMs (rule 5). All SIMs share one VPN
  account, so we assume a provider that keeps no logs (section 10).
- **Out of scope:** the location of a router while it is online; what is
  done online and the accounts used; who bought a SIM; radio fingerprinting
  of the transmitter by specialized equipment nearby; and the modem model,
  which the network learns from the radio capabilities the modem announces,
  whatever its IMEI.
- **Accepted trade-off:** the phone holds a root SSH credential for the
  router.

## 2. Architecture

Everything runs in a native Kotlin app on the phone. No package is installed
on the router, and nothing of Churn's runs there. The app keeps a few files
on the router (section 5), one of which adds rules to the router's firewall.
We first meant to leave the router's own setup alone, but GL's firmware
offers no setting that keeps the router's own traffic off the SIM, so the
rules do it (section 7). blue-merle,
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
  for the whole procedure, so the router's Wi-Fi can stay off (rule 4). The
  router appears as a USB Ethernet adapter (verified). The router has no
  internet during a rotation, so Android doesn't use this network by
  default; the app binds its connections to it explicitly.
- **Network permission:** Android has no permission for the cable alone, so
  the app needs the permission to use the network, which GrapheneOS calls
  Network and lets the user turn off. The app connects to nothing but the
  router.
- **SSH as root.** SSH is on by default on GL.iNet firmware and documented
  by GL.iNet. The app logs in with the router's admin password.
- **Host key:** trusted on first use after showing its fingerprint. A new or
  changed key is never accepted silently.
- **Password:** stored with the Android Keystore.
- **Dependencies:** an SSH library (sshj, which brings Bouncy Castle) and
  AndroidX, nothing else.
- **Modem access:** every AT channel of the modem is held open by a GL or
  Quectel daemon (verified), so the app sends AT commands through GL's own
  command-line tool, which arbitrates between them. Every modem operation
  runs under an exclusive lock, and a failed or unclear answer means the
  step is repeated, never assumed done.

## 4. IMEI pairs

An IMEI is 15 digits: an 8-digit type allocation code (TAC) identifying the
device model, a 6-digit serial, and a Luhn check digit. The Mudi 7 has two
IMEIs, expected to belong to SIM slot 1 and slot 2 (section 10). Churn
uses only slot 2 (section 7) but changes both IMEIs, so they keep looking
like a factory pair.

### TAC: the router's own

Churn's IMEIs keep the router's factory TAC; only the serial changes. On the
Mudi 7 we tested, both IMEIs use `35609021`, which public TAC lists show as
the Mudi 7. A pool of other devices' TACs would not hide the modem, would
make Churn users recognizable, and would risk collisions with real phones.
The full reasoning is in [tac.md](tac.md).

### A random pair for each new card

At step 3 of a swap (section 7), a new SIM profile gets a pair drawn at
random:

- `d` = serial of factory IMEI 2 minus serial of factory IMEI 1, read at
  setup.
- `n = 1,000,000 − |d|`, and `s` is drawn uniformly from 0 to `n − 1` by a
  cryptographically secure random generator.
- Serial 1 = `s + max(0, −d)`, serial 2 = serial 1 + `d`. Both stay within
  000000 to 999999.
- IMEI 1 and IMEI 2 = the TAC of the respective factory IMEI + 6-digit
  serial + Luhn check digit.

A pair derived from the profile and a secret stored on the router would
give a profile used again its old IMEIs. But whoever got the router could
then take a network's records, keep the cards seen with the Mudi 7's TAC
and test each against the secret, one HMAC per card, which lists every card
the router used on that network. A random pair leaves nothing to test.

### The card's ICCID

Step 3 tells cards apart by the ICCID of the SIM profile in use, not by its
IMSI.

- The ICCID identifies a profile: a physical SIM has one, and each profile
  on an eSIM has its own. The chip itself is identified by its EID, which
  Churn never uses.
- Some roaming profiles switch between several IMSIs, depending on the
  country, while their ICCID stays the same. Told apart by its IMSI, the
  same profile could count as a new card and get new IMEIs, which would
  show its issuer that the IMEI was rewritten. By its ICCID, a profile keeps
  its IMEIs while it stays in the router, like a SIM in an ordinary device.

### The record

Step 3 records the card in `/etc/churn/card` (section 5): its ICCID and its
pair, written before the IMEIs. When step 3 runs again with the same card,
because it stopped or because the user kept the old card, it takes the
recorded pair, so the card keeps its IMEIs. Any other card gets its pair
after a question (below) and replaces the record.

A travel card used again after other cards therefore gets a new pair. Its
issuer then sees its IMEI change, which shows that the IMEI was rewritten
(section 5), but rule 8 already rules that out (section 8).

### Place cards

A place card is a new card for one place the user returns to, other than
home and work, such as their favorite hotel or their cabin in the woods
(rule 1). It keeps its pair for every visit, so its network sees the same
IMEIs there each time, and it links only the visits to its own place. The
phone keeps the place list: each place card's ICCID, pair and an optional
name (section 7).

About any card but the recorded one, step 3 asks before it writes the record
or the IMEIs. A card on the place list shows its name or the last four
digits of its ICCID: continuing gives it its kept pair, and canceling shuts
the router down with slot 2 on the built-in eSIM and goes back to step 2.
Any other card becomes either a new place card, which joins the place list
with a random pair, or a travel card, which gets a random pair. Only a new
card should become a place card, since one that has traveled would tie its
earlier places to this one.

### Two IMEIs, factory spacing

A factory Mudi 7 has two different IMEIs whose serials are a small distance
apart: 1 on the unit we tested. Churn's pairs keep the router's own distance
between the serials, so to anyone who sees both IMEIs they look like a
factory pair. The full numbers can differ by more or less, since the last
digit is a check digit computed from all the others. Equal IMEIs would
never occur on a factory unit. Since Churn uses only slot 2, the
network should see only IMEI 2 (section 10), so whether the distance varies
between units doesn't matter.

### The old card's pair until step 3

Step 1 leaves the IMEIs as they are, so the router keeps the old card's
pair until step 3 writes the new card's (section 7).

Step 2's power-on comes before the new card has its pair. Slot 2 is then
parked on the built-in eSIM with no profile enabled, so the card in the
tray stays off and only the eSIM's short bursts go out (section 6). With
no profile enabled, the eSIM presents its test profile, whose identity
belongs to the test network 001/01. No real network serves that network,
so none can authenticate the profile. To a network in range, the power-on
likely looks like a device with that test identity trying to register and
being turned away at once (inferred). Every Mudi 7 likely shows the same
test identity (section 6), so it says nothing about which router it is.

The bursts likely carry no IMEI (inferred). Over LTE and 5G, a device
gives its IMEI only once the network has authenticated it and secured the
connection. Before that, the only identity it gives on request is its
subscriber identity (3GPP TS 24.301 and TS 24.501, subclause 4.4.4.2).
Over 3G, which the router's modem also supports, a network may ask for the
IMEI before that (3GPP TS 24.008, subclause 4.1.1.1.1), so we can't rule
it out (section 10). If the IMEIs do go out, the old card's pair shows
that card's router where the card was in use until step 1, which tells the
network nothing new. That is why step 2's power-on happens where step 1
ran (section 7).

We rejected a throwaway pair, drawn like a card's pair and written in step
1 to keep the old card's IMEIs out of the bursts:

- If the bursts carry the IMEI, a throwaway pair shows a Mudi 7 IMEI that
  no network has seen before, minutes after the old card went quiet at the
  same place, since users rarely move between steps 1 and 2. A new IMEI
  with a rare TAC, right where and when another stopped, hints at a
  rewritten IMEI.
- If they don't, a throwaway pair protects nothing, and it adds an IMEI
  write, saved to the modem's flash, to every swap.
- A card left in the SIM 1 tray registers at step 2's power-on (section
  6). With a throwaway pair, its network would see it with a new IMEI,
  which shows that the IMEI was rewritten. With the old card's pair, a
  card that was in use until step 1 registers as before.
- A new card put into the SIM 1 tray by mistake registers at that power-on
  either way, where and when the old card went quiet, which links the two.
  A throwaway pair doesn't prevent that, so step 3 tells the user to use
  another new card (section 7).

## 5. Storage on the router

Churn keeps its files on the router in `/etc/churn/`, readable by root
only: a README explaining the folder, the factory IMEIs, the record of the
current card (section 4), a restore script and a link to the zero files.
The script writes the factory IMEIs back, deletes the record, puts back
GL's setting for its system log, deletes Churn's zero files, removes
Churn's firewall rules (all section 7) and last deletes the folder itself;
the app runs it on request, and it also works over a plain SSH session if
the app is gone. Outside the folder
are only the firewall rules, in `/etc/nftables.d/churn.nft`, and the zero
files, in `/overlay/churn-zeros/` beside the router's own files. The script
and the rules ship inside the app and are copied to the router, so the
repository has no separate router component.

- **Kept across firmware upgrades.** OpenWrt's upgrade with "keep settings"
  keeps only listed paths, so the app adds `/etc/churn/` and the rules file
  to `/etc/sysupgrade.conf`. Otherwise an upgrade would lose the factory
  IMEIs and the record, and setup would run again and could record already
  rewritten IMEIs as the factory ones. A factory reset still deletes both;
  the README says to restore the factory IMEIs first. If the folder is lost
  anyway, setup can write the factory IMEIs back from the phone's copy
  (section 7).
- **Deleted by a restore.** Once the restore script has undone everything
  else, it deletes the folder, itself included, and the folder's line in
  `/etc/sysupgrade.conf`. It still runs to its end, since the shell reads
  it from the file it opened (verified with BusyBox 1.36.1 on a PC). The
  flash then keeps none of Churn's files, which matters when the router
  goes to someone else, and the app treats it as a new router: it offers
  setup, which turns GL's log off again, rather than a router screen that
  warns about the log the restore turned back on. Setup reads the factory
  IMEIs from the modem, which has them back. A restore that stops earlier
  keeps the folder, so it can run again.
- **Zero files outside the folder.** The zero files (section 7) take about
  2.4 GB, so they stay out of `/etc/churn/`. In it, an upgrade that keeps
  settings would copy them with the folder, and anything that reads all of
  `/etc`, such as `du`, `grep -r` or a copy of it, would read them. They
  stay in `/overlay/churn-zeros/`, on the same flash partition as the
  router's own files but outside them, and the folder links to them as
  `zeros`, next to the README that explains them. `du`, `grep -r`, `cp` and
  the `find` and `tar` that an upgrade runs take only the link (verified
  with BusyBox 1.36.1 on a PC). Other places among the router's own files
  are no better: `/root` is the user's home, `/opt` often holds external
  storage, and `/tmp` and `/var` are in RAM.
- **Updated with the app.** At every connection the app rewrites the README
  and the restore script where they differ from its own copies, so a
  router set up by an earlier version gets a script that undoes everything
  the current version changes.
- **Checked on every connection.** The app checks that the folder exists,
  that the card in slot 2 is the recorded card and that the IMEIs are its
  recorded pair (section 4), all read from the router. At every power-on
  the card registers with whatever IMEIs are there (section 6), so the
  router screen warns if they aren't its pair. That is the case before the
  first swap, when the router has its factory IMEIs and no record; after
  the card was swapped without the app; and maybe
  after a firmware upgrade or reset: GL's firmware contains the command
  that writes IMEI 1, so either might restore the factory IMEI (inferred).
  Nothing stored on the phone goes into the check, so it also holds after
  a swap from another phone or a reinstall of the app. A card in the tray
  while slot 2 is on the built-in eSIM stays off and isn't checked, since
  reading it would take a switch of the slot. The warning asks for a new
  card rather than new IMEIs for this one: the network has already seen the
  card with the other IMEIs, so new ones would only join that link, and a
  card whose IMEI changes shows its issuer that the IMEI was rewritten
  (section 4). A profile enabled on the built-in eSIM isn't checked either:
  the router screen warns about the profile instead (section 7). Nor is an
  eSIM adapter card with no profile enabled, which step 3 gives no pair.
- **What the record reveals.** Anyone with root on the router can read the
  current card's ICCID and IMEIs in the record, which GL's own files and
  the modem show anyway. It names no earlier card: step 3 replaces it, and
  the clearing at the end of step 3 overwrites the old record's bytes in
  the free space (section 7). GL's firmware lists every card it has seen,
  so the app clears that list too. Apart from what the flash chips may keep
  beneath the partition (section 7), nothing else on the router links a
  past SIM to a past IMEI.
- **Factory capture.** The IMEIs read at first setup may already be
  rewritten, for example by blue-merle. The app warns if their TAC isn't a
  known Mudi 7 TAC.

## 6. Measured hardware behavior

We measured one Mudi 7 with GL firmware 4.10.0 (OpenWrt 23.05.4) and a
Quectel RG650V-EU modem. Linux runs on the modem chip itself (Qualcomm
SDX75). Methods: a script on the router polling the modem's radio state once
a second from boot; a broadband RF meter next to the router during boots;
and AT commands sent over SSH.

- **Airplane mode applies only after boot.** With airplane mode on, the
  modem reported its radio switched on (`+CFUN: 1`) for about 12 s before
  GL switched it to airplane mode (`+CFUN: 4`): 46 to 58 s after power-on
  with slot 2 on the built-in eSIM and its profiles disabled, 38 to 51 s
  with an eSIM profile enabled. The modem didn't answer earlier, so the
  radio may have been on before that. A switched-on radio emits only what
  the card makes it send: the RF meter showed only the eSIM's one or two
  short bursts with its profiles disabled, and more than 8 s of RF with a
  profile enabled (below).
- **No SIM, no emission.** With slot 2 set to the physical SIM 2 and both
  trays empty, the meter showed no RF at boot, with airplane mode on or off.
- **A SIM registers at every boot.** With an eSIM profile enabled, the meter
  showed RF for more than 8 s on every boot, whatever GL's airplane mode
  and cellular switches said. On some boots it lasted more than twice as
  long, and another burst of about 2 s followed more than 90 s after
  power-on, with airplane mode on, later than the probes saw the modem
  switch to airplane mode. RF for that long fits a network registration
  (inferred). Airplane mode therefore can't keep a router with a SIM silent
  at power-on.
- **The built-in eSIM emits even without a profile.** With slot 2 set to
  the eSIM and its profiles disabled, the meter showed one or two short
  bursts at boot. Without a profile the chip presents the generic test
  profile that GSMA TS.48 defines for testing devices: IMSI
  `001010123456063` on the test network 001/01 and ICCID
  `89000123456789012341`, the profile's published values. The GSMA meant
  it to come on every eSIM chip, so it is likely the same on every unit
  (inferred). What the bursts likely tell a network is in section 4.
- **Slot selection doesn't keep a card off at boot.** With slot 1 selected
  in GL's interface and slot 2 on an enabled eSIM profile, the router still
  emitted at boot. The modem rejects Quectel's usual slot command, and GL
  switches slots only while running.
- **Switching slots while running is silent.** Switching slot 2 from SIM 2
  to an enabled eSIM profile in airplane mode emitted no RF.
- **Shutting down is silent.** In airplane mode, the meter showed no RF
  while the router shut down, with slot 2 on the built-in eSIM and with
  slot 2 on a card in the SIM 2 tray.
- **A card in slot 2 stays off while slot 2 is on the eSIM.** With slot 2
  set to the built-in eSIM with its profiles disabled and airplane mode on,
  we switched the router off, put an eSIM adapter card into slot 2's tray
  and switched it on. The meter showed only the eSIM's short bursts.
  Switching slot 2 to SIM 2 afterwards, still in airplane mode, emitted
  nothing, and the modem then reported the card's ICCID. A normal SIM card
  in the tray also left only the eSIM's short bursts at power-on.
- **A card in slot 1 always emits.** With a card in slot 1, every boot
  showed RF for more than 8 s, whatever the settings, airplane mode and
  GL's cellular switch included.
- **The SIM 1 tray can be checked while slot 2 is in use.** With slot 2
  as the active slot, the modem still reported whether the SIM 1 tray held
  a card, with airplane mode on and off.
- **No setting starts the radio off.** None of the modem's configuration
  commands offers a power-up radio state, so nothing saved can make a boot
  with an enabled SIM profile silent.
- **Both IMEIs are writable.** IMEI 1 and IMEI 2 can be written, and the
  new values read back at once. They survive a reboot only once the modem
  has saved them: without that, a reboot 5 s after the write brought the
  earlier IMEIs back.
- **No automatic power-on.** With GL's "Power On with Charger" setting off,
  connecting a charger doesn't switch the router on.
- **Auto power-off is limited.** GL's automatic power-off fires only on
  battery and only while no cable or Wi-Fi client is connected.
- **The SIM tray is under the battery.** A physical SIM can't be swapped
  while the router runs on battery.

## 7. Guided SIM swap

The measurements set two constraints. A power-on with a SIM inside registers
where it happens, so the new SIM must not be powered until its own IMEIs are
written, and every power-on that powers a SIM must happen where that SIM
may be used (rule 1). Swapping a SIM while the router runs would mean
running without the battery, which risks file-system corruption, so it
isn't offered.

The app therefore parks slot 2 on the built-in eSIM while the user swaps
the card, because a card in slot 2 stays off as long as slot 2 is set to
the eSIM (section 6). Slot 1 is never used, because a card there emits at
every boot:

1. With the cable connected, the app turns airplane mode on and verifies
   that the radio is off, sets slot 2 to the built-in eSIM with no profile
   enabled and shuts the router down. The IMEIs stay the old card's until
   step 3 (section 4).
2. With the router off, the user takes the old SIM out of slot 2's tray if
   it is still there, puts the new one in and switches the router on where
   step 1 ran. Only the eSIM's short bursts go out, with the generic test
   identity and likely without the IMEIs. If they carry the IMEIs, those are
   the old card's, which show nothing new where that card was in use until
   step 1 (section 4).
3. The app checks that a VPN is still set up (below). Once the radio is
   off after the boot (section 6), it checks that the SIM 1 tray is empty
   (below), turns GL's
   Enhanced Kill Switch on if it is off and adds Churn's firewall rules if
   they are missing, then switches slot 2 to SIM 2 in airplane mode and
   reads the ICCID. Unless the card is the recorded one, the app switches
   slot 2 back to the built-in eSIM while it asks the user about the card
   (section 4), so that the card stays off if the router is switched off and
   on before the answer, then switches it to SIM 2 again and checks that the
   card is the same. It records the card with its pair, writes the pair,
   saves and verifies it, clears the router's record of earlier cards
   (below) and shuts the router down. If it finds no usable card, it
   switches slot 2 back to the built-in eSIM, so the card stays off at the
   next power-on, and stops, clearing nothing.
4. The user switches the router on later, at another place and time, and
   turns airplane mode off there. The new card registers at that power-on,
   whatever airplane mode says (section 6). A place card is switched on at
   its own place only (rule 1). The app also tells the user to destroy the
   card taken out, unless it is a place card or still in SIM 2 (rule 8).

Step 3 ends with a shutdown, which emits nothing (section 6), rather than
leaving the router on in airplane mode until the user goes online. The
router then travels to the next place switched off, as rule 1 asks before
any place the user returns to.

Step 4, not step 2, says to destroy the card taken out: until step 3 has
given the new card its IMEIs, the old card is the fallback, as when the user
keeps it after all. Step 1 notes on the phone which card the record names,
since step 3 replaces the record and may run more than once.

### App behavior

- The step reached is saved, and a foreground service keeps the app alive,
  so the procedure resumes after the screen locks or Android stops the app.
- While the app works on the router, it keeps the phone's screen on, as a
  video player does. A swap failed when we let the screen go dark and
  worked when we kept it on. One way a locked phone breaks a swap:
  GrapheneOS by default blocks new USB connections while the phone is
  locked, and in step 3 the router, just switched on, connects anew.
- Between jobs the app keeps its connection to the router, and connects
  anew if that connection has ended. Android ends the TCP connections of
  an app it freezes in the background, as it may while the phone is
  locked and no step runs. Step 1 failed when we let the phone lock
  before starting it.
- While it is on screen, the app watches for the router's network on the
  cable. The Connect screen says whether the router is there and, once the
  user has connected with the password and trusted the router's key,
  connects by itself when the router shows up. In step 2 the app goes on
  to step 3 by itself when the router shows up. Each starts once each time
  the router shows up, and only while the app is on screen: GrapheneOS
  lets no new USB connection through while the phone is locked, and
  Android lets an app in the background start no foreground service,
  which a step needs. Any USB Ethernet device looks the same to Android,
  but the app checks the router's key before it sends the password. The
  app doesn't open by itself when the router is plugged in, since every
  swap is a procedure the user goes through in the app (section 2).
- Step 3 starts only on a router that has been switched off and on since
  the shutdown before it. Before each shutdown the app notes the boot ID,
  which Linux makes new at every power-on. Right after step 1 the router
  stays on the cable for a while as it shuts down, and step 3 must not
  start on it then: with the old card still in the tray, it would end the
  swap as if the user had kept that card. The check also catches a
  shutdown that didn't happen.
- After the shutdown that ends steps 1 and 3, the app waits for the router
  to leave the cable, and warns if it is still there after two minutes.
  Leaving the cable only means that the router's network went: the router
  shut down, or the cable came out, which the app can't tell apart. When
  the phone loses the network during a shutdown is still to be measured.
- If the cable comes out while the app works on the router, the app ends
  its connection at once and says so. Otherwise a command would wait for
  an answer until its timeout, minutes for some.
- Each step says what to do, where, and why, so the rules don't depend on
  the user's memory.
- When a swap starts on a router with its factory IMEIs, as the first one
  does, step 1 adds that steps 1 to 3 can run at home or at work if the
  router was used there before Churn: until step 3 the router keeps its
  IMEIs, so the swap ties nothing new to that place (rule 7). The app goes
  by the IMEIs, not by a missing record, since a router whose folder was
  lost has no record but may still have a card's pair.
- Before each rotation the app checks the slot 2 setting, airplane mode, and
  that "Power On with Charger" and GL's scheduled reboot are off. A reboot
  is a power-on, so wherever it happens, the SIM registers there. It also
  checks that a VPN is set up (rule 5): with the Enhanced Kill Switch on, a
  router without one has no internet at all, which invites turning the
  switch off. Step 1 is where this check stops a swap, since the old card
  is still there to reach the internet while the user sets one up. While
  none is set up, or while the scheduled reboot is on, the router screen
  says that a swap needs it fixed and turns its swap button off, so the
  user learns it right after setup rather than at step 1. Step 1 still
  checks both before it changes anything, since the screen may be minutes
  old. The app accepts only WireGuard tunnels, the only type we have
  tested.
- Step 3 checks the VPN again, in case it was deleted since step 1, right
  after it turns airplane mode on. Slot 2 is still on the built-in eSIM
  then, so the step can stop and run again once a VPN is set up, without
  another step 1. With the old card out and the new one
  off, the router has no internet over a SIM, so that VPN has to come from a
  WireGuard configuration file. Step 3 is the swap's last look at the
  router. A VPN deleted after it leaves the router without internet, not
  with traffic past the VPN (inferred): Churn's firewall rules let only
  WireGuard's packets out over the SIM and name no tunnel (below).
- Step 3 also stops if a card is in the SIM 1 tray, once the router has
  finished starting and before it changes anything. A card there registers
  at every power-on (section 6), so at step 4 it would go out with the new
  card and link the two, and nothing else in the app would notice it, since
  the app otherwise reads only the active slot. The check comes too late for
  step 2's power-on, where that card has already registered. Slot 2 is
  still on the built-in eSIM, so the step runs again once the user has
  switched the router off and taken the card out. The check also catches a
  new card put into the wrong tray, with a clearer message than the one for
  an empty SIM 2 tray. Such a card has registered where and when the old
  one went quiet, which links the two, so the message also says to use
  another new card in that case.
- Step 3 is the last step before the router goes online, so that is where
  the app makes sure the Enhanced Kill Switch is on and Churn's firewall
  rules are in place (rule 5). Both take a reload, of the network or of
  the firewall, whose effect on the modem is untested, so the app does
  them while slot 2 is still on the built-in eSIM and then checks that the
  radio is still off. It leaves each tunnel's own Kill Switch as it is,
  since the Enhanced Kill Switch blocks that traffic too (section 10).
  Setup leaves both alone: until the first swap the router still uses the
  card it had before Churn, with its factory IMEIs, so there is nothing
  for them to keep apart yet, and the router reaches the internet as
  before while the user tries the app. The router screen warns while GL's
  system log goes to the flash and, once step 3 has recorded a card,
  while the switch is off or the rules are missing, and offers to fix
  each. Step 3 puts both in place before it records a card, so until then
  the warnings would only announce what step 3 does anyway.
- The phone, not the router, keeps the place list (section 4), since whoever
  holds the router can read its files (inferred). Android keeps the app's
  data encrypted with a key tied to the screen lock, and GrapheneOS's duress
  PIN wipes the phone, eSIMs included. Losing the phone or the app's data
  costs the place cards: each would get a new pair, which shows its network
  an IMEI change, so each must be replaced. The router screen lists the
  place cards, by the last four digits of their ICCID, which is usually
  printed on the card, or by a name the user gives one, and can forget one.
  Names stay on the phone. They should mean something only to the user, and
  no card should carry one that would mean something to others: a card
  labeled "cabin" tells whoever finds it where to look.
- The router screen names the card in use in one line: a card on the place
  list as a place card, by its name or the last four digits of its ICCID,
  and any other card by those digits, as a travel card if it is the
  recorded one and as unknown otherwise. While slot 2 is on the built-in
  eSIM, the line says the card in the tray is off, since reading it would
  take a switch of the slot. "No card found" covers an empty tray and a
  card that doesn't answer yet, which the app can't tell apart. An eSIM
  adapter card with no profile enabled shows as such, not by its digits,
  which belong to a placeholder ICCID that other such cards may show too.
  The line turns into a warning if slot 1 is the active slot, which Churn
  never uses, or if a profile is enabled on the built-in eSIM (rule 6).
- The phone also keeps a copy of the router's factory IMEIs, saved with each
  pair the app writes, so that the copy belongs to the router that has that
  pair. If Churn's folder is lost, as after a factory reset without a
  restore, and the modem has the pair the app last wrote, setup writes the
  copy back instead of recording that pair as the factory IMEIs. The record
  is lost with the folder, so the router screen warns until the next swap.
- The app sets `android:hasFragileUserData`, so from Android 10 on the
  system's uninstall dialog offers to keep the app's data, the place list
  and the copy of the factory IMEIs included, though uninstalls from the
  Play Store don't offer it (inferred).

### The router's own traffic

GL's Enhanced Kill Switch blocks only devices' traffic. What the router
sends itself went over the SIM, past the VPN: it synchronized its time
and looked up names at Google's DNS servers. For about 2 seconds after it
went online, it also passed devices' lookups on to Google's servers over
the SIM (all verified; development.md, "uci settings"). GL's update check
would take the same path (inferred). Any of this could link SIMs, and the
lookups showed where devices were about to go. So the app adds firewall
rules of its own, which GL's firewall loads along with GL's rules:

- **Over the SIM, only WireGuard.** Nothing leaves over the SIM but the
  router's WireGuard packets, which carry GL's mark `0x8000`. This also
  holds for devices' traffic, with the Enhanced Kill Switch off and the
  tunnel down (verified), and for IPv6 (inferred). Matching the mark
  instead of the VPN server's address means that a new server needs no new
  rules.
- **The router's own traffic through the tunnel.** What the router itself
  starts toward the SIM gets GL's tunnel mark `0x1000`, so it takes the
  tunnel, or nowhere while the tunnel is down, as devices' traffic does.
  Time sync, and anything else the router fetches, then reach the
  internet from the VPN's address (verified for time sync and a test
  request).
- **Lookups at the VPN's DNS server.** GL's main DNS server, which answers
  the router's own lookups and asks Google's servers, passes them through
  the tunnel to GL's DNS server for the tunnel, which asks the VPN's
  (verified).

Choices and their effects:

- A dropped packet stays dropped in Linux's firewall, whatever other rules
  say. The app can't tell a user's change to GL's routing or firewall from
  GL's own, so instead of checking them, it relies on its rules dropping
  everything but WireGuard over the SIM.
- The rules are a file that GL's firewall reads; nothing of Churn's runs on
  the router. If GL's firewall can't load the file, the app removes it
  again and stops the step, since a file the firewall rejects might keep
  it from loading any rules at the next boot (inferred).
- Time sync waits for the tunnel now, and the tunnel needs a clock no
  earlier than at its last handshake. The router's clock resumes from its
  last write to `/etc`, which GL makes every minute, so the tunnel came up
  after a reboot (verified). At worst, it waits about a minute (inferred).
- The rules cover only the SIM. Over another uplink, such as a hotel's
  Wi-Fi or a cable to a hotel's network, everything works as it did
  without them: devices' traffic goes through the VPN with the Enhanced
  Kill Switch on, but the router's own traffic goes past it (inferred).
  Such a network sees no SIM, but it does see the router's hardware
  address, which never changes, so it ties the router to that stay with or
  without the rules. Rule 4 already rules out the router's Wi-Fi. How
  devices connect to the router, by Wi-Fi, the Ethernet port or USB-C,
  makes no difference to the rules.
- The rules leave GL's tunnel options alone. The tunnel's IP Masquerading
  must stay on, for devices' traffic and the router's own alike: without
  it, their packets would enter the tunnel with addresses the VPN server
  doesn't accept (inferred).

### The router's record of SIMs

GL's firmware records every card the router uses on its flash, where the
record outlasts power-offs and swaps (all verified; development.md,
"Clearing the SIM history"):

- **The system log** goes to a file. It pairs each card's ICCID with its
  IMSI and names the network and cell of each registration, so it is a
  location history of the router. GL keeps it in two files of up to
  128 MB, which on our router went back seven weeks, to its first day.
- **GL's card files** keep each card's settings, such as its APN, PIN and
  roaming, under its ICCID, and are never pruned.
- **GL's eSIM manager** keeps a record of each eSIM chip's profiles, and a
  log.

Whoever gets hold of the router or its admin password can read this
record, which links the router's cards to each other. So the app clears the
record at setup, at step 3 of every swap once the new card has its IMEIs,
and from the router screen while the log goes to the flash:

1. It turns off the system log's file. The log then stays in a 64 KB
   buffer in RAM, which every power-off empties.
2. It deletes the log files.
3. It removes every card but the current one, at step 3 the new one, from
   GL's card files and the eSIM manager's records, and empties the eSIM
   manager's log.
4. Except at setup, it overwrites the flash's free space with zeros. A
   deleted file stays readable on the raw partition until something
   overwrites it: before the clearing, the partition held 460 copies of an
   old card's ICCID, of which only 270 were in files. The zeros stay, as
   files, apart from 512 MB of free space, so deleted data can only be in
   those 512 MB, and the next overwrite fills only them.

Choices and their effects:

- The restore script puts the log setting back (section 5), but what the
  app deleted, the old cards' settings included, doesn't come back.
- A card used again after a clearing has lost its settings, so GL treats
  it as new. It searches for a working APN once, trying the APNs it knows
  for the card's network until one works, and saves that one again
  (verified with the built-in eSIM's profile). An APN or PIN typed in by
  hand has to be typed in again. Rule 8 makes this rare.
- At step 3 the card files may name no card at all until GL records the
  new card when it goes online. GL treats a card it hasn't seen as new
  anyway, so this changes nothing for it (inferred).
- The router's clock doesn't need the log. At power-on it resumes from the
  router's last write to `/etc`, and GL writes other files there every
  minute (verified with the SIM online; see "The router's own traffic"
  above).
- Overwriting all 2.9 GB of free space took about 3 minutes with our
  script on our router, and close to 5 minutes in the app, which keeps
  itself alive meanwhile as during a swap step. Only the first overwrite
  does that. Later ones fill the 512 MB that the zero files leave free,
  which should take under a minute (inferred from the first one's speed).
  For the last seconds of an overwrite the partition is full and GL can't
  save anything; GL's files stayed intact in our test.
- Setup leaves the overwrite to the first swap's step 3, so that trying
  the app doesn't begin with a wait of minutes. Step 3 waits for the
  router to start anyway, and the router goes online only at step 4, at
  another place and time, so the longer wait keeps nobody offline. Until
  then, what setup deleted stays readable to whoever gets hold of the
  router and reads its raw partition. Nothing is added to it while the
  log is off, though, and until the first swap the router keeps its
  factory IMEIs, which the networks have already seen with the cards it
  used before Churn.
- The zero files count as used storage, so `df` shows the partition about
  88% used (verified on a disk image), and 512 MB are left for GL and
  anything installed on the router. The restore script deletes them. If
  they are gone, as after a factory reset, the next overwrite fills all
  the free space again.
- Our router's 7.8 GB flash rates its own wear in steps of 10%, and after
  our tests it still reported under 10% used (verified). An overwrite
  after the first writes about 0.5 GB, so if each block takes 1,000
  writes, a step is about 1,400 overwrites (inferred).
- The overwrite reaches what the flash's controller shows as the
  partition. The flash chips underneath may keep old data for a while
  beyond that, as flash memory does (inferred).
- ext4, the partition's file system, keeps back free space that even root
  can't fill, 16 MB on our router, so that it can finish writes it has
  already accepted on a full partition. A fill with large files reaches
  the single free blocks between small files last, so those are the ones
  the reserve leaves free, and old versions of GL's settings files sit
  there: two days after our first clearing, the raw partition held 8
  copies of an earlier card's ICCID, all in free space in old versions of
  a settings file that names the card in use, and none in the file
  system's journal (verified). So the overwrite sets that reserve to 0
  while it runs, puts it back at the end or when it is stopped (a reboot
  does too), and fills any single blocks its large files leave with 4 KB
  files. On our router, the overwrite then left no card's ICCID on the
  raw partition outside the files that name it (verified). On a disk
  image with 300 old versions of a settings file, it left 299 without
  these changes and none with them (verified on the image).

## 8. Usage rules

The app and the README show these rules in this wording. Here, each is
followed by its reason.

Each card's network records where and when the card was used. Churn's new
IMEIs keep these records apart, and the rules keep everything else from
tying them to each other or to you.

A card here is a normal SIM card or an eSIM adapter card, and Churn knows
two kinds. A travel card is used on the go and never again once you've
swapped it out. A place card is a new card for one place you return to,
other than home and work, and you use it there on every visit and nowhere
else.

Each rule closes a way to tie cards together, or to the user, that new
IMEIs leave open: a place, a device, account or identifier that goes along
from one card to the next, a name, or the timing of a swap (section 1).
Rules 1 to 8 allow no compromise: breaking one ties cards together or to
the user, whatever the others do. Rules 9 to 13 make that harder still,
and some say what to do when one can't be followed.

Always follow these rules:

1. Switch the router off before you get home, to work or to any other place
   you return to, and keep it switched off or without a card there. The only
   exception is a place card at its own place.

   Why: a router with a card registers wherever it is switched on, even in
   airplane mode, while one without a card stays silent (section 6). A
   place the user returns to points to them, so a card that registers there
   is tied to the user and to every other card used there. Switching off
   before arriving keeps the router from ever registering there.

   Home and work point to the user most directly, and a phone with a SIM is
   usually on there, right next to any card, so no card is used there. At
   other places the user returns to, a place card is allowed: it ties only
   the visits to its own place, where the user would otherwise go online
   with their phone, whose SIM would tie those visits to the user and to
   everywhere else they go.

2. Before you leave home or work with the router, remove the SIM card or
   disable the eSIM profile in every phone, tablet, watch or laptop you
   carry, and keep it that way until you're back home or at work.

   Why: a phone, or any other device with an active SIM, that travels with
   the router is seen at the same cells as each of the router's cards.
   Matching the two timelines ties every card to the device, and usually to
   its owner. Airplane mode only while the router is on isn't enough: the
   device's last cell before it goes quiet and its first after it comes
   back frame each of the router's sessions. Removing the SIM card or
   disabling the eSIM profile is also safer than airplane mode, which one
   tap undoes. We measured that a GrapheneOS phone transmits nothing with
   its eSIM profile disabled, even with airplane mode off.

   Only getting back home or to work ends this. At a place card's place, a
   device with its SIM on would be seen at the same cells and times as the
   place card, which ties the card to the device and so to its owner.
   Anywhere else, each time the device comes back on during a trip frames
   the router's sessions more tightly.

3. Use the router only with devices that run Linux, or an Android system
   built for privacy, such as GrapheneOS. That rules out Windows, macOS,
   iPhones, iPads, Chromebooks and the Android that most phones and
   tablets come with.

   Why: a device used with the router travels with it, and its accounts, or
   the SIM it usually holds, tie it to its owner. If it tells its maker
   where it is, that timeline matched against a carrier's records ties
   every card the router used along the way (inferred). A closed-source
   system can do so whatever its settings say, and nobody outside can check
   what it sends. Linux, and Android systems built for privacy such as
   GrapheneOS, are open source, so what they send can be checked. What is
   known about the others:

   - In 2017, Android phones sent the cells around them to Google with
     location services off, even without a SIM and after a factory reset;
     Google said it had discarded the data and would stop
     ([Quartz](https://qz.com/1131515/google-collects-android-users-locations-even-when-location-services-are-disabled)).
   - In 2021, an idle Pixel and an idle iPhone contacted Google and Apple
     every 4.5 minutes on average and sent the IMEI, the hardware serial
     number and the SIM's IMSI, even after the user opted out of telemetry
     ([study](https://www.scss.tcd.ie/Doug.Leith/pubs/apple_google2.pdf)).
   - With location services on, Windows sends the Wi-Fi networks and cells
     around it to Microsoft, which says it removes what identifies the
     device ([Microsoft](https://support.microsoft.com/help/4468240)), and
     Windows Home and Pro can't turn off the diagnostic data they send
     ([Microsoft](https://learn.microsoft.com/en-us/windows/privacy/configure-windows-diagnostic-data-in-your-organization)).

4. Connect to the router by cable only, and keep its Wi-Fi off.

   Why: Wi-Fi broadcasts the router's network name and hardware address
   wherever it goes. Anyone nearby can record them, and Wi-Fi location
   databases learn them from the phones and laptops that see them, those
   connected to the router included. The same name and address then tie
   each card's places to the next card's. A cable broadcasts nothing.

5. Send all traffic through the router's VPN, and don't use the router
   without it.

   Why: without a VPN, every service used through the router sees the
   current card's IP address and logs it with the account, so one account
   ties all cards together, as the IMEI would. The carrier also sees the
   traffic, whose pattern can probably recognize the same devices across
   cards (inferred). A VPN on the router covers every device behind it. The
   app keeps traffic from going around it: it swaps only with a WireGuard
   VPN set up, turns on GL's Enhanced Kill Switch, and adds firewall rules
   that let nothing but the VPN use the card, the router's own traffic
   included (section 7). Whether that also holds while the router boots is
   open (section 10).

6. Put cards only into the SIM 2 tray, never put a second profile on an
   eSIM adapter card, and don't use the router's built-in eSIM.

   Why: each part keeps an identifier from going out with more than one
   card, or a card from registering before it has its IMEIs.

   - The built-in eSIM's EID never changes and goes to the provider's
     server with every profile download, which ties all its profiles
     together. With a profile enabled, it also emits for more than 8 s at
     every boot while slot 2 is set to it (section 6).
   - An eSIM adapter card has an EID too, so any second profile on it is
     tied to the first.
   - A card in the SIM 1 tray emits for more than 8 s at every boot,
     whatever the settings. A card in the SIM 2 tray stays off until the
     app has written its IMEIs (sections 6 and 7).

7. Start each swap right after you're done with the old card, before you
   switch the router off. If you've switched it off already, take the old
   card out before you switch it on again, and start the swap well away from
   home, work and any other place you return to. Switch the new card on
   later, and somewhere else.

   Why: steps 1 to 3 never show the new card: it stays off until step 3 has
   written its IMEIs, and first registers at step 4's power-on (sections 4
   and 7). What they can show is the old card. When the swap starts right
   after the user is done with that card, the router is still on, so step 1
   needs no power-on. In step 2, the user switches the router on again where
   step 1 ran. Slot 2 is then on the built-in eSIM, so only the eSIM's short
   bursts go out. These likely carry no IMEI; if they do, which only 3G
   would allow, they carry the old card's, which show nothing new where that
   card was just in use (section 4). So steps 1 to 3 may run wherever the
   user was using the old card, a place card's own place included. At the
   first swap, that can be home or work, if the router was used there before
   Churn.

   If the user switched the router off without starting the swap, for
   example because they forgot, step 1 needs a power-on, and a card in the
   router registers at every power-on, on any network (section 6). With the
   old card still in, the swap's place and time would be that card's last
   sighting, and the new card's start would have to keep away from that as
   well. With the old card out, that power-on emits nothing (section 6),
   which leaves only step 2's bursts. Those could carry the old card's IMEIs
   only if a 3G network asks for them, and even then they show only the old
   card. Away from the places that point to the user, that only adds a
   sighting to the old card's history, and the new card's later start
   elsewhere keeps the two apart. Sending users back to where the old card
   may be used would be safer still, but few would go.

   Step 4's power-on is the new card's first sighting. Where and when the
   old card went quiet, one Mudi 7 IMEI stopping and another starting would
   be easy to match, even across two carriers' records (section 1). Later
   and somewhere else, the new card's start doesn't line up with the old
   card's end.

8. Once you've swapped out a travel card, destroy it away from home. If you
   run out of new cards, keep using the current one until you get a new
   one.

   Why: a travel card put back in brings back its IMSI and ICCID, which
   ties its earlier use to today, and an eSIM adapter card brings back its
   EID as well (rule 6). The router records only the current card, so the
   old card also gets new IMEIs, which shows its issuer that the IMEI was
   rewritten (section 4). Keeping the current card ties nothing new, which
   makes it the fallback. A used travel card found with the user ties them
   to its whole history, so it is destroyed: cut through the chip, and for
   an eSIM adapter card the adapter goes too, since its EID ties its
   profiles together (rule 6). A place card is the one card that goes back
   in, at its own place only (rule 1).

For maximum privacy, also follow these rules where you can:

<!-- markdownlint-disable MD029 -->
9. Use cards that aren't registered to your name.

   Why: a card registered to a name ties every IMEI it is used with, and
   every place, to that name. Churn can't undo that.

10. Get each new card from a different provider than the last one and than
    your place cards. If you can't, start using it somewhere else and some
    days after you stopped using the last one.

    Why: a provider that issues two consecutive cards sees one Mudi 7 IMEI
    stop and another start, both with a rare TAC and possibly in the same
    area, so timing and place could tie them together (inferred). A
    different provider sees only one of them. A place card's provider sees
    it stop at the end of every visit, so a new card from that provider
    could be matched to it in the same way. Where the same provider can't be
    avoided, a different place and a gap of some days make the stop and the
    start harder to match.

11. Vary when you swap cards, and where you switch the radio on and off.

    Why: regular habits, such as always swapping on the same day or at the
    same station, can tie cards together without any identifier.

12. Pay for the VPN in a way that isn't tied to your name.

    Why: the VPN provider sees each card's IP address in turn, all under
    one VPN account. If that account is tied to a name, so are all the
    cards.

13. Block the SIM 1 tray, for example with a drop of hot glue.

    Why: a card put into the SIM 1 tray by mistake emits at the next boot,
    wherever that happens (rule 6). Step 3 stops if it finds one, but only
    after that boot. A new card put there in a swap has then registered
    where and when the old one went quiet, which ties the two, and at home,
    at work or a place card's place it ties the new card to that place too.
    A blocked tray rules the mistake out.
<!-- markdownlint-enable MD029 -->

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

Hardware, other:

- Do the built-in eSIM's short bursts at power-on carry the IMEI? Over LTE
  and 5G they shouldn't, but over 3G a network may ask for it (section 4).
- Does GL's firmware ever write the IMEIs itself, for example after an
  upgrade or a factory reset?
- Does GL's upgrade page keep the paths listed in `/etc/sysupgrade.conf`?
- Where is "Power On with Charger" stored, so the app can check it?
- Should a rotation clear the modem's cached network state? The modem offers
  commands for it.
- Do Android phones other than the one we tested accept the router's USB
  Ethernet?
- Does GL's Enhanced Kill Switch also block all traffic while the router
  boots, and when a tunnel fails on its own? Rule 5 depends on it. GL's
  documentation says that a tunnel's traffic fails over to "All Other
  Traffic" when the tunnel fails and its own "Kill Switch" is off, and
  that the Enhanced Kill Switch takes the place of "All Other Traffic" and
  blocks all traffic that matches no tunnel. With only the Enhanced Kill
  Switch on, a website no longer loaded after we disconnected the tunnel,
  and the routing rules that block traffic stayed in place (verified).
  Churn's firewall rules block what would leave over the SIM in either
  case, once GL's firewall has loaded them (section 7). Does it load them
  before the modem goes online at boot?
- Does the VPN server, which is the same on every SIM, link the SIMs? Over
  the SIM the router sends nothing else (section 7).
- Can the app give the router's Wi-Fi a new network name and hardware
  address at every swap? OpenWrt has a setting for each; GL's firmware
  may override them. If it can, Wi-Fi would tie nothing across swaps, and
  rule 4 could allow it after a swap, away from places the user returns
  to.
- Does GL's firmware write its log file again once the restore script has
  put the setting back? The app turns it off the same way, which worked
  (section 7).

Design:

- Should Churn's firewall rules also cover an uplink over Ethernet or
  Wi-Fi? Over one, the router's own traffic goes past the VPN (section 7).
  The rules would need the names of those uplinks' interfaces, which we
  haven't seen yet.
- Do real Mudi 7 serials fall in a narrow range? A random serial far
  outside it could stand out. Serials are uniform over all 6 digits until
  this is known.
- Should each place card get a VPN account of its own, which the app would
  switch with the card? All cards share one account, so a provider that
  keeps logs could join them, and the place cards would lead that history to
  the user's places. For now we assume a provider that keeps no logs
  (section 1).

Legal and distribution:

- Is rewriting an IMEI legal in Switzerland and other markets?
- Does Google Play's Device and Network Abuse policy allow an app that
  changes IMEIs?

## 11. Relation to blue-merle

[blue-merle](https://github.com/srlabs/blue-merle) and its fork
[blue-merle v2](https://github.com/WSchlesner/blue-merle-v2) rewrite the
IMEI on GL.iNet routers and were the starting point for Churn. Churn differs
in ways that follow from the sections above: no package is installed on
the router, Churn's IMEIs keep the router's own TAC instead of a pool of
other devices' TACs, and every swap is a guided procedure that teaches the
usage rules. blue-merle v2 was also the source for the command that writes
IMEI 2.
