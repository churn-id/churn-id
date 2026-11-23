# Churn

Churn is an Android phone app for the GL.iNet Mudi 7 (GL-E5800) travel
router. The app guides you through the process of swapping the router SIM
card in a privacy-preserving way, by changing the router's IMEIs (the device
numbers the mobile network sees). Together with the rules below, this keeps
your mobile carrier from building a location history that leads back to
you.

**Status:** released, see [Installing](#installing). Before each release we
run the tests in [release-tests.md](docs/release-tests.md) on our Mudi 7,
but parts of what Churn relies on are still untested
([router-tests.md](docs/router-tests.md) lists them).

## Why change SIM and IMEI?

Whenever your phone is on, the mobile network knows roughly where it is,
and your carrier records that together with your SIM and the phone's IMEI.
Over time this adds up to a location history much like the one Google and
Apple collect from phones, if less precise, and no setting turns it off.
Carriers keep these records for months or years, and authorities can ask
for them.

Such a history gives away whose it is even without a name on the SIM,
because it shows where you sleep and where you work. Changing the SIM
regularly cuts it into short pieces, but they only stay apart if two more
things hold:

- The IMEI changes too. If it stays the same, it ties each new SIM to the
  last one.
- No piece includes your home, your work or other places you return to,
  because those places tie it back to you. The one exception is a place
  card's piece, which holds only its own place (see the rules).

Ordinary phones can't change their IMEI, but the Mudi 7 can. So when you're
out with the router, you switch off your phone's own SIM and get online
through the router. Each time you swap the router's SIM, Churn gives the
router new IMEIs.

## What Churn protects, and what it doesn't

Churn stops the router's IMEI from linking your old SIM to your new one.
It also clears the list of earlier SIMs that the router's firmware keeps.

It doesn't hide:

- where the router is while it's online;
- what you do online, or the accounts you use;
- who bought a SIM;
- that the device is a Mudi 7, which the network can tell from its radio.

Churn only helps together with a VPN. Without one, the websites and apps you
use can link your SIMs through your accounts.

Following the rules below matters as much as the app itself. The
[design document](docs/design.md) explains how Churn works and why.

## Is Churn for you?

Privacy is a puzzle with many pieces, and Churn is one of the last ones. It
removes a single link between your SIMs, the router's IMEIs. Any other link
you leave in place is enough on its own to tie your SIMs together, so Churn
only adds something once the larger pieces are done.

Roughly from larger to smaller, the pieces are:

1. **Your devices.** Your phone knows the most about you. Ordinary Android
   phones, iPhones, and computers with Windows or macOS keep talking to
   Google, Apple or Microsoft, even with location services off, and you
   can't check what they send. Use a phone with an Android system built
   for privacy, such as GrapheneOS, and a computer with Linux. A SIM in your
   phone tells its network wherever you go, and the phone doesn't need one:
   it can go online over Wi-Fi or a cable at home and through the router
   elsewhere, and Signal can replace calls over the mobile network. Bear in
   mind that some countries, such as Germany, don't put through emergency
   calls from a phone without a SIM.
2. **Your accounts.** Every account you log into connects what you do to
   you. Use few, and keep the ones you want private apart from the ones in
   your name.
3. **Your connection.** A VPN hides your SIM's address from the websites
   and apps you use. Pay for it in a way that isn't tied to your name.
4. **Your SIM.** A SIM registered to your name tells the network who you
   are, whatever the router does. Use SIMs that aren't, where the law
   allows it, and pay for them in a way that isn't tied to your name.
   Where SIMs must be registered, you may still be able to buy a profile
   for an eSIM adapter card from a provider abroad that asks for no ID
   and takes Monero, such as [Silent Link](https://silent.link/) or
   [nadanada](https://nadanada.me/). The
   [design document](docs/design.md#8-usage-rules) says what to watch out
   for, under rule 9.
5. **Your router's device numbers.** This is the piece Churn handles.

Churn makes sense for you if you've done the first four and don't want
your carrier's records to add up to a location history that leads back to
you. If you haven't,
start there: it protects you more than Churn can, and Churn can't make up
for it. Either way, Churn needs you to follow the rules below; it doesn't
work on its own.

## What you need

- A GL.iNet Mudi 7 (GL-E5800). We test with GL firmware 4.10.0. The app
  checks the router before each swap and stops if a check it relies on
  fails, for example after a firmware upgrade. Not everything Churn relies
  on can be checked that way: [router-tests.md](docs/router-tests.md)
  lists what we have verified and what not yet.
- An Android phone running a system built for privacy, such as GrapheneOS.
- A USB-C cable to connect the phone to the router, ideally the router's
  own cable.
- The router's admin password.
- A VPN service that keeps no logs, set up on the router with WireGuard.
- A new SIM for each swap, unless you put a place card back in, either
  - a normal SIM card, or
  - an eSIM adapter card, such as [JMP's](https://jmp.chat/esim-adapter),
    containing one eSIM profile.

## Installing

Download the APK from the
[latest release](https://github.com/churn-id/churn-id/releases/latest) and,
before you install it, check that we signed it. The SHA-256 of its
certificate must be

`666acf4ae496bf6e6f69333773ccf2b1e4e9bcd6811ce9ec53e0976b0b14009b`

On Linux, `apksigner verify --print-certs churn-<version>.apk` prints it as
the certificate's SHA-256 digest; `apksigner` is a package in Debian and
Ubuntu. Updates need no such check, since Android installs an update only
if it has the same certificate.

## Rules

Each card's network records where and when the card was used. Churn's new
IMEIs keep these records apart, and the rules keep everything else from
tying them to each other or to you.

A card here is a normal SIM card or an eSIM adapter card, and Churn knows
two kinds. A travel card is used on the go and never again once you've
swapped it out. A place card is a new card for one place you return to,
other than home and work, and you use it there on every visit and nowhere
else.

Always follow these rules:

1. Switch the router off before you get home, to work or to any other place
   you return to, and keep it switched off or without a card there. The only
   exception is a place card at its own place.
2. Before you leave home or work with the router, remove the SIM card or
   disable the eSIM profile in every phone, tablet, watch or laptop you
   carry, and keep it that way until you're back home or at work.
3. Use the router only with devices that run Linux, or an Android system
   built for privacy, such as GrapheneOS. That rules out Windows, macOS,
   iPhones, iPads, Chromebooks and the Android that most phones and
   tablets come with.
4. Connect to the router by cable only, and keep its Wi-Fi off.
5. Send all traffic through the router's VPN, and don't use the router
   without it.
6. Put cards only into the SIM 2 tray, never put a second profile on an
   eSIM adapter card, and don't use the router's built-in eSIM.
7. Start each swap right after you're done with the old card, before you
   switch the router off. If you've switched it off already, take the old
   card out before you switch it on again, and start the swap well away from
   home, work and any other place you return to. Switch the new card on
   later, and somewhere else.
8. Once you've swapped out a travel card, destroy it away from home. If you
   run out of new cards, keep using the current one until you get a new
   one.

Without these rules, your cards can be tied to your name, so follow them
wherever you can:

<!-- markdownlint-disable MD029 -->
9. Use cards that aren't registered to your name.
10. Pay for your cards and the VPN in a way that isn't tied to your name.

For maximum privacy, also follow these rules where you can:

11. Get each new card from a different provider than the last one and than
    your place cards. If you can't, start using it somewhere else and some
    days after you stopped using the last one.
12. Vary when you swap cards, and where you switch the radio on and off.
13. Block the SIM 1 tray, for example with a drop of hot glue.
<!-- markdownlint-enable MD029 -->

The reasons for each rule are in the
[design document](docs/design.md#8-usage-rules).

## Removing Churn

Before a factory reset, and before you stop using Churn or give the router
away, tap Remove Churn from this router in the app. It writes the router's
factory IMEIs back and undoes everything else Churn changed. Without the
app, `sh /etc/churn/restore.sh` over SSH does the same.

A factory reset deletes Churn's folder on the router, and nothing else on
the router or its label shows the factory IMEIs. After a reset, only the
phone that ran the last swap can still put them back, from a copy it keeps
with the app's data.

## Legal

Changing an IMEI is a criminal offense in some countries, for example in the
United Kingdom. Making sure that it is legal where you use the router is up
to you. Nothing in this repository is legal advice.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Churn is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. It is distributed in the hope that it will be useful, but without
any warranty, without even the implied warranty of merchantability or
fitness for a particular purpose. [LICENSE](LICENSE) holds the full text.
