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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
import java.util.function.Consumer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val chatFont = FontFamily(Font(R.font.noto_sans_jp_regular))

internal fun chatTextStyle(textSize: Int) = TextStyle(
    color = Color.White, fontSize = textSize.sp,
    fontFamily = chatFont, lineHeight = (textSize * 1.45f).sp, letterSpacing = 0.sp
)

/** Observable UI only; the activity retains storage, authentication and network work. */
class ComposeUi(activity: ComponentActivity, private val onMenu: Runnable,
                private val onSend: Runnable, private val onPage: IntConsumer) {
    var enabled by mutableStateOf(false)
    var sending by mutableStateOf(false)
    private val draftField = TextFieldState()
    private val hasDraft by derivedStateOf(structuralEqualityPolicy()) { draftField.text.isNotBlank() }
    var draft: String
        get() = draftField.text.toString()
        @OptIn(ExperimentalFoundationApi::class)
        set(value) {
            draftField.setTextAndPlaceCursorAtEnd(value)
            // Draft replacement also occurs when switching people.
            draftField.undoState.clearHistory()
        }
    var attachments by mutableStateOf("")
    var generateImages by mutableStateOf(false)
    var mediaDirectory by mutableStateOf<File?>(null)
    var onAttach by mutableStateOf<Runnable?>(null)
    var onSendNote by mutableStateOf<Runnable?>(null)
    var streaming by mutableStateOf("")
    private var replying by mutableStateOf(false)
    private var replyKey = 0L
    var textSize by mutableIntStateOf(16)
    var messageGap by mutableIntStateOf(12)
    var failure by mutableStateOf("")
    var personName by mutableStateOf("")
    var personIcon by mutableStateOf<Bitmap?>(null)
    var onIcon by mutableStateOf<Runnable?>(null)
    private var messages by mutableStateOf(emptyList<Message>())
    private var first by mutableIntStateOf(0)
    private var total by mutableIntStateOf(0)
    private var panel by mutableStateOf<Panel?>(null)
    private val pages = mutableStateListOf<Panel>()
    var onPageChanged: Consumer<String>? = null
    val pageKey: String get() = pages.lastOrNull()?.key ?: ""
    var pageVersion = 0
        private set
    private var scrollRequest by mutableIntStateOf(0)
    private var scrollTarget = 0
    private var pageDirection = 0
    private val menuFocus = FocusRequester()
    private val attachmentFocus = FocusRequester()
    private var attachmentMenu = false

    class Panel(@JvmField val title: String) {
        @JvmField var key = title
        @JvmField var subtitle = ""
        @JvmField var loading = false
        @JvmField var onResume: Runnable? = null
        @JvmField val blocks = mutableListOf<Block>()
        @JvmField var memoryState: MemoryState? = null
        @JvmField val featuredRows = mutableListOf<Row>()
        internal var scroll = ScrollState(0)
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
    class Block(@JvmField val title: String, @JvmField val text: String)
    class MemoryState(val emotion: String, val reason: String, val situation: String)
    class Row @JvmOverloads constructor(@JvmField val label: String, @JvmField val action: Runnable,
                                       @JvmField val icon: Int = -1) {
        @JvmField var checked = false
        @JvmField var dismiss = true
        @JvmField var description = ""
        @JvmField var section = ""
        @JvmField var destructive = false
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
    private data class Message(val id: Long, val role: String, val text: String, val key: Long = id, val annotation: String = "")

    init {
        activity.setContent {
            MaterialTheme(colorScheme = conversationColors) { Content() }
        }
    }

    fun show(value: Panel) { hide(); panel = value; pageVersion++ }
    fun hide() {
        panel?.let { value -> value.fields.filter { it.secret }.forEach { it.value = "" }; pageVersion++ }
        panel = null
    }
    fun showPage(value: Panel) {
        require(!value.secure && value.fields.none { it.secret })
        hide()
        if (pages.lastOrNull()?.key == value.key) {
            value.scroll = pages.last().scroll
            pages[pages.lastIndex] = value
        } else {
            pages.add(value); pageVersion++
            onPageChanged?.accept(pageKey)
        }
    }
    fun showRoot(value: Panel) {
        if (pages.size == 1 && pageKey == value.key) { showPage(value); return }
        closePages(); showPage(value)
    }
    fun closePages() {
        hide(); pages.clear(); pageVersion++; onPageChanged?.accept("")
    }
    fun isPageVisible(key: String, version: Int) = pageKey == key && pageVersion == version && panel == null
    fun back() {
        if (panel != null) { dismiss(); return }
        if (pages.isEmpty()) return
        pages.removeAt(pages.lastIndex); pageVersion++; onPageChanged?.accept(pageKey)
        pages.lastOrNull()?.onResume?.run()
    }
    fun scrollToLatest() { pageDirection = 0; scrollTarget = 0; scrollRequest++ }
    private fun dismiss() {
        val old = panel
        hide()
        if (old?.onDismiss != null) old.onDismiss?.run() else pages.lastOrNull()?.onResume?.run()
    }

    fun beginReply() { replyKey--; streaming = ""; replying = true; scrollToLatest() }
    fun cancelReply() { replying = false; streaming = "" }

    @JvmOverloads fun setMessages(json: String, replySaved: Boolean = false) {
        val page = JSONObject(json)
        val array = page.getJSONArray("messages")
        val keys = messages.associate { it.id to it.key }
        val latest = page.getInt("first") + array.length() >= page.getInt("total")
        val next = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getLong("id")
            val role = item.getString("role")
            val key = if (replySaved && replying && latest && index == array.length() - 1 && role == "assistant") replyKey else keys[id] ?: id
            Message(id, role, item.getString("text"), key, item.optString("annotation"))
        }
        first = page.getInt("first")
        total = page.getInt("total")
        messages = next
        if (replySaved) cancelReply()
        if (pageDirection != 0) {
            scrollTarget = if (pageDirection < 0) 0 else
                (next.size - 1 + if (first + next.size < total) 1 else 0).coerceAtLeast(0)
            pageDirection = 0
            scrollRequest++
        }
    }

    private fun isInlineSheet(value: Panel?) = value != null && value.sheet && !value.secure && value.fields.isEmpty()

    @Composable private fun Content() {
        LaunchedEffect(pages.isEmpty()) { if (pages.isEmpty()) menuFocus.requestFocus() }
        Box(Modifier.fillMaxSize()) {
            ChatHost()
            PageHost()
            PanelHost()
        }
    }

    @Composable private fun ChatHost() {
        Box(Modifier.fillMaxSize().then(if (pages.isNotEmpty() || isInlineSheet(panel)) Modifier.clearAndSetSemantics { } else Modifier)) {
            ChatContent()
        }
    }

    @Composable private fun PageHost() {
        val value = pages.lastOrNull() ?: return
        val backFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        LaunchedEffect(value.key, panel == null) {
            if (panel == null) { keyboard?.hide(); focus.clearFocus(); backFocus.requestFocus() }
        }
        BackHandler(enabled = panel == null, onBack = ::back)
        ManagementPage(value, backFocus, ::back, personIcon.takeIf { value.subtitle == personName },
            textSize, messageGap, Modifier.then(if (panel != null) Modifier.clearAndSetSemantics { } else Modifier)) {
            PanelFields(value.fields)
        }
    }

    @Composable private fun ChatContent() {
        val list = rememberLazyListState()
        LaunchedEffect(scrollRequest) { if (scrollRequest > 0) list.scrollToItem(scrollTarget) }
        // A reply follows the bottom only while the reader is already there.
        LaunchedEffect(messages.lastOrNull()?.id, replying) {
            if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                ChatHeader()
                if (failure.isNotEmpty()) Text(failure, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error)
                BoxWithConstraints(Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                    val messageWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp) * 0.8f
                    val latest = first + messages.size >= total
                    val pending = latest && (replying || streaming.isNotEmpty())
                    val visible = if (pending) {
                        listOf(Message(-1, "assistant", streaming, if (replying) replyKey else Long.MIN_VALUE)) + messages.asReversed()
                    } else messages.asReversed()
                    LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(messageGap.dp)) {
                        if (!latest) item("newer") {
                            TextButton(onClick = {
                                pageDirection = 1
                                onPage.accept((first + 80).coerceAtMost((total - 80).coerceAtLeast(0)))
                            }) { Text("新しい会話へ") }
                        }
                        items(visible, key = { it.key }) { message ->
                            MessageText(message.text, message.role == "user", messageWidth,
                                typing = message.id == -1L && message.text.isEmpty(), annotation = message.annotation)
                        }
                        if (first > 0) item("older") {
                            TextButton(onClick = {
                                pageDirection = -1
                                onPage.accept((first - 80).coerceAtLeast(0))
                            }) { Text("以前の会話を読む") }
                        }
                    }
                }
                ComposerDock()
            }
        }
    }

    @Composable private fun ChatHeader() {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val colors = MaterialTheme.colorScheme
        val memoryAction = onIcon.takeIf { enabled && personName.isNotBlank() }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(end = 56.dp).heightIn(min = 48.dp)
                    .then(if (memoryAction == null) Modifier else Modifier
                        .clickable(role = Role.Button) { memoryAction.run() }
                        .semantics { contentDescription = "会話相手の記憶を開く" })
                    .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = personIcon
                    if (bitmap != null && personName.isNotBlank()) {
                        Image(remember(bitmap) { bitmap.asImageBitmap() }, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CircleShape))
                    }
                    Text(personName.ifBlank { "会話" }, Modifier.semantics { heading() },
                        color = colors.onSurface, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                SmallFloatingActionButton(
                    onClick = { keyboard?.hide(); focus.clearFocus(); attachmentMenu = false; onMenu.run() },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp).focusRequester(menuFocus),
                    shape = CircleShape,
                    containerColor = colors.surfaceVariant,
                    contentColor = colors.onSurfaceVariant,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                ) {
                    Icon(ConversationIcons.Menu, contentDescription = "メニュー")
                }
            }
        }
    }

    @Composable private fun ComposerDock() {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val colors = MaterialTheme.colorScheme
        val fieldInteractions = remember { MutableInteractionSource() }
        val focused by fieldInteractions.collectIsFocusedAsState()
        val canSend = enabled && !sending && (hasDraft || attachments.isNotBlank())
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (attachments.isNotBlank()) {
                Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 180.dp)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentFiles.references(attachments).forEach { ref ->
                        AttachmentPreview(mediaDirectory, ref, Modifier.width(160.dp), remove = {
                            attachments = AttachmentFiles.references(attachments)
                                .filter { it.fileName != ref.fileName }.joinToString("\n") { it.markdown() }
                        })
                    }
                }
            }
            if (generateImages) {
                Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("画像生成・有料API（説明文から1枚）", Modifier.weight(1f), color = colors.primary)
                    TextButton(onClick = { generateImages = false }) { Text("解除") }
                }
            }
            Row(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = { keyboard?.hide(); focus.clearFocus(); attachmentMenu = true; onAttach?.run() },
                    enabled = enabled && !sending, modifier = Modifier.size(48.dp).focusRequester(attachmentFocus),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Icon(ConversationIcons.Add, contentDescription = "添付ファイルを追加")
                }
                BasicTextField(state = draftField, enabled = enabled, interactionSource = fieldInteractions,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .semantics { contentDescription = "メッセージ入力" }.padding(horizontal = 4.dp, vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary), lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                    decorator = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            val empty by remember { derivedStateOf(structuralEqualityPolicy()) { draftField.text.isEmpty() } }
                            if (empty) Text("メッセージ", Modifier.clearAndSetSemantics { },
                                color = if (focused) colors.onSurface else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    })
                Box(Modifier.size(48.dp).clip(CircleShape)
                    .combinedClickable(enabled = canSend, role = Role.Button, onClick = { onSend.run() },
                        onLongClickLabel = "情景・メタ発言として送る", onLongClick = { onSendNote?.run() }),
                    contentAlignment = Alignment.Center) {
                    Icon(ConversationIcons.Send, contentDescription = "送信",
                        tint = if (canSend) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f))
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

    @Composable private fun MessageText(text: String, user: Boolean, messageWidth: Dp, typing: Boolean = false, annotation: String = "") {
        if (annotation.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (annotation == "scene") "情景 · 返信なし" else "メタ · 返信なし", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                SelectionContainer { MessageBody(text) }
            }
            return
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.TopEnd else Alignment.TopStart) {
            SelectionContainer(Modifier.widthIn(max = messageWidth).messageBubble(user)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (typing) TypingIndicator()
                    MessageBody(text)
                }
            }
        }
    }

    @Composable private fun TypingIndicator() {
        val transition = rememberInfiniteTransition(label = "typing")
        val pulse = List(3) { index ->
            transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(450, delayMillis = index * 150), RepeatMode.Reverse), label = "dot-$index")
        }
        Canvas(Modifier.size(36.dp, 20.dp).semantics { contentDescription = "返答を入力中" }) {
            pulse.forEachIndexed { index, alpha ->
                drawCircle(Color.White.copy(alpha = alpha.value), 2.5.dp.toPx(), Offset((6 + index * 12).dp.toPx(), size.height / 2))
            }
        }
    }

    @Composable private fun MessageBody(text: String) {
        val directory = mediaDirectory
        val rendered by produceState(text, text, directory) {
            value = if (directory != null && text.contains("![") && text.contains("(https://"))
                withContext(Dispatchers.IO) { try { AttachmentMedia.response(directory, text) } catch (_: Exception) { text } }
            else text
        }
        val body = remember(rendered) { AttachmentFiles.withoutReferences(rendered) }
        val style = chatTextStyle(textSize)
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
        val colors = MaterialTheme.colorScheme
        Surface(modifier.fillMaxWidth(if (value.sheet) 1f else 0.88f).heightIn(max = maxHeight)
            .pointerInput(Unit) { detectTapGestures { } },
            shape = if (value.sheet) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    else RoundedCornerShape(28.dp), color = colors.surface) {
        Column(Modifier.then(if (value.sheet) Modifier.navigationBarsPadding() else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(value.title, fontSize = 21.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp,
                    modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() })
                if (value.sheet) IconButton(onClick = ::dismiss, modifier = Modifier.size(48.dp)
                    .then(if (closeFocus == null) Modifier else Modifier
                        .focusProperties { canFocus = true }.focusRequester(closeFocus))) {
                    Icon(ConversationIcons.menu(6), contentDescription = "閉じる", tint = colors.primary,
                        modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                PanelBody(value)
            }
            if (!value.sheet || value.confirmLabel.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::dismiss) {
                    Text(if (value.confirmLabel.isEmpty()) "閉じる" else "キャンセル", fontSize = 14.sp, letterSpacing = 0.sp)
                }
                if (value.confirmLabel.isNotEmpty()) Button(onClick = { value.confirm?.run() },
                    modifier = Modifier.padding(start = 4.dp)) {
                    Text(value.confirmLabel, fontSize = 14.sp)
                }
            }
        }
    }
    }
    @Composable private fun PanelBody(value: Panel) {
        if (value.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
        if (value.message.isNotEmpty()) SelectionContainer {
            Text(value.message, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
                fontSize = 17.sp, letterSpacing = 0.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        value.blocks.forEach { block ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
                Text(block.title, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
                SelectionContainer {
                    Text(block.text, fontSize = 16.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        PanelFields(value.fields)
        value.rows.forEachIndexed { index, row ->
            if (row.section.isNotEmpty() && (index == 0 || row.section != value.rows[index - 1].section)) {
                Text(row.section, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
            }
            Row(Modifier.fillMaxWidth().semantics {
                    if (value.choices) { role = Role.RadioButton; selected = row.checked }
                }.clickable {
                    if (row.dismiss) hide()
                    row.action.run()
                }.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (value.sheet && row.icon >= 0) {
                    Icon(ConversationIcons.menu(row.icon), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(20.dp))
                }
                if (value.choices) {
                    RadioButton(selected = row.checked, onClick = null)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(row.label + (if (row.checked && !value.choices) "  ✓" else ""),
                        color = if (row.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        fontSize = if (!value.sheet) 16.sp else maxOf(18, textSize - 2).sp, letterSpacing = 0.sp)
                    if (row.description.isNotEmpty()) Text(row.description, fontSize = 13.sp, lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    @Composable private fun PanelFields(fields: List<Field>) {
        val colors = MaterialTheme.colorScheme
        fields.forEach { field ->
            key(field) {
                Text(field.label, fontSize = 14.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
                val interactions = remember { MutableInteractionSource() }
                val focused by interactions.collectIsFocusedAsState()
                BasicTextField(field.value, onValueChange = { field.value = it; field.error = "" },
                    singleLine = true, interactionSource = interactions,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(colors.background, RoundedCornerShape(16.dp))
                        .border(1.dp, if (focused) colors.primary else colors.outline, RoundedCornerShape(16.dp))
                        .heightIn(min = 52.dp).semantics { contentDescription = field.label }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) {
                        if (field.value.isEmpty()) Text(if (field.secret) "入力してください" else field.label, color = colors.onSurfaceVariant, fontSize = 16.sp,
                            letterSpacing = 0.sp)
                        inner()
                    } },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text))
                if (field.error.isNotEmpty()) Text(field.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp), fontSize = 14.sp)
            }
        }
    }

}
