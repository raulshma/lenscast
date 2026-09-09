package com.raulshma.lenscast.streaming.onvif

import com.raulshma.lenscast.streaming.rtsp.RtspVideoCodec
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * The single-homed ONVIF entity tokens. One profile, one video source —
 * LensCast serves exactly one of each, so the constants live here and every
 * builder and call site references these symbols.
 */
object OnvifTokens {
    const val PROFILE_TOKEN = "Profile_1"
    const val VIDEO_SOURCE_TOKEN = "VideoSource_1"
    const val VIDEO_ENCODER_TOKEN = "VideoEncoder_1"
    const val AUDIO_ENCODER_TOKEN = "AudioEncoder_1"
}

/**
 * XML-escape every interpolated string before it enters a SOAP document:
 * the five XML entities, apostrophe included (attribute-safe).
 */
internal fun xmlEscape(value: String): String = buildString(value.length) {
    for (c in value) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\'' -> append("&apos;")
        '"' -> append("&quot;")
        else -> append(c)
    }
}

/**
 * Pure builders for the ONVIF device-service SOAP responses — complete
 * SOAP 1.2 envelope strings with the `tds`/`trt`/`tt`/`tdn` namespaces
 * declared. Every dynamic value (URIs, dates, versions, counts) is a
 * parameter, so the builders are fully JVM-testable and the transport
 * (StreamingServer → OnvifServer) never assembles device data inline.
 *
 * The operation set is the minimal Profile S surface Home Assistant's ONVIF
 * integration probes for (the go2rtc reference server implements exactly
 * these): date/time first (HA needs it before anything else), capabilities,
 * services, device information, one video source, one Profile S with H.264
 * (+ AAC when audio is enabled), stream and snapshot URIs.
 */
object OnvifResponses {

    /** GetSystemDateAndTime — UTC wall clock, DST false (HA's first probe). */
    fun getSystemDateAndTime(nowMs: Long): String {
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        val year = String.format(Locale.US, "%04d", utc.get(Calendar.YEAR))
        val month = utc.get(Calendar.MONTH) + 1
        val day = utc.get(Calendar.DAY_OF_MONTH)
        val hour = utc.get(Calendar.HOUR_OF_DAY)
        val minute = utc.get(Calendar.MINUTE)
        val second = utc.get(Calendar.SECOND)
        return envelope(
            "<tds:GetSystemDateAndTimeResponse>" +
                "<tds:SystemDateAndTime>" +
                "<tt:DateTimeType>NTP</tt:DateTimeType>" +
                "<tt:DaylightSavings>false</tt:DaylightSavings>" +
                "<tt:TimeZone><tt:TZ>UTC</tt:TZ></tt:TimeZone>" +
                "<tt:UTCDateTime>" +
                "<tt:Time>" +
                "<tt:Hour>$hour</tt:Hour>" +
                "<tt:Minute>$minute</tt:Minute>" +
                "<tt:Second>$second</tt:Second>" +
                "</tt:Time>" +
                "<tt:Date>" +
                "<tt:Year>$year</tt:Year>" +
                "<tt:Month>$month</tt:Month>" +
                "<tt:Day>$day</tt:Day>" +
                "</tt:Date>" +
                "</tt:UTCDateTime>" +
                "</tds:SystemDateAndTime>" +
                "</tds:GetSystemDateAndTimeResponse>",
        )
    }

    /**
     * GetCapabilities — Device and Media blocks, both XAddrs pointing at the
     * device service URL. The Media side advertises RTP-over-TCP (the RTSP
     * server's interleaved transport is what HA negotiates) and the
     * StreamingUri capability the integration gates on.
     */
    fun getCapabilities(deviceServiceUrl: String): String = envelope(
        "<tds:GetCapabilitiesResponse>" +
            "<tt:Capabilities>" +
            "<tt:Device><tt:XAddr>${xmlEscape(deviceServiceUrl)}</tt:XAddr></tt:Device>" +
            "<tt:Media>" +
            "<tt:XAddr>${xmlEscape(deviceServiceUrl)}</tt:XAddr>" +
            "<tt:StreamingUri>true</tt:StreamingUri>" +
            "<tt:StreamingCapabilities>" +
            "<tt:RTPMulticast>false</tt:RTPMulticast>" +
            "<tt:RTP_TCP>true</tt:RTP_TCP>" +
            "<tt:RTPOverUDP>false</tt:RTPOverUDP>" +
            "</tt:StreamingCapabilities>" +
            "</tt:Media>" +
            "</tt:Capabilities>" +
            "</tds:GetCapabilitiesResponse>",
    )

    /** GetServices — the Device and Media service entries behind one XAddr. */
    fun getServices(deviceServiceUrl: String): String = envelope(
        "<tds:GetServicesResponse>" +
            service(NAMESPACE_DEVICE, deviceServiceUrl, DEVICE_VERSION_MAJOR, DEVICE_VERSION_MINOR) +
            service(NAMESPACE_MEDIA, deviceServiceUrl, MEDIA_VERSION_MAJOR, MEDIA_VERSION_MINOR) +
            "</tds:GetServicesResponse>",
    )

