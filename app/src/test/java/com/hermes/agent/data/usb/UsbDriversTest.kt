package com.hermes.agent.data.usb

import com.hermes.agent.data.tools.UsbPrintTool
import com.hermes.agent.data.tools.hidSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDriversTest {

    private val ids by lazy { UsbIds.parse(javaClass.classLoader!!.getResource("usb.ids.sample")!!.readText()) }

    @Test
    fun parsesUsbIdsNamesAndClasses() {
        assertEquals("QinHeng Electronics", ids.vendor(0x1a86))
        assertEquals("CH340 serial converter", ids.product(0x1a86, 0x7523))
        assertEquals("Human Interface Device", ids.className(0x03))
        assertEquals("Boot Interface Subclass", ids.subclassName(0x03, 0x01))
        assertEquals("Keyboard", ids.protocolName(0x03, 0x01, 0x01))
        assertEquals("Mass Storage", ids.className(0x08))
        assertTrue(ids.version!!.startsWith("20"))
        assertNull(ids.vendor(0xdead))
    }

    private fun kinds(info: UsbInfo, profiles: List<DriverProfile> = emptyList()) =
        UsbCatalog.classify(info, profiles).map { it.tool to it.kind }

    private fun itf(c: Int, s: Int = 0, p: Int = 0) = UsbInfo.Itf(c, s, p)

    @Test
    fun classifiesCommonDevices() {
        // Arduino Uno R4 style CDC-ACM
        assertEquals("usb_serial", kinds(UsbInfo(0x2341, 0x1002, 0xEF, listOf(itf(0x02, 0x02, 0x01), itf(0x0A))))[0].first)
        // CH340: vendor-specific interfaces, recognised by the serial prober
        assertEquals("usb_serial", kinds(UsbInfo(0x1a86, 0x7523, 0xFF, listOf(itf(0xFF, 0x01, 0x02)), serialDriver = "Ch34x"))[0].first)
        // Boot keyboard
        UsbCatalog.classify(UsbInfo(0x046d, 0xc31c, 0, listOf(itf(0x03, 0x01, 0x01))))[0].let {
            assertEquals("usb_hid", it.tool); assertTrue(it.title.contains("keyboard"))
        }
        assertEquals("usb_storage", kinds(UsbInfo(0x0781, 0x5567, 0, listOf(itf(0x08, 0x06, 0x50))))[0].first)
        assertEquals("usb_print", kinds(UsbInfo(0x04b8, 0x0202, 0, listOf(itf(0x07, 0x01, 0x02))))[0].first)
        assertEquals("usb_midi", kinds(UsbInfo(0x0763, 0x2001, 0, listOf(itf(0x01, 0x01), itf(0x01, 0x03))))[0].first)
        // Phone in adb mode: Termux pack
        UsbCatalog.classify(UsbInfo(0x18d1, 0x4ee7, 0, listOf(itf(0x06, 0x01, 0x01), itf(0xFF, 0x42, 0x01))))
            .first { it.kind == DriverKind.TERMUX_PACK && "android-tools" in it.packages }
        // Smart card reader: pack that needs root
        assertTrue(UsbCatalog.classify(UsbInfo(0x076b, 0x3021, 0, listOf(itf(0x0B)))).any { it.needsRoot })
        // Unknown vendor device: only raw I/O
        assertEquals(listOf("usb_transfer" to DriverKind.RAW), kinds(UsbInfo(0x1234, 0x5678, 0xFF, listOf(itf(0xFF)))))
    }

    @Test
    fun installedProfileTakesPriority() {
        val profile = DriverProfile.parse(RELAY, "test")
        val drivers = UsbCatalog.classify(UsbInfo(0x1a86, 0x7523, 0xFF, listOf(itf(0xFF)), serialDriver = "Ch34x"), listOf(profile))
        assertEquals(DriverKind.PROFILE, drivers[0].kind)
        assertEquals("usb_driver_command", drivers[0].tool)
    }

    @Test
    fun parsesDriverProfiles() {
        val p = DriverProfile.parse(RELAY, "https://example.com/relay.json")
        assertEquals("LCUS-1 USB relay", p.name)
        assertEquals(DriverProfile.Transport.SERIAL, p.transport)
        assertEquals(9600, p.baud)
        assertTrue(p.matches(0x1a86, 0x7523))
        assertTrue(!p.matches(0x1a86, 0x5523))
        assertEquals(setOf("on", "off", "raw"), p.commands.keys)
        assertEquals("Relay on", p.commands["on"]!!.description)
        val ctrl = p.init[0] as DriverProfile.Step.Control
        assertEquals(0x40, ctrl.requestType)
        assertTrue(!ctrl.isIn)
        assertEquals(DriverProfile.Step.Write("{data}", "hex"), p.commands["raw"]!!.steps[0])
    }

    @Test
    fun rejectsBrokenProfiles() {
        listOf(
            "not json",
            """{"match":[{"vid":"1a86"}],"commands":{"x":[]}}""",          // no name
            """{"name":"a","commands":{"x":[]}}""",                          // no match
            """{"name":"a","match":[{"vid":"1a86"}]}""",                     // no commands
            """{"name":"a","match":[{"vid":"1a86"}],"transport":"bluetooth","commands":{"x":[]}}""",
            """{"name":"a","match":[{"vid":"1a86"}],"commands":{"x":[{"format_disk":true}]}}"""
        ).forEach { bad -> assertTrue(bad, runCatching { DriverProfile.parse(bad, "t") }.isFailure) }
    }

    @Test
    fun parsesPrinterDeviceId() {
        val text = "MFG:EPSON;CMD:ESC/POS;MDL:TM-T20II;CLS:PRINTER;"
        val raw = byteArrayOf(0, (text.length + 2).toByte()) + text.toByteArray()
        val id = UsbPrintTool.parseDeviceId(raw)
        assertEquals("EPSON", id["MFG"])
        assertEquals("TM-T20II", id["MDL"])
        assertEquals("ESC/POS", id["CMD"])
    }

    @Test
    fun summarisesHidReportDescriptors() {
        // Usage Page (Generic Desktop) 05 01, Usage (Gamepad) 09 05
        assertEquals("Generic Desktop / Gamepad", hidSummary(byteArrayOf(0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01)))
        // Usage Page (Vendor 0xFF00) 06 00 FF, Usage 09 01
        assertEquals("Vendor-defined / usage 0x1", hidSummary(byteArrayOf(0x06, 0x00, 0xFF.toByte(), 0x09, 0x01)))
    }

    companion object {
        val RELAY = """
            {
              "name": "LCUS-1 USB relay",
              "description": "CH340-based single relay board",
              "match": [{"vid": "1a86", "pid": "7523"}],
              "transport": "serial",
              "baud": 9600,
              "init": [{"control": {"request_type": "0x40", "request": 161, "value": 0, "index": 0}}],
              "commands": {
                "on":  {"description": "Relay on",  "steps": [{"write": "A0 01 01 A2", "encoding": "hex"}]},
                "off": [{"write": "A0 01 00 A1", "encoding": "hex"}],
                "raw": [{"write": "{data}", "encoding": "hex"}, {"read": 8, "timeout_ms": 500}]
              }
            }
        """.trimIndent()
    }
}
