package com.hermes.agent.data.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

object BluetoothPermissions {
    val required: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun adapter(context: Context): BluetoothAdapter {
    if (!BluetoothPermissions.granted(context)) {
        throw SecurityException("Bluetooth permission isn't granted. Turn Bluetooth access off and on in Settings to grant it.")
    }
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw IllegalStateException("This device has no Bluetooth.")
    if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is turned off. Ask the user to turn it on.")
    return adapter
}

private fun deviceType(type: Int) = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
    else -> "unknown"
}

private fun majorClass(c: BluetoothClass?) = when (c?.majorDeviceClass) {
    BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio/video"
    BluetoothClass.Device.Major.COMPUTER -> "computer"
    BluetoothClass.Device.Major.PHONE -> "phone"
    BluetoothClass.Device.Major.PERIPHERAL -> "peripheral"
    BluetoothClass.Device.Major.WEARABLE -> "wearable"
    BluetoothClass.Device.Major.HEALTH -> "health"
    BluetoothClass.Device.Major.IMAGING -> "imaging"
    BluetoothClass.Device.Major.TOY -> "toy"
    BluetoothClass.Device.Major.NETWORKING -> "networking"
    null -> null
    else -> "other"
}

@Singleton
class BluetoothStatusTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "bluetooth_status"
    override val capability = Capability.BLUETOOTH
    override val description = "Report whether Bluetooth is on and list the devices paired with this phone."
    override val parameters = schema()

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val adapter = adapter(this.context)
        val bonded = adapter.bondedDevices.orEmpty()
        return buildString {
            appendLine("Bluetooth on. This phone: ${adapter.name ?: "unnamed"}")
            appendLine("Paired devices (${bonded.size}):")
            bonded.forEach { d ->
                appendLine("- ${d.name ?: "unnamed"} ${d.address} ${deviceType(d.type)}${majorClass(d.bluetoothClass)?.let { ", $it" }.orEmpty()}")
            }
        }
    }
}

@Singleton
class BluetoothScanTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "bluetooth_scan"
    override val capability = Capability.BLUETOOTH
    override val description = "Scan for nearby Bluetooth devices (Classic and Low Energy). Returns name, address, signal strength and advertised services."
    override val parameters = schema("seconds" to prop("integer", "How long to scan, 3-15 seconds. Default 8."))

    override fun label(args: JsonObject) = "Scanning Bluetooth for ${args.int("seconds")?.coerceIn(3, 15) ?: 8} s"

    private data class Seen(var name: String?, val address: String, var rssi: Int?, var type: String, val services: MutableSet<String> = mutableSetOf())

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val adapter = adapter(this.context)
        val seconds = args.int("seconds")?.coerceIn(3, 15) ?: 8
        val seen = ConcurrentHashMap<String, Seen>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                val entry = seen.getOrPut(device.address) { Seen(device.name, device.address, rssi, "classic") }
                entry.name = entry.name ?: device.name
                entry.rssi = rssi ?: entry.rssi
            }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val entry = seen.getOrPut(result.device.address) { Seen(null, result.device.address, result.rssi, "BLE") }
                entry.name = entry.name ?: result.scanRecord?.deviceName ?: result.device.name
                entry.rssi = result.rssi
                result.scanRecord?.serviceUuids?.forEach { entry.services += shortUuid(it.uuid) }
                result.scanRecord?.manufacturerSpecificData?.let { data ->
                    for (i in 0 until data.size()) entry.services += "manufacturer 0x%04x".format(data.keyAt(i))
                }
            }
        }

        ContextCompat.registerReceiver(this.context, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        try {
            adapter.startDiscovery()
            adapter.bluetoothLeScanner?.startScan(callback)
            delay(seconds * 1000L)
        } finally {
            runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
            runCatching { adapter.cancelDiscovery() }
            runCatching { this.context.unregisterReceiver(receiver) }
        }

        val devices = seen.values.sortedByDescending { it.rssi ?: -999 }
        return buildString {
            appendLine("Found ${devices.size} devices in $seconds s (strongest signal first):")
            devices.take(60).forEach { d ->
                append("- ${d.name ?: "unnamed"} ${d.address} ${d.type}")
                d.rssi?.let { append(", $it dBm") }
                if (d.services.isNotEmpty()) append(", ${d.services.joinToString()}")
                appendLine()
            }
            if (devices.size > 60) appendLine("…and ${devices.size - 60} more.")
        }
    }
}

@Singleton
class BleReadTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "ble_read"
    override val capability = Capability.BLUETOOTH
    override val description = "Connect to a Bluetooth LE device by address, list its GATT services, and read every readable characteristic " +
        "(e.g. battery level, device name, manufacturer, sensor values)."
    override val parameters = schema("address" to prop("string", "Device address from bluetooth_scan, e.g. AA:BB:CC:DD:EE:FF."), required = listOf("address"))

    override fun label(args: JsonObject) = "Reading Bluetooth device ${args.string("address")}"

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val address = checkedAddress(args)
        GattSession.open(this.context, address).use { session ->
            val gatt = session.gatt
            val out = StringBuilder("Connected to ${gatt.device.name ?: address}.\n")
            var reads = 0
            for (service in gatt.services) {
                out.appendLine("Service ${GATT_NAMES[shortUuid(service.uuid)] ?: shortUuid(service.uuid)}")
                for (c in service.characteristics) {
                    val label = GATT_NAMES[shortUuid(c.uuid)] ?: shortUuid(c.uuid)
                    if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0 || reads >= 40) {
                        out.appendLine("  $label (${describeProps(c.properties)})")
                        continue
                    }
                    reads++
                    out.appendLine("  $label = ${session.read(c)?.let(::formatValue) ?: "(no value)"} (${describeProps(c.properties)})")
                }
            }
            return out.toString()
        }
    }

    private fun describeProps(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString("/").ifEmpty { "no access" }
}

internal fun shortUuid(uuid: UUID): String {
    val s = uuid.toString()
    return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8) else s
}

internal fun formatValue(bytes: ByteArray): String {
    val text = bytes.toString(Charsets.UTF_8)
    val printable = text.isNotEmpty() && text.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
    val hex = bytes.joinToString(" ") { "%02x".format(it) }
    // One byte is almost always a number (battery %, a level), even when it happens to be printable.
    return when {
        bytes.size == 1 -> "${bytes[0].toInt() and 0xff} ($hex)"
        printable -> "\"$text\" ($hex)"
        else -> hex
    }
}

private val GATT_NAMES = mapOf(
    "0x1800" to "Generic Access", "0x1801" to "Generic Attribute", "0x180a" to "Device Information",
    "0x180f" to "Battery", "0x180d" to "Heart Rate", "0x1809" to "Health Thermometer", "0x181a" to "Environmental Sensing",
    "0x2a00" to "Device Name", "0x2a01" to "Appearance", "0x2a19" to "Battery Level", "0x2a24" to "Model Number",
    "0x2a25" to "Serial Number", "0x2a26" to "Firmware Revision", "0x2a27" to "Hardware Revision",
    "0x2a28" to "Software Revision", "0x2a29" to "Manufacturer Name", "0x2a37" to "Heart Rate Measurement",
    "0x2a6e" to "Temperature", "0x2a6f" to "Humidity"
)
