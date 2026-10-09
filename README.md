# Churn

Churn is an Android phone app for the GL.iNet Mudi 7 (GL-E5800) travel
router. The app guides you through the process of swapping the router SIM
card in a privacy-preserving way, by changing the router's IMEIs (the device
numbers the mobile network sees). Together with the rules below, this keeps
your mobile carrier from building a location history that leads back to
you.

**Status:** in development. The app doesn't do anything yet.

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
  because those places tie it back to you.

Ordinary phones can't change their IMEI, but the Mudi 7 can. So when you're
out with the router, you switch off your phone's own SIM and get online
through the router. Each time you swap the router's SIM, Churn gives the
router new IMEIs.

## What Churn protects, and what it doesn't

Churn stops the router's IMEI from linking your old SIM to your new one.

It doesn't hide:

- where the router is while it's online;
- what you do online, or the accounts you use;
- who bought a SIM, if it's registered to a name;
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

1. **Your phone.** It knows the most about you. An ordinary Android phone
   or an iPhone keeps reporting to Google or Apple, even with location
   services off, and you can't check what it sends. Use a phone without
   Google services built in, such as one running GrapheneOS.
2. **Your accounts.** Every account you log into connects what you do to
   you. Use few, and keep the ones you want private apart from the ones in
   your name.
3. **Your connection.** A VPN hides your SIM's address from the websites
   and apps you use. Pay for it in a way that isn't tied to your name.
4. **Your SIM.** A SIM registered to your name tells the network who you
   are, whatever the router does. Use SIMs that aren't, where the law
   allows it.
5. **Your router's device numbers.** This is the piece Churn handles.

Churn makes sense for you if you've done the first four and don't want
your carrier's records to add up to a location history that leads back to
you. If you haven't,
start there: it protects you more than Churn can, and Churn can't make up
for it. Either way, Churn needs you to follow the rules below; it doesn't
work on its own.

## What you need

- A GL.iNet Mudi 7 (GL-E5800). We test with GL firmware 4.10.0.
- An Android phone without Google services built in, such as one running
  GrapheneOS.
- A USB-C cable to connect the phone to the router, ideally the router's
  own cable.
- The router's admin password.
- A VPN service, set up on the router.
- A new SIM for each swap, either
  - a normal SIM card, or
  - an eSIM adapter card, such as [JMP's](https://jmp.chat/esim-adapter),
    containing one eSIM profile.

## Rules

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
8. Use the router only with phones that have no Google services built in,
   such as phones running GrapheneOS, and not with an iPhone. Turn off
   location services on every other device you use with it.

For maximum privacy, also follow these rules where you can:

<!-- markdownlint-disable MD029 -->
9. Use SIM cards that aren't registered to your name. The same goes for
   eSIM profiles.
10. Get each new SIM card or eSIM profile from a different provider than
    the last one. If you can't, start using the new one somewhere else and
    some days after you stopped using the last one.
11. Don't reuse a card once you've swapped it out. If you run out of new
    cards, keep using the current one until you get a new one.
12. Vary when you swap SIMs, and where you switch the radio on and off.
13. Pay for the VPN in a way that isn't tied to your name.
14. Block SIM slot 1, for example with a drop of hot glue.
<!-- markdownlint-enable MD029 -->

The reasons for each rule are in the
[design document](docs/design.md#8-usage-rules).

## Legal

Changing an IMEI is a criminal offense in some countries, for example in the
United Kingdom. Check the law where you use the router.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
