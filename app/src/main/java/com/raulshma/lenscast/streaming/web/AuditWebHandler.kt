package com.raulshma.lenscast.streaming.web

import com.raulshma.lenscast.core.AppJson
import com.raulshma.lenscast.streaming.model.AuditEntryDto
import com.raulshma.lenscast.streaming.model.AuditLogResponseDto

/**
 * GET /api/audit — the audit trail's read surface: newest-first entries
 * (optionally limited), mapped to the wire DTO. DELETE /api/audit — clears
 * the trail; the router audits the clear itself right after, so the fresh
 * trail always opens with `DELETE /api/audit` (a cleared log reads as
 * cleared — the clear is never silent). Mutating routes audit themselves
 * inside the ApiRouter; this handler only reads and clears.
 */
class AuditWebHandler(private val auditLog: AuditLog) {

    private val adapter by lazy { AppJson.moshi.adapter(AuditLogResponseDto::class.java) }
    private val successAdapter by lazy { AppJson.moshi.adapter(com.raulshma.lenscast.streaming.model.SuccessResponse::class.java) }

    fun list(limit: Int? = null): String {
        // Non-positive and absent limits both mean "the whole trail"; the
        // store clamps the read to its own cap.
        val entries = auditLog.entries(limit?.takeIf { it > 0 })
        return adapter.toJson(
            AuditLogResponseDto(
                entries = entries.map { entry ->
                    AuditEntryDto(
                        timestampMs = entry.timestampMs,
                        action = entry.action,
                        detail = entry.detail,
                        outcome = entry.outcome,
                    )
                },
                total = auditLog.count(),
            ),
        )
    }

    fun clear(): String {
        auditLog.clear()
        return successAdapter.toJson(com.raulshma.lenscast.streaming.model.SuccessResponse())
    }
}
