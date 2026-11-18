#!/bin/sh
# Runs the free-space overwrite and restore.sh as the router runs them: with
# BusyBox's ash, in a chroot with the router's paths, stand-ins for uci,
# gl_modem, nft and GL's log service, and a 1 GiB ext4 disk image at
# /overlay. Then it checks what they did to the files, the settings and the
# disk image, from outside the chroot.
#
# CI runs it as root, once RouterScriptsTest has written Router's scripts:
#   sh android/app/src/test/sh/router-scripts-test.sh android/app/build/router-scripts
# It needs a static BusyBox (Debian's busybox-static). That has no flock, so
# the host's stands in for the router's.

set -eu
[ "$(id -u)" -eq 0 ] || { echo "It needs root, for the disk image and the chroot." >&2; exit 2; }
scripts=$(cd "$1" && pwd)
assets=$(cd "$(dirname "$0")/../../main/assets" && pwd)
work=$(mktemp -d)
root=$work/root
loop=
failed=0

FACTORY1=356090215000002
FACTORY2=356090215000077
CARD1=356090211234563
CARD2=356090211235313
ICCID=8941000000000000015

cleanup() {
	for m in overlay dev sys proc; do umount "$root/$m" 2>/dev/null || true; done
	[ -z "$loop" ] || losetup -d "$loop"
	rm -rf "$work"
}
trap cleanup EXIT

# Prints "ok" or "FAIL" with $1, by whether the rest of the arguments, run as a
# command, succeed.
check() {
	what=$1
	shift
	if "$@"; then
		echo "ok: $what"
	else
		echo "FAIL: $what"
		failed=$((failed + 1))
	fi
}

between() { [ "$1" -ge "$2" ] && [ "$1" -lt "$3" ]; }
not() { ! "$@"; }
# Whether the last run whose output went to $work/out printed $1.
printed() { grep -qF "$1" "$work/out"; }

# Runs $1 as Churn's SSH connection runs a command: with the router's shell.
on_router() {
	env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin chroot "$root" /bin/sh -c "$1"
}

# The router's paths, with BusyBox's applets in /bin.
mkdir -p "$root/bin" "$root/usr/bin" "$root/usr/sbin" "$root/etc/churn" "$root/etc/init.d" \
	"$root/etc/nftables.d" "$root/var/lock" "$root/tmp" "$root/overlay" "$root/proc" "$root/sys" "$root/dev"
cp "$(command -v busybox)" "$root/bin/busybox"
chroot "$root" /bin/busybox --install -s /bin
flock=$(command -v flock)
for f in "$flock" $(ldd "$flock" | grep -o '/[^ ]*'); do
	mkdir -p "$root${f%/*}"
	cp "$f" "$root$f"
done
mount -t proc proc "$root/proc"
mount -t sysfs sysfs "$root/sys"
mount --bind /dev "$root/dev"

# Settings as lines key=value in /tmp/uci. Every call of a stand-in goes to
# /tmp/calls.
cat > "$root/usr/bin/uci" <<'EOF'
#!/bin/sh
echo "uci $*" >> /tmp/calls
[ "$1" = -q ] && shift
key=${2%%=*}
get() { awk -F= -v k="$key" '$1 == k { print substr($0, length(k) + 2); f = 1 } END { exit !f }' /tmp/uci; }
others() { awk -F= -v k="$key" '$1 != k' /tmp/uci > /tmp/uci.new; }
case $1 in
	get) get ;;
	set) others && echo "$2" >> /tmp/uci.new && mv /tmp/uci.new /tmp/uci ;;
	delete) get > /dev/null && others && mv /tmp/uci.new /tmp/uci ;;
	commit) ;;
	*) exit 1 ;;
