#!/bin/sh
# Churn: writes the router's factory IMEIs back, from /etc/churn/factory.
# Run it before a factory reset, or to stop using Churn:
#   sh /etc/churn/restore.sh
# It turns airplane mode on first; turn it off when you want to go online.

DIR=/etc/churn

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
tries=0
until at 'AT+CFUN?' | grep -q 'CFUN: 4'; do
	tries=$((tries + 1))
	if [ "$tries" -gt 15 ]; then
		at 'AT+CFUN=4' | grep -q '^OK' && at 'AT+CFUN?' | grep -q 'CFUN: 4' || fail "Can't turn the radio off."
		break
	fi
	sleep 2
done

write 7 "$imei1" || fail "Writing IMEI 1 failed."
write 11 "$imei2" || fail "Writing IMEI 2 failed."
echo "The factory IMEIs are back: IMEI 1 $imei1, IMEI 2 $imei2. Airplane mode is on."
