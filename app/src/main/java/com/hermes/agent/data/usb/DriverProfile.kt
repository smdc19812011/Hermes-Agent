package com.hermes.agent.data.usb

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A declarative userspace USB driver: which devices it matches and the USB operations behind each named
 * command. Profiles are data, never code, so installing one from the internet can't run anything on the phone
 * beyond the USB transfers it lists, and each command still needs the user's approval.
 *
 * {
 *   "name": "LCUS-1 USB relay",
 *   "match": [{"vid": "1a86", "pid": "7523"}],        // pid optional: any product from the vendor
 *   "transport": "serial",                              // raw | hid | serial
 *   "interface": 0, "baud": 9600,
 *   "init": [ <step>, ... ],                            // run before every command
 *   "commands": { "on":  {"description": "Relay on",  "steps": [{"write": "A0 01 01 A2", "encoding": "hex"}]},
 *                 "off": [{"write": "A0 01 00 A1", "encoding": "hex"}] }
 * }
 * Steps: {"write": "...", "encoding": "hex|text|base64"} ("{data}" is replaced by the caller's data),
 *        {"read": 64, "timeout_ms": 1000}, {"delay_ms": 100},
 *        {"control": {"request_type": "0x40", "request": 1, "value": 0, "index": 0, "data": "hex", "length": 0}}
 */
data class DriverProfile(
    val name: String,
    val description: String,
    val matches: List<Pair<Int, Int?>>,
    val transport: Transport,
    val interfaceIndex: Int,
    val baud: Int,
    val init: List<Step>,
    val commands: Map<String, Command>,
    val source: String,
    val json: String
) {
    enum class Transport { RAW, HID, SERIAL }

    data class Command(val description: String, val steps: List<Step>)

    sealed class Step {
        data class Write(val data: String, val encoding: String) : Step()
        data class Read(val bytes: Int, val timeoutMs: Int) : Step()
        data class Delay(val ms: Long) : Step()
        data class Control(val requestType: Int, val request: Int, val value: Int, val index: Int, val data: String?, val length: Int) : Step() {
            val isIn get() = requestType and 0x80 != 0
        }
    }

    fun matches(vid: Int, pid: Int) = matches.any { (v, p) -> v == vid && (p == null || p == pid) }

    companion object {
        fun parse(json: String, source: String): DriverProfile {
            val root = runCatching { JsonParser.parseString(json).asJsonObject }
                .getOrElse { throw IllegalArgumentException("A driver profile must be a JSON object: ${it.message}") }
            val name = root.str("name") ?: throw IllegalArgumentException("Driver profile needs a \"name\".")
            val matches = root.getAsJsonArray("match")?.map { m ->
                val o = m.asJsonObject
                val vid = o.hex("vid") ?: throw IllegalArgumentException("Every match needs a \"vid\".")
                vid to o.hex("pid")
            }.orEmpty()
            require(matches.isNotEmpty()) { "Driver profile needs at least one \"match\" entry with a vid." }
            val transport = when (root.str("transport")?.lowercase() ?: "raw") {
                "raw" -> Transport.RAW; "hid" -> Transport.HID; "serial" -> Transport.SERIAL
                else -> throw IllegalArgumentException("transport must be raw, hid or serial.")
            }
            val commands = root.getAsJsonObject("commands")?.entrySet()?.associate { (cmd, v) ->
                cmd to when {
                    v.isJsonArray -> Command("", steps(v))
                    v.isJsonObject -> Command(v.asJsonObject.str("description").orEmpty(), steps(v.asJsonObject.get("steps") ?: throw IllegalArgumentException("Command \"$cmd\" needs steps.")))
                    else -> throw IllegalArgumentException("Command \"$cmd\" must be a list of steps or {description, steps}.")
                }
            }.orEmpty()
            require(commands.isNotEmpty()) { "Driver profile needs at least one command." }
            return DriverProfile(
                name = name,
                description = root.str("description").orEmpty(),
                matches = matches,
                transport = transport,
                interfaceIndex = root.get("interface")?.asInt ?: 0,
                baud = root.get("baud")?.asInt ?: 115200,
                init = root.get("init")?.let(::steps).orEmpty(),
                commands = commands,
                source = source,
                json = json
            )
        }

        private fun steps(el: JsonElement): List<Step> {
            require(el.isJsonArray) { "steps must be a list." }
            return el.asJsonArray.map { s ->
                val o = s.asJsonObject
                when {
                    o.has("write") -> Step.Write(o.str("write").orEmpty(), o.str("encoding") ?: "text")
                    o.has("read") -> Step.Read(o.get("read").asInt.coerceIn(1, 65536), o.get("timeout_ms")?.asInt?.coerceIn(10, 30_000) ?: 1_000)
                    o.has("delay_ms") -> Step.Delay(o.get("delay_ms").asLong.coerceIn(0, 10_000))
                    o.has("control") -> o.getAsJsonObject("control").let { c ->
                        Step.Control(
                            requestType = c.hex("request_type") ?: throw IllegalArgumentException("control needs request_type"),
                            request = c.hex("request") ?: throw IllegalArgumentException("control needs request"),
                            value = c.hex("value") ?: 0,
                            index = c.hex("index") ?: 0,
                            data = c.str("data"),
                            length = c.get("length")?.asInt?.coerceIn(0, 4096) ?: 0
                        )
                    }
                    else -> throw IllegalArgumentException("Unknown step: $o")
                }
            }
        }

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

        /** Accepts 4660, "4660", "0x1234" or "1234" (vid/pid style hex when given as a string). */
        private fun JsonObject.hex(key: String): Int? {
            val el = get(key)?.takeIf { it.isJsonPrimitive } ?: return null
            if (el.asJsonPrimitive.isNumber) return el.asInt
            val s = el.asString.trim().lowercase()
            return if (s.startsWith("0x")) s.drop(2).toInt(16)
            else if (key == "vid" || key == "pid") s.toInt(16) else s.toInt()
        }
    }
}