esac
EOF
# The modem's IMEIs in /tmp/imei7 and /tmp/imei11, and those it saved to its
# flash in /tmp/saved7 and /tmp/saved11. GL's cellular manager turns the
# radio off once airplane mode is set.
cat > "$root/usr/bin/gl_modem" <<'EOF'
#!/bin/sh
echo "gl_modem $*" >> /tmp/calls
answer() { printf '\r\n%s\r\n\r\nOK\r\n' "$1"; }
case $4 in
	'AT+CFUN?')
		if grep -qx glconfig.general.airplane_mode=1 /tmp/uci; then answer '+CFUN: 4'; else answer '+CFUN: 1'; fi ;;
	AT+EGMR=1,*)
		item=${4#AT+EGMR=1,}
		imei=${item#*,}
		echo "$imei" | tr -d '"' > "/tmp/imei${item%%,*}"
		printf '\r\nOK\r\n' ;;
	AT+EGMR=0,*) answer "+EGMR: \"$(cat "/tmp/imei${4#AT+EGMR=0,}")\"" ;;
	AT+QPRTPARA=1) cp /tmp/imei7 /tmp/saved7 && cp /tmp/imei11 /tmp/saved11 && printf '\r\nOK\r\n' ;;
	*) printf '\r\nERROR\r\n' ;;
esac
EOF
# The chains in GL's table, one per line in /tmp/chains.
cat > "$root/usr/sbin/nft" <<'EOF'
#!/bin/sh
echo "nft $*" >> /tmp/calls
case "$1 $2 $3 $4" in
	"list chain inet fw4") grep -qx "$5" /tmp/chains ;;
	"delete chain inet fw4") grep -qx "$5" /tmp/chains && { grep -vx "$5" /tmp/chains > /tmp/chains.new || true; } &&
		mv /tmp/chains.new /tmp/chains ;;
	*) exit 1 ;;
esac
EOF
printf '#!/bin/sh\necho "log $*" >> /tmp/calls\n' > "$root/etc/init.d/log"
chmod 755 "$root/usr/bin/uci" "$root/usr/bin/gl_modem" "$root/usr/sbin/nft" "$root/etc/init.d/log"

