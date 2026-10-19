# Taildrop receiver ownership and recovery

## Diagnosis and scope

The supplied device investigation identifies the original GUI's inconsistent PID handling in `DropRepository.startFileGet`: it returned the shell's `$!` on success but persisted a nullable, incorrectly inferred child PID. The service then created additional consumers on its periodic check. The same absent persisted PID made the stop path ineffective. The reported four simultaneous consumers and four near-simultaneous copies strongly support concurrent consumption as the principal cause of that incident. The evidence does not require a failing inbox DELETE. The supplied report also rules out treating NUL loss in libsu's `BufferedReader.readLine` as an established cause.

This follow-up supersedes the receiver-PID portion of draft PR #1, while retaining its shared PATH and upload IO fixes. Changes are confined to the GUI. It does not install an APK, access a device, remove existing copies, restart tailscaled, or modify the CLI/module. No private device addresses, file contents, filenames or raw investigation logs are included in this document.

## Root owner protocol

`assets/taildrop-owner.sh` is installed as a content-addressed, root-owned helper under `/data/adb/tailcontrol`. This is outside the module's installation/backup tree and its transient `tailscale/run` directory. Never unlink or replace `inbox.lock` to clear a problem: another live process may still hold the original inode.

There are three distinct ordering mechanisms:

- The Android service has one serialized reconciliation loop. It observes current DataStore configuration rather than using stale Intent extras. `stopSelfResult(startId)` prevents an old stop from terminating a later service request.
- DataStore request revisions fence timeout/error corrections. A stale callback cannot disable a newer user request; the Quick Settings toggle itself is transactional.
- Root control requests use FD-based `flock -n fd` and an immutable UUID generation. Stop and acknowledgement must match the current generation. A separate inbox lock covers the entire root supervisor/child lifetime, not just launch or one receive directory.

All supported GUI consumers use the same inbox lock. The scope is the application's default daemon/socket, deliberately shared across destinations. Manually launched CLI clients do not participate in this lock: recognizable existing unmanaged `file get` processes block startup, and checks between batches pause on detected unmanaged consumers. This is not a server-side exclusive-claim API and cannot enforce mutual exclusion against every possible external LocalAPI client.

The supervisor acquires and holds the inbox lock. Its receiver child inherits FD 8, publishes its boot ID, PID, kernel starttime, UID, exact argv hash and raw expected argv before exec, then runs the CLI. The supervisor verifies FD inheritance while the child is active; unsupported execution environments pause instead of falling back to unlocked receiving. The child retaining the lock also prevents a second owner after a supervisor crash. A durable `pending` marker additionally blocks automatic replay of an interrupted batch.

Root records, not DataStore's cached PID or the application's receipt of `$!`, are authoritative. Losing the start response, cancelling its caller, or losing the GUI PID cache does not authorize another consumer. States distinguish RUNNING, STARTING, STOPPING, STOPPED, PAUSED, BLOCKED and UNKNOWN. Unreadable identity or a held lock without a recognized owner is not interpreted as death. PID/starttime/argv/boot checks are repeated before signalling; this is a shell implementation, not a pidfd-based atomic signalling API.

## Receive acknowledgement and intentional behavior changes

The helper invokes serial, non-looping `tailscale file get --verbose --conflict=... -- <directory>` batches. Each success clears the pending marker. Any nonzero exit pauses automatic consumption and preserves diagnostics. This handles the known `get succeeded / DELETE failed` window conservatively without parsing human-readable error text or requiring a new CLI build.

Important tradeoffs:

