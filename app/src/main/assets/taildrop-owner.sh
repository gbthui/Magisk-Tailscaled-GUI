#!/system/bin/sh
# TailControl protocol 1. All state is private to root; never source state files.
# A single inbox lock is deliberately shared by all receive directories. Do not unlink it.
# Host tests set TC_SH=/bin/sh; the application always uses /system/bin/sh.
set -f
umask 077
ACTION=$1
ROOT=$2
CLI=$3
shift 3
SH=${TC_SH:-/system/bin/sh}
SELF=$0
LEASE_SECONDS=20
STOP_GRACE=5
case "$ROOT" in /*) ;; *) exit 64;; esac
case "$CLI" in /*) ;; *) exit 64;; esac
[ ! -L "$ROOT" ] || exit 64
mkdir -p "$ROOT" || exit 74
[ "$(stat -c %u "$ROOT")" = "$(id -u)" ] || exit 77
chmod 700 "$ROOT" || exit 77
for name in control.lock inbox.lock probe.lock; do [ ! -L "$ROOT/$name" ] || exit 77; done
BOOT=$(cat /proc/sys/kernel/random/boot_id) || exit 74
UID_NOW=$(id -u) || exit 74

# Only FD-based flock syntax is used (Android toybox and BusyBox compatible).
if [ -x /system/bin/flock ]; then
    lock_fd() { /system/bin/flock -n "$1"; }
elif command -v flock >/dev/null 2>&1; then
    lock_fd() { flock -n "$1"; }
elif [ -x /data/adb/ksu/bin/busybox ]; then
    lock_fd() { /data/adb/ksu/bin/busybox flock -n "$1"; }
elif [ -x /data/adb/magisk/busybox ]; then
    lock_fd() { /data/adb/magisk/busybox flock -n "$1"; }
else
    printf 'TC1\tUNKNOWN\t-\t0\tflock-unavailable\n'; exit 69
fi
# Probe a separate uncontended inode so utility errors are not mistaken for lock contention.
probe=$(mktemp "$ROOT/probe.XXXXXX") || exit 74
probe_ok=0
(exec 7>>"$probe"; lock_fd 7) || probe_ok=$?
rm -f "$probe"
[ "$probe_ok" = 0 ] || { printf 'TC1\tUNKNOWN\t-\t0\tflock-probe-failed\n'; exit 69; }

now() { read -r up rest < /proc/uptime || return 1; NOW=${up%%.*}; }
valid_token() { case "$1" in ''|*[!a-zA-Z0-9-]*) return 1;; esac; [ "${#1}" -le 80 ]; }
# This also preserves trailing newlines in a user-selected directory.
read_all() { VALUE=$(cat "$1" && printf '.') || return 1; VALUE=${VALUE%.}; }
atomic() { printf '%s' "$2" > "$1.tmp.$$" && mv -f "$1.tmp.$$" "$1"; }
state() { atomic "$SESSION/state" "$1"; }
log() {
    now || return 1
    printf '%s boot=%s session=%s %s\n' "$NOW" "$BOOT" "$TOKEN" "$*" >> "$ROOT/events.log"
    cap_log "$ROOT/events.log"
}
cap_log() {
    [ -f "$1" ] || return 0
    bytes=$(wc -c < "$1") || return 1
    if [ "$bytes" -gt 131072 ]; then
        tail -c 65536 "$1" > "$1.rotate.$$" || return 1
        # Writers use O_APPEND. Keep the inode: an existing CLI still writes this same file.
        cat "$1.rotate.$$" > "$1"; rm -f "$1.rotate.$$"
    fi
}
# /proc stat field 22, after the final ') '; comm may contain spaces and parentheses.
identity() {
    IPID=$1
    case "$IPID" in ''|*[!0-9]*) return 2;; esac
    [ -d "/proc/$IPID" ] || return 1
    read_all "/proc/$IPID/stat" || return 2
    fields=${VALUE##*) }
    set -- $fields
    [ "$#" -ge 20 ] || return 2
    case "$1" in Z|X) return 1;; esac
    shift 19
    ISTART=$1
    IUID=$(stat -c %u "/proc/$IPID") || return 2
    IDIGEST=$(sha256sum "/proc/$IPID/cmdline") || return 2
    IDIGEST=${IDIGEST%% *}
    [ -n "$ISTART" ] && [ -n "$IDIGEST" ] || return 2
}
record_identity() {
    identity "$1" || return 1
    atomic "$2" "$BOOT $IPID $ISTART $IUID $IDIGEST"
}
# Result: 0 matching live process, 1 proven gone/reused, 2 cannot establish identity.
verify() {
    [ -r "$1" ] || return 2
    read -r rb rp rs ru rd extra < "$1" || :
    [ -z "$extra" ] && [ -n "$rd" ] || return 2
    [ "$rb" = "$BOOT" ] || return 1
    identity "$rp"; code=$?
    [ "$code" = 0 ] || return "$code"
    [ "$ISTART" = "$rs" ] && [ "$IUID" = "$ru" ] && [ "$IDIGEST" = "$rd" ] || return 1
    VERIFIED_PID=$rp
}
# Never infer death from an unreadable /proc entry or a failed root command.
lock_free() { (exec 6>>"$ROOT/inbox.lock"; lock_fd 6) >/dev/null 2>&1; }
load_current() {
    TOKEN=-; SESSION=
    [ -e "$ROOT/current" ] || return 1
    read_all "$ROOT/current" || return 2
    valid_token "$VALUE" || return 2
    TOKEN=$VALUE; SESSION="$ROOT/session-$TOKEN"
    [ -d "$SESSION" ] || return 2
}
report() { printf 'TC1\t%s\t%s\t%s\t%s\n' "$1" "${TOKEN:--}" "${2:-0}" "${3:--}"; }
status() {
    load_current; loaded=$?
    if [ "$loaded" = 1 ]; then
        if lock_free; then
            if [ -n "$(blockers)" ]; then report BLOCKED 0 "unmanaged-receivers:$(blockers | tr '\n' ',')"
            else report STOPPED 0 no-session; fi
        else report UNKNOWN 0 unrecorded-lock-owner; fi
        return
    fi
    if [ "$loaded" != 0 ]; then report UNKNOWN 0 unreadable-owner; return; fi
    read_all "$SESSION/state" || { report UNKNOWN 0 unreadable-state; return; }
    phase=$VALUE
    case "$phase" in
        STOPPED|PAUSED)
            if lock_free; then
                if [ "$phase" = STOPPED ] && [ -n "$(blockers)" ]; then report BLOCKED 0 "unmanaged-receivers:$(blockers | tr '\n' ',')"
                else report "$phase" 0 "$(cat "$SESSION/reason" 2>/dev/null)"; fi
                return
            fi
            # A finishing supervisor may still be releasing its lock.
            ;;
        STARTING|RUNNING|STOPPING|UNKNOWN) ;;
        *) report UNKNOWN 0 invalid-state; return;;
    esac
    verify "$SESSION/supervisor"; code=$?
    if [ "$code" = 0 ]; then
        report "$phase" "$VERIFIED_PID" "$(cat "$SESSION/reason" 2>/dev/null)"; return
    fi
    if [ "$phase" = STARTING ] && [ ! -e "$SESSION/supervisor" ]; then
        now; deadline=$(cat "$SESSION/lease" 2>/dev/null)
        case "$deadline" in ''|*[!0-9]*) report UNKNOWN 0 missing-lease; return;; esac
        if [ "$NOW" -le "$deadline" ]; then report STARTING 0 root-start-pending; return; fi
    elif [ "$code" = 2 ]; then
        report UNKNOWN 0 identity-unavailable; return
    fi
    # A dead supervisor is not proof its receiver is dead. Keep the inbox blocked.
    if ! lock_free; then report UNKNOWN 0 receiver-outlived-supervisor; return; fi
    if [ "$phase" = PAUSED ]; then
        report PAUSED 0 "$(cat "$SESSION/reason" 2>/dev/null)"; return
    fi
    if [ -e "$SESSION/pending" ]; then
        state PAUSED; atomic "$SESSION/reason" interrupted-batch
        report PAUSED 0 interrupted-batch
    else
        state STOPPED; report STOPPED 0 supervisor-exited
    fi
}
# Block recognizable unmanaged default-socket get consumers. Signal authorization is stricter:
# only legacy --loop argv plus the installed module executable may enter legacy-list/legacy-stop.
get_candidate() {
    lp=$1
    # Known old module binaries use these comm names. Avoid expensive hashing of every
    # unrelated process on each inspection (argv/executable checks below remain mandatory).
    IFS= read -r comm < "/proc/$lp/comm" 2>/dev/null || return 1
    case "$comm" in tailscale|tailscaled) ;; *) return 1;; esac
    identity "$lp"; candidate_state=$?
    [ "$candidate_state" = 0 ] || return "$candidate_state"
    [ "$IUID" = "$UID_NOW" ] || return 1
    prefix=$(tr '\000' '\n' < "/proc/$lp/cmdline") || return 2
    first=$(printf '%s\n' "$prefix" | head -n 1)
    [ "${first##*/}" = tailscale ] || return 1
    [ "$(printf '%s\n' "$prefix" | sed -n '2p')" = file ] || return 1
    [ "$(printf '%s\n' "$prefix" | sed -n '3p')" = get ] || return 1
}
loop_candidate() {
    get_candidate "$1" || return 1
    case "$(printf '%s\n' "$prefix" | sed -n '4p')" in --conflict=rename|--conflict=skip|--conflict=overwrite) ;; *) return 1;; esac
    [ "$(printf '%s\n' "$prefix" | sed -n '5p')" = --loop ] || return 1
}
legacy_candidate() {
    loop_candidate "$1" || return 1
    actual=$(stat -Lc '%d:%i' "/proc/$1/exe" 2>/dev/null) || return 1
    expected=$(stat -Lc '%d:%i' "$CLI" 2>/dev/null) || return 1
    [ "$actual" = "$expected" ]
}
blockers() {
    set +f
    for proc in /proc/[0-9]*; do
        set -f
        get_candidate "${proc##*/}"; candidate_state=$?
        case "$candidate_state" in
            0) printf '%s\n' "${proc##*/}";;
            2) printf 'unverified:%s\n' "${proc##*/}";;
        esac
    done
}
legacy_list() {
    set +f
    for proc in /proc/[0-9]*; do
        set -f
        lp=${proc##*/}
        if legacy_candidate "$lp"; then
            printf 'LEGACY\t%s\t%s\t%s\t%s\t%s\n' "$BOOT" "$lp" "$ISTART" "$IUID" "$IDIGEST"
        fi
    done
}
request_stop() { atomic "$SESSION/stop" requested; state STOPPING; }

if [ "$ACTION" = child ]; then
    TOKEN=$1; valid_token "$TOKEN" || exit 64
    SESSION="$ROOT/session-$TOKEN"
    read_all "$SESSION/directory" || exit 74; directory=$VALUE
    policy=$(cat "$SESSION/policy") || exit 74
    # Child inherits FD 8 from the supervisor. Publish its pre-exec PID/starttime and exact
    # post-exec argv BEFORE running the CLI. App cancellation cannot lose this record.
    [ "$(stat -Lc '%d:%i' /proc/$$/fd/8)" = "$(stat -Lc '%d:%i' "$ROOT/inbox.lock")" ] || exit 74
    identity $$ || exit 74
    printf '%s\000' "$CLI" file get --verbose "--conflict=$policy" -- "$directory" > "$SESSION/child.argv" || exit 74
    digest=$(sha256sum "$SESSION/child.argv"); digest=${digest%% *}
    atomic "$SESSION/child" "$BOOT $$ $ISTART $UID_NOW $digest" || exit 74
    exec "$CLI" file get --verbose "--conflict=$policy" -- "$directory"
    exit 126
fi

if [ "$ACTION" = supervise ]; then
    TOKEN=$1; valid_token "$TOKEN" || exit 64
    SESSION="$ROOT/session-$TOKEN"
    exec 8>>"$ROOT/inbox.lock"
    lock_fd 8 || { atomic "$SESSION/reason" inbox-lock-busy; state PAUSED; exit 75; }
    record_identity $$ "$SESSION/supervisor" || exit 74
    # The old app's consumers do not respect this lock. Refuse to add another one.
    legacy=$(blockers)
    if [ -n "$legacy" ]; then
        printf '%s\n' "$legacy" >> "$ROOT/events.log"
        atomic "$SESSION/reason" legacy-receivers-present; state PAUSED; exit 75
    fi
    CHILD=
    interrupted=false
    trap 'interrupted=true' TERM INT HUP
    stop_child() {
        [ -n "$CHILD" ] || return 0
        # Prefer exact recorded identity. Before exec, the child belongs to this shell and still
        # has its wrapper argv; wait for publication/exec instead of signalling a guessed PID.
        tries=0
        while [ "$tries" -lt 20 ]; do
            verify "$SESSION/child"; code=$?
            if [ "$code" = 0 ]; then kill -TERM "$VERIFIED_PID" 2>/dev/null || :; break; fi
            identity "$CHILD" || break
            sleep 0.1; tries=$((tries + 1))
        done
        tries=0
        while [ "$tries" -lt 20 ]; do
            identity "$CHILD" || break
            sleep 0.1; tries=$((tries + 1))
        done
        if verify "$SESSION/child"; then kill -KILL "$VERIFIED_PID" 2>/dev/null || :; fi
        # Do not release the supervisor's lock if the child is still alive or cannot be verified.
        while identity "$CHILD"; do sleep 0.2; done
        wait "$CHILD" 2>/dev/null || :
        CHILD=
    }
    state RUNNING
    log supervisor-started
    while :; do
        now || { atomic "$SESSION/reason" clock-unavailable; state PAUSED; exit 74; }
        deadline=$(cat "$SESSION/lease" 2>/dev/null)
        case "$deadline" in ''|*[!0-9]*) deadline=0;; esac
        if [ "$interrupted" = true ] || [ -e "$SESSION/stop" ] || [ "$NOW" -ge "$deadline" ]; then
            state STOPPED; log stopped-between-batches; exit 0
        fi
        if [ -n "$(blockers)" ]; then
            atomic "$SESSION/reason" unmanaged-receiver-detected; state PAUSED; exit 75
        fi
        # A durable uncertainty marker prevents automatic replay after a supervisor/App crash.
        atomic "$SESSION/pending" batch-started || exit 74
        rm -f "$SESSION/child"
        "$SH" "$SELF" child "$ROOT" "$CLI" "$TOKEN" 9>&- 7>&- >> "$ROOT/receiver.log" 2>&1 &
        CHILD=$!
        grace=0
        while :; do
            identity "$CHILD"; observed=$?
            if [ "$observed" = 1 ]; then break; fi
            if [ "$observed" = 2 ]; then
                log child-identity-unavailable
                atomic "$SESSION/reason" child-identity-unavailable; state UNKNOWN
                sleep 0.2
                continue
            fi
            if [ "$(cat "$SESSION/state")" = UNKNOWN ]; then
                atomic "$SESSION/reason" identity-recovered
                if [ -e "$SESSION/stop" ]; then state STOPPING; else state RUNNING; fi
            fi
            if verify "$SESSION/child"; then
                inherited=$(stat -Lc '%d:%i' "/proc/$VERIFIED_PID/fd/8" 2>/dev/null)
                if [ "$inherited" != "$(stat -Lc '%d:%i' "$ROOT/inbox.lock")" ]; then
                    # The process can exit between identity and fd inspection. Recheck after
                    # a short settle interval before classifying a missing FD as a live violation.
                    sleep 0.1
                    identity "$CHILD"; fd_state=$?
                    if [ "$fd_state" = 1 ]; then break; fi
                    if [ "$fd_state" = 2 ]; then
                        atomic "$SESSION/reason" child-identity-unavailable; state UNKNOWN
                        continue
                    fi
                    inherited=$(stat -Lc '%d:%i' "/proc/$CHILD/fd/8" 2>/dev/null)
                    if [ "$inherited" != "$(stat -Lc '%d:%i' "$ROOT/inbox.lock")" ]; then
                        log child-lost-lock-fd
                        stop_child
                        atomic "$SESSION/reason" lock-fd-not-inherited; state PAUSED
                        exit 74
                    fi
                fi
            fi
            now || { interrupted=true; NOW=0; }
            deadline=$(cat "$SESSION/lease" 2>/dev/null)
            case "$deadline" in ''|*[!0-9]*) deadline=0;; esac
            if [ "$interrupted" = true ] || [ "$NOW" -ge "$deadline" ]; then grace=1; fi
            if [ -e "$SESSION/stop" ] && [ "$grace" = 0 ]; then grace=$((NOW + STOP_GRACE)); fi
            if [ "$grace" != 0 ] && [ "$NOW" -ge "$grace" ]; then
                log interrupting-batch
                stop_child
                atomic "$SESSION/reason" interrupted-batch; state PAUSED
                exit 1
            fi
            cap_log "$ROOT/receiver.log"
            sleep 0.2
        done
        wait "$CHILD"; result=$?; CHILD=
        log "batch-exit=$result"
        if [ "$result" != 0 ]; then
            atomic "$SESSION/reason" "cli-exit-$result"; state PAUSED
            exit "$result"
        fi
        rm -f "$SESSION/pending" "$SESSION/child" "$SESSION/child.argv"
        # No --loop inside the CLI: a failed batch is never silently copied again.
        sleep 1
    done
