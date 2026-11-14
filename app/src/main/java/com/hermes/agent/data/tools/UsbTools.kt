package com.hermes.agent.data.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

private fun usbClass(c: Int) = when (c) {
    UsbConstants.USB_CLASS_AUDIO -> "audio"
    UsbConstants.USB_CLASS_COMM -> "communications/serial"
    UsbConstants.USB_CLASS_HID -> "HID (keyboard/mouse/gamepad)"
    UsbConstants.USB_CLASS_PRINTER -> "printer"
    UsbConstants.USB_CLASS_MASS_STORAGE -> "mass storage"
    UsbConstants.USB_CLASS_HUB -> "hub"
    UsbConstants.USB_CLASS_CDC_DATA -> "CDC data"
    UsbConstants.USB_CLASS_VIDEO -> "video/camera"
    UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "wireless controller"
    UsbConstants.USB_CLASS_VENDOR_SPEC -> "vendor-specific"
    UsbConstants.USB_CLASS_PER_INTERFACE -> "per-interface"
    UsbConstants.USB_CLASS_MISC -> "misc"
    else -> "class $c"
}

private fun describe(manager: UsbManager, d: UsbDevice): String = buildString {
    val access = manager.hasPermission(d)
    // Names and serials are hidden by Android until the user grants access to the device.
    fun safe(block: () -> String?) = if (access) runCatching(block).getOrNull() else null
    append("- ${d.deviceName}: vendor 0x%04x, product 0x%04x".format(d.vendorId, d.productId))
    listOfNotNull(safe { d.manufacturerName }, safe { d.productName }).takeIf { it.isNotEmpty() }?.let { append(" (${it.joinToString(" ")})") }
    safe { d.serialNumber }?.let { append(", serial $it") }
    append(", ${usbClass(d.deviceClass)}")
    append(if (access) ", access granted" else ", access not granted")
    appendLine()
    for (i in 0 until d.interfaceCount) {
        val itf = d.getInterface(i)
        val endpoints = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .joinToString { e -> (if (e.direction == UsbConstants.USB_DIR_IN) "in" else "out") + "/" + e.type }
        appendLine("    interface $i: ${usbClass(itf.interfaceClass)}${if (endpoints.isNotEmpty()) ", endpoints $endpoints" else ""}")
    }
}

@Singleton
class UsbDevicesTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ids: com.hermes.agent.data.usb.UsbIdsRepository,
    private val profiles: com.hermes.agent.data.usb.DriverProfileRepository,
    private val library: com.hermes.agent.data.toolbox.ToolLibraryRepository
) : AgentTool {
    override val name = "usb_devices"
    override val capability = Capability.USB
    override val description = "List and identify devices plugged into this phone over USB: maker and product names (from the USB ID database), " +
        "class, interfaces, and which driver can drive each one (built-in, installed driver profile, Termux pack, or raw I/O) with the tool to call. " +
        "Also lists USB network links such as USB tethering or USB Ethernet adapters."
    override val parameters = schema()

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val manager = this.context.getSystemService(UsbManager::class.java)
            ?: return "This device has no USB host support."
        val devices = manager.deviceList.values
        val accessories = manager.accessoryList.orEmpty()
        val usbNet = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && Regex("^(rndis|usb|ncm|eth|enx)").containsMatchIn(it.name) }
        val db = if (devices.isNotEmpty()) ids.get() else null
        val installedProfiles = profiles.profiles.first()
        val installedPackages = library.tools.first()
            .filter { it.status == com.hermes.agent.data.toolbox.InstallStatus.INSTALLED && it.type == com.hermes.agent.data.toolbox.SourceType.PKG }
            .map { it.address }.toSet()
        return buildString {
            if (devices.isEmpty() && accessories.isEmpty()) appendLine("No USB devices connected (USB OTG). A phone-to-computer cable doesn't show devices here.")
            devices.forEach { d ->
                append(describe(manager, d))
                db?.let { ids ->
                    val vendor = ids.vendor(d.vendorId)
                    val product = ids.product(d.vendorId, d.productId)
                    if (vendor != null || product != null) appendLine("    identified: ${listOfNotNull(vendor, product).joinToString(" ")}")
                }
                val drivers = com.hermes.agent.data.usb.UsbCatalog.classify(d.toInfo(), installedProfiles)
                drivers.forEach { m ->
                    val status = when {
                        m.kind != com.hermes.agent.data.usb.DriverKind.TERMUX_PACK -> ""
                        m.packages.all { it in installedPackages } -> " [installed]"
                        else -> " [not installed: install_tool ${m.packages.filterNot { it in installedPackages }.joinToString(", ")}]" +
                            if (m.needsRoot) " (needs root)" else ""
                    }
                    appendLine("    driver: ${m.title}${m.tool?.let { t -> " → $t" }.orEmpty()}$status. ${m.note}")
                }
                if (drivers.all { it.kind == com.hermes.agent.data.usb.DriverKind.RAW }) {
                    appendLine("    No class driver fits. Find the device's protocol (web_search), then install or write a profile with usb_driver_install.")
                }
            }
            if (devices.isNotEmpty() && db == null) appendLine("(The USB ID database couldn't be downloaded, so names come from the device only.)")
            accessories.forEach { appendLine("- accessory: ${it.manufacturer} ${it.model} ${it.version.orEmpty()}") }
            if (usbNet.isNotEmpty()) {
                appendLine("USB/Ethernet network links:")
                usbNet.forEach { ni -> appendLine("  ${ni.name}: ${ni.interfaceAddresses.joinToString { "${it.address.hostAddress}/${it.networkPrefixLength}" }}") }
            }
            if (devices.any { !manager.hasPermission(it) }) appendLine("Use usb_request_access to see a device's names and serial number.")
        }
    }
}

@Singleton
class UsbRequestAccessTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "usb_request_access"
    override val capability = Capability.USB
    override val description = "Ask the user (via Android's system dialog) to let this app access a USB device, then describe it in full."
    override val parameters = schema("device" to prop("string", "deviceName from usb_devices, e.g. /dev/bus/usb/001/002."), required = listOf("device"))

    override fun label(args: JsonObject) = "Requesting access to ${args.string("device")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val manager = this.context.getSystemService(UsbManager::class.java) ?: return "This device has no USB host support."
        val name = args.string("device") ?: throw IllegalArgumentException("device is required")
        val device = manager.deviceList[name] ?: throw IllegalArgumentException("No USB device named $name. Call usb_devices first.")
        if (manager.hasPermission(device)) return describe(manager, device)

        return when (requestUsbPermission(this.context, manager, device)) {
            true -> describe(manager, device)
            false -> "The user declined access to ${device.deviceName}."
            null -> "No answer within 60 seconds; the access dialog may still be open on the phone."
        }
    }
}

/** Shows Android's USB access dialog; true = granted, false = declined, null = no answer in 60 s. */
internal suspend fun requestUsbPermission(context: Context, manager: UsbManager, device: UsbDevice): Boolean? {
    if (manager.hasPermission(device)) return true
    val action = "${context.packageName}.USB_PERMISSION"
    val answer = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            answer.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    try {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags))
        return withTimeoutOrNull(60_000) { answer.await() }
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

internal fun usbDevice(manager: UsbManager, name: String?): UsbDevice {
    val devices = manager.deviceList
    if (name != null) return devices[name] ?: throw IllegalArgumentException("No USB device named $name. Call usb_devices first.")
    return devices.values.singleOrNull()
        ?: throw IllegalArgumentException(if (devices.isEmpty()) "No USB device is connected." else "Several USB devices are connected; pass device (from usb_devices).")
}
