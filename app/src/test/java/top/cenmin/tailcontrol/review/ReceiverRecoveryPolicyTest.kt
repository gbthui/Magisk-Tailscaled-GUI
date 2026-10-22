package top.cenmin.tailcontrol.review

import org.junit.Test
import top.cenmin.tailcontrol.core.model.ReceiverPhase
import top.cenmin.tailcontrol.core.model.ReceiverRecoveryAction as Action
import top.cenmin.tailcontrol.core.model.ReceiverRecoveryPolicy as Policy

class ReceiverRecoveryPolicyTest {
    @Test fun enabledReceivingRestartsControllerAfterOrdinaryProcessDeath() {
        for (phase in listOf(ReceiverPhase.STOPPED, ReceiverPhase.RUNNING, ReceiverPhase.STARTING, ReceiverPhase.STOPPING)) {
            check(Policy.decide(true, false, false, phase) == Action.RESTART_CONTROLLER)
        }
    }
    @Test fun existingControllerAndDisabledIntentNeverStartAnotherService() {
        check(Policy.decide(true, true, false, ReceiverPhase.RUNNING) == Action.NONE)
        check(Policy.decide(false, false, false, ReceiverPhase.STOPPED) == Action.NONE)
    }
    @Test fun pausedAndBlockedSessionsRequireReviewRatherThanAutomaticAcknowledgement() {
        check(Policy.decide(true, false, false, ReceiverPhase.PAUSED) == Action.REQUIRE_REVIEW)
        check(Policy.decide(true, false, false, ReceiverPhase.BLOCKED) == Action.REQUIRE_REVIEW)
    }
    @Test fun unknownRootIdentityIsNotTreatedAsAnExitedReceiver() {
        check(Policy.decide(true, false, false, ReceiverPhase.UNKNOWN) == Action.NONE)
    }
    @Test fun timedOutSessionCannotAutomaticallyRestartOnForegroundReturn() {
        check(Policy.decide(true, false, true, ReceiverPhase.STOPPED) == Action.REQUIRE_REVIEW)
        check(Policy.systemStopBlocks(10, 10))
    }
    @Test fun oldTimeoutDoesNotBlockANewerExplicitUserRequest() {
        check(!Policy.systemStopBlocks(10, 11))
        check(!Policy.systemStopBlocks(Long.MIN_VALUE, 11))
        check(Policy.systemStopBlocks(-1, 11))
    }
}
