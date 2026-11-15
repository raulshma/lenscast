package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.automation.AutomationTestController
import com.raulshma.lenscast.streaming.model.AutomationTestArmRequestDto
import com.raulshma.lenscast.streaming.model.AutomationTestResponseDto

/**
 * POST /api/automation/test — the Automation Test's wire half (issue #18
 * item 3, the "Test/Apply Now" button): `{ "ruleId": … }` arms the rule —
 * snapshot the restorable state, fire the rule's actions once through the
 * same executor a scheduled fire uses — and POST /api/automation/test/restore
 * winds the touched axes back. The armed id rides the automation state
 * document's `testArmedRuleId`, so a reloaded dashboard re-learns the undo.
 *
 * Lazy provider like [DetectionTestWebHandler]: the controller lives on the
 * app graph, which the stack builder resolves when the first call arrives.
 * Test fires are never journaled — no `lastFiredAt`, no cooldown effects.
 */
class AutomationTestWebHandler(
    private val controller: () -> AutomationTestController?,
) {

    private val responseAdapter by lazy { AppJson.moshi.adapter(AutomationTestResponseDto::class.java) }
    private val requestAdapter by lazy { AppJson.moshi.adapter(AutomationTestArmRequestDto::class.java) }

    /** Arm a rule: fire its actions once, snapshot held for the restore. */
    suspend fun arm(body: String): String {
        val controller = controller()
            ?: return responseAdapter.toJson(
                AutomationTestResponseDto(success = false, error = "Automation test unavailable"),
            )
        val ruleId = requestAdapter.fromJson(body)?.ruleId?.takeIf { it.isNotBlank() }
            ?: return responseAdapter.toJson(
                AutomationTestResponseDto(success = false, error = "ruleId required"),
            )
        val armed = controller.arm(ruleId)
        return responseAdapter.toJson(
            AutomationTestResponseDto(
                success = armed,
                armedRuleId = if (armed) ruleId else null,
                error = if (armed) null else "Unknown rule id: $ruleId",
            ),
        )
    }

    /** Restore the armed test's pre-test state; a no-op success when nothing is armed. */
    suspend fun restore(): String {
        val controller = controller()
            ?: return responseAdapter.toJson(
                AutomationTestResponseDto(success = false, error = "Automation test unavailable"),
            )
        controller.restore()
        return responseAdapter.toJson(AutomationTestResponseDto(success = true))
    }
}
