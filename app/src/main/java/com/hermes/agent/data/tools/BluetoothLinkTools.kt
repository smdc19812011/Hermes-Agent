package com.hermes.agent.data.tools

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject

private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

/** "2a19", "0x2a19" or a full UUID. */
internal fun parseUuid(s: String): UUID {
    val t = s.trim().lowercase().removePrefix("0x")
    return when (t.length) {
        4 -> UUID.fromString("0000$t-0000-1000-8000-00805f9b34fb")
        8 -> UUID.fromString("$t-0000-1000-8000-00805f9b34fb")
        else -> UUID.fromString(t)
    }
}

internal fun checkedAddress(args: JsonObject): String {
    val address = args.string("address")?.uppercase() ?: throw IllegalArgumentException("address is required")
    require(BluetoothAdapter.checkBluetoothAddress(address)) { "$address is not a valid Bluetooth address." }
    return address
}

/** One BLE connection with suspend read/write/subscribe, covering both pre- and post-Android 13 GATT APIs. */
@SuppressLint("MissingPermission")
internal class GattSession private constructor(val gatt: BluetoothGatt, private val events: Channel<Event>) : AutoCloseable {
    sealed class Event {
        data class State(val connected: Boolean, val status: Int) : Event()
        data class Discovered(val status: Int) : Event()
        data class Read(val uuid: UUID, val value: ByteArray?, val status: Int) : Event()
        data class Wrote(val uuid: UUID, val status: Int) : Event()
        data class DescriptorWrote(val status: Int) : Event()
        data class Changed(val uuid: UUID, val value: ByteArray, val at: Long = System.currentTimeMillis()) : Event()
    }

    companion object {
        suspend fun open(context: Context, address: String): GattSession {
            val adapter = adapter(context)
            val events = Channel<Event>(Channel.UNLIMITED)
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    events.trySend(Event.State(newState == BluetoothProfile.STATE_CONNECTED, status))
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.trySend(Event.Discovered(status)) }
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    @Suppress("DEPRECATION") events.trySend(Event.Read(c.uuid, c.value, status))
                }
                override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    events.trySend(Event.Read(c.uuid, value, status))
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    events.trySend(Event.Wrote(c.uuid, status))
                }
                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                    events.trySend(Event.DescriptorWrote(status))
                }
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                    @Suppress("DEPRECATION") c.value?.let { events.trySend(Event.Changed(c.uuid, it)) }
                }
                override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                    events.trySend(Event.Changed(c.uuid, value))
                }
            }
            val gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val session = GattSession(gatt, events)
            try {
                withTimeout(15_000) {
                    val state = session.next<Event.State>()
                    if (!state.connected) throw IllegalStateException("Couldn't connect to $address (status ${state.status}). Is it in range and connectable?")
                    gatt.discoverServices()
                    session.next<Event.Discovered>()
                }
            } catch (e: Exception) {
                session.close()
                throw e
            }
            return session
        }
    }

    suspend inline fun <reified T : Event> next(): T {
        while (true) { val e = events.receive(); if (e is T) return e }
    }

    fun find(characteristic: String, service: String?): BluetoothGattCharacteristic {
        val uuid = parseUuid(characteristic)
        val services = service?.let { s -> listOfNotNull(gatt.getService(parseUuid(s))) } ?: gatt.services
        return services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
            ?: throw IllegalArgumentException("Characteristic $characteristic not found. Use ble_read to list what the device offers.")
    }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray? {
        @Suppress("DEPRECATION")
        if (!gatt.readCharacteristic(c)) return null
        return withTimeoutOrNull(3_000) { next<Event.Read>() }?.value
    }

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, withResponse: Boolean): Int {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = value; gatt.writeCharacteristic(c) }
        }
        if (!started) throw IllegalStateException("The phone refused to start the write (busy, or the characteristic isn't writable).")
        return withTimeoutOrNull(5_000) { next<Event.Wrote>() }?.status ?: -1
    }

    suspend fun subscribe(c: BluetoothGattCharacteristic) {
        check(gatt.setCharacteristicNotification(c, true)) { "Couldn't enable notifications locally." }
        val d = c.getDescriptor(CCCD) ?: return // some devices notify without a CCCD
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) gatt.writeDescriptor(d, value)
        else @Suppress("DEPRECATION") run { d.value = value; gatt.writeDescriptor(d) }
        withTimeoutOrNull(5_000) { next<Event.DescriptorWrote>() }
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        events.close()
    }
}

class BleWriteTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "ble_write"
    override val capability = Capability.BLUETOOTH
    override val needsApproval = true
    override val description = "Write bytes to a characteristic of a Bluetooth LE device (control lights, toys, sensors, UART-over-BLE like Nordic NUS), " +
        "then read the characteristic back if it's readable."
    override val parameters = schema(
        "address" to prop("string", "Device address."),
        "characteristic" to prop("string", "Characteristic UUID, short (\"2a06\") or full."),
        "service" to prop("string", "Optional service UUID when the characteristic appears in several services."),
        "data" to prop("string", "Data to write."),
        "encoding" to Payload.encodingProp,
        "with_response" to prop("boolean", "Wait for the device to confirm the write (default true)."),
        required = listOf("address", "characteristic", "data")
    )

    override fun label(args: JsonObject) = "Write ${Payload.from(args)?.size ?: 0} bytes to ${args.string("characteristic")} on ${args.string("address")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val address = checkedAddress(args)
        val payload = Payload.from(args) ?: throw IllegalArgumentException("data is required")
        GattSession.open(this.context, address).use { s ->
            val c = s.find(args.string("characteristic")!!, args.string("service"))
            val status = s.write(c, payload, args.get("with_response")?.asBoolean != false)
            val readBack = if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) s.read(c) else null
            return buildString {
                append(
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> "Wrote ${payload.size} bytes to ${shortUuid(c.uuid)}."
                        -1 -> "Sent ${payload.size} bytes to ${shortUuid(c.uuid)}; the device didn't confirm in time."
                        else -> "The device rejected the write (GATT status $status)."
                    }
                )
                readBack?.let { append("\nRead back: ").append(Payload.describe(it)) }
            }
        }
    }
}

class BleListenTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "ble_listen"
    override val capability = Capability.BLUETOOTH
    override val description = "Subscribe to notifications or indications from a Bluetooth LE characteristic and return every value received " +
        "for a while (heart rate, sensor streams, UART-over-BLE TX)."
    override val parameters = schema(
        "address" to prop("string", "Device address."),
        "characteristic" to prop("string", "Characteristic UUID that notifies or indicates."),
        "service" to prop("string", "Optional service UUID."),
        "seconds" to prop("integer", "How long to listen (1-60, default 10)."),
        required = listOf("address", "characteristic")
    )

    override fun label(args: JsonObject) = "Listening to ${args.string("characteristic")} on ${args.string("address")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val address = checkedAddress(args)
        val seconds = args.int("seconds")?.coerceIn(1, 60) ?: 10
        GattSession.open(this.context, address).use { s ->
            val c = s.find(args.string("characteristic")!!, args.string("service"))
            s.subscribe(c)
            val start = System.currentTimeMillis()
            val values = mutableListOf<GattSession.Event.Changed>()
            withTimeoutOrNull(seconds * 1000L) {
                while (values.size < 500) {
                    val e = s.next<GattSession.Event.Changed>()
                    if (e.uuid == c.uuid) values += e
                }
            }
            return "Received ${values.size} values from ${shortUuid(c.uuid)} in $seconds s." +
                values.take(100).joinToString("") { "\n+${"%.2f".format((it.at - start) / 1000.0)}s ${formatValue(it.value)}" } +
                if (values.size > 100) "\n…${values.size - 100} more" else ""
        }
    }
}

class BluetoothSerialTool @Inject constructor(@ApplicationContext private val context: Context) : AgentTool {
    override val name = "bt_serial"
    override val capability = Capability.BLUETOOTH
    override val description = "Talk to a Classic Bluetooth serial device (SPP/RFCOMM: HC-05/HC-06 modules, OBD-II dongles, printers, ESP32 BluetoothSerial): " +
        "connect, optionally send data, and return what it sends back. Pair the device in Android settings first."
    override val parameters = schema(
        "address" to prop("string", "Paired device address (see bluetooth_status)."),
        "data" to Payload.dataProp,
        "encoding" to Payload.encodingProp,
        "read_seconds" to prop("integer", "How long to collect incoming data (0-60, default 3)."),
        "uuid" to prop("string", "Service UUID, default the standard SPP UUID 1101."),
        required = listOf("address")
    )

    override fun requiresApproval(args: JsonObject) = Payload.from(args) != null
    override fun label(args: JsonObject) = (Payload.from(args)?.let { "Send ${it.size} bytes to" } ?: "Read from") + " ${args.string("address")} (Bluetooth serial)"

    @SuppressLint("MissingPermission")
    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val adapter = adapter(this@BluetoothSerialTool.context)
        val device = adapter.getRemoteDevice(checkedAddress(args))
        val uuid = args.string("uuid")?.let(::parseUuid) ?: SPP
        adapter.cancelDiscovery() // discovery slows RFCOMM connects to a crawl
        val socket = runCatching { device.createRfcommSocketToServiceRecord(uuid).also { it.connect() } }
            .recoverCatching { device.createInsecureRfcommSocketToServiceRecord(uuid).also { it.connect() } }
            .getOrElse { throw IllegalStateException("Couldn't open a serial link to ${device.address}: ${it.message}. Is it paired, on and in range?") }
        socket.use { s ->
            val payload = Payload.from(args)
            payload?.let { s.outputStream.apply { write(it); flush() } }
            val input = s.inputStream
            val received = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + (args.int("read_seconds")?.coerceIn(0, 60) ?: 3) * 1000L
            val buf = ByteArray(1024)
            // RFCOMM streams have no read timeout: poll available() until the window closes.
            while (System.currentTimeMillis() < deadline && received.size() < 256 * 1024) {
                val n = input.available()
                if (n > 0) received.write(buf, 0, input.read(buf, 0, minOf(n, buf.size))) else Thread.sleep(50)
            }
            "Connected to ${device.name ?: device.address}." + (payload?.let { " Sent ${it.size} bytes." } ?: "") +
                "\nReceived " + Payload.describe(received.toByteArray())
        }
    }
}
