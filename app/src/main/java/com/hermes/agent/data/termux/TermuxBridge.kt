package com.hermes.agent.data.termux

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hermes.agent.data.runtime.HermesScripts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class CommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    /** Termux-side failure (e.g. allow-external-apps disabled); null when the command itself ran. */
    val termuxError: String?
) {
    val ok: Boolean get() = termuxError == null && exitCode == 0
}

/**
 * Runs shell scripts inside Termux via its RUN_COMMAND intent
 * (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).
 * Scripts go over stdin so secrets never appear in a process argument list.
 */
@Singleton
class TermuxBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    /** Runs [script] with bash and waits for it to finish. */
    suspend fun run(script: String, label: String, timeoutMs: Long = 120_000): CommandResult {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<CommandResult>()
        pending[id] = deferred
        try {
            start(script, label, resultIntent(id))
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Starts a long-running [script] (e.g. the gateway) as a Termux background task and returns at once. */
    fun launch(script: String, label: String) = start(script, label, resultIntent = null)

    private fun start(script: String, label: String, resultIntent: PendingIntent?) {
        if (!isInstalled()) throw TermuxUnavailableException("Termux is not installed.")
        if (!hasPermission()) throw TermuxUnavailableException("Permission to run commands in Termux has not been granted.")
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService")
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX/bin/bash")
            putExtra(EXTRA_STDIN, HermesScripts.wrapForStdin(script))
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_COMMAND_LABEL, label)
            resultIntent?.let { putExtra(EXTRA_PENDING_INTENT, it) }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            throw TermuxUnavailableException("Termux refused the command: ${e.message}")
        }
    }

    private fun resultIntent(id: Int): PendingIntent {
        val intent = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_EXECUTION_ID, id)
        // Mutable so Termux can attach the result bundle.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        // Termux's own fixed install locations, not this app's storage.
        @SuppressLint("SdCardPath") const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        @SuppressLint("SdCardPath") const val TERMUX_HOME = "/data/data/com.termux/files/home"

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        internal const val EXTRA_EXECUTION_ID = "execution_id"

        private val nextId = AtomicInteger(1000)
        internal val pending = ConcurrentHashMap<Int, CompletableDeferred<CommandResult>>()
    }
}

class TermuxUnavailableException(message: String) : Exception(message)

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxBridge.EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra("result") ?: return
        val err = bundle.getInt("err", -1)
        val errmsg = bundle.getString("errmsg")
        val result = CommandResult(
            stdout = bundle.getString("stdout").orEmpty(),
            stderr = bundle.getString("stderr").orEmpty(),
            exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
            // err is -1 (RESULT_CODE_OK) when Termux itself succeeded.
            termuxError = if (err != -1 && !errmsg.isNullOrBlank()) errmsg else null
        )
        TermuxBridge.pending[id]?.complete(result)
    }
}
