package com.raulshma.lenscast.core

import java.util.Locale

/**
 * The backup destinations the BackupWorker can route an upload to, and the
 * pure routing verdict that picks one. [wireName] is the persisted/API string
 * form ("webdav" / "telegram") — the one spelling the DTO contract uses.
 */
enum class BackupTarget(val wireName: String) {
    WEBDAV("webdav"),
    TELEGRAM("telegram"),
}

/**
 * Pure backup-target routing. The selected target wins only when its
 * credentials are configured; otherwise the verdict is null and the worker
 * fails the attempt exactly like a missing WebDAV URL always did — there is
 * no silent cross-target fallback, so captures never land on a service the
 * user did not select.
 */
object BackupTargetPolicy {

    /** The persisted/API default: `"webdav"`. */
    const val DEFAULT_WIRE_NAME = "webdav"

    /**
     * Tolerant parse of the stored/API string through the one enum-parsing
     * idiom ([parseEnum] over the case-normalized wire name, explicit
     * fallback): null/blank/unknown decodes to webdav.
     */
    fun parse(raw: String?): BackupTarget =
        parseEnum(raw?.trim()?.uppercase(Locale.US), BackupTarget.WEBDAV)

    /**
     * The routing verdict: the selected [target] when configured (WebDAV URL
     * is an http(s) URL, Telegram token and chat id are both non-blank),
     * null — nothing usable — otherwise.
     */
    fun resolve(
        target: BackupTarget,
        webdavConfigured: Boolean,
        telegramConfigured: Boolean,
    ): BackupTarget? = when (target) {
        BackupTarget.WEBDAV -> if (webdavConfigured) BackupTarget.WEBDAV else null
        BackupTarget.TELEGRAM -> if (telegramConfigured) BackupTarget.TELEGRAM else null
    }
}
