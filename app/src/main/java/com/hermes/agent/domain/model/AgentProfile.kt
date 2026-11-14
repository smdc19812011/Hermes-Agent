package com.hermes.agent.domain.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.tools.Capability
import java.util.UUID

/**
 * A saved agent: its own instructions, models and reach. Chats can run as an agent, and delegate_task hands it sub-tasks.
 * Gson trees are built by hand so R8 renaming can't break saved files.
 */
data class AgentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String = "",
    /** Tried in order; each is "model-id" or "Provider name: model-id". Empty = the chat's normal routing. */
    val models: List<String> = emptyList(),
    /** Null = whatever Settings allows. Never more than Settings allows. */
    val deviceAccess: Set<Capability>? = null,
    /** Tool names this agent may use; empty = every tool its device access enables. */
    val tools: List<String> = emptyList(),
    /** Names of connections whose tools this agent always gets, whatever their scope. */
    val connections: List<String> = emptyList(),
    val createdByAi: Boolean = false
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("description", description)
        addProperty("instructions", instructions)
        add("models", JsonArray().apply { models.forEach { add(it) } })
        deviceAccess?.let { caps -> add("deviceAccess", JsonArray().apply { caps.forEach { add(it.name) } }) }
        add("tools", JsonArray().apply { tools.forEach { add(it) } })
        add("connections", JsonArray().apply { connections.forEach { add(it) } })
        addProperty("createdByAi", createdByAi)
    }.toString()

    companion object {
        fun fromJson(text: String): AgentProfile? = runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            fun list(key: String) = o.getAsJsonArray(key)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            AgentProfile(
                id = o.get("id").asString,
                name = o.get("name").asString,
                description = o.get("description")?.asString.orEmpty(),
                instructions = o.get("instructions")?.asString.orEmpty(),
                models = list("models"),
                deviceAccess = o.getAsJsonArray("deviceAccess")?.let { list("deviceAccess").mapNotNull { n -> Capability.entries.firstOrNull { it.name == n } }.toSet() },
                tools = list("tools"),
                connections = list("connections"),
                createdByAi = o.get("createdByAi")?.asBoolean ?: false
            )
        }.getOrNull()
    }
}

/** A skill: reusable instructions in Hermes' SKILL.md format (YAML front matter with name and description, then markdown). */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    /** Names of connections whose tools switch on once this skill is loaded. */
    val connections: List<String> = emptyList()
)
