package com.hermes.agent.ui.toolbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.usb.DriverKind

@Composable
fun UsbDriversSection(termuxReady: Boolean, vm: UsbDriversViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Rescan when devices are plugged in or removed.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { vm.scan() }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Text("USB drivers", style = MaterialTheme.typography.titleMedium)
    Text(
        "Android doesn't let apps install kernel drivers, so devices are driven from this app: built-in drivers for serial, HID, printers, " +
            "USB drives and MIDI, Termux packs where they exist, and driver profiles for everything else.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    state.busy?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    state.message?.let {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                IconButton(onClick = vm::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp)) }
            }
        }
    }

    if (state.devices.isEmpty()) {
        Text("No USB devices connected. Plug one in with a USB OTG adapter.", style = MaterialTheme.typography.bodySmall)
    }
    state.devices.forEach { d ->
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Usb, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall)
                        Text("${d.ids} · ${d.deviceName}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                d.drivers.filter { it.kind != DriverKind.RAW || d.drivers.size == 1 }.forEach { m ->
                    Text(
                        "• ${m.title}" + (m.tool?.let { " (AI tool: $it)" } ?: "") + if (m.needsRoot) " — needs root" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (d.missingPackages.isNotEmpty()) {
                    Button(enabled = termuxReady && state.busy == null, onClick = { vm.installPackages(d.missingPackages) }) {
                        Text("Install driver (${d.missingPackages.joinToString()})")
                    }
                } else if (d.drivers.all { it.kind == DriverKind.RAW }) {
                    Text(
                        "No driver yet. Ask the AI to find this device's protocol and install a driver profile, or add a profile URL below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    Text("Driver profiles", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = state.profileInput,
        onValueChange = vm::setProfileInput,
        label = { Text("Profile URL or JSON") },
        placeholder = { Text("https://github.com/…/relay.json") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 4
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = state.profileInput.isNotBlank() && state.busy == null, onClick = vm::installProfile) { Text("Install profile") }
        OutlinedButton(enabled = state.busy == null, onClick = vm::updateIds) { Text("Update USB ID database") }
    }
    state.profiles.forEach { p ->
        ListItem(
            headlineContent = { Text(p.name) },
            supportingContent = {
                Text(
                    p.matches.joinToString { (v, pid) -> "%04x:%s".format(v, pid?.let { "%04x".format(it) } ?: "*") } +
                        " · ${p.transport.name.lowercase()} · ${p.commands.keys.joinToString()}",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = { IconButton(onClick = { vm.removeProfile(p.name) }) { Icon(Icons.Default.Delete, "Remove ${p.name}") } }
        )
    }
}
