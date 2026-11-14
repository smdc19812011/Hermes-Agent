package com.hermes.agent.data.runtime

import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType

/** Shell scripts run inside Termux. Pure functions so they can be checked with bash in unit tests. */
object HermesScripts {
    const val LOCAL_PORT = 8642
    const val STATE_DIR = "\$HOME/.hermes-android"
    private const val ENV_EOF = "HERMES_ENV_EOF"
    private const val SIGNING_KEY_FINGERPRINT = "C572B5FDD1A29CCFA9A912B6840B0848E139156D"

    const val ALLOW_EXTERNAL_APPS_COMMAND =
        "mkdir -p ~/.termux && (grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings"

    fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Runs [inner] where Hermes lives: directly in Termux, or inside the Ubuntu userspace. */
    fun inEnv(method: InstallMethod, inner: String): String = when (method) {
        InstallMethod.TERMUX_PACKAGE -> inner
        InstallMethod.UBUNTU_PROOT -> "proot-distro login ubuntu --shared-tmp -- bash -l <<'$ENV_EOF'\n$inner\n$ENV_EOF"
    }

    fun logged(file: String, body: String) = listOf(
        "mkdir -p \"$STATE_DIR\"",
        "exec > >(tee \"$STATE_DIR/$file\") 2>&1",
        body
    ).joinToString("\n")

    fun install(method: InstallMethod): String {
        val common = listOf(
            "set -e",
            "export DEBIAN_FRONTEND=noninteractive",
            "APT=\"apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold\"",
            "echo '==> Updating Termux packages'",
            "apt-get update",
            "\$APT upgrade",
            "echo '==> Installing sandbox runtimes (Python, Node.js, OpenSSH)'",
            "\$APT install curl gnupg python nodejs openssh coreutils procps termux-tools"
        )
        val hermes = when (method) {
            InstallMethod.TERMUX_PACKAGE -> listOf(
                "echo '==> Adding the Hermes Agent package source'",
                "mkdir -p \"\$PREFIX/etc/apt/keyrings\"",
                "curl -fsSL https://hermes-assets.nousresearch.com/releases/termux/stable/key.asc -o \"\$PREFIX/etc/apt/keyrings/hermes-agent.asc\"",
                "fp=\$(gpg --show-keys --with-colons --with-fingerprint \"\$PREFIX/etc/apt/keyrings/hermes-agent.asc\" | awk -F: '/^fpr/ {print \$10; exit}')",
                "if [ \"\$fp\" != \"$SIGNING_KEY_FINGERPRINT\" ]; then echo \"Signing key fingerprint mismatch: \$fp\" >&2; exit 1; fi",
                "echo \"deb [signed-by=\$PREFIX/etc/apt/keyrings/hermes-agent.asc] https://hermes-assets.nousresearch.com/releases/termux/stable hermes-stable main\" > \"\$PREFIX/etc/apt/sources.list.d/hermes-agent.list\"",
                "apt-get update",
                "echo '==> Installing Hermes Agent'",
                "\$APT install hermes-agent"
            )
            InstallMethod.UBUNTU_PROOT -> listOf(
                "echo '==> Installing Ubuntu userspace'",
                "\$APT install proot-distro",
                "[ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ] || proot-distro install ubuntu",
                "echo '==> Installing Hermes Agent inside Ubuntu'",
                inEnv(
                    method,
                    listOf(
                        "set -e",
                        "export DEBIAN_FRONTEND=noninteractive",
                        "apt-get update",
                        "apt-get install -y curl git ca-certificates xz-utils python3 nodejs",
                        // Mobile links stall mid-clone; let git ride out slow stretches instead of aborting.
                        "git config --global http.postBuffer 524288000",
                        "git config --global http.lowSpeedLimit 1000",
                        "git config --global http.lowSpeedTime 120",
                        "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- --non-interactive --skip-browser"
                    ).joinToString("\n")
                )
            )
        }
        return (common + hermes + listOf("echo '==> Installed:'", inEnv(method, "hermes --version")))
            .joinToString("\n")
    }

