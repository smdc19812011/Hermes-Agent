package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.data.toolbox.ToolboxScripts
import com.hermes.agent.domain.tools.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShellTool @Inject constructor(private val termux: TermuxBridge) : AgentTool {
    override val name = "run_command"
    override val capability = Capability.SHELL
    override val needsApproval = true
    override val description = "Run a bash command in Termux on this phone and return its output. The user approves each command. " +
        "Programs from the shared tool library are on the PATH; if one is missing, call install_tool. Commands time out after 120 seconds."
    override val parameters = schema(
        "command" to prop("string", "The bash command to run."),
        required = listOf("command")
    )

    override fun label(args: JsonObject) = "$ ${args.string("command")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val command = args.string("command") ?: throw IllegalArgumentException("command is required")
        if (!termux.isInstalled() || !termux.hasPermission()) {
            throw IllegalStateException("Termux isn't set up. Finish steps 1-3 on the Agent runtime screen.")
        }
        val r = termux.run("${ToolboxScripts.PATH_EXPORT}\ncd \"\$HOME\" && timeout 120 bash -c ${com.hermes.agent.data.runtime.HermesScripts.shellQuote(command)}", "Hermes command", 150_000)
        r.termuxError?.let { throw IllegalStateException(it) }
        val out = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) append("\n[stderr]\n").append(r.stderr.trimEnd())
            append("\n[exit code ${r.exitCode}${if (r.exitCode == 124) ", timed out" else ""}]")
        }
        return if (out.length > 12_000) out.take(12_000) + "\n[truncated]" else out
    }
}
