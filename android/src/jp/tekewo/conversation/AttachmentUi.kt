package jp.tekewo.conversation

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun AttachmentPreview(person: File?, ref: AttachmentFiles.Ref, modifier: Modifier = Modifier, remove: (() -> Unit)? = null) {
    val context = LocalContext.current
    var expanded by remember(person, ref.fileName) { mutableStateOf(false) }
    var failed by remember(person, ref.fileName) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, person, ref.fileName) {
        value = withContext(Dispatchers.IO) {
            try {
                if (person == null) return@withContext null
                if (ref.image()) AttachmentMedia.thumbnail(AttachmentFiles.read(person, ref))
                else if (ref.video()) MediaMetadataRetriever().use { video ->
                    video.setDataSource(AttachmentFiles.resolve(person, ref.fileName).path)
                    video.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                } else null
            } catch (_: Exception) { failed = true; null }
        }
    }
    Column(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp))
        .background(Color.Black.copy(alpha = 0.16f)).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().clickable {
            if (person == null) return@clickable
            if (ref.image() || ref.video()) expanded = true
            else try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", AttachmentFiles.resolve(person, ref.fileName))
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    if (ref.fileName.endsWith(".pdf")) "application/pdf" else "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) { Toast.makeText(context, "ファイルを開けるアプリが見つかりません。", Toast.LENGTH_LONG).show() }
        }, contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratio = image.width.toFloat() / image.height
                val width = minOf(maxWidth, (if (remove != null) 100.dp else 280.dp) * ratio)
                Image(image.asImageBitmap(), contentDescription = ref.name,
                    modifier = Modifier.width(width).height(width / ratio), contentScale = ContentScale.Fit)
            }
            else Text(if (failed) "添付ファイルを読み込めません" else ref.name, Modifier.padding(12.dp), color = Color.White)
            if (ref.video()) Icon(ConversationIcons.Play, contentDescription = "動画を再生",
                tint = Color.White, modifier = Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ref.name, Modifier.weight(1f).padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            if (remove != null) IconButton(onClick = remove) {
                Icon(ConversationIcons.menu(6), contentDescription = "${ref.name}を取り外す", tint = Color.White)
            }
        }
    }
    if (expanded && person != null) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF13171C)).safeDrawingPadding()) {
            TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) { Text("閉じる") }
            if (ref.video()) AndroidView(factory = { viewContext ->
                VideoView(viewContext).apply {
                    val controls = MediaController(viewContext)
                    controls.setAnchorView(this); setMediaController(controls)
                    setVideoURI(Uri.fromFile(AttachmentFiles.resolve(person, ref.fileName)))
                    setOnErrorListener { _, _, _ -> Toast.makeText(viewContext, "この動画を再生できません。", Toast.LENGTH_LONG).show(); true }
                    setOnPreparedListener { start() }
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { it.stopPlayback() })
            else bitmap?.let { image -> Image(image.asImageBitmap(), contentDescription = ref.name,
                modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit) }
                ?: Text(if (failed) "画像を読み込めませんでした。" else "画像を読み込んでいます…", Modifier.padding(24.dp), color = Color.White)
        }
    }
}