    fun configure(s: RuntimeSettings, llm: Provider?): String {
        val lines = mutableListOf(
            "set -e",
            "hermes setup --non-interactive >/dev/null 2>&1 || true",
            set("API_SERVER_ENABLED", "true"),
            set("API_SERVER_HOST", "127.0.0.1"),
            set("API_SERVER_PORT", LOCAL_PORT.toString()),
            set("API_SERVER_KEY", s.serverKey)
        )
        if (llm != null) lines += modelLines(llm)
        lines += set("terminal.backend", s.backend.hermesValue)
        when (s.backend) {
            SandboxBackend.LOCAL -> Unit
            SandboxBackend.SSH -> {
                lines += set("TERMINAL_SSH_HOST", s.sshHost.trim())
                lines += set("TERMINAL_SSH_USER", s.sshUser.trim())
                lines += set("TERMINAL_SSH_PORT", s.sshPort.trim().ifBlank { "22" })
                if (s.sshKeyPath.isNotBlank()) lines += set("TERMINAL_SSH_KEY", s.sshKeyPath.trim())
            }
            SandboxBackend.DOCKER -> {
                // Hermes shells out to the docker CLI, which honours DOCKER_HOST from the environment.
                lines += set("DOCKER_HOST", s.dockerHost.trim())
                lines += set("terminal.docker_image", s.dockerImage.trim().ifBlank { "python:3.12-slim" })
            }
            SandboxBackend.MODAL -> {
                lines += set("MODAL_TOKEN_ID", s.modalTokenId.trim())
                lines += set("MODAL_TOKEN_SECRET", s.modalTokenSecret.trim())
            }
            SandboxBackend.DAYTONA -> lines += set("DAYTONA_API_KEY", s.daytonaApiKey.trim())
        }
        lines += "echo '==> Hermes configured. Restart the gateway to apply.'"

        val dockerCli = when {
            s.backend != SandboxBackend.DOCKER -> null
            s.installMethod == InstallMethod.TERMUX_PACKAGE ->
                "command -v docker >/dev/null || { apt-get install -y root-repo && apt-get install -y docker; }"
            else -> "command -v docker >/dev/null || apt-get install -y docker.io"
        }
        return inEnv(s.installMethod, listOfNotNull(dockerCli, lines.joinToString("\n")).joinToString("\n"))
    }

    /**
     * Kept as the Termux task itself (not nohup'd) so Termux's service stays alive with it.
     * The gateway only serves the API when API_SERVER_KEY is in Hermes's .env, so it is written
     * here if missing; the grep keeps restarts from paying for a slow `hermes config set`.
     */
    fun start(method: InstallMethod, serverKey: String) = listOf(
        "termux-wake-lock >/dev/null 2>&1 || true",
        "mkdir -p \"$STATE_DIR\"",
        "exec >> \"$STATE_DIR/gateway.log\" 2>&1",
        "echo \"--- \$(date) starting gateway\"",
        // Hermes's terminal tool inherits this, so library tools work there too (Termux install).
        com.hermes.agent.data.toolbox.ToolboxScripts.PATH_EXPORT,
        inEnv(
            method,
            listOf(
                "envfile=\"\${HERMES_HOME:-\$HOME/.hermes}/.env\"",
                "grep -qsF ${shellQuote(serverKey)} \"\$envfile\" || ${set("API_SERVER_KEY", serverKey)}",
                "exec hermes gateway run"
            ).joinToString("\n")
        )
    ).joinToString("\n")

    fun stop() = listOf(
        "pkill -f 'hermes gateway run' && echo 'Gateway stopped.' || echo 'Gateway was not running.'",
        "termux-wake-unlock >/dev/null 2>&1 || true"
    ).joinToString("\n")

    fun readLog(name: String, lines: Int) =
        "tail -n $lines \"$STATE_DIR/$name\" 2>/dev/null || echo '(no log yet)'"

    fun detect(method: InstallMethod) = inEnv(method, "command -v hermes >/dev/null && hermes --version")

    /** Writes [code] verbatim with a quoted heredoc and runs it under `timeout`. */
    fun sandbox(interpreter: String, fileName: String, code: String, timeoutSeconds: Int, delimiter: String): String {
        val binary = interpreter.substringBefore(' ')
        return listOf(
            com.hermes.agent.data.toolbox.ToolboxScripts.PATH_EXPORT,
            "dir=\"$STATE_DIR/sandbox\"",
            "mkdir -p \"\$dir\" && cd \"\$dir\" || exit 1",
            "cat > $fileName <<'$delimiter'",
            code,
            delimiter,
            "command -v $binary >/dev/null || { echo \"$binary is not installed. Run: pkg install python nodejs\" >&2; exit 127; }",
            "timeout $timeoutSeconds $interpreter $fileName"
        ).joinToString("\n")
    }

    /** Wraps a script so bash parses it whole before running; commands that read stdin can't eat it. */
    fun wrapForStdin(script: String) = "{\n$script\n} < /dev/null\n"

    private fun modelLines(llm: Provider): List<String> = when (llm.type) {
        ProviderType.OPENROUTER -> listOf(
            set("OPENROUTER_API_KEY", llm.apiKey),
            set("model.provider", "openrouter"),
            set("model.default", llm.defaultModel)
        )
        ProviderType.ANTHROPIC -> listOf(
            set("ANTHROPIC_API_KEY", llm.apiKey),
            set("model.provider", "anthropic"),
            set("model.default", llm.defaultModel)
        )
        ProviderType.HERMES -> emptyList()
        // Every other provider speaks the OpenAI wire format: configure it as a custom endpoint.
        else -> listOf(
            set("model.provider", "custom"),
            set("model.base_url", llm.effectiveBaseUrl),
            set("model.api_key", llm.apiKey),
            set("model.default", llm.defaultModel)
        )
    }

    private fun set(key: String, value: String) = "hermes config set $key ${shellQuote(value)}"
}
