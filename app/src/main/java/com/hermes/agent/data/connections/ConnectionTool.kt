package com.hermes.agent.data.connections

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.tools.AgentTool
import com.hermes.agent.domain.tools.Capability
import com.hermes.agent.domain.tools.ToolContext

/** One tool from a connection, shown to the model as "<connection>__<tool>". */
class ConnectionTool(
    val connection: Connection,
    val remote: RemoteTool,
    private val repository: ConnectionRepository
) : AgentTool {
    override val name = Connection.toolName(connection.name, remote.name)
    override val capability = Capability.CONNECTIONS
    override val description = "[${connection.name}] ${remote.description}".take(1024)
    override val parameters: JsonObject = runCatching { JsonParser.parseString(remote.schema).asJsonObject }.getOrNull()
        ?.also { if (!it.has("type")) it.addProperty("type", "object") }
        ?: JsonObject().apply { addProperty("type", "object") }

    override fun requiresApproval(args: JsonObject): Boolean = when (connection.approval) {
        ApprovalPolicy.ALWAYS -> true
        ApprovalPolicy.NEVER -> false
        ApprovalPolicy.WRITES -> !(remote.readOnly || (remote.path == null && remote.method == null && connection.type == ConnectionType.REST &&
            args.get("method")?.takeIf { it.isJsonPrimitive }?.asString.equals("GET", true)))
    }

    override fun label(args: JsonObject) = "${connection.name}: ${remote.name}"

    override suspend fun run(args: JsonObject, context: ToolContext): String =
        repository.call(repository.get(connection.id) ?: connection, remote, args, context.conversationId, context.attach)
}
