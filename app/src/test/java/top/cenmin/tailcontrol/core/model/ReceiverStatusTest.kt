package top.cenmin.tailcontrol.core.model

import org.junit.Test

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
}
