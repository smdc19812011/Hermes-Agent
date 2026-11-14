package com.hermes.agent.domain.tools

import com.google.gson.JsonObject

/** A class of device access the user switches on in Settings. Off by default. */
enum class Capability(val displayName: String, val description: String, val emoji: String) {
    NETWORK("Local network", "See this phone's network, scan the LAN, and send/receive TCP and UDP data (you approve every send).", "📡"),
    INTERNET("Internet", "Fetch web pages and APIs, and search the web.", "🌐"),
    BLUETOOTH("Bluetooth", "Scan, read, write and subscribe to Bluetooth LE devices, and talk to Classic serial (SPP) devices (you approve every send).", "🔵"),
    USB("USB", "Identify and drive USB devices: serial, HID, printers, USB drives, MIDI, raw I/O, and downloadable driver profiles (you approve every send).", "🔌"),
    MEDIA("Images, audio & video", "Generate images and speech, search for images, and post files from the web or made in Termux into the chat to download.", "🎨"),
    SHELL("Termux shell", "Run commands in Termux and install missing tools (nmap, adb, pip/npm packages, GitHub releases). You approve every command and install.", "🖥️")
}

interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject
    val capability: Capability
    val needsApproval: Boolean get() = false

    /** Per call: send/receive tools only ask when this call actually transmits. */
    fun requiresApproval(args: JsonObject): Boolean = needsApproval

    /** Short human-readable summary of a call, shown in the chat while it runs. */
    fun label(args: JsonObject): String = name

    /** Returns text for the model. Throwing is fine: the message is passed back to the model. */
    suspend fun run(args: JsonObject, context: ToolContext): String
}

data class ToolContext(
    val conversationId: String,
    val enabled: Set<Capability>,
    /** Files a tool posts to the chat; collected and shown when the tool returns. */
    val attach: (com.hermes.agent.domain.model.Attachment) -> Unit = {}
)

data class ToolCall(val id: String, val name: String, val arguments: String)

/** Binary input a model can read directly, already base64-encoded. */
sealed class InputPart {
    data class Image(val mime: String, val base64: String) : InputPart()
    data class Document(val name: String, val mime: String, val base64: String) : InputPart()
    data class Audio(val format: String, val base64: String) : InputPart()
}

/** Provider-neutral transcript used inside one tool-using turn. */
sealed class ChatTurn {
    data class User(val text: String, val parts: List<InputPart> = emptyList()) : ChatTurn()
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : ChatTurn()
    data class ToolResult(val callId: String, val name: String, val content: String) : ChatTurn()
}

/** Tiny builder for JSON Schema objects. */
fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply { props.forEach { (k, v) -> add(k, v) } })
    add("required", com.google.gson.JsonArray().apply { required.forEach { add(it) } })
}

fun prop(type: String, description: String, enum: List<String>? = null) = JsonObject().apply {
    addProperty("type", type)
    addProperty("description", description)
    enum?.let { e -> add("enum", com.google.gson.JsonArray().apply { e.forEach { add(it) } }) }
}

fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
