package com.hermes.agent.data.tools

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import com.hermes.agent.data.usb.DriverProfileExecutor
import com.hermes.agent.data.usb.DriverProfileRepository
import com.hermes.agent.data.usb.UsbInfo
import com.hermes.agent.domain.tools.*
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.inject.Inject
import kotlin.coroutines.resume

internal fun UsbDevice.toInfo() = UsbInfo(
    vid = vendorId,
    pid = productId,
    deviceClass = deviceClass,
    interfaces = (0 until interfaceCount).map { getInterface(it).let { i -> UsbInfo.Itf(i.interfaceClass, i.interfaceSubclass, i.interfaceProtocol) } },
    serialDriver = UsbSerialProber.getDefaultProber().probeDevice(this)?.javaClass?.simpleName?.removeSuffix("SerialDriver")
)

private fun usbManager(context: Context) = context.getSystemService(UsbManager::class.java)
    ?: throw IllegalStateException("This device has no USB host support.")

private suspend fun accessibleDevice(context: Context, name: String?, filter: (UsbDevice) -> Boolean = { true }): Pair<UsbManager, UsbDevice> {
    val manager = usbManager(context)
    val device = if (name != null) usbDevice(manager, name) else {
        manager.deviceList.values.filter(filter).singleOrNull()
            ?: throw IllegalArgumentException("Pass device (from usb_devices): ${manager.deviceList.values.count(filter)} matching devices are connected.")
    }
    when (requestUsbPermission(context, manager, device)) {
        true -> Unit
        false -> throw SecurityException("The user declined access to ${device.deviceName}.")
        null -> throw IllegalStateException("No answer to the USB access dialog within 60 seconds.")
    }
    return manager to device
}

private fun UsbDevice.interfaceOf(cls: Int): UsbInterface? = (0 until interfaceCount).map { getInterface(it) }.firstOrNull { it.interfaceClass == cls }

class UsbHidTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_hid"
    override val capability = Capability.USB
    override val description = "Drive a USB HID device (gamepads, macro pads, USB relays, UPS units, sensors, custom HID gadgets): " +
        "read its report descriptor summary and input reports, send output reports, or get/set feature reports. " +
        "Claiming a keyboard or mouse pauses it as a system input device until the call ends."
    override val parameters = schema(
        "device" to prop("string", "deviceName from usb_devices. Optional when only one HID device is connected."),
        "action" to prop("string", "read (default), send_output, get_feature, set_feature.", listOf("read", "send_output", "get_feature", "set_feature")),
        "data" to prop("string", "Report bytes for send_output/set_feature; the first byte is the report ID (0 if the device uses none)."),
        "encoding" to Payload.encodingProp,
        "report_id" to prop("integer", "Report ID for get_feature, default 0."),
        "seconds" to prop("integer", "How long to collect input reports for read (1-30, default 5).")
    )

    override fun requiresApproval(args: JsonObject) = args.string("action") in setOf("send_output", "set_feature")
    override fun label(args: JsonObject) = "HID ${args.string("action") ?: "read"}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val (manager, device) = accessibleDevice(this@UsbHidTool.context, args.string("device")) { it.interfaceOf(0x03) != null }
        val itf = device.interfaceOf(0x03) ?: throw IllegalArgumentException("${device.deviceName} has no HID interface.")
        val conn = manager.openDevice(device) ?: throw IllegalStateException("Android couldn't open ${device.deviceName}.")
        try {
            check(conn.claimInterface(itf, true)) { "Couldn't claim the HID interface." }
            val eps = (0 until itf.endpointCount).map { itf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
            val inEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val outEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            when (args.string("action") ?: "read") {
                "send_output" -> {
                    val b = Payload.from(args) ?: throw IllegalArgumentException("data is required")
                    val n = if (outEp != null) conn.bulkTransfer(outEp, b, b.size, 2_000)
                    else conn.controlTransfer(0x21, 0x09, (0x02 shl 8) or (b[0].toInt() and 0xff), itf.id, b, b.size, 2_000)
                    if (n < 0) "The device didn't accept the output report." else "Sent a ${b.size}-byte output report."
                }
                "set_feature" -> {
                    val b = Payload.from(args) ?: throw IllegalArgumentException("data is required")
                    val n = conn.controlTransfer(0x21, 0x09, (0x03 shl 8) or (b[0].toInt() and 0xff), itf.id, b, b.size, 2_000)
                    if (n < 0) "The device rejected the feature report." else "Set a ${b.size}-byte feature report."
                }
                "get_feature" -> {
                    val id = args.int("report_id") ?: 0
                    val buf = ByteArray(256)
                    val n = conn.controlTransfer(0xA1, 0x01, (0x03 shl 8) or id, itf.id, buf, buf.size, 2_000)
                    if (n < 0) "The device returned no feature report $id." else "Feature report $id: " + Payload.describe(buf.copyOf(n))
                }
                else -> {
                    val desc = ByteArray(4096)
                    val dn = conn.controlTransfer(0x81, 0x06, 0x2200, itf.id, desc, desc.size, 2_000)
                    val summary = if (dn > 0) hidSummary(desc.copyOf(dn)) else "report descriptor unavailable"
                    val ep = inEp ?: return@withContext "HID device ($summary). It has no input endpoint to read."
                    val reports = mutableListOf<String>()
                    val deadline = System.currentTimeMillis() + (args.int("seconds")?.coerceIn(1, 30) ?: 5) * 1000L
                    val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
                    while (System.currentTimeMillis() < deadline && reports.size < 200) {
                        val n = conn.bulkTransfer(ep, buf, buf.size, 250)
                        if (n > 0) reports += buf.copyOf(n).joinToString(" ") { "%02x".format(it) }
                    }
                    "HID device ($summary). ${reports.size} input reports:" +
                        reports.distinct().take(60).joinToString("") { "\n- $it" } +
                        if (reports.size > 60) "\n(showing distinct reports only)" else ""
                }
            }
        } finally {
            runCatching { conn.releaseInterface(itf) }
            conn.close()
        }
    }

}

    /** First usage page/usage of a report descriptor, e.g. "Generic Desktop / Gamepad". */
internal fun hidSummary(d: ByteArray): String {
    var page = -1
    var usage = -1
    var i = 0
    while (i < d.size && (page < 0 || usage < 0)) {
        val prefix = d[i].toInt() and 0xff
        val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
        val value = (0 until size).fold(0) { acc, k -> acc or ((d.getOrNull(i + 1 + k)?.toInt() ?: 0) and 0xff shl (8 * k)) }
        when (prefix and 0xfc) { 0x04 -> if (page < 0) page = value; 0x08 -> if (usage < 0) usage = value }
        i += 1 + size
    }
    val pageName = when (page) { 0x01 -> "Generic Desktop"; 0x0C -> "Consumer"; 0x0D -> "Digitizer"; 0x84, 0x85 -> "Power/UPS"; in 0xFF00..0xFFFF -> "Vendor-defined"; else -> "usage page 0x%x".format(page) }
    val usageName = if (page == 0x01) when (usage) { 2 -> "Mouse"; 4 -> "Joystick"; 5 -> "Gamepad"; 6 -> "Keyboard"; 7 -> "Keypad"; 8 -> "Multi-axis"; else -> "usage 0x%x".format(usage) }
    else "usage 0x%x".format(usage)
    return "$pageName / $usageName"
}

class UsbPrintTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_print"
    override val capability = Capability.USB
    override val description = "Drive a USB printer: read its IEEE 1284 device ID (maker, model, command languages), and send raw print data " +
        "such as plain text, ESC/POS for receipt printers, or PCL/PostScript."
    override val parameters = schema(
        "device" to prop("string", "deviceName from usb_devices. Optional when only one printer is connected."),
        "data" to prop("string", "Raw bytes to print. Leave out to only read the device ID."),
        "encoding" to Payload.encodingProp
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = Payload.from(args)?.let { "Print ${it.size} bytes" } ?: "Read printer ID"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val (manager, device) = accessibleDevice(this@UsbPrintTool.context, args.string("device")) { it.interfaceOf(0x07) != null }
        val itf = device.interfaceOf(0x07) ?: throw IllegalArgumentException("${device.deviceName} has no printer interface.")
        val conn = manager.openDevice(device) ?: throw IllegalStateException("Android couldn't open ${device.deviceName}.")
        try {
            check(conn.claimInterface(itf, true)) { "Couldn't claim the printer interface." }
            val buf = ByteArray(1024)
            val n = conn.controlTransfer(0xA1, 0x00, 0, (itf.id shl 8) or itf.alternateSetting, buf, buf.size, 2_000)
            val id = if (n > 2) parseDeviceId(buf.copyOf(n)) else emptyMap()
            val out = StringBuilder("Printer: ${id["MFG"] ?: id["MANUFACTURER"] ?: "?"} ${id["MDL"] ?: id["MODEL"] ?: ""}".trim())
            (id["CMD"] ?: id["COMMAND SET"])?.let { out.append("\nCommand languages: $it") }
            Payload.from(args)?.let { data ->
                val ep = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                    ?: throw IllegalStateException("The printer has no bulk OUT endpoint.")
                var sent = 0
                while (sent < data.size) {
                    val chunk = data.copyOfRange(sent, minOf(data.size, sent + 16_384))
                    val k = conn.bulkTransfer(ep, chunk, chunk.size, 10_000)
                    if (k <= 0) break
                    sent += k
                }
                out.append("\nSent $sent of ${data.size} bytes.")
            }
            out.toString()
        } finally {
            runCatching { conn.releaseInterface(itf) }
            conn.close()
        }
    }

    companion object {
        /** IEEE 1284 device ID: 2-byte big-endian length, then "KEY:value;KEY:value;". */
        fun parseDeviceId(raw: ByteArray): Map<String, String> {
            val len = ((raw[0].toInt() and 0xff) shl 8) or (raw[1].toInt() and 0xff)
            val text = String(raw, 2, (len - 2).coerceIn(0, raw.size - 2), Charsets.ISO_8859_1)
            return text.split(';').mapNotNull { part ->
                val k = part.substringBefore(':', "").trim().uppercase()
                if (k.isEmpty()) null else k to part.substringAfter(':').trim()
            }.toMap()
        }
    }
}

class UsbStorageTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_storage"
    override val capability = Capability.USB
    override val description = "Read and write files on a USB flash drive or card reader (FAT32) plugged into the phone: list folders, read a text file, or write one."
    override val parameters = schema(
        "action" to prop("string", "list (default), read, write.", listOf("list", "read", "write")),
        "path" to prop("string", "Folder or file path on the drive, e.g. / or /notes/todo.txt."),
        "data" to prop("string", "Content for write."),
        "encoding" to Payload.encodingProp
    )

    override fun requiresApproval(args: JsonObject) = args.string("action") == "write"
    override fun label(args: JsonObject) = "USB drive: ${args.string("action") ?: "list"} ${args.string("path") ?: "/"}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val manager = usbManager(this@UsbStorageTool.context)
        val drive = UsbMassStorageDevice.getMassStorageDevices(this@UsbStorageTool.context).firstOrNull()
            ?: throw IllegalStateException("No USB drive is connected.")
        when (requestUsbPermission(this@UsbStorageTool.context, manager, drive.usbDevice)) {
            true -> Unit
            false -> throw SecurityException("The user declined access to the drive.")
            null -> throw IllegalStateException("No answer to the USB access dialog within 60 seconds.")
        }
        drive.init()
        try {
            val fs = drive.partitions.firstOrNull()?.fileSystem ?: throw IllegalStateException("No readable FAT32 partition on the drive.")
            val path = args.string("path")?.trim('/')?.ifEmpty { null }
            when (args.string("action") ?: "list") {
                "read" -> {
                    val file = path?.let { fs.rootDirectory.search(it) } ?: throw IllegalArgumentException("File not found: ${args.string("path")}")
                    require(!file.isDirectory) { "${file.name} is a folder." }
                    val bytes = UsbFileStreamFactory.createBufferedInputStream(file, fs).use { input ->
                        val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                        while (total < 200_000) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n }
                        out.toByteArray()
                    }
                    "${file.name} (${file.length} bytes):\n" + Payload.describe(bytes, 100_000)
                }
                "write" -> {
                    val target = path ?: throw IllegalArgumentException("path is required")
                    val data = Payload.from(args) ?: ByteArray(0)
                    val dir = target.substringBeforeLast('/', "").let { d -> if (d.isEmpty()) fs.rootDirectory else fs.rootDirectory.search(d) }
                        ?: throw IllegalArgumentException("Folder not found: ${target.substringBeforeLast('/')}")
                    val name = target.substringAfterLast('/')
                    val file: UsbFile = dir.search(name) ?: dir.createFile(name)
                    UsbFileStreamFactory.createBufferedOutputStream(file, fs).use { it.write(data) }
                    "Wrote ${data.size} bytes to /$target."
                }
                else -> {
                    val dir = path?.let { fs.rootDirectory.search(it) } ?: fs.rootDirectory
                    require(dir.isDirectory) { "${dir.name} is a file; use action read." }
                    val entries = dir.listFiles().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    "Drive \"${fs.volumeLabel.trim()}\": ${fs.freeSpace / 1_048_576} MB free of ${fs.capacity / 1_048_576} MB.\n/${path.orEmpty()}:" +
                        entries.take(200).joinToString("") { "\n- ${it.name}${if (it.isDirectory) "/" else " (${it.length} bytes)"}" }
                }
            }
        } finally {
            drive.close()
        }
    }
}

class UsbMidiTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_midi"
    override val capability = Capability.USB
    override val description = "Talk to USB MIDI devices (keyboards, controllers, synths): list them, send MIDI messages " +
        "(e.g. note on \"90 3C 64\", program change \"C0 05\"), and record what they send for a few seconds."
    override val parameters = schema(
        "data" to prop("string", "MIDI bytes to send, usually hex."),
        "encoding" to Payload.encodingProp,
        "listen_seconds" to prop("integer", "How long to record incoming messages (0-30, default 3)."),
        "device" to prop("string", "Device name from the list, when several MIDI devices are connected.")
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = Payload.from(args)?.let { "Send ${it.size} MIDI bytes" } ?: "Listen to MIDI"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val midi = this.context.getSystemService(MidiManager::class.java) ?: throw IllegalStateException("This phone has no MIDI support.")
        @Suppress("DEPRECATION")
        val infos = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        else midi.devices.toList()).filter { it.type == MidiDeviceInfo.TYPE_USB }
        if (infos.isEmpty()) return "No USB MIDI devices are connected."
        fun nameOf(i: MidiDeviceInfo) = i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device ${i.id}"
        val info = args.string("device")?.let { want -> infos.firstOrNull { nameOf(it).contains(want, ignoreCase = true) } } ?: infos.first()

        val device: MidiDevice = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont -> midi.openDevice(info, { d -> cont.resume(d) }, Handler(Looper.getMainLooper())) }
        } ?: throw IllegalStateException("Couldn't open ${nameOf(info)}.")
        try {
            val received = Collections.synchronizedList(mutableListOf<String>())
            val outPort = if (info.outputPortCount > 0) device.openOutputPort(0) else null
            outPort?.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    received += msg.copyOfRange(offset, offset + count).joinToString(" ") { "%02x".format(it) }
                }
            })
            val payload = Payload.from(args)
            if (payload != null) {
                val inPort = device.openInputPort(0) ?: throw IllegalStateException("${nameOf(info)} has no input port to send to.")
                inPort.use { it.send(payload, 0, payload.size) }
            }
            delay((args.int("listen_seconds")?.coerceIn(0, 30) ?: 3) * 1000L)
            outPort?.close()
            return "Devices: ${infos.joinToString { nameOf(it) }}.\nUsing ${nameOf(info)}." +
                (payload?.let { " Sent ${it.size} bytes." } ?: "") +
                "\nReceived ${received.size} messages" + received.take(100).joinToString("") { "\n- $it" }
        } finally {
            device.close()
        }
    }
}

