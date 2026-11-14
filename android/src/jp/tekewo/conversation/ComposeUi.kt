package jp.tekewo.conversation

import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import org.json.JSONObject
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import java.util.function.IntConsumer
import kotlin.math.roundToInt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val chatFont = FontFamily(Font(R.font.noto_sans_jp_regular))

/** Observable UI only; the activity retains storage, authentication and network work. */
class ComposeUi(activity: ComponentActivity, private val onMenu: Runnable,
                private val onSend: Runnable, private val onPage: IntConsumer) {
    var enabled by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var draft by mutableStateOf("")
    var attachments by mutableStateOf("")
    var generateImages by mutableStateOf(false)
    var mediaDirectory by mutableStateOf<File?>(null)
    var onAttach by mutableStateOf<Runnable?>(null)
    var streaming by mutableStateOf("")
    var textSize by mutableIntStateOf(16)
    var messageGap by mutableIntStateOf(12)
    var failure by mutableStateOf("")
    var personName by mutableStateOf("")
    var personIcon by mutableStateOf<Bitmap?>(null)
    var onIcon by mutableStateOf<Runnable?>(null)
    private var feeling by mutableStateOf("")
    private var messages by mutableStateOf(emptyList<Message>())
    private var first by mutableIntStateOf(0)
    private var total by mutableIntStateOf(0)
    private var panel by mutableStateOf<Panel?>(null)
    private var scrollRequest by mutableIntStateOf(0)
    private var scrollTarget = 0
    private var pageDirection = 0
    private val menuFocus = FocusRequester()
    private val attachmentFocus = FocusRequester()
    private var attachmentMenu = false

    class Panel(@JvmField val title: String) {
        @JvmField var sheet = false
        @JvmField var choices = false
        @JvmField var message = ""
        @JvmField var secure = false
        @JvmField var dismissOnClickOutside = true
        @JvmField var confirmLabel = ""
        @JvmField var confirm: Runnable? = null
        @JvmField var onDismiss: Runnable? = null
        @JvmField val rows = mutableListOf<Row>()
        @JvmField val fields = mutableListOf<Field>()
        @JvmField val sliders = mutableListOf<Setting>()
        @JvmField var preview = false
    }
    class Row @JvmOverloads constructor(@JvmField val label: String, @JvmField val action: Runnable,
                                       @JvmField val icon: Int = -1) {
        @JvmField var checked = false
        @JvmField var dismiss = true
    }
    class Field(@JvmField val label: String, @JvmField val secret: Boolean) {
        // No rememberSaveable: secrets and large drafts must not enter activity saved state.
        var value by mutableStateOf("")
        var error by mutableStateOf("")
    }
    class Setting(@JvmField val label: String, @JvmField val min: Int, @JvmField val max: Int,
                  value: Int, @JvmField val change: IntConsumer) {
        var value by mutableIntStateOf(value)
    }
    private data class Message(val id: Long, val role: String, val text: String)

    init {
        activity.setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF13171C), surface = Color(0xFF1C2229),
                primary = Color(0xFFA6C4BC), onPrimary = Color(0xFF13171C),
                onBackground = Color(0xFFE6EEF2), onSurface = Color(0xFFE6EEF2)
            )) { Content() }
        }
    }

    fun show(value: Panel) { panel = value }
    fun hide() { panel = null }
    fun scrollToLatest() { pageDirection = 0; scrollTarget = 0; scrollRequest++ }
    private fun dismiss() { val old = panel; panel = null; old?.onDismiss?.run() }

    fun setMessages(json: String) {
        val page = JSONObject(json)
        val array = page.getJSONArray("messages")
        val next = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Message(item.getLong("id"), item.getString("role"), item.getString("text"))
        }
        first = page.getInt("first")
        total = page.getInt("total")
        feeling = page.optString("assistant_emotion", "")
        messages = next
        if (pageDirection != 0) {
            scrollTarget = if (pageDirection < 0) 0 else
                (next.size - 1 + if (first + next.size < total) 1 else 0).coerceAtLeast(0)
            pageDirection = 0
            scrollRequest++
        }
    }

    private fun isInlineSheet(value: Panel?) = value != null && value.sheet && !value.secure && value.fields.isEmpty()

    @Composable private fun Content() {
        Box(Modifier.fillMaxSize()) {
            ChatHost()
            PanelHost()
        }
    }

    @Composable private fun ChatHost() {
        Box(Modifier.fillMaxSize().then(if (isInlineSheet(panel)) Modifier.clearAndSetSemantics { } else Modifier)) {
            ChatContent()
        }
    }

    @Composable private fun ChatContent() {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val list = rememberLazyListState()
        LaunchedEffect(scrollRequest) { if (scrollRequest > 0) list.scrollToItem(scrollTarget) }
        // A reply follows the bottom only while the reader is already there.
        LaunchedEffect(messages.lastOrNull()?.id, streaming) {
            if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { keyboard?.hide(); focus.clearFocus(); attachmentMenu = false; onMenu.run() },
                        modifier = Modifier.size(48.dp).focusRequester(menuFocus)) {
                        Icon(ConversationIcons.Menu, contentDescription = "メニュー", tint = Color(0xFFDEE8EF))
                    }
                }
                if (failure.isNotEmpty()) Text(failure, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val bubbleWidth = minOf((maxWidth - 32.dp).coerceAtLeast(0.dp) * 0.86f, 600.dp)
                val latest = first + messages.size >= total
                val latestAssistant = messages.lastOrNull { it.role == "assistant" }?.id
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(messageGap.dp)) {
                    if (first + messages.size < total) item("newer") {
                        TextButton(onClick = {
                            pageDirection = 1
                            onPage.accept((first + 80).coerceAtMost((total - 80).coerceAtLeast(0)))
                        }) {
                            Text("新しい会話へ")
                        }
                    }
                    if (streaming.isNotEmpty() && first + messages.size >= total)
                        item("streaming") { MessageText(streaming, false, bubbleWidth, feeling) }
                    items(messages.asReversed(), key = { it.id }) { message ->
                        MessageText(message.text, message.role == "user", bubbleWidth,
                            if (latest && streaming.isEmpty() && message.id == latestAssistant) feeling else "")
                    }
                    if (first > 0) item("older") {
                        TextButton(onClick = {
                            pageDirection = -1; onPage.accept((first - 80).coerceAtLeast(0))
                        }) { Text("以前の会話を読む") }
                    }
                }
                }
                if (attachments.isNotBlank()) Row(Modifier.fillMaxWidth().heightIn(max = 180.dp)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentFiles.references(attachments).forEach { ref ->
                        AttachmentPreview(mediaDirectory, ref, Modifier.width(160.dp), remove = {
                            attachments = AttachmentFiles.references(attachments).filter { it.fileName != ref.fileName }.joinToString("\n") { it.markdown() }
                        })
                    }
                }
                if (generateImages) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("画像生成・有料API（説明文から1枚）", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { generateImages = false }) { Text("解除") }
                }
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 10.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { keyboard?.hide(); focus.clearFocus(); attachmentMenu = true; onAttach?.run() },
                        enabled = enabled && !sending, modifier = Modifier.size(48.dp).focusRequester(attachmentFocus)) {
                        Icon(ConversationIcons.Add, contentDescription = "添付ファイルを追加", tint = Color(0xFFDEE8EF))
                    }
                    BasicTextField(value = draft, onValueChange = { draft = it }, enabled = enabled,
                        modifier = Modifier.weight(1f).background(Color(0xFF1C2229), RoundedCornerShape(24.dp))
                            .heightIn(min = 48.dp).semantics { contentDescription = "メッセージ入力" }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        textStyle = TextStyle(color = Color(0xFFE6EEF2), fontSize = 16.sp),
                        cursorBrush = SolidColor(Color(0xFFA6C4BC)), maxLines = 5,
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } })
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onSend.run() }, enabled = enabled && !sending && (draft.isNotBlank() || attachments.isNotBlank()),
                        modifier = Modifier.size(48.dp).alpha(if (sending) 0.45f else 1f)
                            .background(Color(0xFF36594B), RoundedCornerShape(24.dp))) {
                        Icon(ConversationIcons.Send, contentDescription = "送信", tint = Color(0xFFDEE8EF))
                    }
                }
            }
        }
    }

    @Composable private fun PanelHost() {
        val value = panel
        val inline = isInlineSheet(value)
        val closeFocus = remember { FocusRequester() }
        var returnFocus by remember { mutableStateOf(false) }
        LaunchedEffect(value) {
            if (inline) {
                closeFocus.requestFocus()
                returnFocus = true
            } else if (returnFocus) {
                if (attachmentMenu) attachmentFocus.requestFocus() else menuFocus.requestFocus()
                returnFocus = false
            }
        }
        if (value == null) return
        if (!inline) { PanelContent(value); return }
        BackHandler(onBack = ::dismiss)
        Box(Modifier.fillMaxSize().imePadding()
            .semantics { paneTitle = value.title }) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.55f))
                .semantics { contentDescription = "メニューを閉じる" }
                .clickable(enabled = value.dismissOnClickOutside,
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::dismiss))
            PanelSurface(value, Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(), closeFocus)
        }
    }

    @Composable private fun MessageText(text: String, user: Boolean, bubbleWidth: Dp, emotion: String = "") {
        val directory = mediaDirectory
        val rendered by produceState(text, text, directory) {
            value = if (directory != null && text.contains("![") && text.contains("(https://"))
                withContext(Dispatchers.IO) { try { AttachmentMedia.response(directory, text) } catch (_: Exception) { text } }
            else text
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top) {
            if (!user) {
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .clickable(enabled = onIcon != null, role = Role.Button) { onIcon?.run() }
                        .semantics { contentDescription = "${personName.ifEmpty { "人物" }}の短期記憶を見る" }
                        .padding(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center) {
                        val bitmap = personIcon
                        if (bitmap != null) Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape))
                        else Text(if (personName.isEmpty()) "?" else personName.substring(0, personName.offsetByCodePoints(0, 1)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                    }
                    if (emotion.isNotEmpty()) Text(emotion, fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            SelectionContainer(Modifier.widthIn(max = if (user) bubbleWidth else (bubbleWidth - 64.dp).coerceAtLeast(48.dp))
                .background(if (user) Color(0xFF2D4434) else Color(0xFF533643), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val body = remember(rendered) { AttachmentFiles.withoutReferences(rendered) }
                // ImGui sizes Noto's 1448-unit ascent/descent; Compose sizes its 1000-unit em.
                val style = TextStyle(color = Color.White, fontSize = (textSize * (1000f / 1448f)).sp,
                    fontFamily = chatFont,
                    lineHeight = textSize.sp, letterSpacing = 0.sp)
                if (body.isNotEmpty()) Markdown(
                    markdownState = rememberMarkdownState(body, retainState = true),
                    modifier = Modifier,
                    colors = markdownColor(text = Color.White),
                    annotator = markdownAnnotator(markdownAnnotatorConfig(eolAsNewLine = true)),
                    typography = markdownTypography(
                        h1 = style.copy(fontSize = style.fontSize * 1.6f, lineHeight = style.lineHeight * 1.6f, fontWeight = FontWeight.Bold),
                        h2 = style.copy(fontSize = style.fontSize * 1.4f, lineHeight = style.lineHeight * 1.4f, fontWeight = FontWeight.Bold),
                        h3 = style.copy(fontSize = style.fontSize * 1.2f, lineHeight = style.lineHeight * 1.2f, fontWeight = FontWeight.Bold),
                        h4 = style.copy(fontWeight = FontWeight.Bold),
                        h5 = style.copy(fontWeight = FontWeight.Bold),
                        h6 = style.copy(fontWeight = FontWeight.Bold),
                        text = style, paragraph = style, quote = style,
                        ordered = style, bullet = style, list = style, table = style,
                        textLink = TextLinkStyles(style.copy(color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline).toSpanStyle()),
                        code = style.copy(fontFamily = FontFamily.Monospace),
                        inlineCode = style.copy(fontFamily = FontFamily.Monospace)),
                    loading = { Text(body, style = style) },
                    error = { Text(body, style = style) })
                remember(rendered) { AttachmentFiles.references(rendered) }.forEach { ref ->
                    AttachmentPreview(mediaDirectory, ref)
                }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun PanelContent(value: Panel) {
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
            securePolicy = if (value.secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            dismissOnClickOutside = value.dismissOnClickOutside, usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.apply {
                    if (attributes.dimAmount != 0.55f) setDimAmount(0.55f)
                    val gravity = if (value.sheet) Gravity.BOTTOM else Gravity.CENTER
                    if (attributes.gravity != gravity) setGravity(gravity)
                    val height = if (value.sheet) WRAP_CONTENT else MATCH_PARENT
                    if (attributes.width != MATCH_PARENT || attributes.height != height) setLayout(MATCH_PARENT, height)
                }
            }
            Box(Modifier.fillMaxWidth().then(if (value.sheet) Modifier else Modifier.fillMaxHeight())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding(),
                contentAlignment = if (value.sheet) Alignment.BottomCenter else Alignment.Center) {
                // Keep the platform modal window, but allocate only the sheet's height.
                Box(Modifier.matchParentSize().clickable(enabled = value.dismissOnClickOutside,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = ::dismiss))
                PanelSurface(value)

            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun PanelSurface(value: Panel, modifier: Modifier = Modifier, closeFocus: FocusRequester? = null) {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
        Surface(modifier.fillMaxWidth(if (value.sheet) 1f else 0.88f).heightIn(max = maxHeight)
            .pointerInput(Unit) { detectTapGestures { } },
            shape = if (value.sheet) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    else RoundedCornerShape(28.dp), color = Color(0xFF1C2229)) {
        Column(Modifier.then(if (value.sheet) Modifier.navigationBarsPadding() else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(value.title, fontSize = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
                    modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() })
                if (value.sheet) IconButton(onClick = ::dismiss, modifier = Modifier.size(48.dp)
                    .then(if (closeFocus == null) Modifier else Modifier
                        .focusProperties { canFocus = true }.focusRequester(closeFocus))) {
                    Icon(ConversationIcons.menu(6), contentDescription = "閉じる", tint = Color(0xFFA6C4BC),
                        modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (value.message.isNotEmpty()) SelectionContainer {
                    Text(value.message, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
                        fontSize = 17.sp, letterSpacing = 0.sp)
                }
                if (value.preview) Text("こんにちは。\n今日の続きから話そう。", fontSize = textSize.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)
                        .background(Color(0xFF273139), RoundedCornerShape(16.dp)).fillMaxWidth().padding(16.dp))
                value.fields.forEach { field ->
                    key(field) {
                        val interactions = remember { MutableInteractionSource() }
                        val focused by interactions.collectIsFocusedAsState()
                        BasicTextField(field.value, onValueChange = { field.value = it; field.error = "" },
                            singleLine = true, interactionSource = interactions,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .background(Color(0xFF13171C), RoundedCornerShape(16.dp))
                                .border(1.dp, if (focused) Color(0xFFA6C4BC) else Color(0xFF39434D), RoundedCornerShape(16.dp))
                                .heightIn(min = 52.dp).semantics { contentDescription = field.label }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            textStyle = TextStyle(color = Color(0xFFE6EEF2), fontSize = 16.sp),
                            cursorBrush = SolidColor(Color(0xFFA6C4BC)),
                            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                                if (field.value.isEmpty()) Text(field.label, color = Color(0xFF9CABB5), fontSize = 16.sp,
                                    letterSpacing = 0.sp)
                                inner()
                            } },
                            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text))
                        if (field.error.isNotEmpty()) Text(field.error, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp), fontSize = 14.sp)
                    }
                }
                value.sliders.forEach { setting ->
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    Text("${setting.label}  ${setting.value}", Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
                        fontSize = 17.sp, letterSpacing = 0.sp)
                    Slider(setting.value.toFloat(), onValueChange = {
                        setting.value = it.roundToInt(); setting.change.accept(setting.value)
                    }, valueRange = setting.min.toFloat()..setting.max.toFloat(),
                        steps = setting.max - setting.min - 1,
                        modifier = Modifier.padding(horizontal = 12.dp).height(48.dp)
                            .semantics { contentDescription = setting.label },
                        thumb = { Box(Modifier.size(12.dp).background(Color(0xFFA6C4BC), CircleShape)) },
                        track = { Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                            val start = Offset(if (rtl) size.width else 0f, center.y)
                            val end = Offset(if (rtl) 0f else size.width, center.y)
                            val fraction = (setting.value - setting.min).toFloat() / (setting.max - setting.min)
                            drawLine(Color(0xFF4B5258), start, end, strokeWidth = size.height)
                            drawLine(Color(0xFFA6C4BC), start, Offset(start.x + (end.x - start.x) * fraction, center.y),
                                strokeWidth = size.height)
                        } })
                }
                value.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().semantics {
                            if (value.choices) { role = Role.RadioButton; selected = row.checked }
                        }.clickable {
                            if (row.dismiss) hide()
                            row.action.run()
                        }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (value.sheet && row.icon >= 0) {
                            Icon(ConversationIcons.menu(row.icon), contentDescription = null,
                                tint = Color(0xFFA6C4BC), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(20.dp))
                        }
                        if (value.choices) {
                            RadioButton(selected = row.checked, onClick = null)
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(row.label + (if (row.checked && !value.choices) "  ✓" else ""),
                            fontSize = if (value.sheet) maxOf(18, textSize - 2).sp else 16.sp,
                            letterSpacing = 0.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
            if (!value.sheet) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss) {
                    Text(if (value.confirmLabel.isEmpty()) "閉じる" else "キャンセル", fontSize = 14.sp, letterSpacing = 0.sp)
                }
                if (value.confirmLabel.isNotEmpty()) TextButton(onClick = { value.confirm?.run() },
                    modifier = Modifier.padding(4.dp).background(Color(0xFF36594B), RoundedCornerShape(16.dp))) {
                    Text(value.confirmLabel, fontSize = 14.sp, color = Color(0xFFE6EEF2))
                }
            }
        }
    }
    }
}
