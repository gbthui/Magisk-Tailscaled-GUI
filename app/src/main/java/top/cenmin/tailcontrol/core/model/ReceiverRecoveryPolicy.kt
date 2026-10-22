package top.cenmin.tailcontrol.core.model

/** Foreground-only recovery; never acknowledge an uncertain batch or invent a dead receiver. */
enum class ReceiverRecoveryAction { NONE, RESTART_CONTROLLER, REQUIRE_REVIEW }

object ReceiverRecoveryPolicy {
    fun systemStopBlocks(blockedRevision: Long, currentRevision: Long): Boolean =
        blockedRevision == -1L || blockedRevision == currentRevision

    fun decide(enabled: Boolean, controllerActive: Boolean, blockedBySystem: Boolean,
               phase: ReceiverPhase): ReceiverRecoveryAction = when {
        !enabled || controllerActive -> ReceiverRecoveryAction.NONE
        blockedBySystem -> ReceiverRecoveryAction.REQUIRE_REVIEW
        phase == ReceiverPhase.PAUSED || phase == ReceiverPhase.BLOCKED -> ReceiverRecoveryAction.REQUIRE_REVIEW
        phase == ReceiverPhase.UNKNOWN -> ReceiverRecoveryAction.NONE
        else -> ReceiverRecoveryAction.RESTART_CONTROLLER
    }
}
