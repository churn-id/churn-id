Churn
=====

This folder belongs to Churn, an Android app that gives this router new
IMEIs with every SIM swap: https://github.com/churn-id/churn-id

  secret      32 random bytes, the key Churn derives the IMEIs with
  factory     IMEI 1 and IMEI 2 as they were when Churn was set up
  log_file    the file GL's system log went to before Churn turned that off
  restore.sh  writes those IMEIs back, puts GL's log setting back and
              removes Churn's firewall rules

Churn's firewall rules are in /etc/nftables.d/churn.nft. Over the SIM they
let out only the VPN's own packets, and they send the router's own traffic
through the VPN. They don't cover internet connections over Wi-Fi or the
Ethernet port.

GL's system log records every SIM card's ICCID and IMSI and the cells it
used. Churn turns off GL's copy of it in /etc/sys_log, so the log stays in
memory and is gone at power-off. At setup and at every swap Churn also
removes all but the current card from GL's records and overwrites the free
space on the flash.

A factory reset deletes this folder and the rules. Before one, and if you
stop using Churn, write the factory IMEIs back over SSH:

  sh /etc/churn/restore.sh

The script turns airplane mode on first and works without the app.

Firmware upgrades that keep settings keep this folder and the rules,
because Churn lists both in /etc/sysupgrade.conf.
