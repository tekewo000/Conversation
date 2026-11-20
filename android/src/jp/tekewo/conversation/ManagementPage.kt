package jp.tekewo.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val pageBackground = conversationColors.background
private val pageSurface = conversationColors.surface
private val pageInk = conversationColors.onSurface
private val pageMuted = conversationColors.onSurfaceVariant
private val pageRule = conversationColors.outlineVariant
private val pageAccent = conversationColors.primary
private val pageShape = RoundedCornerShape(16.dp)

@Composable
internal fun ManagementPage(
    panel: ComposeUi.Panel,
    backFocus: FocusRequester,
    onBack: () -> Unit,
    portrait: Bitmap?,
    textSize: Int,
    messageGap: Int,
    modifier: Modifier = Modifier,
    fields: @Composable () -> Unit
) {
    val episode = panel.key.startsWith("episode:")
    Surface(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }, color = pageBackground) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
            .semantics { paneTitle = panel.title }, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).focusRequester(backFocus)) {
                    Icon(ConversationIcons.Back, contentDescription = "戻る", tint = pageAccent)
                }
                Text(if (episode) "体験" else panel.title, Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = pageRule)
            key(panel.key) {
                Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(panel.scroll).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp)) {
                    if (episode) Text(panel.title, Modifier.padding(bottom = 12.dp).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall)
                    if (panel.subtitle.isNotEmpty()) PageIdentity(panel.subtitle, portrait, panel.key == "management")
                    if (panel.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    if (panel.message.isNotEmpty()) SelectionContainer {
                        Text(panel.message, Modifier.padding(bottom = 20.dp), color = pageMuted,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    panel.featuredRows.forEach { row ->
                        FeatureRow(row)
                        Spacer(Modifier.height(8.dp))
                    }
                    panel.memoryState?.let { CurrentMemory(it) }
                    if (panel.key == "recent" && panel.blocks.isNotEmpty()) Transcript(panel.blocks)
                    else panel.blocks.forEach { block -> PageBlock(block) }
                    if (panel.preview) DisplayPreview(textSize, messageGap)
                    fields()
                    panel.sliders.forEach { setting -> DisplaySlider(setting) }
                    if (panel.rows.isNotEmpty()) PageRows(panel)
                }
            }
        }
    }
}

@Composable
private fun PageIdentity(name: String, portrait: Bitmap?, prominent: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)
        .then(if (prominent) Modifier.background(pageSurface, pageShape).padding(14.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (prominent) 48.dp else 28.dp).clip(CircleShape)
            .background(conversationColors.surfaceVariant), contentAlignment = Alignment.Center) {
            if (portrait != null) Image(remember(portrait) { portrait.asImageBitmap() },
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            else Icon(ConversationIcons.Person, null, modifier = Modifier.size(if (prominent) 24.dp else 16.dp),
                tint = pageMuted)
        }
        Text(name, color = if (prominent) pageInk else pageMuted,
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FeatureRow(row: ComposeUi.Row) {
    Row(Modifier.fillMaxWidth().clip(pageShape).background(pageSurface)
        .clickable(role = Role.Button) { row.action.run() }.heightIn(min = 68.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.icon >= 0) {
            val icon = if (row.icon == 5) ConversationIcons.Link else ConversationIcons.menu(row.icon)
            Icon(icon, null, tint = pageAccent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label, color = pageInk, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (row.description.isNotEmpty()) Text(row.description, color = pageMuted,
                style = MaterialTheme.typography.bodyMedium)
        }
        Icon(ConversationIcons.Chevron, null, tint = pageMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun CurrentMemory(state: ComposeUi.MemoryState) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)
        .background(pageSurface, pageShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("現在の状態", Modifier.semantics { heading() }, color = pageAccent,
            style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryField("感情", state.emotion, prominent = true)
                MemoryField("理由", state.reason)
                MemoryField("状況", state.situation)
            }
        }
    }
}

@Composable
private fun MemoryField(label: String, body: String, prominent: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = pageMuted, style = MaterialTheme.typography.labelMedium)
        Text(body.ifBlank { "未記録" }, color = pageInk,
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Transcript(blocks: List<ComposeUi.Block>) {
    Column(Modifier.fillMaxWidth().background(pageSurface, pageShape).padding(horizontal = 16.dp)) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) HorizontalDivider(color = pageRule)
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(block.title, Modifier.semantics { heading() }, color = pageAccent,
                    style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun PageBlock(block: ComposeUi.Block) {
    HorizontalDivider(color = pageRule)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(block.title, Modifier.semantics { heading() }, color = pageAccent,
            style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(block.text, color = pageInk, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun PageRows(panel: ComposeUi.Panel) {
    val episodes = panel.key == "episodes" || panel.key.startsWith("episode:")
    panel.rows.forEachIndexed { index, row ->
        if (index == 0 || row.section != panel.rows[index - 1].section) {
            if (index > 0) Spacer(Modifier.height(16.dp))
            if (row.section.isNotEmpty()) Text(row.section, Modifier.padding(bottom = 8.dp).semantics { heading() },
                color = pageAccent, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider(color = pageRule)
        Row(Modifier.fillMaxWidth()
            .semantics { if (panel.choices || row.checked) selected = row.checked }
            .clickable(role = if (panel.choices) Role.RadioButton else Role.Button) { row.action.run() }
            .heightIn(min = 58.dp).padding(vertical = if (episodes) 14.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = if (row.destructive) MaterialTheme.colorScheme.error else pageInk
            if (panel.choices) RadioButton(selected = row.checked, onClick = null)
            else if (row.icon >= 0) Box(Modifier.size(36.dp).background(pageSurface, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                val icon = when {
                    row.icon == 2 || row.icon == 5 && panel.key == "people" -> ConversationIcons.Person
                    row.icon == 3 && panel.key == "settings" -> ConversationIcons.Picture
                    else -> ConversationIcons.menu(row.icon)
                }
                val tint = if (row.destructive) ink else pageAccent
                Icon(icon, null, tint = tint,
                    modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.label, color = ink,
                    style = if (episodes) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                if (row.description.isNotEmpty()) Text(row.description, color = pageMuted,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (!panel.choices && panel.key != "display") Icon(if (row.checked) ConversationIcons.Check else ConversationIcons.Chevron,
                null, tint = pageMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DisplayPreview(textSize: Int, messageGap: Int) {
    val style = chatTextStyle(textSize)
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).border(1.dp, pageRule, pageShape).padding(16.dp)) {
        Text("プレビュー", color = pageAccent, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
        Text("おかえり。今日どうだった？", style = style,
            modifier = Modifier.widthIn(max = 360.dp)
                .background(messageBubbleColor(false), messageBubbleShape(false)).padding(12.dp))
        Spacer(Modifier.height(messageGap.dp))
        Text("聞いてほしいことがあるよ。", style = style,
            modifier = Modifier.align(Alignment.End).widthIn(max = 360.dp)
                .background(messageBubbleColor(true), messageBubbleShape(true)).padding(12.dp))
    }
}

@Composable
private fun DisplaySlider(setting: ComposeUi.Setting) {
    HorizontalDivider(color = pageRule)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(setting.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(setting.value.toString(), color = pageAccent, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(pageSurface, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
    }
    Slider(value = setting.value.toFloat(), onValueChange = {
        setting.value = it.roundToInt()
        setting.change.accept(setting.value)
    }, valueRange = setting.min.toFloat()..setting.max.toFloat(), steps = setting.max - setting.min - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = setting.label })
}
