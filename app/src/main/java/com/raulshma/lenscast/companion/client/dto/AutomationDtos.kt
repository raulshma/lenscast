package com.raulshma.lenscast.companion.client.dto

import com.raulshma.lenscast.automation.schedule.AutomationRule
import com.squareup.moshi.JsonClass

/**
 * The companion client's automation-surface DTOs: faithful Kotlin
 * transcriptions of the GET/PUT `/api/automation` documents and the
 * `/api/automation/test` (+`/restore`) pair. Unlike most of this package,
 * these embed the DOMAIN rule type
 * [com.raulshma.lenscast.automation.schedule.AutomationRule] directly — the
 * server does too (the rule is the ONE declaration: domain, persistence and
 * wire shape; there is no separate wire clone to keep in lockstep), so the
 * client consumes the same data classes the app's own screens render.
 *
 * The rule graph's vocabulary enums (`triggerType`, `conditionMode`,
 * `missedRunPolicy`) serialize as their canonical string wire ids ONLY
 * through `AutomationRuleJson`'s Moshi instance — the client's automation
 * methods decode/encode with that instance (plain `AppJson` would emit the
 * enums' NAMES, a wire break against every persisted document and the
 * web/contract fixtures). The read-only `catalog`/`triggers`/`conditions`/
 * `limits` blocks the device documents also carry are deliberately NOT
 * mirrored here: the companion's editor renders from the app's own catalogs
 * (they are device-published read-only projections this client never sends
 * back), so Moshi simply ignores those members on decode.
 */

/**
 * GET /api/automation (and the PUT answer): the master toggle, the
 * configured rules (each decorated with a read-only `lastFiredAt` decoded
 * from the device's fire journal — never persisted, never sent back), and
 * the armed Automation Test's rule id (null = nothing armed).
 */
@JsonClass(generateAdapter = true)
data class RemoteAutomationState(
    val enabled: Boolean = false,
    val rules: List<AutomationRule> = emptyList(),
    val testArmedRuleId: String? = null,
)

/**
 * PUT /api/automation body: the master toggle plus the full rule list (the
 * document persists wholesale, like the settings sections). A null/blank
 * rule id lets the device mint the fire-journal UUID; an echoed
 * `lastFiredAt` is ignored (the device's sanitizer resets it).
 */
@JsonClass(generateAdapter = true)
data class RemoteAutomationUpdateRequest(
    val enabled: Boolean = false,
    val rules: List<AutomationRule> = emptyList(),
)

/** POST /api/automation/test body: the rule to fire once (snapshot held). */
@JsonClass(generateAdapter = true)
data class RemoteAutomationTestRequest(val ruleId: String = "")

/**
 * POST /api/automation/test (+`/restore`) answer: whether the arm succeeded
 * (a failed arm names the error — an unknown rule id, or the controller
 * being unavailable) and the now-armed id on success; the restore answers
 * plain success (a no-op success when nothing was armed).
 */
@JsonClass(generateAdapter = true)
data class RemoteAutomationTestResponse(
    val success: Boolean = false,
    val armedRuleId: String? = null,
    val error: String? = null,
)
