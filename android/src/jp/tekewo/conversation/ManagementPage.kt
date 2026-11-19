package jp.tekewo.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

private val pageBackground = Color(0xFF191725)
private val pageSurface = Color(0xFF272439)
private val pageInk = Color(0xFFF6F0FD)
private val pageMuted = Color(0xFFC9C0D8)
private val pageRule = Color(0xFF4A425E)
private val lilac = Color(0xFFDED1FF)
private val leaf = Color(0xFFE1EDAE)
private val blue = Color(0xFFD5E2FF)
private val sand = Color(0xFFF1D4AF)
private val darkInk = Color(0xFF242033)
private val pageShape = RoundedCornerShape(16.dp)
internal val managementColors = darkColorScheme(
    primary = lilac, onPrimary = darkInk, background = pageBackground, onBackground = pageInk,
    surface = pageSurface, onSurface = pageInk, onSurfaceVariant = pageMuted,
    outline = pageRule, error = Color(0xFFFFB7BE)
)

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
    MaterialTheme(colorScheme = managementColors) {
        Surface(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }, color = pageBackground) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .semantics { paneTitle = panel.title }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(start = 12.dp, top = 4.dp)) {
                    FilledTonalIconButton(onClick = onBack,
                        modifier = Modifier.size(48.dp).focusRequester(backFocus),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = pageSurface, contentColor = lilac)) {
                        Icon(ConversationIcons.Back, contentDescription = "戻る")
                    }
                }
                key(panel.key) {
                    Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(panel.scroll).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp)) {
                        PageTitle(panel, portrait)
                        if (panel.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                        if (panel.message.isNotEmpty()) SelectionContainer {
                            Text(panel.message, Modifier.padding(bottom = 20.dp), color = pageMuted,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        panel.featuredRows.forEach { row ->
                            val color = when (panel.key) {
                                "management" -> leaf
                                "settings" -> blue
                                "backup" -> sand
                                else -> if (row.icon == 3) leaf else lilac
                            }
                            FeatureRow(row, color, art = panel.key == "management")
                            Spacer(Modifier.height(10.dp))
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
}

@Composable
private fun PageTitle(panel: ComposeUi.Panel, portrait: Bitmap?) {
    val accent = when (panel.key) {
        "memory", "recent", "episodes" -> leaf
        "settings", "connection", "response", "display", "licenses" -> blue
        "backup" -> sand
        else -> if (panel.key.startsWith("episode:")) leaf else lilac
    }
    Text(panel.title, Modifier.semantics { heading() }, color = accent,
        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
    if (panel.subtitle.isNotEmpty()) {
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (portrait != null) Image(remember(portrait) { portrait.asImageBitmap() },
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(24.dp).clip(CircleShape))
            Text(panel.subtitle, color = pageMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun FeatureRow(row: ComposeUi.Row, color: Color, art: Boolean) {
    Row(Modifier.fillMaxWidth().clip(pageShape).background(color)
        .clickable(role = Role.Button) { row.action.run() }.heightIn(min = if (art) 112.dp else 68.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!art && row.icon >= 0) {
            val icon = if (row.icon == 5) ConversationIcons.Link else ConversationIcons.menu(row.icon)
            Icon(icon, null, tint = darkInk, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label, color = darkInk,
                style = if (art) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            if (row.description.isNotEmpty()) Text(row.description, color = darkInk,
                style = MaterialTheme.typography.bodyMedium)
        }
        if (art) JournalMark()
        Icon(ConversationIcons.Chevron, null, tint = darkInk, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun JournalMark() {
    Canvas(Modifier.size(60.dp, 68.dp)) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(w * .06f)
        val cover = Size(w * .62f, h * .74f)
        rotate(-18f) {
            drawRoundRect(darkInk, Offset(w * .09f, h * .08f), cover, corner, style = Stroke(1.5.dp.toPx()))
        }
        rotate(11f) {
            val origin = Offset(w * .28f, h * .16f)
            drawRoundRect(darkInk, origin, cover, corner)
            for (index in 0..2) {
                val y = h * (.38f + index * .13f)
                val end = w * (if (index == 2) .64f else .76f)
                drawLine(leaf, Offset(w * .42f, y), Offset(end, y),
                    strokeWidth = 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun CurrentMemory(state: ComposeUi.MemoryState) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp)
        .background(pageSurface, pageShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("現在の状態", Modifier.semantics { heading() }, color = lilac,
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
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).background(lilac, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(block.title, Modifier.semantics { heading() }, color = lilac,
                        style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable
private fun PageBlock(block: ComposeUi.Block) {
    HorizontalDivider(color = pageRule)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(block.title, Modifier.semantics { heading() }, color = lilac,
            style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(block.text, color = pageMuted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun PageRows(panel: ComposeUi.Panel) {
    val episodes = panel.key == "episodes" || panel.key.startsWith("episode:")
    panel.rows.forEachIndexed { index, row ->
        if (index == 0 || row.section != panel.rows[index - 1].section) {
            if (index > 0) Spacer(Modifier.height(16.dp))
            if (row.section.isNotEmpty()) Text(row.section, Modifier.padding(bottom = 8.dp).semantics { heading() },
                color = lilac, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider(color = pageRule)
        Row(Modifier.fillMaxWidth()
            .semantics { if (panel.choices || row.checked) selected = row.checked }
            .clickable(role = if (panel.choices) Role.RadioButton else Role.Button) { row.action.run() }
            .heightIn(min = 54.dp).padding(vertical = if (episodes) 14.dp else 9.dp),
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
                val tint = if (row.destructive) ink else if (episodes) leaf else lilac
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
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).background(pageSurface, pageShape).padding(16.dp)) {
        Text("プレビュー", color = lilac, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
        Text("おかえり。今日どうだった？", style = style,
            modifier = Modifier.widthIn(max = 360.dp).background(Color(0xFF533643), pageShape).padding(12.dp))
        Spacer(Modifier.height(messageGap.dp))
        Text("聞いてほしいことがあるよ。", style = style,
            modifier = Modifier.align(Alignment.End).widthIn(max = 360.dp)
                .background(Color(0xFF2D4434), pageShape).padding(12.dp))
    }
}

@Composable
private fun DisplaySlider(setting: ComposeUi.Setting) {
    HorizontalDivider(color = pageRule)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(setting.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(setting.value.toString(), color = lilac, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.background(pageSurface, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
    }
    Slider(value = setting.value.toFloat(), onValueChange = {
        setting.value = it.roundToInt()
        setting.change.accept(setting.value)
    }, valueRange = setting.min.toFloat()..setting.max.toFloat(), steps = setting.max - setting.min - 1,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = setting.label })
}
