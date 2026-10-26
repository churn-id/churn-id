#!/bin/sh
# Churn: writes the router's factory IMEIs back, from /etc/churn/factory,
# deletes Churn's record of the card in SIM 2, puts back GL's setting for
# saving the system log, deletes Churn's zero files, removes Churn's
# firewall rules and, last, deletes /etc/churn/ itself, so that the app
# treats the router as new.
# Run it before a factory reset, or to stop using Churn:
#   sh /etc/churn/restore.sh
# It turns airplane mode on first; turn it off when you want to go online.

DIR=/etc/churn
FIREWALL=/etc/nftables.d/churn.nft

fail() {
	echo "$1" >&2
	exit 1
}

at() {
	gl_modem -B cpu AT "$1" </dev/null 2>&1
}

write() {  # $1 = item, $2 = IMEI; reads it back
	at "AT+EGMR=1,$1,\"$2\"" | grep -q '^OK' && at "AT+EGMR=0,$1" | grep -q "\"$2\""
}

# The same lock as the app's modem commands.
exec 9>/var/lock/churn.lock && flock 9 || fail "Can't take Churn's lock."

{ read -r imei1 && read -r imei2; } < "$DIR/factory" || fail "Can't read $DIR/factory."
for imei in "$imei1" "$imei2"; do
	case "$imei" in
		[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
		*) fail "$DIR/factory doesn't hold two IMEIs." ;;
	esac
done

# The radio stays off while the IMEIs change.
uci set glconfig.general.airplane_mode=1 && uci commit glconfig || fail "Can't turn airplane mode on."
# GL's cellular manager turns the radio off right after airplane mode is set.
tries=0
until at 'AT+CFUN?' | grep -q 'CFUN: 4'; do
	tries=$((tries + 1))
	[ "$tries" -le 15 ] || fail "The radio didn't turn off, so the IMEIs are unchanged."
	sleep 2
done

write 7 "$imei1" || fail "Writing IMEI 1 failed."
write 11 "$imei2" || fail "Writing IMEI 2 failed."
# Without it, a reboot soon after the write brings the earlier IMEIs back.
at 'AT+QPRTPARA=1' | grep -q '^OK' || fail "The IMEIs are written but not saved, so a reboot may undo them."

# The record gives the card in SIM 2 IMEIs the router no longer has.
rm -f "$DIR/card" || fail "The factory IMEIs are back, but deleting $DIR/card failed."

# GL's system log goes back to the flash file it went to before Churn turned
# that off.
if [ -s "$DIR/log_file" ]; then
	read -r log_file < "$DIR/log_file" &&
		uci set "system.@system[0].log_file=$log_file" && uci commit system && /etc/init.d/log reload ||
		fail "The factory IMEIs are back, but putting back GL's setting for saving the system log failed."
	rm -f "$DIR/log_file"
fi

# The zero files that take most of the flash's free space between
# clearings, the link to them, and fill files left by a clearing that
# stopped.
rm -rf /overlay/churn-zeros /overlay/churn-fill.* "$DIR/zeros" ||
	fail "The factory IMEIs are back, but deleting Churn's zero files in /overlay/churn-zeros failed."

# The firewall rules go after everything but the folder, so that a restore
# that stops earlier keeps them.
# Airplane mode is on, so nothing goes out over the SIM once they are gone.
# The chain test covers a reload that failed after the file was gone.
if [ -e "$FIREWALL" ] || nft list chain inet fw4 churn_sim >/dev/null 2>&1; then
	# fw4 warns about GL's own options at every reload, so only its exit status counts.
	rm -f "$FIREWALL" && fw4 reload >/dev/null 2>&1 ||
		fail "The factory IMEIs are back, but removing Churn's firewall rules failed."
fi
sed -i '/^\/etc\/nftables\.d\/churn\.nft$/d' /etc/sysupgrade.conf ||
	fail "The factory IMEIs are back, but /etc/sysupgrade.conf still lists $FIREWALL."

# The folder goes last, so that a restore that stops earlier can run again.
# Without it, the app treats the router as new. Deleting this script doesn't
# stop it: the shell keeps reading it from the file it opened.
sed -i '/^\/etc\/churn\/$/d' /etc/sysupgrade.conf ||
	fail "The factory IMEIs are back, but /etc/sysupgrade.conf still lists $DIR/."
rm -rf "$DIR" || fail "The factory IMEIs are back, but deleting $DIR failed."
echo "The factory IMEIs are back: IMEI 1 $imei1, IMEI 2 $imei2. GL's setting for saving the system log is as before. Churn's zero files, firewall rules and folder are removed. Airplane mode is on."
