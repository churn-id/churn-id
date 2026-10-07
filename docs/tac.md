# TAC choice for derived IMEIs

Appendix to [design.md](design.md). The first 8 digits of an IMEI are the
type allocation code (TAC), which identifies the device model. This document
explains why Churn's derived IMEIs keep the router's own factory TAC instead
of using a pool of other devices' TACs. *Verified* means checked in a source
linked here or on a MUDI 7 we tested; *inferred* means reasoned but not
tested.

## Choice

Each derived IMEI keeps the TAC of the factory IMEI it replaces. Only the
6-digit serial is derived (design.md, section 4), followed by the Luhn check
digit. The IMEISV software version stays untouched, which only makes sense
if the TAC stays the router's own.

On the MUDI 7 we tested, IMEI 1 and IMEI 2 both use TAC `35609021`. fccid.io
and imei.info list it as the GL.iNet MUDI 7 (verified); Osmocom's TAC
database has no manufacturer for it. The `35` prefix means BABT issued it.

## Why

The adversary is the carrier and anyone who later reads its records. They
want to link the identity used before a SIM swap to the one used after it.

1. **The network sees the modem anyway.** When attaching, the modem sends
   its radio capabilities: bands, 3GPP release, features. The RG650V-EU
   announces a Release 17 capability set that some European networks even
   fail on (GL.iNet forum thread 68449, verified). Another device's TAC
   doesn't change that, so it can't widen the group of devices the router
   looks like beyond "devices with this modem". It only adds a mismatch: a
   phone TAC with this modem's capability set says the IMEI was rewritten
   (inferred: whether carriers check this live is unknown, but the data is
   in their own core network).
2. **A published pool is a signature.** The repository is public, so any
   fixed list of foreign TACs becomes "Churn user if the TAC is in this list
   and the modem is an RG650V". The router's own TAC is shared with every
   unmodified MUDI 7.
3. **Collisions with real phones.** Popular phone TACs have most of their
   1,000,000 serials in use, so a random serial is likely some real phone's
   IMEI (inferred). On the same network that looks like a clone, and if that
   phone is blocklisted, the carrier refuses the router. A niche router TAC
   has far fewer units, so collisions are rare.
4. **Carrier behavior stays as before.** Carriers apply policy per TAC; for
   example, Cisco's mobility management selects operator policy by TAC
   (verified). With the own TAC, plans, tethering rules and data-SIM checks
   see the same device class as without Churn. A phone TAC changes that
   unpredictably, and using one to get around hotspot rules can breach the
   SIM's terms.

The cost: in the carrier's records both identities carry the MUDI 7 TAC, a
rare device. Keeping them apart then rests on time and place, which the swap
procedure and the usage rules separate (design.md, sections 7 and 8). With a
foreign TAC, point 1 gives the same linkage through the capability set, plus
the signature of point 2.

## Rejected options

- **blue-merle v2's pool** (`files/usr/share/blue-merle/tac_pool.json`): 7
  TACs (US iPhones, a US Galaxy S23 Ultra, GL-X3000, NETGEAR M6 Pro, MiFi X
  Pro), all marked unverified and chosen for US bands. Its comment cites
  forum thread 68449 for throttling of LTE-only TACs; the thread is about
  Release 17 registration failures in Europe and doesn't mention TACs
  (first page checked).
- **The original blue-merle pool** (`files/lib/blue-merle/imei_generate.py`):
  16 TACs with no source or explanation.
- **A large phone pool from a TAC database:** the GSMA database is licensed,
  and open sources are crowd-sourced. Even verified, such a pool fails points
  1 to 4.
- **A random 8-digit TAC:** most are unallocated, which a carrier can see in
  its own GSMA TAC list.

## Possible extension

The router's TAC belongs to the MUDI 7, not to every product with the
RG650V-EU. Two kinds of TAC could widen the group without changing the radio
fingerprint, each only once seen on a real device and not only in a
database:

- other TACs GL.iNet uses for the MUDI 7 with the same EU modem, for example
  in later batches. Not the North American variant, whose bands differ.
- a TAC Quectel uses for the RG650V-EU module in other products.

None is known yet, so derived IMEIs use the router's own TAC only.

## Open questions

- **Serial range:** real MUDI 7 serials may sit in a narrow block if few
  units shipped. A derived serial far outside that block could stand out to
  someone who sees many MUDI 7 IMEIs (inferred). Serials stay uniform over
  all 6 digits until IMEIs from more units are known.
- **Legality:** rewriting an IMEI is a criminal offense in some countries,
  for example under the UK's Mobile Telephones (Re-programming) Act 2002.
  Switzerland and other markets are not checked yet.

## Sources

- blue-merle: <https://github.com/srlabs/blue-merle>
- blue-merle v2: <https://github.com/WSchlesner/blue-merle-v2>
- GL.iNet forum thread 68449: <https://forum.gl-inet.com/t/68449>
- Cisco MME operator policy by IMEI-TAC:
  <https://www.cisco.com/c/en/us/td/docs/wireless/asr_5000/21-28/mme-admin/21-28-mme-admin/m_oppol_imei-tac.html>
- Osmocom TAC database: <https://tacdb.osmocom.org/>
- GSMA TAC allocation: <https://www.gsmaservices.com/device-services/tac-allocation/>