    private fun service(namespace: String, xaddr: String, major: Int, minor: Int): String =
        "<tt:Service>" +
            "<tt:Namespace>$namespace</tt:Namespace>" +
            "<tt:XAddr>${xmlEscape(xaddr)}</tt:XAddr>" +
            "<tt:Version><tt:Major>$major</tt:Major><tt:Minor>$minor</tt:Minor></tt:Version>" +
            "</tt:Service>"

    /** GetDeviceInformation — static identity metadata, all escaped. */
    fun getDeviceInformation(
        manufacturer: String,
        model: String,
        firmwareVersion: String,
        serialNumber: String,
        hardwareId: String,
    ): String = envelope(
        "<tds:GetDeviceInformationResponse>" +
            "<tt:Manufacturer>${xmlEscape(manufacturer)}</tt:Manufacturer>" +
            "<tt:Model>${xmlEscape(model)}</tt:Model>" +
            "<tt:FirmwareVersion>${xmlEscape(firmwareVersion)}</tt:FirmwareVersion>" +
            "<tt:SerialNumber>${xmlEscape(serialNumber)}</tt:SerialNumber>" +
            "<tt:HardwareId>${xmlEscape(hardwareId)}</tt:HardwareId>" +
            "</tds:GetDeviceInformationResponse>",
    )

    /** GetVideoSources — exactly one source, sized to the live configuration. */
    fun getVideoSources(
        videoSourceToken: String = OnvifTokens.VIDEO_SOURCE_TOKEN,
        width: Int,
        height: Int,
        fps: Int,
    ): String = envelope(
        "<trt:GetVideoSourcesResponse>" +
            "<tt:VideoSource token=\"${xmlEscape(videoSourceToken)}\">" +
            "<tt:Framerate>$fps</tt:Framerate>" +
            "<tt:Resolution><tt:Width>$width</tt:Width><tt:Height>$height</tt:Height></tt:Resolution>" +
            "</tt:VideoSource>" +
            "</trt:GetVideoSourcesResponse>",
    )

    /**
     * GetProfiles — the single Profile S: the configured codec's video
     * encoder over the configured dimensions/bitrate/fps (ONVIF advertises
     * the bitrate in kbps, LensCast configures in bps), plus the AAC audio
     * encoder only when audio is enabled.
     */
    fun getProfiles(
        profileToken: String = OnvifTokens.PROFILE_TOKEN,
        videoSourceToken: String = OnvifTokens.VIDEO_SOURCE_TOKEN,
        width: Int,
        height: Int,
        videoBitrate: Int,
        fps: Int,
        audioEnabled: Boolean,
        videoCodec: RtspVideoCodec = RtspVideoCodec.H264,
    ): String {
        val h265 = videoCodec == RtspVideoCodec.H265
        val encoding = if (h265) "H265" else "H264"
        val codecOptions = if (h265) {
            "<tt:H265><tt:GovLength>$fps</tt:GovLength><tt:H265Profile>Main</tt:H265Profile></tt:H265>"
        } else {
            "<tt:H264><tt:GovLength>$fps</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>"
        }
        return envelope(
        "<trt:GetProfilesResponse>" +
            "<tt:Profiles token=\"${xmlEscape(profileToken)}\" fixed=\"true\">" +
            "<tt:Name>${xmlEscape(PROFILE_NAME)}</tt:Name>" +
            "<tt:VideoSourceConfiguration token=\"${xmlEscape(videoSourceToken)}\">" +
            "<tt:Name>${xmlEscape(VIDEO_SOURCE_NAME)}</tt:Name>" +
            "<tt:UseCount>1</tt:UseCount>" +
            "<tt:SourceToken>${xmlEscape(videoSourceToken)}</tt:SourceToken>" +
            "<tt:Bounds x=\"0\" y=\"0\" width=\"$width\" height=\"$height\"/>" +
            "</tt:VideoSourceConfiguration>" +
            "<tt:VideoEncoderConfiguration token=\"${xmlEscape(OnvifTokens.VIDEO_ENCODER_TOKEN)}\">" +
            "<tt:Name>${xmlEscape(VIDEO_ENCODER_NAME)}</tt:Name>" +
            "<tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>$encoding</tt:Encoding>" +
            "<tt:Resolution><tt:Width>$width</tt:Width><tt:Height>$height</tt:Height></tt:Resolution>" +
            "<tt:Quality>4</tt:Quality>" +
            "<tt:RateControl>" +
            "<tt:FrameRateLimit>$fps</tt:FrameRateLimit>" +
            "<tt:EncodingInterval>1</tt:EncodingInterval>" +
            "<tt:BitrateLimit>${videoBitrate / 1000}</tt:BitrateLimit>" +
            "</tt:RateControl>" +
            codecOptions +
            multicastBlock() +
            "<tt:SessionTimeout>PT10S</tt:SessionTimeout>" +
            "</tt:VideoEncoderConfiguration>" +
            (if (audioEnabled) audioEncoderBlock() else "") +
            "</tt:Profiles>" +
            "</trt:GetProfilesResponse>",
    )
    }

