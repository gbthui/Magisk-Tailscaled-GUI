# Codex review follow-up to 775dd27

All seven findings from review 5580119431 were independently accepted as actionable. No module/CLI changes, device cleanup, merge, or release is part of this follow-up.

| Review comment | Decision and implementation |
| --- | --- |
| 4320897874: upload FGS per progress event | Start once synchronously from the visible send action, before launching IO. Progress goes through an in-process StateFlow registry; the existing service updates its notification. Notification-rendering exceptions are isolated from the file writer. Session IDs prevent stale completion/progress from removing or recreating another upload. Repeated service-start calls are unnecessary even on OS versions which happen to allow an already-running FGS. |
| 4320897878: enabled receiving after process death | MainActivity.onResume performs foreground-only reconciliation, cancelled on pause and rechecked before starting. It recreates only the controller for known, enabled sessions. PAUSED/BLOCKED require review; UNKNOWN never authorizes another receiver. START_NOT_STICKY, leases and timeout shutdown remain. A synchronous revision-scoped system-stop marker prevents a process death before the async DataStore update from accidentally reopening a timed-out session; explicit user start can clear it. |
| 4320897883: down failure skips TUN cleanup | Execute enabled cleanup independently of the down exit status; preserve and label both failures and their exit codes instead of claiming a disconnect succeeded. Cancellation is not swallowed. |
| 4320897889: receiver notice opens Home | Handle show_drop_screen in both onCreate and onNewIntent. A saved, consumable navigation request sends the existing NavController to Drop, with Home retained as the back target. Consumed extras are removed; old acknowledgements cannot consume a newer notification. |
| 4320897893: restart after partial route edit | Sync, restore and removal restart only after both edits and stop succeed. Restore requires both backup files. On a partial failure, leave TUN stopped and explicitly require repair/restore before reconnecting. This is not a claim of a filesystem transaction across two files. Manager operations are serialized. |
| 4320897895: unpublished STOPPING owner | Reproduce UNKNOWN on the old helper. Handle absence in STOPPING like STARTING only after the startup lease expires and the inbox lock is free. A held lock or malformed/unreadable existing identity stays UNKNOWN; a pending batch stays PAUSED. A delayed supervisor checks its current generation and stop request before publishing/running. |
| 4320897899: missing TUN prevents removal | Remove owned on-disk blocks even without the optional executable (or when stopping it fails), report live-rule cleanup separately, and never restart after failed stop/edits. Do not persist the disabled UI state and hide retry controls while disk edits remain incomplete. Unrelated script content is preserved. |

## Regression evidence

The previous helper failed the newly added expired-unpublished-STOPPING test (`UNKNOWN` instead of `STOPPED`). Six new Linux process tests cover recovery, unexpired leases, held locks, pending batches, malformed records and late launch. The full local host ownership suite passes 28 tests.

Five new JUnit test classes provide 28 cases: upload registry (5), foreground-recovery policy (6), navigation request consumption (4), independent disconnect cleanup (5), and real temporary-file TUN operations/failure injection (8). The local JVM harness passed all 28 against production logic, with constructor-only Android interfaces stubbed. Android compilation and full Gradle tests are a separate CI check. Existing host-mksh, Android 15 FD/emulator and command/upload tests remain enabled.

The UI/system behavior still needs real device acceptance: background an upload without aborting it; reopen after a process kill with receiving enabled; confirm timed-out/uncertain sessions require explicit action; tap receiver notifications from both cold and existing singleTask activities; verify failed route edits leave TUN stopped. No host-only test is represented as KernelSU/Magisk device validation.