- `skip` name conflicts also pause further batches. After resolving the conflict, the user must review and explicitly acknowledge resumption in the app. This is intentionally stricter than the CLI's automatic `--loop` retry behavior.
- Pausing on failure is not an exactly-once protocol. A copied file may still have an inbox entry after a failure, lost DELETE response or interruption. The recovery dialog warns that resuming may produce another copy. Nothing is silently deleted or hash-deduplicated; two legitimate identical sends remain two transfers.
- Non-looping polling incurs extra CLI startup work compared with a permanently waiting CLI. Battery use, Doze behavior and performance must be assessed on actual target devices before release. A future CLI protocol with explicit per-item acknowledgement could avoid this tradeoff.
- Process-crash recovery is fail-closed. The filesystem records use atomic replacement, but this is not a power-loss-safe database or a substitute for server-side item identifiers/transactions.

Runtime diagnostics are appended to `events.log` and `receiver.log` under the owner directory and tail-limited. They include generation, timestamps, exit codes and verbose CLI output. Root owner/session metadata is retained for audit. Do not publish the logs without redacting private names and paths.

## Shutdown, legacy migration and Android limits

A normal stop asks the supervisor to stop between batches. An active batch gets a bounded grace period; interruption pauses the session for review rather than silently replaying it. A matching recorded child can be stopped after its supervisor dies. Failed confirmation retains state; no global `pkill tailscale` is used by the receiving controller.

Old APK receivers do not acquire the new lock and may have no usable saved PID. The app therefore provides an explicit legacy inspection/confirmation action. The snapshot includes boot ID, PID, starttime, UID and argv hash; the helper also checks the executable inode against the installed module before signalling. One-shot receivers, uploads, Ping and login are not selected for cleanup. Changed identities and survivors are reported, not announced as stopped. A different/old executable which cannot be attributed safely can still block startup and may require device-side investigation. Installing this patch alone does not remotely clean up existing device processes.

The Android service is a bounded observer/controller with a 20-second renewable root lease. Normal app/service loss stops renewal and causes the root owner to stop; failed identity checks remain fail-closed rather than authorizing an unsafe signal or a second receiver. A root supervisor killed while its child survives leaves a locked/pending session for explicit recovery, not an automatically restarted consumer.

Both dataSync services implement Android 15's `onTimeout(int, int)` and call `stopSelf` promptly without awaiting root IO. Receiving stops renewal immediately and requests generation-scoped cleanup; uploads cancel only registered upload jobs. Separate attention notifications survive foreground-service shutdown when notification permission is granted. This does not turn dataSync into an unlimited daemon or evade its quota with sticky restarts. Android 15+ boot broadcasts no longer attempt to launch dataSync; the user is told to reopen the app. A permanently root-hosted receiving product, independent of the GUI, is a separate design decision.

## Validation

On Linux, run `bash tests/run-receiver-tests.sh`. The test helper uses real host processes, `/proc`, flock file descriptors and a compiled fixture CLI; it neither requires root nor contacts a real inbox/device. Cases cover parallel starts, discarded/closed responses, stale stops, shared locks across destinations, missing records, PID identity changes, legacy blockers, literal paths, interrupted ownership, FD inheritance, successful duplicate user sends and failed delete acknowledgements. The fixture also demonstrates four unlocked readers creating four copies without a delete error. These are mechanism tests, not an Android or real Tailscale transport reproduction.

`ReceiverStatusTest` adds JVM protocol parsing tests to the existing Gradle unit tests. The Android CI must run `testDebugUnitTest assembleDebug`; its result is separate from host tests.

Before release, test the built APK on the reported KernelSU/Android 15 environment and Magisk: real FD inheritance across `su`/mksh/Go, legacy migration, one send/one receipt, verified shutdown, rapid STOP/START, configuration changes, app termination, real `/proc` identity reads, Doze, and shortened dataSync timeouts. Preserve and restore any test-only Android timeout settings. Check file hashes and process identities; do not delete existing copies to make the test appear successful.

## References

- Original GUI snapshot: `2498bc1bb038d25c2804a213ac1c427d58049498`, `core/data/DropRepository.kt` and `service/DropProtectService.kt`.
- CLI snapshot: `88ab8c82047b33f25d8d57ee35c5d84ea3eb5297`, `cmd/tailscale/cli/file.go` and `feature/taildrop/retrieve.go`.
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
