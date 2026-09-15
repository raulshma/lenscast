package com.raulshma.lenscast.core.push

/**
 * Pure RFC 8292 `sub` (Subject) claim validation for the VAPID identity.
 * Push services reject the whole JWT when `sub` is neither a `mailto:` nor
 * an `https:` URI — and they reject it at dispatch time, so a mistyped
 * subject surfaces as silently dead pushes, never as a visible error. The
 * settings field is free text saved per keystroke, so the UI asks [problem]
 * for its live error state and the sender routes through [orDefault] rather
 * than dispatching with a claim every push service will refuse.
 */
object VapidSubjectPolicy {

    const val MAILTO_SCHEME = "mailto:"
    const val HTTPS_SCHEME = "https://"

    /** null when [subject] is a usable RFC 8292 sub; otherwise the reason it is not. */
    fun problem(subject: String): String? {
        val trimmed = subject.trim()
        return when {
            trimmed.isEmpty() -> "blank"
            trimmed.startsWith(MAILTO_SCHEME, ignoreCase = true) -> null
            trimmed.startsWith(HTTPS_SCHEME, ignoreCase = true) -> null
            else -> "scheme"
        }
    }

    /** [fallback] unless [subject] passes [problem] — the sender's fail-open path. */
    fun orDefault(subject: String, fallback: String): String =
        if (problem(subject) == null) subject else fallback
}