    /** AAC encoder block — the RTSP audio track's ONVIF mirror. */
    private fun audioEncoderBlock(): String =
        "<tt:AudioEncoderConfiguration token=\"${xmlEscape(OnvifTokens.AUDIO_ENCODER_TOKEN)}\">" +
            "<tt:Name>${xmlEscape(AUDIO_ENCODER_NAME)}</tt:Name>" +
            "<tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>AAC</tt:Encoding>" +
            "<tt:BitrateLimit>$AUDIO_BITRATE_KBPS</tt:BitrateLimit>" +
            "<tt:SampleRate>$AUDIO_SAMPLE_RATE</tt:SampleRate>" +
            multicastBlock() +
            "<tt:SessionTimeout>PT10S</tt:SessionTimeout>" +
            "</tt:AudioEncoderConfiguration>"

    /** The no-multicast transport block the encoder schemas require. */
    private fun multicastBlock(): String =
        "<tt:Multicast>" +
            "<tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address>" +
            "<tt:Port>0</tt:Port><tt:TTL>0</tt:TTL><tt:AutoStart>false</tt:AutoStart>" +
            "</tt:Multicast>"

    /** GetStreamUri — the RTSP URI the client PLAYs (TCP interleaved). */
    fun getStreamUri(
        rtspUri: String,
        profileToken: String = OnvifTokens.PROFILE_TOKEN,
    ): String = mediaUriResponse("GetStreamUriResponse", rtspUri)

    /** GetSnapshotUri — the web server's JPEG snapshot route. */
    fun getSnapshotUri(snapshotUri: String): String =
        mediaUriResponse("GetSnapshotUriResponse", snapshotUri)

    /**
     * Shared MediaUri response shape. The [profileToken] parameter keeps the
     * requested profile explicit at call sites — the ONVIF response schema
     * carries no profile-token field to echo it into, so a future
     * multi-profile server would branch on it rather than print it.
     */
    private fun mediaUriResponse(operation: String, uri: String): String = envelope(
        "<trt:$operation>" +
            "<tt:MediaUri>" +
            "<tt:Uri>${xmlEscape(uri)}</tt:Uri>" +
            "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>" +
            "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>" +
            "<tt:Timeout>PT60S</tt:Timeout>" +
            "</tt:MediaUri>" +
            "</trt:$operation>",
    )

    /**
     * The shared fault response for unknown or malformed operations —
     * `ter:ActionNotSupported`, the verdict ONVIF clients map to
     * "this server does not implement that".
     */
    fun fault(message: String = UNSUPPORTED_MESSAGE): String = envelope(
        "<s:Fault>" +
            "<s:Code>" +
            "<s:Value>s:Sender</s:Value>" +
            "<s:Subcode><s:Value>$ACTION_NOT_SUPPORTED</s:Value></s:Subcode>" +
            "</s:Code>" +
            "<s:Reason><s:Text xml:lang=\"en\">${xmlEscape(message)}</s:Text></s:Reason>" +
            "</s:Fault>",
    )

    /** Complete SOAP 1.2 envelope with every namespace the responses use. */
    private fun envelope(body: String): String =
        XML_DECLARATION + ENVELOPE_OPEN + "<s:Body>$body</s:Body></s:Envelope>"

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    private const val ENVELOPE_OPEN =
        "<s:Envelope" +
            " xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:tt=\"http://www.onvif.org/ver10/schema\"" +
            " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"" +
            " xmlns:trt=\"http://www.onvif.org/ver20/media/wsdl\"" +
            " xmlns:tdn=\"http://www.onvif.org/ver10/network/wsdl\"" +
            ">"
    private const val NAMESPACE_DEVICE = "http://www.onvif.org/ver10/device/wsdl"
    private const val NAMESPACE_MEDIA = "http://www.onvif.org/ver20/media/wsdl"
    private const val ACTION_NOT_SUPPORTED = "ter:ActionNotSupported"
    private const val UNSUPPORTED_MESSAGE = "The requested operation is not supported"

    /** Media service version advertised via GetServices. */
    private const val DEVICE_VERSION_MAJOR = 16
    private const val DEVICE_VERSION_MINOR = 12
    private const val MEDIA_VERSION_MAJOR = 2
    private const val MEDIA_VERSION_MINOR = 0

    private const val PROFILE_NAME = "LensCast Stream"
    private const val VIDEO_SOURCE_NAME = "LensCast Video Source"
    private const val VIDEO_ENCODER_NAME = "LensCast H264"
    private const val AUDIO_ENCODER_NAME = "LensCast AAC"

    /** ONVIF audio-encoder literals — the RTSP AAC track serves one rate. */
    private const val AUDIO_BITRATE_KBPS = 64
    private const val AUDIO_SAMPLE_RATE = 48000
}
