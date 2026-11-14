package jp.tekewo.conversation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
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
import org.junit.Assert.assertTrue
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

    private fun openShortTermMemory() {
        compose.onNodeWithContentDescription("${name}の短期記憶を見る").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("短期記憶")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun paidImageApiHasItsOwnSettingsAndMaskedKeyField() {
        val keys = ConnectionStore(context, "image-api")
        val saved = keys.load()
        val subscription = ConnectionStore(context).load().toString()
        val fakeKey = "sk-synthetic_key_for_settings_test_only"
        try {
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("画像生成API").performClick()
        compose.onNodeWithContentDescription("画像生成用APIキー").assertExists()
            .assert(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.runOnIdle {
            val root = compose.activity.window.decorView.rootView
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it !== root && it.visibility == android.view.View.VISIBLE && it.isAttachedToWindow }
            assertTrue((dialog.layoutParams as android.view.WindowManager.LayoutParams).flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onNodeWithContentDescription("画像生成用APIキー").performTextInput(fakeKey)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { keys.load().optString("api_key") == fakeKey }
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("画像生成API").performClick()
        compose.onNodeWithContentDescription("画像生成用APIキー").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("APIキーを削除").performClick()
        compose.waitUntil(5_000) { !keys.load().has("api_key") }
        assertEquals(subscription, ConnectionStore(context).load().toString())
        } finally { if (saved.length() == 0) keys.delete() else keys.save(saved) }
    }

    @Test fun pickedImageAndImageModeSurviveActivityRecreation() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null); people.commit(person)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("添付ファイルを追加").performClick()
        compose.onNodeWithText("画像を生成（有料API）").performClick()
        val fixture = java.io.File(person.directory, "fixture.png")
        val bitmap = android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        fixture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", fixture)
        val target = MainActivity::class.java.getDeclaredField("pendingAttachmentPersonId").apply { isAccessible = true }
        val received = MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.Intent::class.java).apply { isAccessible = true }
        val uiField = MainActivity::class.java.getDeclaredField("compose").apply { isAccessible = true }
        compose.runOnUiThread {
            target.set(compose.activity, person.id)
            received.invoke(compose.activity, 84, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
        }
        compose.waitUntil(10_000) { (uiField.get(compose.activity) as ComposeUi).attachments.isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            val ui = uiField.get(compose.activity) as ComposeUi
            val saved = AttachmentFiles.references(ui.attachments).single()
            assertEquals("fixture.png", saved.name)
            assertTrue(AttachmentFiles.resolve(person.directory, saved.fileName).isFile)
            assertTrue(ui.generateImages)
        }
    }

    @Test fun replyImmediatelyUpdatesIconDetailsBeforeMemoryExtractionAndSurvivesRecreation() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        compose.activityRule.scenario.recreate()
        val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(compose.activity) as ChatSession
        val refresh = MainActivity::class.java.getDeclaredMethod("refreshMessages").apply { isAccessible = true }
        var requests = 0
        session.reply("試合に勝ったよ", true, { _, _, delta ->
            requests++
            delta.accept("やったね！")
            "やったね！\n<assistant_state>\n" +
                """{"emotion":"嬉しい","reason":"勝ったと聞いた","event":"試合結果を一緒に喜んだ","scope":"shared"}"""
        }, object : ChatSession.Listener {
            override fun userSaved() {}
            override fun streaming(text: String) { assertTrue(!text.contains("assistant_state")) }
            override fun replySaved() { compose.activity.runOnUiThread { refresh.invoke(compose.activity) } }
        })
        assertEquals(1, requests)
        val pending = MainActivity::class.java.getDeclaredMethod("nativePendingJob").apply { isAccessible = true }
        assertTrue(org.json.JSONObject(pending.invoke(null) as String).getLong("id") > 0)
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("嬉しい")).fetchSemanticsNodes().isNotEmpty()
        }
        openShortTermMemory()
        compose.onNodeWithText("勝ったと聞いた", substring = true)
            .assertTextContains("感情\n嬉しい\n\n理由\n勝ったと聞いた\n\n状況\n試合結果を一緒に喜んだ")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("嬉しい")).fetchSemanticsNodes().isNotEmpty()
        }
        openShortTermMemory()
        compose.onNodeWithText("勝ったと聞いた", substring = true).assertExists()
    }

    @Test fun semanticRecallFlowsThroughTheRealReplyStoreWithOriginalEvidence() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null); people.commit(person)
        compose.activityRule.scenario.recreate()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        val apply = MainActivity::class.java.getDeclaredMethod("nativeApplyJob", java.lang.Long.TYPE, ByteArray::class.java).apply { isAccessible = true }
        add.invoke(null, "user".toByteArray(), "強い雨の日は家で過ごす。ほかにはケーキを焼くのが好き。".toByteArray())
        add.invoke(null, "assistant".toByteArray(), "好みを覚えておくね".toByteArray())
        val memories = org.json.JSONArray()
        for (i in 0 until 32) memories.put(org.json.JSONObject().put("kind", "fact").put("actor", "user").put("subject", "user")
            .put("key", "topic-$i").put("text", if (i == 0) "強い雨の日は外出を控えて家で過ごすのが好きです。" else "卵と小麦粉でケーキを作るのが趣味です。")
            .put("scope", "real").put("certainty", "explicit").put("source_ids", org.json.JSONArray().put(1)))
        assertEquals("", apply.invoke(null, 1L, org.json.JSONObject().put("memories", memories).toString().toByteArray()))
        val query = "土砂降りならどうしている？"
        val lexical = MainActivity::class.java.getDeclaredMethod("nativeContext", ByteArray::class.java).apply { isAccessible = true }
        val ordinary = org.json.JSONObject(lexical.invoke(null, query.toByteArray()) as String).getJSONArray("memories")
        assertTrue((0 until ordinary.length()).none { ordinary.getJSONObject(it).getLong("id") == 1L })
        val rag = MainActivity::class.java.getDeclaredField("semanticMemory").apply { isAccessible = true }.get(compose.activity) as SemanticMemory
        rag.refresh()
        compose.waitUntil(30_000) { rag.search(query).contains(1) }
        val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(compose.activity) as ChatSession
        var sawSource = false
        session.reply(query, true, { _, input, _ ->
            val reference = org.json.JSONObject(input.getJSONObject(0).getString("content").split("\n", limit = 2)[1])
            val selected = reference.getJSONArray("memories")
            val rain = (0 until selected.length()).map { selected.getJSONObject(it) }.first { it.getLong("id") == 1L }
            assertEquals(1L, rain.getJSONArray("source_ids").getLong(0))
            sawSource = true
            "雨が強い日は家で過ごすんだね\n<assistant_state>\nnull"
        }, object : ChatSession.Listener {
            override fun userSaved() {}
            override fun streaming(text: String) {}
            override fun replySaved() {}
        })
        assertTrue(sawSource)
    }

    @Test fun iconAndCurrentFeelingSurviveRecreationAndIconCanBeReset() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        val image = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.rgb(80, 140, 120))
        val output = java.io.ByteArrayOutputStream()
        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        image.recycle()
        people.setIcon(person.id, output.toByteArray())
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread {
            val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java)
            add.isAccessible = true
            add.invoke(null, "user".toByteArray(), "試合が終わったよ".toByteArray())
            add.invoke(null, "assistant".toByteArray(), "ほっとした。一緒に喜べて嬉しい".toByteArray())
            val apply = MainActivity::class.java.getDeclaredMethod("nativeApplyJob", java.lang.Long.TYPE, ByteArray::class.java)
            apply.isAccessible = true
            assertEquals("", apply.invoke(null, 1L, """{"memories":[],"continuity":{"threads":[],"episodes":[],"assistant_state":{"emotion":"ほっとして嬉しい","reason":"試合終了の報告","event":"一緒に喜んだ会話","scope":"shared","source_ids":[1,2]}}}""".toByteArray()))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("ほっとして嬉しい")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("ほっとして嬉しい").assertExists()
        compose.onNodeWithText("試合終了の報告").assertDoesNotExist()
        compose.onNodeWithText("一緒に喜んだ会話").assertDoesNotExist()
        openShortTermMemory()
        compose.onNodeWithText("短期記憶").assertExists()
        compose.onNodeWithText("試合終了の報告", substring = true)
            .assertTextContains("感情\nほっとして嬉しい\n\n理由\n試合終了の報告\n\n状況\n一緒に喜んだ会話")
        compose.onNodeWithContentDescription("閉じる").performClick()
        compose.onNodeWithText("試合終了の報告", substring = true).assertDoesNotExist()
        openShortTermMemory()
        compose.onNodeWithText("人物アイコンを設定").performClick()
        compose.onNodeWithText("写真・画像から選ぶ").assertExists()
        compose.onNodeWithText("名前のアイコンに戻す").performClick()
        compose.waitUntil(10_000) { PersonRegistry(context.filesDir).icon(person.id).isEmpty() }
        compose.onNodeWithText("ほっとして嬉しい").assertExists()
    }

    @Test fun iconShowsEmptyShortTermMemoryWithoutInventingDetails() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        compose.activityRule.scenario.recreate()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        add.invoke(null, "assistant".toByteArray(), "こんにちは".toByteArray())
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("${name}の短期記憶を見る"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        openShortTermMemory()
        compose.onNodeWithText("感情\n未記録\n\n理由\n未記録\n\n状況\n未記録").assertExists()
    }

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
