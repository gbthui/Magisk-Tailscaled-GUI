#!/system/bin/sh
# Runs only against a disposable emulator fixture directory, never /data/adb/tailcontrol.
set -f
BASE=$1
case "$BASE" in /data/local/tmp/tailcontrol-fd-ci-*) ;; *) echo 'Refusing non-fixture path' >&2; exit 64;; esac
HELPER="$BASE/taildrop-owner.sh"
CLI="$BASE/tailscale"
TEST_SH=/system/bin/sh
OWNER_ROOT="$BASE/owner"
export TC_SH="$TEST_SH" TC_FIXTURE="$BASE"
PASSED=0
pass() { PASSED=$((PASSED + 1)); printf 'PASS %s: %s\n' "$PASSED" "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; cat "$BASE/result" "$BASE/last-error" 2>/dev/null; exit 1; }
cleanup() {
    # Signals are limited to PIDs whose live argv contains this unique test directory.
    for record in "$BASE"/test-pids; do
        [ -f "$record" ] || continue
        while IFS= read -r pid; do
            case "$pid" in ''|*[!0-9]*) continue;; esac
            if tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -Fq "$BASE/"; then
                kill -KILL "$pid" 2>/dev/null || :
            fi
        done < "$record"
    done
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP
[ "$(id -u)" = 0 ] || fail 'emulator adb root is required'
case "${KSH_VERSION:-}" in *MIRBSD*KSH*) ;; *) fail 'must run the Android mksh, not a host shell';; esac
printf 'Android shell: %s\nAPI: %s\nFingerprint: %s\n' "$KSH_VERSION" "$(getprop ro.build.version.sdk)" "$(getprop ro.build.fingerprint)"
printf 'Helper: '; sha256sum "$HELPER"
[ -x /system/bin/flock ] || fail 'system flock missing'

# Source only the production function, not a separately maintained test implementation.
sed -n '/^lock_fd() {$/,/^}$/p' "$HELPER" > "$BASE/lock-function.sh"
[ -s "$BASE/lock-function.sh" ] || fail 'missing production lock_fd'
. "$BASE/lock-function.sh"
FLOCK_TOOL=/system/bin/flock; FLOCK_APPLET=false
if "$TEST_SH" -c 'exec 7>>"$1"; /system/bin/flock -n 7' negative "$BASE/negative.lock" > "$BASE/negative.log" 2>&1; then
    fail 'negative control did not reproduce close-on-exec'
fi
grep -q 'Bad file descriptor' "$BASE/negative.log" || fail 'negative control failed for the wrong reason'
pass 'old unguarded external flock reproduces EBADF on this Android mksh'
if "$TEST_SH" -c 'plain_lock() { /system/bin/flock -n "$1"; }; exec 7>>"$1"; plain_lock 7 7>&7' negative "$BASE/negative.lock" > "$BASE/function-negative.log" 2>&1; then
    fail 'function-call redirection unexpectedly fixed the negative control'
fi
grep -q 'Bad file descriptor' "$BASE/function-negative.log" || fail 'function-call negative control failed for another reason'
pass 'function-call-only redirection is insufficient'

competitor() {
    "$TEST_SH" -c 'exec 5>>"$1"; /system/bin/flock -n 5 5>&5' competitor "$1" >/dev/null 2>&1
}
fd_holder() {
    number=$1; lock_path=$2
    case "$number" in
        6) exec 6>>"$lock_path";;
        7) exec 7>>"$lock_path";;
        8) exec 8>>"$lock_path";;
        9) exec 9>>"$lock_path";;
        *) exit 64;;
    esac
    lock_fd "$number" || exit 40
    # The lock tool is already gone. A separately opened OFD must still be excluded.
    if competitor "$lock_path"; then exit 41; fi
}
for tool in standalone toybox; do
    if [ "$tool" = standalone ]; then FLOCK_TOOL=/system/bin/flock; FLOCK_APPLET=false
    else FLOCK_TOOL=/system/bin/toybox; FLOCK_APPLET=true; fi
    for number in 6 7 8 9; do
        lock_path="$BASE/$tool-$number.lock"
        (fd_holder "$number" "$lock_path") || fail "$tool FD $number did not retain an exclusive lock"
        competitor "$lock_path" || fail "$tool FD $number lock was not released after holder exit"
        pass "$tool FD $number: tool inheritance, exclusion, holder lifetime and release"
    done
done

