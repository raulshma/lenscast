package com.raulshma.lenscast.streaming.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnvifRequestParserTest {

    @Test
    fun `prefixed soap body resolves the operation local name`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
              <s:Body>
                <tds:GetDeviceInformation>
                  <tds:DeviceType xmlns="http://www.onvif.org/ver10/schema"/>
                </tds:GetDeviceInformation>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals("GetDeviceInformation", OnvifRequestParser.operation(body))
    }

    @Test
    fun `default namespace body resolves the operation`() {
        val body = """
            <Envelope xmlns="http://www.w3.org/2003/05/soap-envelope">
              <Body>
                <GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>
              </Body>
            </Envelope>
        """.trimIndent()
        assertEquals("GetSystemDateAndTime", OnvifRequestParser.operation(body))
    }

    @Test
    fun `media namespace prefixed body resolves the operation`() {
        val body = """<s:Envelope><s:Body><trt:GetProfiles xmlns="x"/></s:Body></s:Envelope>"""
        assertEquals("GetProfiles", OnvifRequestParser.operation(body))
    }

    @Test
    fun `garbage body yields null`() {
        assertNull(OnvifRequestParser.operation("this is not soap at all"))
        assertNull(OnvifRequestParser.operation("<html><body>nope</body></html>"))
        assertNull(OnvifRequestParser.operation("12345"))
    }

    @Test
    fun `empty and null bodies yield null`() {
        assertNull(OnvifRequestParser.operation(null))
        assertNull(OnvifRequestParser.operation(""))
        assertNull(OnvifRequestParser.operation("   \n  "))
    }

    @Test
    fun `body element without content yields null`() {
        assertNull(OnvifRequestParser.operation("<s:Envelope><s:Body></s:Body></s:Envelope>"))
        assertNull(OnvifRequestParser.operation("<s:Envelope><s:Body/></s:Envelope>"))
    }

    @Test
    fun `envelope without a body yields null`() {
        assertNull(OnvifRequestParser.operation("<s:Envelope><s:Header/></s:Envelope>"))
    }
}
