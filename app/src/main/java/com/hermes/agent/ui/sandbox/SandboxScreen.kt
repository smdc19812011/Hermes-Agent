package com.hermes.agent.ui.sandbox

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.agent.data.sandbox.SandboxLanguage
import com.hermes.agent.data.sandbox.SandboxResult

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SandboxScreen(
    onBack: () -> Unit,
    onOpenRuntime: () -> Unit,
    vm: SandboxViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Code sandbox") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (state.running) {
                ExtendedFloatingActionButton(onClick = { vm.stop() }, icon = { Icon(Icons.Default.Stop, null) }, text = { Text("Stop") })
            } else {
                val blocked = state.language.needsTermux && !state.termuxReady
                ExtendedFloatingActionButton(
                    onClick = { if (!blocked) vm.run() },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    text = { Text("Run") },
                    containerColor = if (blocked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SandboxLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = state.language == lang,
                        onClick = { vm.setLanguage(lang) },
                        label = { Text(lang.displayName) }
                    )
                }
            }

            if (state.language.needsTermux && !state.termuxReady) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${state.language.displayName} runs in Termux in the background. Finish steps 1–3 on the Agent runtime screen to enable it. In-app JavaScript works without Termux.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onOpenRuntime) { Text("Open Agent runtime") }
                    }
                }
            } else {
                Text(
                    if (state.language.needsTermux) "Runs in Termux under ~/.hermes-android/sandbox, stopped after 60 seconds."
                    else "Runs in an isolated JavaScript engine with no file or network access. The last expression's value is shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = state.code,
                onValueChange = vm::setCode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                label = { Text("Code") }
            )

            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.result?.let { ResultPanel(it) }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
fun ResultPanel(result: SandboxResult) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val ok = result.error == null && result.exitCode == 0
            Text(
                when {
                    result.error != null -> "Failed"
                    ok -> "Finished"
                    else -> "Exited with code ${result.exitCode}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.weight(1f))
            Text("${result.durationMs} ms", style = MaterialTheme.typography.labelSmall)
        }
        result.error?.let { OutputBlock(it, isError = true) }
        if (result.stdout.isNotBlank()) OutputBlock(result.stdout, isError = false)
        if (result.stderr.isNotBlank()) OutputBlock(result.stderr, isError = true)
        if (result.error == null && result.stdout.isBlank() && result.stderr.isBlank()) {
            Text("(no output)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OutputBlock(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        SelectionContainer {
            Text(
                text.trimEnd(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)
            )
        }
    }
}
