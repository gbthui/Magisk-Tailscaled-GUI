# Root command and Taildrop regression fix

## Scope

The GUI previously configured a fallback PATH in `RootShell`, but file sharing bypassed it with a separate `su -c tailscale ...` process. The fix belongs in the GUI; no Magisk module changes are required.

`ShellCommand` supplies quoting and a deterministic environment for ordinary jobs, dedicated streaming shells, standalone stdin processes, and copied SSH commands. The module's installation directories take precedence over the inherited PATH. The setup executes after `su`; ordinary/streaming libsu jobs isolate it in a subshell so the shared shell is not polluted. Shared and dedicated libsu shells use the same builder flags. Executability is checked per tool without a cached `which tailscale` decision.

Known-size, unknown-size, and empty file uploads now use one IO implementation. Unknown-size transfers show bytes processed and an indeterminate indicator. The byte count measures input handed to the CLI, not peer acknowledgement. Only a successful CLI exit plus successful complete input makes the transfer complete. Both output pipes are drained concurrently and bounded; final diagnostics are collected before reporting the result. Cancellation no longer runs `pkill tailscale` or reconnects the daemon.

Receivers quote their directory, create/check it in the root context, retain startup output in `/data/adb/tailscale/run/tailcontrol-file-get.log`, verify the receive process, and persist its actual PID. A receive PID must identify `tailscale file get`, not an unrelated process. The service checks CLI capabilities for all entry points, including boot and Quick Settings. A missing or failed CLI is not interpreted as supported.

AltRepo operations check for an executable `tailscaled.tun` before editing scripts, reject failed stop operations, and report restoration/start failures. Enabling an unavailable integration does not save the preference. Script rewrites use private same-directory temporary files instead of predictable shared temporary paths.

The historical assertion in `upstream-investigation.md` that every Tailscale version from 1.90 onward removed `file` is not a valid compatibility rule. Capability checks use the installed executable's help and exit status instead.

## Automated checks

Run on a Linux/macOS host with JDK 17, the project Gradle wrapper, and Android SDK 36:

```sh
./gradlew testDebugUnitTest assembleDebug
```

`ShellRegressionTest` exercises real host shell processes and pipes with a fixture CLI. It covers missing PATH, all command modes, literal special characters and Unicode, receiver directory quoting/identity, independent TUN discovery, capability failures, progress edge cases, empty/binary streams, late stderr, output backpressure, source failure/truncation, and cancellation without terminating an unrelated process. Run with a UTF-8 locale.

PR CI runs the unit tests and builds a debug APK without signing secrets. Signing, public release updates, and Telegram delivery are restricted to master, not pull requests. CI uses the dependency versions checked into the repository rather than downgrading Kotlin/KSP at runtime.

During this change, 18 regression cases passed in a standalone JVM harness using the production command/IO classes. Additional non-Compose Kotlin source was type-checked against interface stubs. These checks do not constitute an Android APK build or device validation. The local editing environment did not have Android SDK/Gradle dependencies or direct network access; Android compilation and packaging must be verified by CI or a configured development machine.

## Device regression checklist

- With only the full CLI path working, check status, both Ping screens, netcheck, login, service controls, and uploads. Repeat with normal module mounts enabled.
- Send known-size, unknown-size, and empty files, including names with spaces, quotes, dollar signs, and Chinese characters. Confirm content at the peer. Deny/revoke URI access and confirm a useful failure rather than a successful truncated transfer.
- Cancel an upload while ping and file receiving are active. Check the upload process has exited and the other operations remain active. Test both Magisk and KernelSU process/namespace behavior.
- Receive into a path with spaces/quotes. Test an unwritable path, failed receiver startup, repeated restarts, root denial, and a CLI built without Taildrop. Inspect the startup log; no dead PID should be recorded as a successful receiver.
- With no `tailscaled.tun`, confirm the AltRepo toggle is rejected before scripts change. With a compatible module, test sync, stop failure, restore failure, and successful restore.

An external terminal still needs a suitable SSH client for `tailscale ssh`. This fix supplies root/PATH/argument handling; it does not install OpenSSH.
