package com.raulshma.lenscast.streaming.rtsp

/**
 * Pure encoder color-format selection: the capability ladder mapping the
 * requested [RtspInputFormat] onto a MediaCodec color format, with AUTO
 * fallback. The color-format constants mirror the stable platform values
 * (MediaCodecInfo.CodecCapabilities) so the ladder is Android-free and
 * JVM-tested; [H264Encoder] passes the codec's supported set in.
 */
object EncoderFormatPolicy {

    // Mirror of MediaCodecInfo.CodecCapabilities — stable platform constants.
    const val COLOR_FORMAT_YUV420_PLANAR = 19
    const val COLOR_FORMAT_YUV420_PACKED_SEMI_PLANAR = 20
    const val COLOR_FORMAT_YUV420_SEMI_PLANAR = 21
    const val COLOR_FORMAT_YUV420_FLEXIBLE = 0x7F420888

    data class SelectedFormat(
        val colorFormat: Int,
        val effectiveInputFormat: RtspInputFormat,
        /** True when the requested format was unsupported and the AUTO ladder chose instead (caller logs the fallback). */
        val fellBackToAuto: Boolean = false,
    )

    fun choose(supportedColorFormats: Set<Int>, requestedInputFormat: RtspInputFormat): SelectedFormat {
        val requested = when (requestedInputFormat) {
            RtspInputFormat.AUTO -> autoChoice(supportedColorFormats)
            RtspInputFormat.NV21 -> {
                val color = COLOR_FORMAT_YUV420_PACKED_SEMI_PLANAR
                if (supportedColorFormats.contains(color)) SelectedFormat(color, RtspInputFormat.NV21) else null
            }
            RtspInputFormat.NV12 -> {
                val color = COLOR_FORMAT_YUV420_SEMI_PLANAR
                if (supportedColorFormats.contains(color)) SelectedFormat(color, RtspInputFormat.NV12) else null
            }
            RtspInputFormat.I420 -> when {
                supportedColorFormats.contains(COLOR_FORMAT_YUV420_PLANAR) ->
                    SelectedFormat(COLOR_FORMAT_YUV420_PLANAR, RtspInputFormat.I420)
                supportedColorFormats.contains(COLOR_FORMAT_YUV420_FLEXIBLE) ->
                    SelectedFormat(COLOR_FORMAT_YUV420_FLEXIBLE, RtspInputFormat.I420)
                else -> null
            }
        }

        val selected = requested
            ?: autoChoice(supportedColorFormats)?.copy(fellBackToAuto = requestedInputFormat != RtspInputFormat.AUTO)
        if (selected != null) return selected

        // Nothing the codec declares is on our ladder: hard default rather
        // than refusing to encode.
        return SelectedFormat(COLOR_FORMAT_YUV420_SEMI_PLANAR, RtspInputFormat.NV12)
    }

    private fun autoChoice(supportedColorFormats: Set<Int>): SelectedFormat? {
        val preferred = listOf(
            COLOR_FORMAT_YUV420_SEMI_PLANAR,
            COLOR_FORMAT_YUV420_PLANAR,
            COLOR_FORMAT_YUV420_FLEXIBLE,
            // Keep packed semi-planar as last resort. On some devices this format is
            // ambiguously implemented and may behave like NV12/NV21 inconsistently.
            COLOR_FORMAT_YUV420_PACKED_SEMI_PLANAR,
        )
        val color = preferred.firstOrNull { supportedColorFormats.contains(it) } ?: return null
        return SelectedFormat(color, mapColorFormatToInputFormat(color))
    }

    private fun mapColorFormatToInputFormat(colorFormat: Int): RtspInputFormat {
        return when (colorFormat) {
            // Treat packed semi-planar as NV12 for compatibility. In practice this avoids
            // frequent magenta/green tint issues seen when assuming strict NV21 ordering.
            COLOR_FORMAT_YUV420_PACKED_SEMI_PLANAR -> RtspInputFormat.NV12
            COLOR_FORMAT_YUV420_SEMI_PLANAR -> RtspInputFormat.NV12
            COLOR_FORMAT_YUV420_PLANAR,
            COLOR_FORMAT_YUV420_FLEXIBLE -> RtspInputFormat.I420
            else -> RtspInputFormat.NV12
        }
    }
}
