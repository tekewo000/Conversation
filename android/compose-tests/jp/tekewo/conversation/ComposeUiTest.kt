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
        compose.onAllNodes(hasContentDescription("小春の短期記憶を見る")).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/conversation-avatar-chat.png").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
        compose.onAllNodes(hasContentDescription("小春の短期記憶を見る"))[0].performClick()
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
