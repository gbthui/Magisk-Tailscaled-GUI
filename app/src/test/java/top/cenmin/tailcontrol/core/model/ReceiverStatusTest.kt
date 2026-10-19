package top.cenmin.tailcontrol.core.model

import org.junit.Test
import top.cenmin.tailcontrol.core.shell.CommandResult

class ReceiverStatusTest {
    @Test fun noResponseIsNotEvidenceOfExit() {
        check(ReceiverStatus.parse(emptyList()).phase == ReceiverPhase.UNKNOWN)
        check(ReceiverStatus.parse(listOf("su denied")).phase == ReceiverPhase.UNKNOWN)
    }
    @Test fun parsesOnlyTheStructuredOwnerResponse() {
        val result = ReceiverStatus.parse(listOf("noise", "TC1\tRUNNING\tsession-1\t123\tok"))
        check(result.phase == ReceiverPhase.RUNNING && result.generation == "session-1" && result.pid == 123)
        check(!result.quiescent)
    }
    @Test fun malformedOrMissingGenerationCannotAuthorizeAnAction() {
        for (line in listOf("TC1\tRUNNING\t-\t123\tok", "TC1\tMISSING\tx\t1\tx", "TC1\tRUNNING\t../x\t1\tx")) {
            check(ReceiverStatus.parse(listOf(line)).phase == ReceiverPhase.UNKNOWN)
        }
    }
    @Test fun unknownAndStoppingAreNotQuiescent() {
        check(!ReceiverStatus(ReceiverPhase.UNKNOWN).quiescent)
        check(!ReceiverStatus(ReceiverPhase.STOPPING).quiescent)
        check(ReceiverStatus(ReceiverPhase.PAUSED).quiescent)
        check(ReceiverStatus(ReceiverPhase.STOPPED).quiescent)
    }
    @Test fun legacySnapshotRetainsAllIdentityFields() {
        val hash = "ab".repeat(32)
        val receiver = LegacyReceiver.parse("LEGACY\taaaa-bbbb\t123\t456\t0\t$hash") ?: error("Missing snapshot")
        check(receiver.arguments().contentEquals(arrayOf("aaaa-bbbb", "123", "456", "0", hash)))
        check(LegacyReceiver.parse("123") == null)
    }

    @Test fun nonzeroProbeExitUsesTheReasonNotTheProtocolRecord() {
        val result = ReceiverStatus.fromCommand(CommandResult(false, 69,
            listOf("TC1\tUNKNOWN\t-\t0\tflock-probe-failed"),
            listOf("flock: Bad file descriptor")))
        check(result.phase == ReceiverPhase.UNKNOWN)
        check(result.reason.contains("flock-probe-failed"))
        check(result.reason.contains("exit 69"))
        check(result.reason.contains("Bad file descriptor"))
        check(!result.reason.contains("TC1") && !result.reason.contains('\t'))
        check(result.generation == null && result.pid == null)
    }
    @Test fun failedCommandCannotClaimRunningEvenWithAValidRecord() {
        val result = ReceiverStatus.fromCommand(CommandResult(false, 1,
            listOf("TC1\tRUNNING\tsession-1\t123\tok"), listOf("root IO failed")))
        check(result.phase == ReceiverPhase.UNKNOWN && !result.quiescent)
        check(result.generation == "session-1")
    }
    @Test fun successfulBlockedRecordRemainsBlocked() {
        val result = ReceiverStatus.fromCommand(CommandResult(true, 0,
            listOf("TC1\tBLOCKED\t-\t0\tunmanaged-receivers:11,12,13,14,15,"), emptyList()))
        check(result.phase == ReceiverPhase.BLOCKED)
        check(result.reason.contains("unmanaged-receivers"))
        check(!result.reason.contains("TC1"))
    }
    @Test fun malformedFailedResponseRemainsUnknownWithoutRawProtocol() {
        val result = ReceiverStatus.fromCommand(CommandResult(false, 74,
            listOf("TC1\tbroken"), listOf("state read failed")))
        check(result.phase == ReceiverPhase.UNKNOWN)
        check(result.reason.contains("Malformed") && result.reason.contains("state read failed"))
        check(!result.reason.contains("TC1"))
    }
    @Test fun missingFailedResponseIsStillUnknown() {
        val result = ReceiverStatus.fromCommand(CommandResult(false, 126, emptyList(), listOf("root denied")))
        check(result.phase == ReceiverPhase.UNKNOWN)
        check(result.reason.contains("root denied") && result.reason.contains("126"))
    }
}
