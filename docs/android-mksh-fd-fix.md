# Android mksh descriptor inheritance follow-up

## Cause and evidence boundary

The device report for CI merge commit `7e2f923` (PR head `63b45ae`) confirms that the installed helper matched SHA-256 `903d60fc899933588f5f022ac65a404cf9af7e3566d1dc63c2b5e609ccbc3912`. On its Android mksh, `exec 7>>file` opens a descriptor which is closed on an external exec. Consequently, a later `flock -n 7` does not receive FD 7, although the shell still has it. A redirection on the shell function call does not fix the external exec inside it.

This is a bug in the earlier GUI helper's portability, not proof of a competing inbox lock, a missing executable, broken permissions, NUL parsing, or a failed Taildrop DELETE. The earlier `/bin/sh`-only host tests did not cover this mksh behavior. The supplied five legacy receivers are a separate blocker; this patch does not remotely stop them or presume they disappeared.

## Targeted changes

- `lock_fd` now selects an executable/argument vector and applies static `6>&6`, `7>&7`, `8>&8`, or `9>&9` **on the actual external command**. Standalone system/PATH flock and both BusyBox fallback paths share this leaf implementation. Unsupported descriptor numbers are rejected; no eval or reopening of the lock file is used.
- The supervisor explicitly passes FD 8 into the child shell, and the child explicitly preserves it on the final native CLI exec. Control FD 9 remains excluded. The same open-file-description is retained, not a new lock handle.
- Probe, inbox/control locking, post-exec FD checks, UNKNOWN behavior, and legacy-consumer blocking remain enabled.
- Status re-reads the supervisor's final state after proving it exited and the inbox lock is free. This avoids overwriting a just-published specific PAUSED error with the generic `interrupted-batch` reason, a race exposed by the missing-FD regression.
- `ReceiverStatus.fromCommand` parses a structured error record even for a nonzero helper exit, then keeps the phase UNKNOWN and combines a bounded diagnostic summary. The UI no longer displays raw `TC1` protocol lines as an error. Failed commands cannot report a healthy RUNNING state.

## Regression layers

1. Existing 22 owner/process tests run under both host `/bin/sh` and **host mksh**, not merely with an environment variable while still invoking `/bin/sh`. All helper entrypoints and descendants use the selected shell.
2. `test_lock_fds.py` extracts the production `lock_fd` function and checks FDs 6/7/8/9, independent competing open-file-descriptions, continued ownership after the lock tool exits, and release after the holder exits. It tests installed standalone/Toybox/BusyBox tools, explicitly skips missing applets, and separately labels the BusyBox argv-adapter contract test. Actual mksh negative controls must reproduce the old direct-call and function-redirection failures. A shell-to-shell-to-native test keeps the lock after the original owner dies.
3. A separate Android 15 emulator job runs `/system/bin/sh` (mksh) and real system flock/Toybox, using a native NDK-compiled fixture. It checks both negative controls, all four FD numbers, real exclusion/release, the complete production helper chain, native FD 8 with no FD 9 leakage, child survival after supervisor death, one fixture send/one file, stop/reopen, and five old-style fixture consumers producing BLOCKED without being killed automatically.
4. Five new JVM parser tests cover exit 69 diagnostics, malformed/missing records, failed commands claiming RUNNING, and successful BLOCKED status. They are included alongside the five existing protocol tests and 18 upload/command tests.

The Android script and runner refuse non-fixture paths and physical-device serials. They operate only in a unique `/data/local/tmp/tailcontrol-fd-ci-*` directory. The native fixture is not Tailscale, does not connect to a network, and is not packaged in the app. A passing emulator test is not a claim that a KernelSU/Magisk device or actual file exchange was tested.

## Device acceptance

After installing the new build, compare the APK's packaged helper SHA-256 with the newly installed content-addressed helper. Do not patch or overwrite the old helper in place, unlink lock files, or force UNKNOWN to RUNNING.

On the reported device, if the old consumers still exist, the expected result after this fix is **BLOCKED**, not RUNNING. Use the explicit identity-checked inspection/confirmation path or separately approved device diagnostics to handle them. Then verify real FD inheritance, one actual send/one received file, confirmed receiver exit on close, reopening, and owner/child lifetime behavior. Preserve existing duplicate files and evidence. This change does not alter the previous pause-on-batch-failure or bounded Android session policy.

References:
- AOSP mksh `src/exec.c` (`iosetup`) and `src/main.c` (`ksh_dup2`): https://android.googlesource.com/platform/external/mksh/+/refs/heads/main/src/
- The user's Android mksh probe and deployed-helper report supplied with this task. Raw private device logs are not copied into the repository.
