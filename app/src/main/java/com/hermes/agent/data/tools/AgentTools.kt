package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.agents.AgentLibraryRepository
import com.hermes.agent.data.agents.SkillFormat
import com.hermes.agent.domain.model.AgentProfile
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.tools.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadSkillTool @Inject constructor(private val library: AgentLibraryRepository) : AgentTool {
    override val name = "read_skill"
    override val capability = Capability.AGENTS
    override val description = "Load a saved skill's full instructions. Skills are listed by name and description in your instructions; " +
        "read the matching one before starting a task it covers, then follow it."
    override val parameters = schema("name" to prop("string", "Skill name"), required = listOf("name"))
    override fun label(args: JsonObject) = "Reading skill ${args.string("name")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val name = args.string("name") ?: error("name is required")
        val skill = library.skill(name)
            ?: error("No skill called $name. Saved skills: ${library.skills.value.joinToString { it.name }.ifEmpty { "none" }}.")
        return "# Skill: ${skill.name}\n${skill.description}\n\n${skill.body}"
    }
}

@Singleton
class SaveSkillTool @Inject constructor(private val library: AgentLibraryRepository) : AgentTool {
    override val name = "save_skill"
    override val capability = Capability.AGENTS
    override val needsApproval = true
    override val description = "Save reusable instructions as a skill every chat can load later, or improve an existing one (same name replaces it). " +
        "Use it after working out a procedure worth repeating: the exact steps, tools and commands that worked, pitfalls, and how to check the result. " +
        "The user approves each save."
    override val parameters = schema(
        "name" to prop("string", "Short name, e.g. find-adb-devices"),
        "description" to prop("string", "One line saying when to use it; this is what future chats see when choosing a skill"),
        "instructions" to prop("string", "The skill itself, in markdown: steps, commands, checks"),
        "connections" to arrayProp("Optional names of connections (Settings → Connections) whose tools the skill needs; they switch on when it's loaded"),
        required = listOf("name", "description", "instructions")
    )
    override fun label(args: JsonObject) =
        "${if (library.skill(args.string("name").orEmpty()) != null) "Updating" else "Saving"} skill ${args.string("name")?.let(SkillFormat::slug)}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val skill = library.saveSkill(Skill(
            name = args.string("name") ?: error("name is required"),
            description = args.string("description") ?: error("description is required"),
            body = args.string("instructions") ?: error("instructions are required"),
            connections = args.strings("connections")
        ))
        return "Saved skill ${skill.name}. Every chat with Skills & agents on can now load it with read_skill."
    }
}

@Singleton
class CreateAgentTool @Inject constructor(private val library: AgentLibraryRepository) : AgentTool {
    override val name = "create_agent"
    override val capability = Capability.AGENTS
    override val needsApproval = true
    override val description = "Create or update (same name) a saved agent: a specialist with its own instructions, models and tools. " +
        "The user can start chats with it, and delegate_task can hand it sub-tasks. The user approves each save."
    override val parameters = schema(
        "name" to prop("string", "Agent name, e.g. Network scout"),
        "description" to prop("string", "One line: what it's for"),
        "instructions" to prop("string", "Its system instructions: role, how to work, what to return"),
        "models" to arrayProp("Optional models to try in order, as \"model-id\" or \"Provider name: model-id\". Leave out to use the chat's models."),
        "device_access" to arrayProp("Optional device access it may use (never more than the user allows in Settings). Leave out for all allowed.",
            Capability.entries.map { it.name }),
        "tools" to arrayProp("Optional tool names it may use. Leave out for every tool its device access enables."),
        "connections" to arrayProp("Optional names of connections (Settings → Connections) whose tools it always gets"),
        "avatar" to prop("string", "Optional look shown for it in chat and beside Hermes while it's delegated a task. Leave out to keep the default.",
            com.hermes.agent.domain.model.AgentAvatars.PRESETS.map { it.id }),
        required = listOf("name", "instructions")
    )
    override fun label(args: JsonObject) = "${if (library.agentNamed(args.string("name").orEmpty()) != null) "Updating" else "Creating"} agent ${args.string("name")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val name = args.string("name") ?: error("name is required")
        val existing = library.agentNamed(name)
        val access = args.strings("device_access").takeIf { it.isNotEmpty() }
            ?.map { n -> Capability.entries.firstOrNull { it.name.equals(n, true) } ?: error("Unknown device access $n. Use: ${Capability.entries.joinToString { it.name }}") }?.toSet()
        val agent = library.saveAgent(AgentProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            description = args.string("description").orEmpty(),
            instructions = args.string("instructions") ?: error("instructions are required"),
            models = args.strings("models"),
            deviceAccess = access,
            tools = args.strings("tools"),
            connections = args.strings("connections"),
            avatar = args.string("avatar")?.let { a -> com.hermes.agent.domain.model.AgentAvatars.PRESETS.firstOrNull { it.id == a }?.id }
                ?: existing?.avatar ?: com.hermes.agent.domain.model.AgentAvatars.DEFAULT_ID,
            createdByAi = existing?.createdByAi ?: true
        ))
        return "${if (existing != null) "Updated" else "Created"} agent ${agent.name}. The user can start a chat with it from the new-chat button, " +
            "and delegate_task can use it now."
    }
}

/** The work happens in the chat loop, which owns models and approvals; this tool only describes it and hands over. */
@Singleton
class DelegateTaskTool @Inject constructor(private val library: AgentLibraryRepository) : AgentTool {
    override val name = "delegate_task"
    override val capability = Capability.AGENTS
    override val description = "Hand a self-contained sub-task to a sub-agent, which works on it with its own tools and returns a report. " +
        "Use it to split big jobs, get a specialist's view, or keep long tool output out of this chat. The sub-agent sees only what you pass in task and context. " +
        "Several delegate_task calls in one turn run one after another."
    override val parameters = schema(
        "task" to prop("string", "What the sub-agent should do and what to report back"),
        "agent" to prop("string", "Optional saved agent to use; leave out for a general sub-agent on this chat's model"),
        "context" to prop("string", "Optional facts the sub-agent needs, such as IPs, file names or earlier findings"),
        required = listOf("task")
    )
    override fun label(args: JsonObject) =
        "Sub-agent${args.string("agent")?.let { " $it" }.orEmpty()}: ${args.string("task").orEmpty().take(60)}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        args.string("agent")?.let { n ->
            library.agentNamed(n) ?: error("No agent called $n. Agents: ${library.agents.value.joinToString { it.name }.ifEmpty { "none; leave agent out" }}.")
        }
        val delegate = context.delegate ?: error("Sub-agents can't start their own sub-agents; do this part yourself.")
        return delegate(args)
    }
}
