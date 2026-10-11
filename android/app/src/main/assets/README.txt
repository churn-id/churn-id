Churn
=====

This folder belongs to Churn, an Android app that gives this router new
IMEIs with every SIM swap: https://github.com/churn-id/churn-id

  secret      32 random bytes, the key Churn derives the IMEIs with
  factory     IMEI 1 and IMEI 2 as they were when Churn was set up
  restore.sh  writes those IMEIs back

A factory reset deletes this folder. Before one, and if you stop using
Churn, write the factory IMEIs back over SSH:

  sh /etc/churn/restore.sh

The script turns airplane mode on first and works without the app.

Firmware upgrades that keep settings keep this folder, because Churn lists
it in /etc/sysupgrade.conf.
