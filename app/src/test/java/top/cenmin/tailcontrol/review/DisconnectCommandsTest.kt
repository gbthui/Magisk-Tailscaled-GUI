package top.cenmin.tailcontrol.review

import kotlinx.coroutines.runBlocking
import org.junit.Test
import top.cenmin.tailcontrol.core.shell.CommandResult
import top.cenmin.tailcontrol.core.shell.DisconnectCommands

class DisconnectCommandsTest {
    private fun success() = CommandResult(true, 0, listOf("ok"), emptyList())
    @Test fun cleanupStillRunsWhenDaemonDownFails() = runBlocking {
        val order = mutableListOf<String>()
        val result = DisconnectCommands.run(true,
            { order += "down"; CommandResult.failure("daemon unavailable", 1) },
            { order += "cleanup"; success() })
        check(order == listOf("down", "cleanup"))
        check(!result.ok && result.code == 1 && result.text.contains("daemon unavailable"))
    }
    @Test fun bothFailuresAreReportedWithoutClaimingDisconnectSucceeded() = runBlocking {
        val result = DisconnectCommands.run(true,
            { CommandResult.failure("daemon missing", 1) }, { CommandResult.failure("script missing", 127) })
        check(!result.ok && result.text.contains("daemon missing") && result.text.contains("script missing"))
        check(!result.text.contains("Tailscale disconnected"))
    }
    @Test fun successfulDownStillReportsFailedCleanup() = runBlocking {
        val result = DisconnectCommands.run(true, { success() }, { CommandResult.failure("cleanup error", 2) })
        check(!result.ok && result.code == 2 && result.text.contains("cleanup error"))
    }
    @Test fun disabledIntegrationDoesNotRunOptionalCleanup() = runBlocking {
        val result = DisconnectCommands.run(false, { success() }, { error("must not run") })
        check(result.ok)
    }
    @Test fun bothSuccessesAreRequiredForOverallSuccess() = runBlocking {
        check(DisconnectCommands.run(true, { success() }, { success() }).ok)
    }
}
