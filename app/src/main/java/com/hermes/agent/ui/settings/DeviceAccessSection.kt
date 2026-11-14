package com.hermes.agent.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.data.tools.BluetoothPermissions
import com.hermes.agent.domain.tools.Capability

/** Android permissions a capability needs to work fully. Location only adds the Wi-Fi name for Local network. */
private fun permissionsFor(capability: Capability): List<String> = when (capability) {
    // Android 12+ ignores a fine-location request made without coarse.
    Capability.NETWORK -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    Capability.BLUETOOTH -> BluetoothPermissions.required
    Capability.SHELL -> listOf(TermuxBridge.PERMISSION_RUN_COMMAND)
    Capability.INTERNET, Capability.USB, Capability.MEDIA -> emptyList()
}

private fun missing(context: Context, capability: Capability) = permissionsFor(capability).filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

@Composable
fun DeviceAccessSection(enabled: Set<Capability>, onChange: (Set<Capability>) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }

    Text(
        "Let the AI use tools on this phone when you chat with a regular model provider. Everything is off until you turn it on. " +
            "Hermes has its own tools and ignores these switches.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Capability.entries.forEach { capability ->
        val on = capability in enabled
        // Re-checked on resume (refresh) so returning from the permission dialog updates the hints.
        val needed = remember(on, refresh) { if (on) missing(context, capability) else emptyList() }
        val termuxMissing = remember(on, refresh) {
            capability == Capability.SHELL && on &&
                runCatching { context.packageManager.getPackageInfo(TermuxBridge.TERMUX_PACKAGE, 0) }.isFailure
        }
        Column {
            ListItem(
                headlineContent = { Text("${capability.emoji}  ${capability.displayName}") },
                supportingContent = { Text(capability.description, style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Switch(checked = on, onCheckedChange = { turnOn ->
                        onChange(if (turnOn) enabled + capability else enabled - capability)
                        if (turnOn) missing(context, capability).takeIf { it.isNotEmpty() }?.let { launcher.launch(it.toTypedArray()) }
                    })
                }
            )
            when {
                termuxMissing -> Hint("Needs Termux. Set it up on the Agent runtime screen.")
                needed.isNotEmpty() && capability == Capability.NETWORK -> Hint(
                    "Optional: allow location to let the AI see the Wi-Fi network's name.",
                    action = "Allow"
                ) { launcher.launch(needed.toTypedArray()) }
                needed.isNotEmpty() -> Hint("Permission needed for this to work.", action = "Grant") { launcher.launch(needed.toTypedArray()) }
            }
        }
    }
}

@Composable
private fun Hint(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}