fi

# All external control requests serialize here. Never inherit this lock in the supervisor.
exec 9>>"$ROOT/control.lock"
lock_fd 9 || { report UNKNOWN 0 control-busy; exit 75; }
case "$ACTION" in
    status) status;;
    start)
        TOKEN=$1; directory=$2; policy=$3
        valid_token "$TOKEN" || exit 64
        case "$directory" in /*) ;; *) exit 64;; esac
        case "$policy" in rename|skip|overwrite) ;; *) exit 64;; esac
        requested=$TOKEN
        old=$(status)
        oldstate=$(printf '%s\n' "$old" | cut -f2)
        load_current || :
        case "$oldstate" in
            RUNNING|STARTING)
                read_all "$SESSION/directory" || exit 74; olddir=$VALUE
                oldpolicy=$(cat "$SESSION/policy") || exit 74
                if [ "$olddir" = "$directory" ] && [ "$oldpolicy" = "$policy" ]; then
                    if [ "$oldstate" = RUNNING ] || verify "$SESSION/supervisor"; then
                        now; atomic "$SESSION/lease" "$((NOW + LEASE_SECONDS))"
                    fi
                    status
                else request_stop; report STOPPING 0 reconfigure; fi
                exit 0;;
            STOPPED) ;;
            *) printf '%s\n' "$old"; exit 0;;
        esac
        if ! lock_free; then report UNKNOWN 0 inbox-lock-busy; exit 0; fi
        legacy=$(blockers)
        if [ -n "$legacy" ]; then report BLOCKED 0 "unmanaged-receivers:$(blockers | tr '\n' ',')"; printf '%s\n' "$legacy"; exit 0; fi
        [ -x "$CLI" ] || { report UNKNOWN 0 cli-not-executable; exit 126; }
        mkdir -p "$directory" && [ -d "$directory" ] && [ -w "$directory" ] || { report UNKNOWN 0 directory-unwritable; exit 73; }
        TOKEN=$requested; SESSION="$ROOT/session-$TOKEN"
        mkdir "$SESSION" || exit 73
        atomic "$SESSION/directory" "$directory" && atomic "$SESSION/policy" "$policy" || exit 74
        state STARTING; now; atomic "$SESSION/lease" "$((NOW + LEASE_SECONDS))"
        atomic "$ROOT/current" "$TOKEN" || exit 74
        nohup "$SH" "$SELF" supervise "$ROOT" "$CLI" "$TOKEN" 9>&- </dev/null >> "$ROOT/events.log" 2>&1 &
        # $! is advisory, never the sole ownership record.
        report STARTING "$!" root-started;;
    renew)
        expected=$1
        load_current || { report UNKNOWN 0 no-current-owner; exit 0; }
        if [ "$expected" != "$TOKEN" ]; then report UNKNOWN 0 stale-generation; exit 0; fi
        now; atomic "$SESSION/lease" "$((NOW + LEASE_SECONDS))"; status;;
    stop)
        expected=$1
        load_current || { status; exit 0; }
        if [ "$expected" != "$TOKEN" ]; then report UNKNOWN 0 stale-generation; exit 0; fi
        current=$(status); phase=$(printf '%s\n' "$current" | cut -f2)
        case "$phase" in
            STARTING|RUNNING|STOPPING) request_stop; report STOPPING 0 stop-requested;;
            UNKNOWN)
                # A killed supervisor cannot service a stop request; only signal the exact
                # root-published child identity. The pending marker survives this cleanup.
                if verify "$SESSION/child"; then
                    now
                    since=$(cat "$SESSION/orphan-stop-at" 2>/dev/null)
                    case "$since" in ''|*[!0-9]*) since=$NOW; atomic "$SESSION/orphan-stop-at" "$since";; esac
                    if [ "$((NOW - since))" -ge 2 ]; then
                        # The identity has just been revalidated on this separate stop request.
                        kill -KILL "$VERIFIED_PID" 2>/dev/null || :
                    else kill -TERM "$VERIFIED_PID" 2>/dev/null || :; fi
                    report STOPPING 0 stopping-recorded-child
                else printf '%s\n' "$current"; fi;;
            *) printf '%s\n' "$current";;
        esac;;
    acknowledge)
        expected=$1
        load_current || exit 74
        [ "$expected" = "$TOKEN" ] && lock_free || { report UNKNOWN 0 cannot-acknowledge-live-owner; exit 0; }
        current=$(status); phase=$(printf '%s\n' "$current" | cut -f2)
        [ "$phase" = PAUSED ] || { printf '%s\n' "$current"; exit 0; }
        # Explicit UI confirmation only. This does not delete any received/inbox files.
        log user-acknowledged-uncertain-batch
        rm -f "$SESSION/pending"
        state STOPPED; report STOPPED 0 acknowledged;;
    legacy-list) legacy_list;;
    legacy-stop)
        # Caller supplies the exact identity from a previously shown confirmation dialog.
        rb=$1; rp=$2; rs=$3; ru=$4; rd=$5
        [ "$rb" = "$BOOT" ] && legacy_candidate "$rp" || exit 65
        [ "$ISTART" = "$rs" ] && [ "$IUID" = "$ru" ] && [ "$IDIGEST" = "$rd" ] || exit 65
        kill -TERM "$rp" || exit 74
        # No broad pkill, no automatic KILL on an ambiguous/changed identity.
        printf 'STOP_REQUESTED\t%s\n' "$rp";;
    *) exit 64;;
esac
