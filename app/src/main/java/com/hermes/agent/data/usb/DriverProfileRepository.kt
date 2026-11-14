package com.hermes.agent.data.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.data.tools.Payload
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("usb_driver_profiles")

/** Installed driver profiles, shared by every chat and the Tool library screen. */
@Singleton
class DriverProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val key = stringPreferencesKey("profiles")

    val profiles: Flow<List<DriverProfile>> = context.profileStore.data.map { prefs ->
        val array = prefs[key]?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() } ?: JsonArray()
        array.mapNotNull { el ->
            val o = el.asJsonObject
            runCatching { DriverProfile.parse(o.get("json").asString, o.get("source").asString) }.getOrNull()
        }
    }

    /** Installs from a URL (http/https) or from raw JSON text; replaces a profile with the same name. */
    suspend fun install(urlOrJson: String): DriverProfile {
        val input = urlOrJson.trim()
        val (json, source) = if (input.startsWith("{")) input to "pasted" else fetch(input) to input
        val profile = DriverProfile.parse(json, source)
        context.profileStore.edit { prefs ->
            val kept = profiles.first().filterNot { it.name == profile.name }
            prefs[key] = JsonArray().apply {
                (kept + profile).forEach { p -> add(JsonObject().apply { addProperty("json", p.json); addProperty("source", p.source) }) }
            }.toString()
        }
        return profile
    }

    suspend fun remove(name: String) {
        context.profileStore.edit { prefs ->
            prefs[key] = JsonArray().apply {
                profiles.first().filterNot { it.name == name }
                    .forEach { p -> add(JsonObject().apply { addProperty("json", p.json); addProperty("source", p.source) }) }
            }.toString()
        }
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Give a profile URL (https://…) or paste the JSON." }
        // GitHub page links become raw file links.
        val raw = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").find(url)
            ?.destructured?.let { (o, r, path) -> "https://raw.githubusercontent.com/$o/$r/$path" } ?: url
        val client = okHttpClient.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(raw).build()).execute().use { r ->
            check(r.isSuccessful) { "Couldn't download the profile: HTTP ${r.code}" }
            val body = r.body?.string().orEmpty()
            check(body.length < 256 * 1024) { "That file is too large to be a driver profile." }
            body
        }
    }
}

/** Runs a profile's steps over its transport. Every command opens, runs and closes; nothing stays claimed. */
object DriverProfileExecutor {
    fun run(
        manager: UsbManager,
        device: UsbDevice,
        profile: DriverProfile,
        command: String,
        data: String?
    ): String {
        val cmd = profile.commands[command]
            ?: throw IllegalArgumentException("${profile.name} has no command \"$command\". Commands: ${profile.commands.keys.joinToString()}")
        val steps = profile.init + cmd.steps
        val log = StringBuilder("${profile.name} → $command\n")
        val connection = manager.openDevice(device) ?: throw IllegalStateException("Android couldn't open ${device.deviceName}.")
        try {
            if (profile.transport == DriverProfile.Transport.SERIAL) {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    ?: throw IllegalStateException("The profile uses serial, but no serial driver recognises this device.")
                val port = driver.ports.getOrElse(profile.interfaceIndex) { driver.ports.first() }
                port.open(connection)
                try {
                    port.setParameters(profile.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    steps.forEach { step ->
                        when (step) {
                            is DriverProfile.Step.Write -> bytes(step, data).let { port.write(it, 2_000); log.appendLine("wrote ${it.size} bytes") }
                            is DriverProfile.Step.Read -> {
                                val buf = ByteArray(maxOf(step.bytes, 64))
                                val n = port.read(buf, step.timeoutMs)
                                log.appendLine("read " + Payload.describe(buf.copyOf(maxOf(n, 0)), 4096))
                            }
                            is DriverProfile.Step.Delay -> Thread.sleep(step.ms)
                            is DriverProfile.Step.Control -> control(connection, step, log)
                        }
                    }
                } finally {
                    runCatching { port.close() }
                }
                return log.toString().trim()
            }

            val itf = device.getInterface(profile.interfaceIndex.coerceIn(0, device.interfaceCount - 1))
            check(connection.claimInterface(itf, true)) { "Couldn't claim interface ${itf.id}." }
            val eps = (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            val out: UsbEndpoint? = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type != UsbConstants.USB_ENDPOINT_XFER_CONTROL }
            val inEp: UsbEndpoint? = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type != UsbConstants.USB_ENDPOINT_XFER_CONTROL }
            try {
                steps.forEach { step ->
                    when (step) {
                        is DriverProfile.Step.Write -> {
                            val b = bytes(step, data)
                            val sent = when {
                                out != null -> connection.bulkTransfer(out, b, b.size, 2_000)
                                // HID without an OUT endpoint: SET_REPORT (output report; first byte is the report ID).
                                profile.transport == DriverProfile.Transport.HID ->
                                    connection.controlTransfer(0x21, 0x09, (0x02 shl 8) or (b.firstOrNull()?.toInt()?.and(0xff) ?: 0), itf.id, b, b.size, 2_000)
                                else -> throw IllegalStateException("Interface ${itf.id} has no OUT endpoint to write to.")
                            }
                            log.appendLine(if (sent < 0) "write failed" else "wrote $sent bytes")
                        }
                        is DriverProfile.Step.Read -> {
                            val ep = inEp ?: throw IllegalStateException("Interface ${itf.id} has no IN endpoint to read from.")
                            val buf = ByteArray(step.bytes)
                            val n = connection.bulkTransfer(ep, buf, buf.size, step.timeoutMs)
                            log.appendLine(if (n < 0) "read: nothing within ${step.timeoutMs} ms" else "read " + Payload.describe(buf.copyOf(n), 4096))
                        }
                        is DriverProfile.Step.Delay -> Thread.sleep(step.ms)
                        is DriverProfile.Step.Control -> control(connection, step, log)
                    }
                }
            } finally {
                runCatching { connection.releaseInterface(itf) }
            }
            return log.toString().trim()
        } finally {
            connection.close()
        }
    }

    private fun bytes(step: DriverProfile.Step.Write, data: String?): ByteArray {
        val text = if ("{data}" in step.data) {
            step.data.replace("{data}", data ?: throw IllegalArgumentException("This command needs data."))
        } else step.data
        return Payload.decode(text, step.encoding)
    }

    private fun control(c: UsbDeviceConnection, s: DriverProfile.Step.Control, log: StringBuilder) {
        val buf = if (s.isIn) ByteArray(s.length) else s.data?.let { Payload.decode(it, "hex") } ?: ByteArray(0)
        val n = c.controlTransfer(s.requestType, s.request, s.value, s.index, buf, buf.size, 2_000)
        log.appendLine(
            when {
                n < 0 -> "control 0x%02x/0x%02x failed".format(s.requestType, s.request)
                s.isIn -> "control in: " + Payload.describe(buf.copyOf(n), 1024)
                else -> "control out: $n bytes"
            }
        )
    }
}
