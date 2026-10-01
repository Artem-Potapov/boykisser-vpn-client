#!/system/bin/sh
# Capture every VPN TUN generation while Android replaces interfaces during failover.
# This exists because a tcpdump attached to the name tun0 exits when that interface is deleted;
# the authoritative any/underlay captures continue while this helper reattaches by ifindex.
#
# Usage (root shell):
#   capture-vpn-traffic.bash OUTPUT_DIR [UNDERLAY_INTERFACE]
#
# OUTPUT_DIR must not already exist. Stop with Ctrl-C; the helper interrupts and flushes every
# tcpdump before returning. Keep any.pcap as the packet oracle and tun-*.pcap for per-generation
# explanation. The optional STOP file is useful for scripted tests and automation.

set -u

OUT=${1:-}
UNDERLAY=${2:-eth0}
if [ -z "$OUT" ]; then
    printf 'usage: %s OUTPUT_DIR [UNDERLAY_INTERFACE]\n' "$0" >&2
    exit 64
fi
if [ "$(id -u)" -ne 0 ]; then
    printf 'root privileges are required for packet capture\n' >&2
    exit 77
fi
if [ -e "$OUT" ]; then
    printf 'output directory already exists: %s\n' "$OUT" >&2
    exit 73
fi

mkdir -p "$OUT" || exit 1
PIDS="$OUT/.pids"
: > "$PIDS"
: > "$OUT/capture-events.log"

log_event() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%N%Z)" "$*" >> "$OUT/capture-events.log"
}

process_start_time() {
    pid=$1
    awk '{ print $22 }' "/proc/$pid/stat" 2>/dev/null || true
}

process_matches() {
    label=$1
    pid=$2
    expected_start=$3
    [ -r "/proc/$pid/stat" ] || return 1
    actual_start=$(process_start_time "$pid")
    [ -n "$actual_start" ] && [ "$actual_start" = "$expected_start" ] || return 1
    state=$(awk '{ print $3 }' "/proc/$pid/stat" 2>/dev/null || true)
    [ "$state" != Z ] || return 1
    cmdline=$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
    case "$label" in
        monitor)
            case "$cmdline" in "ip monitor "*|*" ip monitor "*|*"/ip monitor "*) return 0;; esac
            ;;
        *)
            case "$cmdline" in *tcpdump*) return 0;; esac
            ;;
    esac
    return 1
}

start_capture() {
    label=$1
    interface=$2
    file=$3
    shift 3
    log_event "START label=$label interface=$interface file=$file"
    tcpdump -i "$interface" -nn -s 0 -B 4096 -U -w "$file" "$@" \
        > "${file%.pcap}.log" 2>&1 &
    pid=$!
    sleep 0.05
    started_at=$(process_start_time "$pid")
    if [ -z "$started_at" ] || ! process_matches "$label" "$pid" "$started_at"; then
        wait "$pid" 2>/dev/null || true
        log_event "FAILED label=$label interface=$interface"
        printf 'capture failed: %s (%s)\n' "$label" "$(cat "${file%.pcap}.log" 2>/dev/null)" >&2
        return 1
    fi
    printf '%s %s %s %s\n' "$label" "$pid" "$started_at" "$interface" >> "$PIDS"
    return 0
}

start_monitor() {
    file=$1
    log_event "START label=monitor file=$file"
    ip monitor all > "$file" 2>&1 &
    pid=$!
    sleep 0.05
    started_at=$(process_start_time "$pid")
    if [ -z "$started_at" ] || ! process_matches monitor "$pid" "$started_at"; then
        wait "$pid" 2>/dev/null || true
        log_event "FAILED label=monitor"
        printf 'network monitor failed\n' >&2
        return 1
    fi
    printf 'monitor %s %s monitor\n' "$pid" "$started_at" >> "$PIDS"
    return 0
}

retry_tun_capture() {
    label=$1
    interface=$2
    ifindex=${label#tun-}
    attempt_file="$OUT/retry-$ifindex"
    attempt=$(cat "$attempt_file" 2>/dev/null || printf '0')
    attempt=$((attempt + 1))
    printf '%s\n' "$attempt" > "$attempt_file"
    file="$OUT/tun-$ifindex-retry-$attempt.pcap"
    log_event "RETRY label=$label interface=$interface file=$file attempt=$attempt"
    start_capture "$label" "$interface" "$file"
}

check_captures() {
    active="$OUT/.pids.active"
    : > "$active"
    while read -r label pid started_at interface; do
        [ -n "${pid:-}" ] || continue
        if process_matches "$label" "$pid" "$started_at"; then
            printf '%s %s %s %s\n' "$label" "$pid" "$started_at" "$interface" >> "$active"
            continue
        fi
        wait "$pid" 2>/dev/null || true
        case "$label" in
            any|underlay)
                log_event "FAILED label=$label pid=$pid"
                printf 'capture failed after startup: %s (pid=%s)\n' "$label" "$pid" >&2
                rm -f "$active"
                return 1
                ;;
            monitor)
                log_event "FAILED label=monitor pid=$pid"
                printf 'network monitor failed after startup (pid=%s)\n' "$pid" >&2
                rm -f "$active"
                return 1
                ;;
            tun-*)
                # Discovery below retries if this generation still exists. Never overwrite its pcap.
                [ -e "$OUT/retry-${label#tun-}" ] || printf '0\n' > "$OUT/retry-${label#tun-}"
                log_event "TUN_CAPTURE_ENDED label=$label pid=$pid"
                ;;
        esac
    done < "$PIDS"
    mv "$active" "$PIDS"
    return 0
}

stop_captures() {
    [ "${STOPPED:-0}" -eq 1 ] && return
    STOPPED=1
    log_event STOPPING
    while read -r label pid started_at interface; do
        [ -n "${pid:-}" ] || continue
        if process_matches "$label" "$pid" "$started_at"; then
            kill -INT "$pid" 2>/dev/null || true
        fi
    done < "$PIDS"
    sleep 2
    while read -r label pid started_at interface; do
        [ -n "${pid:-}" ] || continue
        if process_matches "$label" "$pid" "$started_at"; then
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done < "$PIDS"
    while read -r label pid started_at interface; do
        [ -n "${pid:-}" ] || continue
        wait "$pid" 2>/dev/null || true
    done < "$PIDS"
    sync
    log_event STOPPED
}

trap 'stop_captures; exit 0' INT TERM
trap 'stop_captures' EXIT

start_capture any any "$OUT/any.pcap" || exit 1
start_capture underlay "$UNDERLAY" "$OUT/underlay.pcap" || exit 1
start_monitor "$OUT/ip-monitor.log" || exit 1

while :; do
    if [ -e "$OUT/STOP" ]; then
        rm -f "$OUT/STOP"
        exit 0
    fi

    check_captures || exit 1

    ip -o link show 2>/dev/null | awk -F': ' '
        $2 ~ /^tun[0-9]+([:@]|$)/ {
            split($2, name, "@")
            print $1, name[1]
        }
    ' > "$OUT/.links"
    while read -r ifindex ifname; do
        [ -n "${ifindex:-}" ] || continue
        label="tun-$ifindex"
        grep -q "^$label " "$PIDS" 2>/dev/null && continue
        if [ -e "$OUT/retry-$ifindex" ]; then
            retry_tun_capture "$label" "$ifname" || true
            continue
        fi
        file="$OUT/tun-$ifindex.pcap"
        log_event "TUN_GENERATION ifindex=$ifindex interface=$ifname"
        start_capture "$label" "$ifname" "$file" || true
    done < "$OUT/.links"
    sleep 0.05
done
