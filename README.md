# Churn

Churn is an Android app for the GL.iNet MUDI 7 (GL-E5800) travel router.
When you swap the router's SIM through the app, Churn also gives the router
new IMEIs, the device numbers the mobile network sees. That way the network
can't use the router to link the SIM you used before the swap to the one you
use after it.

**Status:** in development. The app doesn't do anything yet.

## What Churn protects, and what it doesn't

Churn stops the router's IMEI from linking your old SIM to your new one.

It doesn't hide:

- where the router is while it's online;
- what you do online, or the accounts you use;
- who bought a SIM, if it's registered to a name;
- that the device is a MUDI 7, which the network can tell from its radio.

Following the rules below matters as much as the app itself. The
[design document](docs/design.md) explains how Churn works and why.

## What you need

- A GL.iNet MUDI 7 (GL-E5800). We test with GL firmware 4.10.0.
- An Android phone and a USB-C cable to connect it to the router.
- The router's admin password.
- A new SIM for each swap: a physical SIM, or a profile on a removable eSIM
  card.

## Rules

1. At home, at work and anywhere else you return to, keep the router
   switched off or without a SIM.
2. Before you get to such a place, unplug everything and switch the router
   off.
3. Connect to the router by cable only, and keep its Wi-Fi off.
4. Use physical SIMs or removable eSIM cards, not the router's built-in
   eSIM.
5. Where you can, use SIMs that aren't registered to your name.
6. Get each new SIM or profile from a different provider than the last one.
7. Don't reuse a SIM or eSIM card once you've swapped it out.
8. Vary when you swap SIMs, and where you switch the radio on and off.
9. Keep the battery in the router.

The reasons for each rule are in the
[design document](docs/design.md#8-usage-rules).

## Legal

Changing an IMEI is a criminal offence in some countries, for example in the
United Kingdom. Check the law where you use the router.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
