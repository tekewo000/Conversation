package jp.tekewo.conversation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import android.view.View
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var ui: ComposeUi
    private var menus = 0
    private var sends = 0
    private val pages = mutableListOf<Int>()

    private fun install() {
        ui = ComposeUi(compose.activity, { menus++ }, { sends++ }, { pages.add(it) })
    }

    @Test fun typingShowsThePersonBeforeTextAndKeepsTheBubbleThroughSaveAndEmotionUpdate() {
        fun page(emotion: String, reply: Boolean) = """{"first":0,"total":${if (reply) 2 else 1},"assistant_emotion":"$emotion","messages":[{"id":1,"role":"user","text":"こんにちは"}${if (reply) ",{\"id\":2,\"role\":\"assistant\",\"text\":\"返答です\"}" else ""}]}"""
        compose.runOnUiThread {
            install()
            ui.personName = "小春"
            ui.setMessages(page("穏やか", false))
            ui.beginReply()
        }
        val icon = compose.onNodeWithContentDescription("小春の記憶を見る").fetchSemanticsNode().id
        compose.onNodeWithContentDescription("返答を入力中").assertIsDisplayed()
        compose.runOnUiThread { ui.streaming = "返答です" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("返答です")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返答を入力中").assertDoesNotExist()
        val body = compose.onNodeWithText("返答です").fetchSemanticsNode().id
        compose.runOnUiThread { ui.setMessages(page("嬉しい", true), true) }
        compose.onNodeWithText("返答です").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", ui.streaming) }
        assertEquals(icon, compose.onNodeWithContentDescription("小春の記憶を見る").fetchSemanticsNode().id)
        assertEquals(body, compose.onNodeWithText("返答です").fetchSemanticsNode().id)
        compose.runOnUiThread { ui.setMessages(page("ほっとした", true)) }
        compose.onNodeWithText("ほっとした").assertIsDisplayed()
        assertEquals(body, compose.onNodeWithText("返答です").fetchSemanticsNode().id)
    }

    @Test fun cancellingAnEmptyReplyRemovesTypingAndLeavesTheConversation() {
        compose.runOnUiThread {
            install()
            ui.setMessages("""{"first":0,"total":1,"messages":[{"id":1,"role":"user","text":"こんにちは"}]}""")
            ui.beginReply()
        }
        compose.onNodeWithContentDescription("返答を入力中").assertIsDisplayed()
        compose.runOnUiThread { ui.cancelReply() }
        compose.onNodeWithContentDescription("返答を入力中").assertDoesNotExist()
        compose.onNodeWithText("こんにちは").assertIsDisplayed()
    }

    @Test fun streamingDoesNotPullTheReaderBackToTheBottom() {
        val messages = (1..20).joinToString(",") { """{"id":$it,"role":"user","text":"過去の会話 $it"}""" }
        compose.runOnUiThread {
            install()
            ui.setMessages("""{"first":0,"total":20,"messages":[$messages]}""")
            ui.beginReply()
            ui.streaming = "受信中"
        }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(1)
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(before >= 1f)
        compose.runOnUiThread { ui.streaming = "受信中の続き" }
        assertEquals(before, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
    }

    @Test fun menuIsAtTopRightAndAttachmentButtonIsAtBottomLeft() {
        compose.runOnUiThread { install(); ui.enabled = true }
        val menu = compose.onNodeWithContentDescription("メニュー").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(menu.center.x > root.center.x && menu.center.y < root.center.y)
        val attach = compose.onNodeWithContentDescription("添付ファイルを追加").fetchSemanticsNode().boundsInRoot
        assertTrue(attach.center.x < root.center.x && attach.center.y > root.center.y)
    }

    @Test fun markdownRendersForUserAssistantAndStreamingReply() {
        compose.runOnUiThread {
            install()
            ui.setMessages("""{"first":0,"total":2,"messages":[{"id":1,"role":"user","text":"**ユーザーの太字** と `code`"},{"id":2,"role":"assistant","text":"## AIの見出し\n\n- 項目\n\n> 引用\n\n[リンク](https://example.com)\n\n```text\n**コードは原文**\n```"}]}""")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("ユーザーの太字", substring = true)).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.Text].any { text -> !text.text.contains("**") } }
        }
        compose.onNodeWithText("ユーザーの太字", substring = true).assertIsDisplayed()
        val user = compose.onNodeWithText("ユーザーの太字", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertTrue(user.text.contains("code") && !user.text.contains('`'))
        assertTrue(user.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        compose.onNodeWithText("AIの見出し").assertIsDisplayed()
        compose.onNodeWithText("項目").assertIsDisplayed()
        compose.onNodeWithText("引用").assertIsDisplayed()
        compose.onNodeWithText("リンク").assertIsDisplayed()
        compose.onNodeWithText("**コードは原文**", substring = true).assertIsDisplayed()
        compose.runOnUiThread { ui.streaming = "**途中の返答**" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("途中の返答")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("途中の返答").assertIsDisplayed()
        compose.runOnUiThread { ui.streaming = "**途中の返答が更新**" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("途中の返答が更新")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("途中の返答が更新").assertIsDisplayed()
    }

    @Test fun markdownTableAndEscapedSyntaxAreReadable() {
        compose.runOnUiThread {
            install()
            ui.setMessages("""{"first":0,"total":1,"messages":[{"id":1,"role":"user","text":"| 項目 | 値 |\n| --- | --- |\n| 名前 | 小春 |\n\n1. 番号付き\n\n~~取り消し~~ と \\*そのまま\\*"}]}""")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("小春", substring = true)).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.Text].any { text -> text.text.trim() == "小春" } }
        }
        compose.onNodeWithText("小春", substring = true).assertIsDisplayed()
        compose.onNodeWithText("番号付き").assertIsDisplayed()
        compose.onNodeWithText("取り消し と *そのまま*").assertIsDisplayed()
        val text = compose.onNodeWithText("取り消し と *そのまま*").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough })
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/conversation-markdown.png").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }

    @Test fun latestAssistantShowsOnlyCurrentFeelingBesideItsIcon() {
        var opened = 0
        compose.runOnUiThread {
            install()
            ui.personName = "小春"
            ui.onIcon = Runnable { opened++ }
            ui.setMessages("""{"first":0,"total":4,"assistant_emotion":"ほっとして嬉しい","messages":[{"id":1,"role":"user","text":"試合が終わったよ"},{"id":2,"role":"assistant","text":"お疲れさま"},{"id":3,"role":"user","text":"ありがとう"},{"id":4,"role":"assistant","text":"一緒に喜べて嬉しいよ"}]}""")
        }
        compose.onNodeWithText("ほっとして嬉しい").assertIsDisplayed()
        compose.onAllNodes(hasText("ほっとして嬉しい")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        compose.onNodeWithText("一緒に喜べて嬉しいよ").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("小春の記憶を見る")).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/conversation-avatar-chat.png").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        compose.onAllNodes(hasContentDescription("小春の記憶を見る"))[0].performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.runOnUiThread {
            ui.setMessages("""{"first":0,"total":1,"assistant_emotion":"","messages":[{"id":1,"role":"assistant","text":"続けよう"}]}""")
        }
        compose.onNodeWithText("ほっとして嬉しい").assertDoesNotExist()
    }

    @Test fun noPersonDisablesComposerButLeavesMenuAvailable() {
        compose.runOnUiThread { install() }
        compose.onNodeWithContentDescription("メッセージ入力").assertIsNotEnabled()
        compose.onNodeWithContentDescription("送信").assertIsNotEnabled()
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.runOnIdle {
            assertEquals(1, menus)
            assertEquals(0, sends)
        }
    }

    @Test fun busyReplyAllowsDraftEditingAndPreventsAnotherSend() {
        compose.runOnUiThread {
            install()
            ui.enabled = true
            ui.sending = true
        }
        compose.onNodeWithContentDescription("メッセージ入力")
            .assertIsEnabled().performTextInput("次に聞くこと 😀")
        compose.onNodeWithContentDescription("送信").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("次に聞くこと 😀", ui.draft)
            assertEquals(0, sends)
            ui.sending = false
        }
        compose.onNodeWithContentDescription("送信").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun whitespaceDraftCannotBeSent() {
        compose.runOnUiThread {
            install()
            ui.enabled = true
            ui.draft = " \n　"
        }
        compose.onNodeWithContentDescription("送信").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sends) }
    }

    @Test fun panelActionRunsOnceAndDismissesItsPanel() {
        var actions = 0
        compose.runOnUiThread {
            install()
            val panel = ComposeUi.Panel("操作の確認")
            panel.rows.add(ComposeUi.Row("人物を追加", { actions++ }))
            ui.show(panel)
        }
        compose.onNodeWithText("人物を追加").performClick()
        compose.onNodeWithText("操作の確認").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun sheetCloseButtonDismissesAndNotifiesItsOwner() {
        var dismissals = 0
        compose.runOnUiThread {
            install()
            val panel = ComposeUi.Panel("人物のメニュー")
            panel.sheet = true
            panel.onDismiss = Runnable { dismissals++ }
            panel.rows.add(ComposeUi.Row("人物を追加", {}))
            ui.show(panel)
        }
        compose.onNodeWithContentDescription("閉じる").performClick()
        compose.onNodeWithText("人物のメニュー").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun backDismissesSheetOnceAndComposerRemainsUsable() {
        var dismissals = 0
        compose.runOnUiThread {
            install()
            ui.enabled = true
            val panel = ComposeUi.Panel("戻る操作の確認")
            panel.sheet = true
            panel.onDismiss = Runnable { dismissals++ }
            panel.rows.add(ComposeUi.Row("会話", {}))
            ui.show(panel)
        }
        compose.onNodeWithText("戻る操作の確認").assertIsDisplayed()
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("戻る操作の確認")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("戻る操作の確認").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
        }
        compose.onNodeWithContentDescription("メッセージ入力")
            .assertIsEnabled().performClick().assertIsFocused().performTextInput("戻ったあとも入力できる")
        compose.runOnIdle { assertEquals("戻ったあとも入力できる", ui.draft) }
    }

    @Test fun pureSheetDoesNotCreateAnotherNativeWindow() {
        compose.runOnUiThread {
            install()
            val panel = ComposeUi.Panel("小さなメニュー")
            panel.sheet = true
            panel.rows.add(ComposeUi.Row("会話", {}))
            ui.show(panel)
        }
        compose.onNodeWithText("小さなメニュー").assertIsDisplayed()
        compose.runOnIdle {
            val roots = WindowInspector.getGlobalWindowViews().filter {
                it.visibility == View.VISIBLE && it.isAttachedToWindow
            }
            android.util.Log.i("ComposeUiProbe", "Pure menu native window count=${roots.size}")
            assertEquals("Pure menu must share the activity window", 1, roots.size)
        }
    }

    @Test fun openSheetHidesBackgroundComposerFromAccessibility() {
        compose.runOnUiThread {
            install()
            ui.enabled = true
        }
        compose.onNodeWithContentDescription("メッセージ入力").assertIsEnabled()
        compose.runOnIdle {
            val panel = ComposeUi.Panel("人物の選択")
            panel.sheet = true
            panel.rows.add(ComposeUi.Row("合成の人物", {}))
            ui.show(panel)
        }
        compose.onNodeWithText("人物の選択").assertIsDisplayed()
        compose.onNodeWithContentDescription("メッセージ入力").assertDoesNotExist()
        compose.onNodeWithContentDescription("送信").assertDoesNotExist()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun tabAndDpadFocusStayInsideOpenSheet() {
        compose.runOnUiThread {
            install()
            ui.enabled = true
            val panel = ComposeUi.Panel("キー操作のメニュー")
            panel.sheet = true
            panel.rows.add(ComposeUi.Row("会話", {}))
            panel.rows.add(ComposeUi.Row("人物", {}))
            ui.show(panel)
        }
        compose.onNodeWithContentDescription("閉じる").assertIsFocused()
        val menuControl = hasContentDescription("閉じる") or hasText("会話") or hasText("人物")
        for (key in listOf(Key.Tab, Key.DirectionDown)) {
            repeat(8) {
                compose.onRoot().performKeyInput { pressKey(key) }
                compose.onNode(isFocused() and menuControl).assertIsDisplayed()
            }
        }
        compose.onNodeWithContentDescription("メッセージ入力").assertDoesNotExist()
    }

    @Test fun secureSheetRetainsProtectedNativeWindowAndMaskedInput() {
        compose.runOnUiThread {
            install()
            val panel = ComposeUi.Panel("秘密の接続情報")
            panel.sheet = true
            panel.secure = true
            panel.fields.add(ComposeUi.Field("GitHubトークン", true))
            ui.show(panel)
        }
        compose.onNodeWithContentDescription("GitHubトークン")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle {
            val activityRoot = compose.activity.window.decorView.rootView
            val roots = WindowInspector.getGlobalWindowViews().filter {
                it.visibility == View.VISIBLE && it.isAttachedToWindow
            }
            assertEquals("Secure panel retains its native modal window", 2, roots.size)
            val dialog = roots.single { it !== activityRoot }
            val attributes = dialog.layoutParams as WindowManager.LayoutParams
            assertTrue("Secure panel must prevent screenshots", attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test fun sheetHeaderKeepsPanelOpenButBackdropDismissesExactlyOnce() {
        var dismissals = 0
        compose.runOnUiThread {
            install()
            val panel = ComposeUi.Panel("内側のメニュー")
            panel.sheet = true
            panel.onDismiss = Runnable { dismissals++ }
            panel.rows.add(ComposeUi.Row("会話", {}))
            ui.show(panel)
        }
        compose.onNodeWithText("内側のメニュー").performClick().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals) }
        val metrics = compose.activity.resources.displayMetrics
        val downTime = android.os.SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                metrics.widthPixels / 2f, metrics.heightPixels / 4f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("内側のメニュー")).fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun secretInputIsMaskedAndDoesNotRestoreAfterActivityRecreation() {
        lateinit var tokenField: ComposeUi.Field
        fun showTokenPanel() {
            install()
            val panel = ComposeUi.Panel("接続情報")
            panel.secure = true
            tokenField = ComposeUi.Field("GitHubトークン", true)
            panel.fields.add(tokenField)
            ui.show(panel)
        }
        compose.runOnUiThread { showTokenPanel() }
        compose.onNodeWithContentDescription("GitHubトークン")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("synthetic-test-secret")
        compose.runOnIdle { assertEquals("synthetic-test-secret", tokenField.value) }
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread { showTokenPanel() }
        compose.onNodeWithContentDescription("GitHubトークン")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle { assertEquals("", tokenField.value) }
    }

    @Test fun japaneseEmojiAndProvisionalReplyAreReadableAsText() {
        compose.runOnUiThread {
            install()
            ui.enabled = true
            ui.setMessages("""{"first":0,"total":1,"messages":[{"id":1,"role":"assistant","text":"こんにちは 😀\n二行目です。"}]}""")
            ui.streaming = "続きを考えています 🐈"
        }
        compose.onNodeWithText("こんにちは 😀\n二行目です。")
            .assertIsDisplayed().assertTextEquals("こんにちは 😀\n二行目です。")
        compose.onNodeWithText("続きを考えています 🐈").assertIsDisplayed()
        compose.runOnIdle { ui.streaming = "" }
        compose.onNodeWithText("続きを考えています 🐈").assertDoesNotExist()
        compose.onNodeWithText("こんにちは 😀\n二行目です。").assertIsDisplayed()
    }

    @Test fun historyNavigationUsesPageOffsetsAndHidesLatestPartialOnOlderPage() {
        fun page(first: Int): String {
            val records = (first + 1..first + 80).joinToString(",") {
                """{"id":$it,"role":"user","text":"過去の会話 $it"}"""
            }
            return """{"first":$first,"total":160,"messages":[$records]}"""
        }
        compose.runOnUiThread {
            install()
            ui.setMessages(page(80))
        }
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("以前の会話を読む"))
        compose.onNodeWithText("以前の会話を読む").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0), pages)
            ui.setMessages(page(0))
            ui.streaming = "最新ページだけに表示する途中の返答"
        }
        compose.onNodeWithText("最新ページだけに表示する途中の返答").assertDoesNotExist()
        compose.onNodeWithText("過去の会話 80").assertIsDisplayed()
        compose.onNodeWithText("新しい会話へ").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0, 80), pages)
            ui.streaming = ""
            ui.setMessages(page(80))
        }
        compose.onNodeWithText("過去の会話 81").assertIsDisplayed()
    }
}
