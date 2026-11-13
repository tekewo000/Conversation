package jp.tekewo.conversation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import org.json.JSONObject
import java.util.function.IntConsumer
import kotlin.math.roundToInt

/** Observable UI only; the activity retains storage, authentication and network work. */
class ComposeUi(activity: ComponentActivity, private val onMenu: Runnable,
                private val onSend: Runnable, private val onPage: IntConsumer) {
    var enabled by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var draft by mutableStateOf("")
    var streaming by mutableStateOf("")
    var textSize by mutableIntStateOf(16)
    var messageGap by mutableIntStateOf(12)
    var failure by mutableStateOf("")
    private var messages by mutableStateOf(emptyList<Message>())
    private var first by mutableIntStateOf(0)
    private var total by mutableIntStateOf(0)
    private var panel by mutableStateOf<Panel?>(null)
    private var scrollRequest by mutableIntStateOf(0)
    private var scrollTarget = 0
    private var pageDirection = 0

    class Panel(@JvmField val title: String) {
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
    class Row(@JvmField val label: String, @JvmField val action: Runnable) {
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
                primary = Color(0xFFA8D5C5), onPrimary = Color(0xFF12372D),
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
        messages = next
        if (pageDirection != 0) {
            scrollTarget = if (pageDirection < 0) 0 else
                (next.size - 1 + if (first + next.size < total) 1 else 0).coerceAtLeast(0)
            pageDirection = 0
            scrollRequest++
        }
    }

    @Composable private fun Content() {
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
                if (failure.isNotEmpty()) Text(failure, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
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
                        item("streaming") { MessageText(streaming, false) }
                    items(messages.asReversed(), key = { it.id }) { message ->
                        MessageText(message.text, message.role == "user")
                    }
                    if (first > 0) item("older") {
                        TextButton(onClick = {
                            pageDirection = -1; onPage.accept((first - 80).coerceAtLeast(0))
                        }) { Text("以前の会話を読む") }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { keyboard?.hide(); focus.clearFocus(); onMenu.run() }, Modifier.size(48.dp)) {
                        Icon(Icons.Default.Menu, contentDescription = "メニュー")
                    }
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, enabled = enabled,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "メッセージ入力" },
                        placeholder = { Text("メッセージ") }, textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                        maxLines = 5, shape = MaterialTheme.shapes.extraLarge)
                    IconButton(onClick = { onSend.run() }, enabled = enabled && !sending && draft.isNotBlank(),
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                    }
                }
            }
        }
        panel?.let { PanelContent(it) }
    }

    @Composable private fun MessageText(text: String, user: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            SelectionContainer(Modifier.fillMaxWidth(if (user) 0.9f else 1f)) {
                Text(text, fontSize = textSize.sp, lineHeight = (textSize * 1.55f).sp,
                    modifier = if (user) Modifier.background(MaterialTheme.colorScheme.surface,
                        MaterialTheme.shapes.large).padding(14.dp) else Modifier.padding(vertical = 4.dp))
            }
        }
    }

    @Composable private fun PanelContent(value: Panel) {
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
            securePolicy = if (value.secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            dismissOnClickOutside = value.dismissOnClickOutside, usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp)
                .imePadding().heightIn(max = 640.dp), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(vertical = 16.dp)) {
                    Text(value.title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        if (value.message.isNotEmpty()) SelectionContainer {
                            Text(value.message, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (value.preview) Text("こんにちは。\n今日の続きから話そう。", fontSize = textSize.sp,
                            modifier = Modifier.padding(8.dp))
                        value.fields.forEach { field ->
                            key(field) {
                                OutlinedTextField(field.value, onValueChange = { field.value = it; field.error = "" },
                                    label = { Text(field.label) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    isError = field.error.isNotEmpty(),
                                    supportingText = if (field.error.isNotEmpty()) ({ Text(field.error) }) else null,
                                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                    keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text))
                            }
                        }
                        value.sliders.forEach { setting ->
                            Text("${setting.label}  ${setting.value}", Modifier.padding(8.dp))
                            Slider(setting.value.toFloat(), onValueChange = {
                                setting.value = it.roundToInt(); setting.change.accept(setting.value)
                            }, valueRange = setting.min.toFloat()..setting.max.toFloat(),
                                steps = setting.max - setting.min - 1,
                                modifier = Modifier.semantics { contentDescription = setting.label })
                        }
                        value.rows.forEach { row ->
                            Text((if (row.checked) "✓  " else "") + row.label,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (row.dismiss) hide()
                                    row.action.run()
                                }.heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 14.dp),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::dismiss) { Text(if (value.confirmLabel.isEmpty()) "閉じる" else "キャンセル") }
                        if (value.confirmLabel.isNotEmpty()) TextButton(onClick = { value.confirm?.run() }) { Text(value.confirmLabel) }
                    }
                }
            }
        }
    }
}
