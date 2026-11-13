package jp.tekewo.conversation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