class UsbDriverInstallTool @Inject constructor(private val profiles: DriverProfileRepository) : AgentTool {
    override val name = "usb_driver_install"
    override val capability = Capability.USB
    override val needsApproval = true
    override val description = "Install a USB driver profile so every chat can drive a device that has no built-in driver. " +
        "Pass a URL to a profile JSON (e.g. a GitHub link) or the JSON itself, written from the device's documented protocol. Format: " +
        "{\"name\", \"match\":[{\"vid\":\"1a86\",\"pid\":\"7523\"}], \"transport\":\"raw|hid|serial\", \"interface\":0, \"baud\":9600, " +
        "\"init\":[steps], \"commands\":{\"on\":{\"description\":\"…\",\"steps\":[steps]}}}. Steps: {\"write\":\"A0 01 01 A2\",\"encoding\":\"hex\"} " +
        "(\"{data}\" inserts the caller's data), {\"read\":64,\"timeout_ms\":1000}, {\"delay_ms\":100}, " +
        "{\"control\":{\"request_type\":\"0x40\",\"request\":1,\"value\":0,\"index\":0,\"data\":\"hex\",\"length\":0}}."
    override val parameters = schema(
        "source" to prop("string", "Profile URL or the profile JSON."),
        required = listOf("source")
    )

    override fun label(args: JsonObject) = "Install USB driver " + (args.string("source")?.let { if (it.startsWith("{")) "profile" else it } ?: "")

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val p = profiles.install(args.string("source") ?: throw IllegalArgumentException("source is required"))
        return "Installed driver profile \"${p.name}\" (${p.transport.name.lowercase()}) for " +
            p.matches.joinToString { (v, pid) -> "%04x:%s".format(v, pid?.let { "%04x".format(it) } ?: "*") } +
            ". Commands: " + p.commands.entries.joinToString { (k, c) -> if (c.description.isBlank()) k else "$k (${c.description})" } +
            ". Use usb_driver_command to run them."
    }
}

class UsbDriverCommandTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profiles: DriverProfileRepository
) : AgentTool {
    override val name = "usb_driver_command"
    override val capability = Capability.USB
    override val needsApproval = true
    override val description = "Run a command from an installed USB driver profile on the matching connected device."
    override val parameters = schema(
        "command" to prop("string", "Command name from the profile, e.g. on, off, status."),
        "device" to prop("string", "deviceName from usb_devices. Optional when one device matches a profile."),
        "data" to prop("string", "Value for commands whose steps contain {data}."),
        required = listOf("command")
    )

    override fun label(args: JsonObject) = "USB driver command \"${args.string("command")}\""

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val installed = profiles.profiles.first()
        val (manager, device) = accessibleDevice(this@UsbDriverCommandTool.context, args.string("device")) { d ->
            installed.any { it.matches(d.vendorId, d.productId) }
        }
        val profile = installed.firstOrNull { it.matches(device.vendorId, device.productId) }
            ?: throw IllegalStateException("No installed driver profile matches ${device.deviceName}. Install one with usb_driver_install.")
        DriverProfileExecutor.run(manager, device, profile, args.string("command")!!, args.string("data"))
    }
}
