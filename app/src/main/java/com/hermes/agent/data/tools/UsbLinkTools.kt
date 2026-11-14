package com.hermes.agent.data.tools

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

private fun manager(context: Context) = context.getSystemService(UsbManager::class.java)
    ?: throw IllegalStateException("This device has no USB host support.")

private suspend fun openAccess(context: Context, manager: UsbManager, deviceName: String?) =
    usbDevice(manager, deviceName).also { device ->
        when (requestUsbPermission(context, manager, device)) {
            true -> Unit
            false -> throw SecurityException("The user declined access to ${device.deviceName}.")
            null -> throw IllegalStateException("No answer to the USB access dialog within 60 seconds.")
        }
    }

class UsbSerialTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_serial"
    override val capability = Capability.USB
    override val description = "Talk to a USB serial device (Arduino, ESP32, Raspberry Pi Pico, GPS, modems, FTDI/CP210x/CH340/PL2303 adapters): " +
        "open it at a baud rate, optionally send data, and return what it sends back. Leave data out to just listen."
    override val parameters = schema(
        "device" to prop("string", "deviceName from usb_devices. Optional when only one device is connected."),
        "baud" to prop("integer", "Baud rate, default 115200."),
        "data" to Payload.dataProp,
        "encoding" to Payload.encodingProp,
        "read_seconds" to prop("integer", "How long to collect incoming data (0-60, default 3)."),
        "dtr" to prop("boolean", "Raise DTR/RTS on open (default true; many boards only talk with DTR set)."),
        "port" to prop("integer", "Port index on multi-port adapters, default 0.")
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = (Payload.from(args)?.let { "Send ${it.size} bytes over USB serial" } ?: "Read USB serial") +
        " at ${args.int("baud") ?: 115200} baud"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val manager = manager(this@UsbSerialTool.context)
        val device = openAccess(this@UsbSerialTool.context, manager, args.string("device"))
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IllegalStateException(
                "${device.deviceName} (vendor 0x%04x, product 0x%04x) isn't a recognised serial device. Try usb_transfer for raw endpoints."
                    .format(device.vendorId, device.productId)
            )
        val port = driver.ports.getOrNull(args.int("port") ?: 0) ?: throw IllegalArgumentException("That adapter has ${driver.ports.size} port(s).")
        val connection = manager.openDevice(device) ?: throw IllegalStateException("Android couldn't open ${device.deviceName}.")
        val baud = args.int("baud") ?: 115200
        val payload = Payload.from(args)
        try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (args.get("dtr")?.asBoolean != false) {
                runCatching { port.setDTR(true); port.setRTS(true) }
            }
            payload?.let { port.write(it, 2_000) }
            val received = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + (args.int("read_seconds")?.coerceIn(0, 60) ?: 3) * 1000L
            val buf = ByteArray(port.readEndpoint?.maxPacketSize?.coerceAtLeast(64) ?: 4096)
            while (System.currentTimeMillis() < deadline && received.size() < 256 * 1024) {
                val n = port.read(buf, 200)
                if (n > 0) received.write(buf, 0, n)
            }
            "Opened ${driver.javaClass.simpleName.removeSuffix("SerialDriver")} serial device ${device.deviceName} at $baud baud." +
                (payload?.let { " Sent ${it.size} bytes." } ?: "") +
                "\nReceived " + Payload.describe(received.toByteArray())
        } finally {
            runCatching { port.close() }
        }
    }
}

class UsbTransferTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_transfer"
    override val capability = Capability.USB
    override val description = "Raw USB I/O for devices without a serial driver: claim an interface, write to its bulk/interrupt OUT endpoint " +
        "and/or read from its IN endpoint. Get interface and endpoint numbers from usb_devices."
    override val parameters = schema(
        "device" to prop("string", "deviceName from usb_devices. Optional when only one device is connected."),
        "interface" to prop("integer", "Interface index, default 0."),
        "data" to Payload.dataProp,
        "encoding" to Payload.encodingProp,
        "read_bytes" to prop("integer", "How many bytes to read from the IN endpoint (0 to skip, default 512)."),
        "timeout_ms" to prop("integer", "Per-transfer timeout, default 2000.")
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = (Payload.from(args)?.let { "Write ${it.size} bytes to" } ?: "Read from") +
        " USB interface ${args.int("interface") ?: 0}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val manager = manager(this@UsbTransferTool.context)
        val device = openAccess(this@UsbTransferTool.context, manager, args.string("device"))
        val index = args.int("interface") ?: 0
        require(index in 0 until device.interfaceCount) { "${device.deviceName} has ${device.interfaceCount} interfaces." }
        val itf = device.getInterface(index)
        val endpoints = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK || it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
        val out: UsbEndpoint? = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val inEp: UsbEndpoint? = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val timeout = args.int("timeout_ms")?.coerceIn(50, 30_000) ?: 2_000
        val payload = Payload.from(args)
        val readBytes = args.int("read_bytes")?.coerceIn(0, 65_536) ?: 512

        val connection = manager.openDevice(device) ?: throw IllegalStateException("Android couldn't open ${device.deviceName}.")
        try {
            check(connection.claimInterface(itf, true)) { "Couldn't claim interface $index (another driver may own it)." }
            val report = StringBuilder("Interface $index on ${device.deviceName}.")
            payload?.let {
                val ep = out ?: throw IllegalStateException("Interface $index has no bulk/interrupt OUT endpoint.")
                val sent = connection.bulkTransfer(ep, it, it.size, timeout)
                report.append(if (sent < 0) " Write failed or timed out." else " Wrote $sent bytes to endpoint 0x%02x.".format(ep.address))
            }
            if (readBytes > 0) {
                val ep = inEp ?: throw IllegalStateException("Interface $index has no bulk/interrupt IN endpoint.")
                val buf = ByteArray(readBytes)
                val got = connection.bulkTransfer(ep, buf, buf.size, timeout)
                report.append("\nRead from endpoint 0x%02x: ".format(ep.address))
                    .append(if (got < 0) "nothing within $timeout ms" else Payload.describe(buf.copyOf(got)))
            }
            report.toString()
        } finally {
            runCatching { connection.releaseInterface(itf) }
            connection.close()
        }
    }
}
