package com.hermes.agent.ui.chat

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.hermes.agent.domain.model.Attachment
import java.io.File

/** Everything the attachment views need from the screen, so they stay free of the ViewModel. */
class AttachmentActions(
    val fileOf: (Attachment) -> File,
    val save: (Attachment, Uri) -> Unit,
    val share: (Attachment, (Uri, String) -> Unit) -> Unit
)

@Composable
fun AttachmentList(items: List<Attachment>, actions: AttachmentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { AttachmentItem(it, actions) }
    }
}

@Composable
private fun AttachmentItem(a: Attachment, actions: AttachmentActions) {
    val context = LocalContext.current
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = pendingSave
        if (uri != null && target != null) actions.save(target, uri)
        pendingSave = null
    }
    var fullscreen by remember { mutableStateOf(false) }
    val model: Any = if (a.isRemote) a.uri else actions.fileOf(a)

    fun share() = actions.share(a) { uri, mime ->
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, a.name))
    }
    fun open() = actions.share(a) { uri, mime ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 300.dp)) {
        Column {
            when (a.kind) {
                Attachment.Kind.IMAGE -> AsyncImage(
                    model = model, contentDescription = a.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .clickable { fullscreen = true }
                )
                Attachment.Kind.VIDEO -> Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { fullscreen = true }) {
                    AsyncImage(
                        model = model, contentDescription = a.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).background(Color.Black)
                    )
                    Icon(Icons.Default.PlayCircle, "Play ${a.name}", Modifier.size(56.dp), tint = Color.White)
                }
                Attachment.Kind.AUDIO -> AudioPlayer(a, actions)
                Attachment.Kind.FILE -> Row(Modifier.fillMaxWidth().clickable { open() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${a.mime} · ${formatSize(a.size)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.caption.ifBlank { a.name }, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { pendingSave = a; saveLauncher.launch(a.name) }) { Icon(Icons.Default.Download, "Save ${a.name}") }
                IconButton(onClick = ::share) { Icon(Icons.Default.Share, "Share ${a.name}") }
            }
        }
    }

    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (a.kind == Attachment.Kind.VIDEO) VideoPlayer(a, actions) else AsyncImage(model = model, contentDescription = a.name, modifier = Modifier.fillMaxWidth())
                IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(a: Attachment, actions: AttachmentActions) {
    val source = if (a.isRemote) a.uri else actions.fileOf(a).absolutePath
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                if (a.isRemote) setVideoURI(Uri.parse(source)) else setVideoPath(source)
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AudioPlayer(a: Attachment, actions: AttachmentActions) {
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(a.uri) {
        runCatching {
            if (a.isRemote) player.setDataSource(a.uri) else player.setDataSource(actions.fileOf(a).absolutePath)
            player.setOnCompletionListener { playing = false }
            player.prepareAsync()
        }.onFailure { error = it.message }
        onDispose { player.release() }
    }
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = {
            runCatching { if (playing) player.pause() else player.start() }.onSuccess { playing = !playing }.onFailure { error = it.message }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(error ?: formatSize(a.size), style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long) = when {
    bytes <= 0 -> "online"
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