request() {
    action=$1; shift
    "$TEST_SH" "$HELPER" "$action" "$OWNER_ROOT" "$CLI" "$@" > "$BASE/result" 2> "$BASE/last-error"
    result=$?
    [ "$result" = 0 ] || fail "helper $action exited $result"
    STATE=$(sed -n '/^TC1/p' "$BASE/result" | cut -f2)
    TOKEN=$(sed -n '/^TC1/p' "$BASE/result" | cut -f3)
}
await_state() {
    expected=$1; attempt=0
    while [ "$attempt" -lt 100 ]; do
        request status
        [ "$STATE" = "$expected" ] && return 0
        sleep 0.1; attempt=$((attempt + 1))
    done
    fail "expected $expected, got $STATE"
}
track_record() {
    record=$1
    attempt=0
    while [ ! -s "$record" ] && [ "$attempt" -lt 100 ]; do sleep 0.1; attempt=$((attempt + 1)); done
    [ -s "$record" ] || fail "identity not published: $record"
    read -r boot tracked_pid start uid digest < "$record" || :
    printf '%s\n' "$tracked_pid" >> "$BASE/test-pids"
}
mkdir -p "$BASE/dest" || fail 'fixture directory'
printf slow > "$BASE/mode"
request status
[ "$STATE" = STOPPED ] || fail 'fresh owner should pass probe and be stopped'
pass 'full production helper probe and control lock pass on Android'
request start one "$BASE/dest" rename
await_state RUNNING
track_record "$OWNER_ROOT/session-one/supervisor"; supervisor=$tracked_pid
track_record "$OWNER_ROOT/session-one/child"; child=$tracked_pid
attempt=0
while [ "$(readlink "/proc/$child/exe")" != "$CLI" ] && [ "$attempt" -lt 100 ]; do sleep 0.1; attempt=$((attempt + 1)); done
[ "$(readlink "/proc/$child/exe")" = "$CLI" ] || fail 'child did not exec native fixture'
[ "$(stat -Lc '%d:%i' "/proc/$child/fd/8")" = "$(stat -Lc '%d:%i' "$OWNER_ROOT/inbox.lock")" ] || fail 'native CLI lost FD 8'
[ ! -e "/proc/$child/fd/9" ] || fail 'control FD 9 leaked into native CLI'
pass 'supervisor -> child mksh -> native CLI retains FD 8 but not control FD 9'
request start duplicate "$BASE/dest" rename
[ "$TOKEN" = one ] && [ "$STATE" = RUNNING ] || fail 'duplicate start created a different owner'
pass 'duplicate request reuses existing owner while child lives'

kill -KILL "$supervisor" || fail 'fixture supervisor kill'
await_state UNKNOWN
[ -e "/proc/$child/fd/8" ] || fail 'child lock disappeared with supervisor'
if competitor "$OWNER_ROOT/inbox.lock"; then fail 'lock released while child survived supervisor'; fi
request start after-crash "$BASE/dest" rename
[ "$STATE" = UNKNOWN ] || fail 'new receiver started after supervisor crash'
pass 'native child retains exclusive inbox lock after supervisor exit; second owner refused'
request stop one
await_state PAUSED
[ ! -e "/proc/$child/fd/8" ] || fail 'recorded child still holds lock after stop'
competitor "$OWNER_ROOT/inbox.lock" || fail 'inbox lock not released after child exit'
pass 'targeted stop ends inherited lock lifetime, uncertain batch remains paused'
request acknowledge one

printf normal > "$BASE/mode"
printf 'one fixture transfer\n' > "$BASE/inbox"
request start two "$BASE/dest" rename
await_state RUNNING
track_record "$OWNER_ROOT/session-two/supervisor"
attempt=0
while [ ! -f "$BASE/dest/receipt-0" ] && [ "$attempt" -lt 100 ]; do sleep 0.1; attempt=$((attempt + 1)); done
[ -f "$BASE/dest/receipt-0" ] || fail 'no received fixture file'
sleep 2
set +f
set -- "$BASE/dest"/receipt-*
set -f
[ "$#" = 1 ] || fail 'single fixture send generated duplicate outputs'
pass 'one fixture send produces one file after the Android FD fix'
request stop two
await_state STOPPED
competitor "$OWNER_ROOT/inbox.lock" || fail 'normal stop did not release inbox lock'
request start three "$BASE/dest" rename
await_state RUNNING
track_record "$OWNER_ROOT/session-three/supervisor"
request stop three
await_state STOPPED
pass 'normal stop and reopen release/reacquire the same inbox lock'

# Five old-style fixture loops must block, not disappear because a lock probe was fixed.
OWNER_ROOT="$BASE/owner-with-legacy"
printf slow > "$BASE/mode"
legacy_pids=
for number in 1 2 3 4 5; do
    "$CLI" file get --conflict=rename --loop "$BASE/dest" >/dev/null 2>&1 &
    pid=$!; legacy_pids="$legacy_pids $pid"; printf '%s\n' "$pid" >> "$BASE/test-pids"
done
sleep 0.2
request start forbidden "$BASE/dest" rename
[ "$STATE" = BLOCKED ] || fail "five unmanaged consumers should yield BLOCKED, not $STATE"
[ ! -e "$OWNER_ROOT/current" ] || fail 'a new owner session was created despite legacy blockers'
for pid in $legacy_pids; do kill -0 "$pid" || fail 'helper killed a legacy fixture without confirmation'; done
pass 'five old-style consumers yield BLOCKED, no new session and no automatic killing'
printf 'ANDROID MKSH FD SMOKE: %s checks passed\n' "$PASSED"