# The overlay as an ext4 image, written out in full as on the router, whose
# reserve is then 4096 blocks too.
truncate -s 1G "$work/overlay.img"
mkfs.ext4 -q -F -E lazy_itable_init=0,lazy_journal_init=0 "$work/overlay.img"
loop=$(losetup -f --show "$work/overlay.img")
mount "$loop" "$root/overlay"
ext4=/sys/fs/ext4/${loop##*/}
reserve() { cat "$ext4/reserved_clusters"; }
written_kb() { sync; cat "$ext4/lifetime_write_kbytes"; }
# The free space as the overwrite counts it: what df shows as size less used.
free_kb() { sync; stat -f -c '%f %S' "$root/overlay" | awk '{ print $1 * $2 / 1024 }'; }
# How often $1 is on the image, as a whole run of digits.
copies() {
	sync
	echo 3 > /proc/sys/vm/drop_caches
	tr -cs '0-9' '\n' < "$loop" | grep -cx "$1" || true
}

overwrite() { on_router "$(cat "$scripts/overwrite-free-space.sh")"; }
# Waits until a run started in the background has begun to fill.
filling() {
	i=0
	while [ ! -e "$root/overlay/churn-fill.0" ] && [ "$i" -lt 300 ]; do
		sleep 0.1
		i=$((i + 1))
	done
}

# GL's files on the overlay. Each uci commit writes a file anew and renames
# it over the old one, whose blocks stay as they were. Files written in
# between leave those blocks apart, between used ones.
config=$root/overlay/upper/etc/config
mkdir -p "$config"
rewrite() {
	printf "config sim\n\toption iccid '%s'\n\toption n '%s'\n" "$ICCID" "$1" > "$config/sim.new"
	mv "$config/sim.new" "$config/sim"
}
i=0
while [ "$i" -lt 300 ]; do
	rewrite "$i"
	head -c 4096 /dev/urandom > "$config/other.$i"
	i=$((i + 1))
done

echo "== The first overwrite"
check "the image holds old versions of GL's file" [ "$(copies "$ICCID")" -gt 1 ]
check "ext4 keeps 4096 blocks back" [ "$(reserve)" -eq 4096 ]
overwrite > "$work/out" && status=0 || status=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "it says what the fill left free" printed "kB free after the fill"
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "no fill file is left" [ -z "$(find "$root/overlay" -maxdepth 1 -name 'churn-fill.*')" ]
check "the zero files are in /overlay/churn-zeros" [ -n "$(ls "$root/overlay/churn-zeros")" ]
check "/etc/churn/zeros links to them" [ "$(readlink "$root/etc/churn/zeros")" = /overlay/churn-zeros ]
check "512 MB to 576 MB are free" between "$(free_kb)" 524288 589824
check "only the current version of GL's file is left" [ "$(copies "$ICCID")" -eq 1 ]
check "Router's count of the numbers finds it once, in a file" \
	[ "$(echo "$ICCID" | on_router "$(cat "$scripts/count-numbers.sh")")" = "1 1" ]

echo "== The next overwrite"
rewrite next
free=$(free_kb)
written=$(written_kb)
overwrite > /dev/null && status=0 || status=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "it writes only the free space, which the zero files left" \
	between $(($(written_kb) - written)) "$free" $((free + 16384))
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "512 MB to 576 MB are free" between "$(free_kb)" 524288 589824
check "only the current version of GL's file is left" [ "$(copies "$ICCID")" -eq 1 ]

echo "== An overwrite that a signal stops"
zeros=$(ls "$root/overlay/churn-zeros")
env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin chroot "$root" /bin/sh -c "$(cat "$scripts/overwrite-free-space.sh")" &
pid=$!
filling
check "the reserve is 0 while it fills" [ "$(reserve)" -eq 0 ]
kill -TERM "$pid"
wait "$pid" && status=0 || status=$?
check "it exits with 1" [ "$status" -eq 1 ]
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "no fill file is left" [ -z "$(find "$root/overlay" -maxdepth 1 -name 'churn-fill.*')" ]
check "the zero files are as before" [ "$(ls "$root/overlay/churn-zeros")" = "$zeros" ]

# As a run killed before it set the reserve back leaves it.
echo "== An overwrite that finds ext4's reserve at 0"
echo 0 > "$ext4/reserved_clusters"
written=$(written_kb)
overwrite > "$work/out" 2>&1 && status=0 || status=$?
check "it exits with 3" [ "$status" -eq 3 ]
check "it says why" printed "ext4's reserve is 0"
check "it writes nothing" [ $(($(written_kb) - written)) -lt 1024 ]
check "the zero files are as before" [ "$(ls "$root/overlay/churn-zeros")" = "$zeros" ]
echo 4096 > "$ext4/reserved_clusters"

echo "== An overwrite on a read-only overlay"
mount -o remount,ro "$root/overlay"
overwrite > "$work/out" 2>&1 && status=0 || status=$?
mount -o remount,rw "$root/overlay"
check "it exits with 6, for the free space it couldn't fill" [ "$status" -eq 6 ]
check "it says how much" printed "kB free after the fill"
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "the zero files are as before" [ "$(ls "$root/overlay/churn-zeros")" = "$zeros" ]

echo "== Two overwrites at once"
overwrite > /dev/null &
pid=$!
filling
overwrite > /dev/null && status=0 || status=$?
wait "$pid" && first=0 || first=$?
check "the first exits with 0" [ "$first" -eq 0 ]
check "the second, which waits for it, exits with 0" [ "$status" -eq 0 ]
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "512 MB to 576 MB are free" between "$(free_kb)" 524288 589824
check "no fill file is left" [ -z "$(find "$root/overlay" -maxdepth 1 -name 'churn-fill.*')" ]

# As a power cut leaves it after a run moved its parts and before it freed
# the 512 MB: the parts take all of the partition but what the run's 4 KB
# files took, which it deleted, and the mount sets the reserve back.
echo "== An overwrite that finds the partition full"
echo 0 > "$ext4/reserved_clusters"
mkdir "$root/overlay/small"
n=$(ls "$root/overlay/churn-zeros" | sort -n | tail -n 1)
while n=$((n + 1)) && dd if=/dev/zero of="$root/overlay/churn-zeros/$n" bs=1M count=64 2>/dev/null; do :; done
i=0
while dd if=/dev/zero of="$root/overlay/small/$i" bs=4k count=1 2>/dev/null; do i=$((i + 1)); done
rm -r "$root/overlay/small"
echo 4096 > "$ext4/reserved_clusters"
check "not even a directory fits" not mkdir "$root/overlay/test" 2>/dev/null
overwrite > /dev/null && status=0 || status=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "the reserve is back" [ "$(reserve)" -eq 4096 ]
check "512 MB to 576 MB are free" between "$(free_kb)" 524288 589824

echo "== The file system after the overwrites"
fsck_clean() { e2fsck -fn "$loop" > "$work/fsck" 2>&1 || { cat "$work/fsck"; false; }; }
umount "$root/overlay"
check "e2fsck finds it clean" fsck_clean
mount "$loop" "$root/overlay"

echo "== What reads Churn's folder takes only the link (F4)"
link=$(on_router "$(cat "$scripts/zeros-link.sh")") || true
check "Router's read gives the link's target" [ "$(echo "$link" | sed -n 1p)" = /overlay/churn-zeros ]
check "du counts under 1 MB in the folder" [ "$(echo "$link" | sed -n 2p)" -lt 1024 ]
check "find lists no zero file" [ -z "$(on_router 'find /etc/churn' | grep /etc/churn/zeros/)" ]
check "grep -r reads no zero file" [ -z "$(on_router 'grep -rl "" /etc/churn' | grep /etc/churn/zeros/)" ]
check "tar takes no zero file" [ -z "$(on_router 'tar -cf - /etc/churn 2>/dev/null | tar -tf -' | grep etc/churn/zeros/)" ]
check "cp -r copies the link" on_router 'cp -r /etc/churn /tmp/copy && [ -L /tmp/copy/zeros ] && rm -r /tmp/copy'

# Churn's files and GL's settings as a swap leaves them, with a fill file
# that a clearing that stopped left behind, and the firewall rules loaded.
set_up() {
	cp "$assets/restore.sh" "$assets/README.txt" "$root/etc/churn/"
	printf '%s\n' "$FACTORY1" "$FACTORY2" > "$root/etc/churn/factory"
	printf '%s\n' "$ICCID" "$CARD1" "$CARD2" > "$root/etc/churn/card"
	echo 1 > "$root/etc/churn/version"
	echo /etc/sys_log > "$root/etc/churn/log_file"
	echo 0 > "$root/etc/churn/kmwan"
	[ -L "$root/etc/churn/zeros" ] || ln -s /overlay/churn-zeros "$root/etc/churn/zeros"
	mkdir -p "$root/overlay/churn-zeros"
	head -c 65536 /dev/zero > "$root/overlay/churn-fill.3"
	echo "# Churn's firewall rules" > "$root/etc/nftables.d/churn.nft"
	printf '%s\n' /etc/dropbear/ /etc/churn/ /etc/nftables.d/churn.nft > "$root/etc/sysupgrade.conf"
	printf '%s\n' glconfig.general.airplane_mode=0 kmwan.modem_cpu.disabled=1 > "$root/tmp/uci"
	echo "$CARD1" > "$root/tmp/imei7"
	echo "$CARD2" > "$root/tmp/imei11"
	rm -f "$root/tmp/saved7" "$root/tmp/saved11"
	printf '%s\n' churn_sim churn_route churn_dns > "$root/tmp/chains"
	: > "$root/tmp/calls"
}
setting() { awk -F= -v k="$1" '$1 == k { print substr($0, length(k) + 2) }' "$root/tmp/uci"; }
called() { grep -qxF "$1" "$root/tmp/calls"; }
# The line in /tmp/calls of the first call that starts with $1.
call_line() { grep -nF "$1" "$root/tmp/calls" | sed -n '1s/:.*//p'; }
restore() { on_router 'sh /etc/churn/restore.sh' > "$work/out" 2>&1; }

echo "== restore.sh after the overwrites"
set_up
restore && status=0 || status=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "it ran to its end, after deleting itself (F12)" \
	printed "The factory IMEIs are back: IMEI 1 $FACTORY1, IMEI 2 $FACTORY2."
check "airplane mode is on" [ "$(setting glconfig.general.airplane_mode)" = 1 ]
check "it committed airplane mode" called "uci commit glconfig"
check "it turned the radio off before writing IMEIs" \
	[ "$(call_line "uci commit glconfig")" -lt "$(call_line "gl_modem -B cpu AT AT+EGMR=1")" ]
check "the modem has the factory IMEIs" [ "$(cat "$root/tmp/imei7") $(cat "$root/tmp/imei11")" = "$FACTORY1 $FACTORY2" ]
check "and saved them" [ "$(cat "$root/tmp/saved7") $(cat "$root/tmp/saved11")" = "$FACTORY1 $FACTORY2" ]
check "GL's system log goes to its file again" [ "$(setting 'system.@system[0].log_file')" = /etc/sys_log ]
check "and the log service reloaded" called "log reload"
check "GL's connection check is as GL had it" [ "$(setting kmwan.modem_cpu.disabled)" = 0 ]
check "the zero files are gone" [ ! -e "$root/overlay/churn-zeros" ]
check "the fill file is gone" [ ! -e "$root/overlay/churn-fill.3" ]
check "the free space is back" [ "$(free_kb)" -gt 900000 ]
check "the firewall rules are gone" [ ! -e "$root/etc/nftables.d/churn.nft" ]
check "and their chains" [ ! -s "$root/tmp/chains" ]
check "Churn's folder is gone" [ ! -e "$root/etc/churn" ]
check "sysupgrade.conf keeps only GL's line" [ "$(cat "$root/etc/sysupgrade.conf")" = /etc/dropbear/ ]

echo "== restore.sh without GL's settings saved, and with the chains gone"
mkdir -p "$root/etc/churn"
set_up
echo > "$root/etc/churn/kmwan"
rm "$root/etc/churn/log_file"
: > "$root/tmp/chains"
restore && status=0 || status=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "GL's connection check has no setting, as before" [ -z "$(grep '^kmwan\.' "$root/tmp/uci")" ]
check "GL's system log stays off" [ -z "$(setting 'system.@system[0].log_file')" ]
check "the log service didn't reload" not called "log reload"
check "no chain was deleted" [ -z "$(grep '^nft delete' "$root/tmp/calls")" ]
check "Churn's folder is gone" [ ! -e "$root/etc/churn" ]

echo "== restore.sh without the factory IMEIs"
mkdir -p "$root/etc/churn"
set_up
rm "$root/etc/churn/factory"
restore && status=0 || status=$?
check "it exits with 1" [ "$status" -eq 1 ]
check "it says why" printed "Can't read /etc/churn/factory."
check "airplane mode is as it was" [ "$(setting glconfig.general.airplane_mode)" = 0 ]
check "the modem has the card's IMEIs" [ "$(cat "$root/tmp/imei7") $(cat "$root/tmp/imei11")" = "$CARD1 $CARD2" ]
check "Churn's folder is kept" [ -e "$root/etc/churn/card" ]

echo "== restore.sh while an overwrite fills"
mkdir -p "$root/etc/churn"
set_up
overwrite > /dev/null &
pid=$!
filling
restore && status=0 || status=$?
wait "$pid" && first=0 || first=$?
check "it exits with 0" [ "$status" -eq 0 ]
check "the overwrite, which it waits for, exits with 0" [ "$first" -eq 0 ]
check "the zero files are gone" [ ! -e "$root/overlay/churn-zeros" ]
check "no fill file is left" [ -z "$(find "$root/overlay" -maxdepth 1 -name 'churn-fill.*')" ]
check "the free space is back" [ "$(free_kb)" -gt 900000 ]
check "Churn's folder is gone" [ ! -e "$root/etc/churn" ]

echo "$failed failed"
[ "$failed" -eq 0 ]
