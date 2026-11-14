package com.hermes.agent.data.sandbox

import android.content.Context
import androidx.annotation.Keep
import androidx.concurrent.futures.await
import androidx.javascriptengine.JavaScriptSandbox
import com.google.gson.Gson
import com.hermes.agent.data.runtime.HermesScripts
import com.hermes.agent.data.termux.TermuxBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

enum class SandboxLanguage(val displayName: String, val needsTermux: Boolean) {
    PYTHON("Python", true),
    JAVASCRIPT("JavaScript (Node)", true),
    BASH("Bash", true),
    JAVASCRIPT_IN_APP("JavaScript (in-app)", false);

    companion object {
        /** Maps a Markdown code-fence tag to a runnable language, preferring Termux runtimes. */
        fun fromFence(tag: String, termuxReady: Boolean): SandboxLanguage? = when (tag.lowercase()) {
            "python", "py", "python3" -> PYTHON
            "javascript", "js", "node", "mjs" -> if (termuxReady) JAVASCRIPT else JAVASCRIPT_IN_APP
            "bash", "sh", "shell", "zsh" -> BASH
            else -> null
        }
    }
}

data class SandboxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val durationMs: Long,
    val error: String? = null
)

@Singleton
class SandboxRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termux: TermuxBridge,
    private val gson: Gson
) {
    fun termuxReady(): Boolean = termux.isInstalled() && termux.hasPermission()

    suspend fun run(language: SandboxLanguage, code: String, timeoutSeconds: Int = 60): SandboxResult {
        val start = System.currentTimeMillis()
        return try {
            if (language == SandboxLanguage.JAVASCRIPT_IN_APP) runInApp(code, timeoutSeconds, start)
            else runInTermux(language, code, timeoutSeconds, start)
        } catch (e: Exception) {
            SandboxResult("", "", null, System.currentTimeMillis() - start, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun runInTermux(language: SandboxLanguage, code: String, timeoutSeconds: Int, start: Long): SandboxResult {
        val interpreter = when (language) {
            SandboxLanguage.PYTHON -> "python3 -u"
            SandboxLanguage.JAVASCRIPT -> "node"
            else -> "bash"
        }
        val file = when (language) {
            SandboxLanguage.PYTHON -> "snippet.py"
            SandboxLanguage.JAVASCRIPT -> "snippet.js"
            else -> "snippet.sh"
        }
        val script = HermesScripts.sandbox(interpreter, file, code, timeoutSeconds, "HERMES_SNIPPET_${System.nanoTime()}")
        val result = termux.run(script, "Hermes sandbox: ${language.displayName}", timeoutMs = (timeoutSeconds + 30) * 1000L)
        val timedOut = result.exitCode == 124
        return SandboxResult(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            durationMs = System.currentTimeMillis() - start,
            error = result.termuxError ?: if (timedOut) "Stopped after $timeoutSeconds s" else null
        )
    }

    private suspend fun runInApp(code: String, timeoutSeconds: Int, start: Long): SandboxResult {
        if (!JavaScriptSandbox.isSupported()) {
            return SandboxResult("", "", null, 0, "This device's WebView does not support the JavaScript sandbox. Install Termux to run Node instead.")
        }
        // The isolate has no console; capture console.* calls and the last expression's value.
        val wrapped = """
            (function () {
              const out = [];
              const fmt = (a) => a.map((x) => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
              const console = { log: (...a) => out.push(fmt(a)), info: (...a) => out.push(fmt(a)),
                warn: (...a) => out.push('warn: ' + fmt(a)), error: (...a) => out.push('error: ' + fmt(a)) };
              let value, error = null;
              try { value = eval(${gson.toJson(code)}); } catch (e) { error = String(e && e.stack || e); }
              return JSON.stringify({ out: out.join('\n'), value: value === undefined ? null : String(value), error });
            })()
        """.trimIndent()
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).await()
        try {
            val isolate = sandbox.createIsolate()
            try {
                val raw = withTimeout(timeoutSeconds * 1000L) { isolate.evaluateJavaScriptAsync(wrapped).await() }
                val parsed = gson.fromJson(raw, InAppResult::class.java)
                val stdout = listOfNotNull(parsed.out.takeIf { it.isNotEmpty() }, parsed.value?.let { "=> $it" })
                    .joinToString("\n")
                return SandboxResult(stdout, parsed.error.orEmpty(), if (parsed.error == null) 0 else 1, System.currentTimeMillis() - start)
            } finally {
                isolate.close()
            }
        } finally {
            sandbox.close()
        }
    }

    @Keep
    private data class InAppResult(val out: String = "", val value: String? = null, val error: String? = null)
}
