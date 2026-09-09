package com.raulshma.lenscast.streaming.onvif

/**
 * Pure SOAP-request classifier for the ONVIF device service.
 *
 * In a SOAP 1.2 document the first child element of the `Body` element IS
 * the requested operation (per the SOAP/ONVIF one-operation-per-body
 * contract), so routing only needs that element's local-name.
 *
 * Documented simplification: a regex over the Body's first child — no
 * namespace resolution, no attribute parsing, no entity decoding. ONVIF
 * operation names are unique across the device (`tds:`) and media (`trt:`)
 * namespaces, so the local-name alone routes the request. Anything that
 * does not look like a body returns null and the caller answers with the
 * shared fault. Pure JVM code, tested without sockets.
 */
object OnvifRequestParser {

    /**
     * The operation local-name of [body]'s first Body child, or null when
     * the body is null, blank, garbage, or carries no Body element.
     * Handles both prefixed (`<tds:GetDeviceInformation ...>`) and
     * default-namespace (`<GetSystemDateAndTime ...>`) bodies.
     */
    fun operation(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val inner = BODY_REGEX.find(body)?.groupValues?.get(1) ?: return null
        // The first element-like token inside the Body content. Closing tags
        // (`</`), comments (`<!--`) and declarations (`<?`) cannot match the
        // leading name character, so the first match is the operation child.
        val child = FIRST_CHILD_REGEX.find(inner) ?: return null
        // Group 1 is the prefixed form's local-name, group 2 the default-ns one.
        return child.groupValues[1].ifBlank { child.groupValues[2] }.ifBlank { null }
    }

    /** Body open tag with any prefix; captures everything up to its closing tag. */
    private val BODY_REGEX = Regex(
        "<(?:[A-Za-z_][\\w.-]*:)?Body[^>]*>(.*?)</(?:[A-Za-z_][\\w.-]*:)?Body>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** `<prefix:LocalName` or `<LocalName` — captures the local-name. */
    private val FIRST_CHILD_REGEX = Regex(
        "<[A-Za-z_][\\w.-]*:([A-Za-z_][\\w.-]*)|<([A-Za-z_][\\w.-]*)",
    )
}
