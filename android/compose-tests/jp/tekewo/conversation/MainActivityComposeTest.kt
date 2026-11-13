package jp.tekewo.conversation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MainActivityComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "ComposeProbe-${UUID.randomUUID()}"
    private var previousPerson: String? = null
    private var previousTextSize = 16
    private var previousGap = 12

    @Test fun namedPersonDraftAndDisplaySettingsSurviveRecreation() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        if (people.active() == null) {
            compose.onNodeWithContentDescription("メッセージ入力").assertIsNotEnabled()
            compose.onNodeWithContentDescription("送信").assertIsNotEnabled()
        }
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("人物").performClick()
        compose.onNodeWithText("人物を追加").performClick()
        compose.onNodeWithContentDescription("名前").performTextInput(name)
        compose.onNodeWithText("作成").performClick()
        compose.waitUntil(20_000) { PersonRegistry(context.filesDir).active()?.name == name }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("メッセージ入力")
            .assertIsEnabled().performTextInput("合成テストの下書き 😀")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("メッセージ入力")
            .assertTextContains("合成テストの下書き 😀")
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("表示設定").performClick()
        compose.onNodeWithContentDescription("文字サイズ")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(22f) }
        compose.onNodeWithContentDescription("メッセージの間隔")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        compose.onNodeWithContentDescription("閉じる").performClick()
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(22, preferences.getInt("text_size", 0))
            assertEquals(18, preferences.getInt("message_gap", 0))
        }
        compose.onNodeWithContentDescription("メッセージ入力")
            .assertTextContains("合成テストの下書き 😀")
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("人物").performClick()
        compose.onNodeWithText("人物を削除").performClick()
        compose.onNodeWithText(name).performClick()
        compose.onNodeWithText("人物を削除").performClick()
        compose.waitUntil(20_000) { PersonRegistry(context.filesDir).list().none { it.name == name } }
    }

    @After fun removeOnlySyntheticPersonAndRestoreSettings() {
        compose.activityRule.scenario.close()
        val people = PersonRegistry(context.filesDir)
        people.list().firstOrNull { it.name == name }?.let { synthetic ->
            val close = MainActivity::class.java.getDeclaredMethod(
                "nativeSwitchPerson", ByteArray::class.java, ByteArray::class.java)
            close.isAccessible = true
            close.invoke(null, byteArrayOf(), byteArrayOf())
            people.delete(synthetic.id)
            context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .remove("draft_${synthetic.id}").commit()
        }
        previousPerson?.let { people.activate(it) }
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
            .putInt("text_size", previousTextSize).putInt("message_gap", previousGap).commit()
    }
}
