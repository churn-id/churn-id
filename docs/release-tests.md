# Release tests

Before each release, we run the app on a Mudi 7 and a phone as a user
does, to check that it does what [README.md](../README.md) and
[design.md](design.md) say. [router-tests.md](router-tests.md) lists the
claims Churn relies on, each with the test that is to confirm it. This
document puts the tests that need a person in the order a release runs
them, with the release build, and the app's own tests where they help.
Each step names the claims it checks by their IDs, such as W3, and
router-tests.md says what each check takes.

## Full and short runs

A full run comes before the first release, and before any release that
changes a swap step, a command the app sends to the router, `restore.sh`,
Churn's firewall rules or the clearing of the SIM history. It begins at
home and ends at a place that doesn't matter (rule 1), with five swaps
and the removal.

Any other release, such as one that only changes texts or updates a
library, gets a short run: the upgrade (section 1), one swap that puts a
test card in as a travel card and goes online as section 4 describes, and
section 6. A firmware that `TESTED_PLATFORMS` in `Checks.kt` doesn't list
yet goes through router-tests.md's "New firmware" first.

## What you need

- The release build: an APK of the commit to be released, from
  `./gradlew assembleRelease` with the signing variables in
  [CONTRIBUTING.md](../CONTRIBUTING.md#signing). For a full run, also the
  debug build of the same commit, from `./gradlew assembleDebug`.
- For the upgrade, the phone with the previous release, installed from an
  APK signed with the same key, and its data.
- A Mudi 7 with a firmware that `TESTED_PLATFORMS` lists, with GL's
  scheduled reboot and "Power On with Charger" off, a WireGuard VPN set
  up, and the VPN's configuration file.
- A phone with GrapheneOS, and a computer with Linux, connected to the
  router by cable (rule 4), for SSH, GL's web interface, websites and the
  debug logs.
- Two test cards with data: a normal SIM card and an eSIM adapter card
  with one profile, which you can disable and enable again. The run ties
  them to each other, so they are for testing only.
- For a full run, an RF meter and GL's pre-installed profile on the
  built-in eSIM.

A run swaps out the card in the router, which ends its use unless it is
a place card, so start a run once you're done with that card. A full run
deletes the app's data more than once, place cards included, so run it
on a phone whose place cards you don't rely on.

Note the app's version and commit, the phone's model and GrapheneOS
version, and the versions of the GL firmware and the modem. Keep the debug
log on throughout: turn it on in the app's menu right after each install.

The two builds are signed with different keys, so either replaces the
other only after an uninstall, which deletes the app's data. To switch
builds, share the debug log, uninstall the app, install the other build
and turn the debug log on.

## 1. Upgrade

From the second release on, where the router may be on with its card:

1. Install the release build over the previous release, which keeps the
   app's data.
2. Connect: the app asks for neither the router's key nor the password,
   and the router screen still lists the place cards.
3. Router checks pass or don't apply, F1's included: connecting rewrites
   `README.txt` and `restore.sh` in Churn's folder where this version's
   differ (design.md, section 5).

## 2. Router tests

In a full run, at home, with the router's card taken out while the router
was off, both trays empty and no profile enabled on the built-in eSIM, as
tiers 2 and 3 require:

1. Switch to the debug build, connect, and set the router up if it isn't.
2. Run the dry run, the power cycle test, with the RF meter next to the
   router (B8), and the removal and clearing tests, as router-tests.md
   describes.
3. Over SSH from the computer, run `sh /etc/churn/restore.sh`, as
   `README.txt` in Churn's folder says to do without the app. It ends with
   the line that the factory IMEIs are back (F12).

## 3. First use

In a full run, still at home, switch to the release build, which then
meets the router as a new one:

1. The intro shows the legal notice and the rules. Rules, on the Connect
   and router screens, shows them again later.
2. With Churn's Network permission off, Connect fails with a message that
   asks for the permission (C4). Turn it on again.
3. The router's key shows with a fingerprint that
   `ssh-keyscan 192.168.8.1 | ssh-keygen -lf -` on the computer also
   prints.
4. The setup screen shows the factory IMEIs with no warning. Once the
   router is set up, the router screen names them as the factory IMEIs
   and has none of the debug build's test buttons, and Router checks pass
   or don't apply.
5. Turn GL's scheduled reboot on and tap Refresh: the router screen says
   that a swap needs it off, and its swap button is off. Turn it off
   again, delete the VPN and tap Refresh: the same for the VPN. Set the
   VPN up again from its configuration file.
6. Forget this router: the next connection shows the key again and asks
   for the password.
7. With the password saved, plugging the cable in on the Connect screen
   connects by itself, once each time (C6).

## 4. Swaps

In a full run, at a place that doesn't matter (rule 1), with the RF meter
next to the router throughout. The swaps break rules on purpose, to see
what the app does then: they follow one another at one place (rule 7),
use the SIM 1 tray and the built-in eSIM (rule 6), and keep cards that
step 4 says to destroy (rule 8). That is one more reason the test cards
are for testing only. Steps 1 and 3 list what they did, and Router checks
pass or don't apply at every connection, but for D22 from swap 1's step 7
until swap 2's step 3. On the meter:

- nothing while the router shuts down (B7);
- only the built-in eSIM's short bursts when the router is switched on at
  step 2 (B10), with a normal SIM card (B12) or an adapter card (B11) in
  the tray;
- nothing during step 3 (S4, B11, B13), including the first swap's
  reloads for the Enhanced Kill Switch and Churn's firewall rules
  (K8, D19) and its restart of GL's connection check (D23);
- and the card registering when the router is switched on at step 4 (B9).

### Going online

To go online at step 4, switch the router on and turn airplane mode off
on its screen. Each time the run goes online:

- The tunnel comes up and the clock syncs (D16), and GL shows the
  cellular connection as connected (D17).
- The computer loads a website, and a lookup of a made-up name gets the
  answer that the name doesn't exist (D3).
- The router screen names the card (W7), with no warning.

At the first swap of a run, also:

- With the tunnel's own Kill Switch off and the tunnel disconnected in
  GL's web interface, no website loads (K9), and the lookup gets no
  answer (D3). Connect the tunnel again.
- After a reboot from GL's web interface, the tunnel comes up again and
  the clock syncs (D16).

### Swap 1: the first swap, a normal SIM card as a travel card

1. With both trays empty and airplane mode on, turn the Enhanced Kill
   Switch off and enable GL's pre-installed profile on the built-in eSIM,
   both in GL's web interface. Tap Refresh: the router screen warns about
   the profile.
2. Swap the SIM card. Step 1 says that steps 1 to 3 may run at home or at
   work, as the router has its factory IMEIs (W5). Let the phone lock on
   step 1's screen for 10 minutes. Then unlock it, tick that "Power On
   with Charger" is off and tap Start: the step runs (C9), disables the
   profile (E6) and shuts the router down.
3. Put the normal SIM card into the empty SIM 2 tray (W6), switch the
   router on and plug the cable in with the app open: it goes on to step 3
   by itself.
4. Answer step 3's question a minute after it shows, with Travel card
   (W1). The card answers as the eSIM does (E10), and the first clearing
   takes a few minutes.
5. Step 4 asks to destroy no card, since no card was recorded before the
   swap. Tap Done and switch to the debug build. Switch the router on,
   connect, and run the leak watch (D9, D11, D16, D21, U10), which stands
   in for step 4: turn airplane mode off on the router's screen when it
   asks.
6. Switch back to the release build and connect. Its new data gets a copy
   of the factory IMEIs only with the next pair it writes, at swap 2, in
   time for section 5. Then make the checks under "Going online".
7. Turn the Enhanced Kill Switch off in GL's web interface. Over SSH,
   delete Churn's firewall rules, turn GL's connection check back on for
   the SIM and have GL save its system log on the flash again:

   ```sh
   rm /etc/nftables.d/churn.nft && fw4 reload
   uci set kmwan.modem_cpu.disabled=0 && uci commit kmwan
   /etc/init.d/kmwan restart
   uci set system.@system[0].log_file=/etc/sys_log && uci commit system
   /etc/init.d/log reload
   ```

   Tap Refresh: the router screen warns about all four and offers no fix.
   For the first three it says to swap to a new card, and for the log that
   the next swap turns it off. Router checks report D22 as failed, since
   the check can't tell your command from GL.

### Swap 2: the adapter card as a new place card

1. With the normal SIM card online, swap the SIM card. Step 1 no longer
   mentions home and work (W5).
2. At step 2, take the normal SIM card out and put the adapter card in.
   Stop the app in Android's settings and open it again: it shows step 2
   (C10). Switch the router on.
3. Answer step 3's question with Place card. Step 3 lists that it turned
   the Enhanced Kill Switch on, added Churn's firewall rules and turned
   GL's connection check off. The clearing takes under a minute (H16).
4. Step 4 asks to destroy the normal SIM card, which you keep for the
   tests (W4), and says to switch the router on only at the place card's
   place. Go online.
5. The router screen names the place card by the last four digits of its
   ICCID, and by its name once you give it one there (W7), and none of
   swap 1's warnings is left. Note its IMEIs.
6. Uninstall the app, keeping its data, and install it again: the router
   screen still lists the place card, and the app connects without asking
   for the password (C7).

### Swap 3: the normal SIM card again, and step 3's stops

1. Swap the SIM card. At step 2, take the adapter card out, leave the tray
   empty and switch the router on: step 3 stops with slot 2 back on the
   built-in eSIM, and clears nothing (W8).
2. Switch the router off, put the normal SIM card into the SIM 1 tray and
   switch the router on, which makes the card register (B14). Tap Try
   again: step 3 stops at the card before it changes anything (B15).
3. Switch the router off, move the card into the SIM 2 tray, switch the
   router on and tap Try again. While step 3 waits for the router to
   finish starting, pull the cable out: the app says at once that the
   router left the cable (C5). Plug it in again and tap Try again.
4. Cancel at step 3's question, and then cancel the swap at step 2.
   Switch the router on: the meter shows only the eSIM's short bursts
   (W2), and the router screen says that the card in SIM 2 is off (W7).
5. Swap the SIM card again, leaving the card in the tray at step 2, and
   answer step 3's question with Travel card.
6. Step 4 asks to destroy no card, since the card taken out is a place
   card (W4). Go online: GL finds the card's APN again after the clearing
   (H9), and the router screen names it as a travel card, with other IMEIs
   than in swap 1.

### Swap 4: the place card back, with the VPN gone

1. Swap the SIM card. At step 2, take the normal SIM card out and put the
   adapter card in, then restart the phone and open the app: it shows
   step 2 (C10).
2. Switch the router on without the phone's cable, and delete the VPN in
   GL's web interface. Then plug the cable in: step 3 stops while the card
   is still off (W9). Set the VPN up again from its configuration file.
3. Tap Try again and lock the phone, and unlock it a minute later: the
   step has gone on to its question (C8).
4. The question names the place card, and Continue gives it the IMEIs it
   had after swap 2 (W3).
5. Step 4 asks to destroy the normal SIM card (W4).

### Swap 5: the place card kept, and an adapter card without a profile

1. With the router off, take the adapter card out, disable its profile,
   put it back and switch the router on: the router screen names it as an
   adapter card with no profile enabled (E13).
2. Swap the SIM card, leaving the card in the tray at step 2: step 3 stops
   at it as at an empty tray (E13).
3. Switch the router off, take the card out, enable its profile, put it
   back and switch the router on. Tap Try again: step 3 asks nothing,
   since the card is the recorded one, and gives it its IMEIs again.
4. Step 4 asks to destroy no card, since the card is still in SIM 2 (W4).

## 5. Removal

In a full run, at the same place, once the router is off after swap 5:

1. Take the card out, so that no card registers from here on.
2. Before the first release only, reset the router to its factory
   settings in GL's web interface and set its admin password again. Over
   SSH, `/etc/churn/`, `/etc/nftables.d/churn.nft` and
   `/overlay/churn-zeros/` are gone (F13). Connect, trusting the router's
   new key if the reset made one, and set the router up: the setup screen
   takes the factory IMEIs from the phone's copy (F5, F14), and the router
   screen then warns until the next swap.
3. With the router on and connected, forget the place card: the router
   screen no longer lists it. Tap Remove Churn from this router, and
   switch the router off and on: the setup screen shows the factory IMEIs
   with no warning (F7).

## 6. Texts and docs

- Read every screen that the run showed: each says what to do, where and
  why (design.md, section 7), and its rules match README.md word for word.
  A short run reads the screens whose texts the release changed.
- Search the debug logs for the last six digits of each test card's ICCID
  and of every IMEI that the router screen showed: none appears (P6). In a
  full run, the release build's log, shared at the end, holds the last
  three swaps it began, from swap 3's second start on, and nothing from
  before them (design.md, section 7).
- README.md's status line and its firmware version, which
  `TESTED_PLATFORMS` lists, and the status line of design.md fit the
  release.

## Results

Open an issue for the run, with the versions noted above and whether the
run was full or short. Each failure gets an issue of its own, linked
there, and after a fix, the steps it affects run again. The release waits
until the run has passed, or until the run's issue says why a failure
doesn't hold it up. Claims that the run confirmed for the first time
become *verified* in router-tests.md.
