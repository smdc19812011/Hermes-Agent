package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.toolbox.InstallStatus
import com.hermes.agent.data.toolbox.SourceType
import com.hermes.agent.data.toolbox.ToolLibraryRepository
import com.hermes.agent.domain.tools.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListInstalledToolsTool @Inject constructor(private val library: ToolLibraryRepository) : AgentTool {
    override val name = "list_installed_tools"
    override val capability = Capability.SHELL
    override val description = "List programs in the shared tool library (installed for every chat) and their install status."
    override val parameters = schema()

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val tools = library.tools.first()
        if (tools.isEmpty()) return "The tool library is empty. Use install_tool to add what you need."
        return tools.joinToString("\n") { "- ${it.name} (${it.type.displayName}: ${it.address}): ${it.status.name.lowercase()}" +
            it.message.takeIf { m -> m.isNotBlank() }?.let { m -> " — $m" }.orEmpty() }
    }
}

@Singleton
class InstallToolTool @Inject constructor(private val library: ToolLibraryRepository) : AgentTool {
    override val name = "install_tool"
    override val capability = Capability.SHELL
    override val needsApproval = true
    override val description = "Install a program this task needs but the phone doesn't have, into the shared tool library so every chat can use it. " +
        "Accepts a Termux package name (e.g. nmap, android-tools for adb/fastboot, iproute2), pip:<package>, npm:<package>, " +
        "a GitHub repository (owner/repo, installs the latest release built for this phone), a git URL ending in .git, or a direct download URL. " +
        "Prefer Termux packages: downloaded Linux binaries only run if built for Android or statically linked. The user approves each install."
    override val parameters = schema(
        "address" to prop("string", "What to install, e.g. \"nmap\", \"pip:speedtest-cli\", \"sharkdp/fd\"."),
        "type" to prop("string", "Optional: force the source type.", SourceType.entries.map { it.name }),
        required = listOf("address")
    )

    override fun label(args: JsonObject) = "Install ${args.string("address")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val address = args.string("address") ?: throw IllegalArgumentException("address is required")
        val forced = args.string("type")?.let { t -> SourceType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
        val tool = library.add(address, forced)
        val outcome = library.install(tool.id)
        val tail = outcome.log.lines().filter { it.isNotBlank() }.takeLast(25).joinToString("\n")
        return if (outcome.tool.status == InstallStatus.INSTALLED) {
            "Installed ${outcome.tool.name}. It's on the PATH for run_command in every chat.\n$tail"
        } else {
            "Install of ${outcome.tool.name} failed: ${outcome.tool.message}\n$tail"
        }
    }
}
