package com.hermes.agent.data.runtime

import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the generated scripts through real bash, the way Termux receives them (on stdin, wrapped). */
class HermesScriptsTest {
    private lateinit var home: File
    private lateinit var bin: File

    @Before
    fun setUp() {
        home = Files.createTempDirectory("hermes-home").toFile()
        bin = File(home, "bin").apply { mkdirs() }
        stub("hermes", "printf 'hermes'; for a in \"\$@\"; do printf ' [%s]' \"\$a\"; done; printf '\\n'")
        // proot-distro login ubuntu --shared-tmp -- CMD...  ->  run CMD here, inheriting stdin.
        stub("proot-distro", "while [ \"\$1\" != \"--\" ]; do shift; done; shift; exec \"\$@\"")
        stub("termux-wake-lock", "exit 0")
        stub("termux-wake-unlock", "exit 0")
    }

    private fun stub(name: String, body: String) {
        File(bin, name).apply { writeText("#!/bin/bash\n$body\n"); setExecutable(true) }
    }

    private data class Run(val out: String, val err: String, val code: Int)

    private fun runViaStdin(script: String): Run {
        val p = ProcessBuilder("bash")
            .directory(home)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
            }
            .start()
        p.outputStream.use { it.write(HermesScripts.wrapForStdin(script).toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        assertTrue("script timed out", p.waitFor(60, TimeUnit.SECONDS))
        return Run(out, err, p.exitValue())
    }

    private fun assertSyntax(script: String) {
        val p = ProcessBuilder("bash", "-n").start()
        p.outputStream.use { it.write(HermesScripts.wrapForStdin(script).toByteArray()) }
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        assertEquals("bash -n failed: $err", 0, p.exitValue())
    }

    private val tricky = "it's \$HOME \"q\" `x` \\n }"

    private fun settings(method: InstallMethod, backend: SandboxBackend) = RuntimeSettings(
        installMethod = method,
        serverKey = "hk_$tricky",
        backend = backend,
        sshHost = "box.example", sshUser = "me",
        dockerHost = "ssh://me@box", modalTokenId = "id", modalTokenSecret = "s", daytonaApiKey = "d"
    )

    private val llm = Provider(
        id = "p", name = "OR", type = ProviderType.OPENROUTER, apiKey = "sk-or-$tricky",
        baseUrl = "", models = listOf("nousresearch/hermes-3-llama-3.1-405b"), isEnabled = true, priority = 0
    )

    @Test
    fun everyScriptParses() {
        for (method in InstallMethod.entries) {
            assertSyntax(HermesScripts.logged("install.log", HermesScripts.install(method)))
            assertSyntax(HermesScripts.start(method, "hk_$tricky"))
            assertSyntax(HermesScripts.detect(method))
            for (backend in SandboxBackend.entries) {
                assertSyntax(HermesScripts.logged("configure.log", HermesScripts.configure(settings(method, backend), llm)))
            }
        }
        assertSyntax(HermesScripts.stop())
        assertSyntax(HermesScripts.readLog("gateway.log", 40))
    }

    @Test
    fun configurePassesSecretsVerbatim() {
        for (method in InstallMethod.entries) {
            val r = runViaStdin(HermesScripts.configure(settings(method, SandboxBackend.SSH), llm))
            assertEquals(r.err, 0, r.code)
            assertTrue(r.out, r.out.contains("hermes [config] [set] [API_SERVER_KEY] [hk_$tricky]"))
            assertTrue(r.out, r.out.contains("hermes [config] [set] [OPENROUTER_API_KEY] [sk-or-$tricky]"))
            assertTrue(r.out, r.out.contains("hermes [config] [set] [model.provider] [openrouter]"))
            assertTrue(r.out, r.out.contains("hermes [config] [set] [terminal.backend] [ssh]"))
            assertTrue(r.out, r.out.contains("hermes [config] [set] [TERMINAL_SSH_HOST] [box.example]"))
        }
    }

    @Test
    fun startWritesMissingKeyThenRunsGateway() {
        val key = "hk_0123456789abcdef0123"
        for (method in InstallMethod.entries) {
            File(home, ".hermes").deleteRecursively()
            File(home, ".hermes-android/gateway.log").delete()
            val r = runViaStdin(HermesScripts.start(method, key))
            assertEquals(r.err, 0, r.code)
            val log = File(home, ".hermes-android/gateway.log").readText()
            assertTrue(log, log.contains("hermes [config] [set] [API_SERVER_KEY] [$key]"))
            assertTrue(log, log.indexOf("[API_SERVER_KEY]") < log.indexOf("hermes [gateway] [run]"))
        }
    }

    @Test
    fun startSkipsKeyWriteWhenAlreadyConfigured() {
        val key = "hk_0123456789abcdef0123"
        File(home, ".hermes").mkdirs()
        File(home, ".hermes/.env").writeText("OTHER=1\nAPI_SERVER_KEY=\"$key\"\n")
        val r = runViaStdin(HermesScripts.start(InstallMethod.TERMUX_PACKAGE, key))
        assertEquals(r.err, 0, r.code)
        val log = File(home, ".hermes-android/gateway.log").readText()
        assertTrue(log, !log.contains("[API_SERVER_KEY]"))
        assertTrue(log, log.contains("hermes [gateway] [run]"))
    }

    @Test
    fun sandboxRunsCodeVerbatim() {
        val code = """
            import sys
            def f(x):
                return f"{x}!"   # } and ${'$'}HOME and 'quotes' stay literal
            print(f("it's"), "${'$'}HOME")
            print(sys.stdin.read() == "")
        """.trimIndent()
        val r = runViaStdin(HermesScripts.sandbox("python3 -u", "snippet.py", code, 20, "HERMES_SNIPPET_TEST"))
        assertEquals(r.err, 0, r.code)
        assertEquals("it's! \$HOME\nTrue\n", r.out)
    }

    @Test
    fun sandboxTimesOut() {
        val r = runViaStdin(HermesScripts.sandbox("bash", "snippet.sh", "sleep 30", 1, "HERMES_SNIPPET_TEST"))
        assertEquals(124, r.code)
    }

    @Test
    fun sandboxReportsMissingInterpreter() {
        val r = runViaStdin(HermesScripts.sandbox("definitely-not-installed", "x", "1", 5, "HERMES_SNIPPET_TEST"))
        assertEquals(127, r.code)
        assertTrue(r.err, r.err.contains("is not installed"))
    }
}
