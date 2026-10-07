# Churn

Churn is an Android phone app for the GL.iNet Mudi 7 (GL-E5800) travel
router. The app guides you through the process of swapping the router SIM
card in a privacy-preserving way. When you swap the router's SIM through the
app, Churn also gives the router new IMEIs, the device numbers the mobile
network sees. That way the network can't use the router to link the SIM you
used before the swap to the one you use after it.

**Status:** in development. The app doesn't do anything yet.

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

## What you need

- A GL.iNet Mudi 7 (GL-E5800). We test with GL firmware 4.10.0.
- An Android phone and a USB-C cable to connect it to the router, ideally
  the router's own cable.
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
7. Before you leave a place you return to with the router, turn off the
   SIM card or eSIM profile in every phone you carry, and keep it off
   until you're back.

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

The reasons for each rule are in the
[design document](docs/design.md#8-usage-rules).

## Legal

Changing an IMEI is a criminal offense in some countries, for example in the
United Kingdom. Check the law where you use the router.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
