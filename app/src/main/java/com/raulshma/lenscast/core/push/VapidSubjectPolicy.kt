package com.raulshma.lenscast.core.push

/**
 * Pure RFC 8292 `sub` (Subject) claim validation for the VAPID identity.
 * Push services reject the whole JWT when `sub` is neither a `mailto:` nor
 * an `https:` URI — and they reject it at dispatch time, so a mistyped
 * subject surfaces as silently dead pushes, never as a visible error. The
 * settings field is free text saved per keystroke, so the UI asks [isUsable]
 * for its live error state and the sender routes through [orDefault] rather
 * than dispatching with a claim every push service will refuse.
 */
object VapidSubjectPolicy {

    const val MAILTO_SCHEME = "mailto:"
    const val HTTPS_SCHEME = "https://"

    /** Whether [subject] is a usable RFC 8292 sub: non-blank, `mailto:` or `https:`. */
    fun isUsable(subject: String): Boolean {
        val trimmed = subject.trim()
        return trimmed.startsWith(MAILTO_SCHEME, ignoreCase = true) ||
            trimmed.startsWith(HTTPS_SCHEME, ignoreCase = true)
    }

    /** [fallback] unless [subject] fails [isUsable] — the sender's fail-open path. */
    fun orDefault(subject: String, fallback: String): String =
        if (isUsable(subject)) subject else fallback
}
