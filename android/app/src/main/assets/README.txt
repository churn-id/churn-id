Churn
=====

This folder belongs to Churn, an Android app that gives this router new
IMEIs with every SIM swap: https://github.com/churn-id/churn-id

  factory     IMEI 1 and IMEI 2 as they were when Churn was set up
  card        the ICCID of the card in SIM 2, and the random IMEIs Churn
              gave that card
  log_file    the file GL's system log went to before Churn turned that off
  restore.sh  writes the factory IMEIs back, deletes card, puts GL's log
              setting back, deletes Churn's zero files, removes Churn's
              firewall rules and, last, deletes this folder
  zeros       a link to /overlay/churn-zeros, where Churn keeps its zero
              files (below)

Churn's firewall rules are in /etc/nftables.d/churn.nft. Over the SIM they
let out only the VPN's own packets, and they send the router's own traffic
through the VPN. They don't cover internet connections over Wi-Fi or the
Ethernet port.

GL's system log records every SIM card's ICCID and IMSI and the cells it
used. Churn turns off GL's copy of it in /etc/sys_log, so the log stays in
memory and is gone at power-off. At setup and at every swap Churn also
removes all but the current card from GL's records, and at every swap it
overwrites the free space on the flash. It keeps the zeros it wrote as
files in /overlay/churn-zeros, apart from 512 MB of free space, so that
the next overwrite only has to fill those 512 MB. That folder is outside
/etc, so firmware upgrades don't copy the zero files, and du or grep over
/etc don't read them.

A factory reset deletes this folder, the rules and the zero files. Before
one, and if you stop using Churn, write the factory IMEIs back over SSH:

  sh /etc/churn/restore.sh

The script turns airplane mode on first and works without the app. Once
it has undone everything else, it deletes this folder and its line in
/etc/sysupgrade.conf, so the app treats the router as new.

Firmware upgrades that keep settings keep this folder and the rules,
because Churn lists both in /etc/sysupgrade.conf.
