package jp.tekewo.conversation

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
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

    private fun captureScreen(fileName: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 3_000)
        val bitmap = automation.takeScreenshot()
        try {
            java.io.File(context.cacheDir, fileName).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun createManagementPerson(displayName: String = name) {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        if (displayName != name) people.setDisplayName(person.id, displayName)
        compose.activityRule.scenario.recreate()
    }

    @Test fun displayNameCanBeEditedWithoutReplacingThePersonOrDraft() {
        createManagementPerson()
        val before=PersonRegistry(context.filesDir).active()!!
        val key=(context.applicationContext as ConversationApplication).get(before.directory,false)
        val add=MainActivity::class.java.getDeclaredMethod("nativeAddMessage",ByteArray::class.java,ByteArray::class.java).apply { isAccessible=true }
        compose.runOnUiThread { assertTrue((add.invoke(null,"assistant".toByteArray(),"表示名テストの返信".toByteArray()) as Long)>0) }
        compose.onNodeWithContentDescription("メッセージ入力").performTextInput("名前変更中の下書き")
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("人物").performClick()
        compose.onNodeWithText("名前を編集").performClick()
        compose.onNodeWithContentDescription("表示名").performTextClearance()
        compose.onNodeWithContentDescription("表示名").performTextInput("短い表示名 😀")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { PersonRegistry(context.filesDir).active()?.displayName=="短い表示名 😀" }
        compose.onNodeWithText("短い表示名 😀").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("会話相手のプロフィールを開く").assertTextContains("短い表示名 😀")
        compose.onNodeWithContentDescription("メッセージ入力").assertTextContains("名前変更中の下書き")
        val after=PersonRegistry(context.filesDir).active()!!
        assertEquals(before.id,after.id);assertEquals(before.name,after.name)
        org.junit.Assert.assertArrayEquals(key,(context.applicationContext as ConversationApplication).get(after.directory,false))
    }

    private fun openConnectionPage(): ComposeUi {
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("ChatGPT").performClick()
        return MainActivity::class.java.getDeclaredField("compose").apply { isAccessible = true }.get(compose.activity) as ComposeUi
    }

    private fun completeSyntheticSignIn(error: String?, version: Int) {
        compose.runOnUiThread {
            if (error == null) {
                val connection = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(compose.activity)
                ChatGptConnection::class.java.getDeclaredField("state").apply { isAccessible = true }.set(connection,
                    org.json.JSONObject().put("access_token", "synthetic-ui-only").put("refresh_token", "synthetic-ui-only")
                        .put("subject", "synthetic-ui-only").put("client_id", "synthetic-ui-only"))
            }
            MainActivity::class.java.getDeclaredMethod("finishSignIn", String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(compose.activity, error, version)
        }
    }

    @Test fun completedSignInRefreshesTheVisibleConnectionPage() {
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val welcome = preferences.getBoolean("plan_welcome", false)
        try {
            preferences.edit().putBoolean("plan_welcome", true).commit()
            val page = openConnectionPage()
            compose.onNodeWithText("Continue with ChatGPT").assertExists()
            completeSyntheticSignIn(null, page.pageVersion)
            compose.onNodeWithText("Continue with ChatGPT").assertDoesNotExist()
            compose.onNodeWithText("ログアウト").assertExists()
        } finally { preferences.edit().putBoolean("plan_welcome", welcome).commit() }
    }

    @Test fun reconnectingRestartsADelayedPersonaReview() {
        createManagementPerson()
        val manager = androidx.work.WorkManager.getInstance(context)
        val delayed = androidx.work.OneTimeWorkRequest.Builder(MemoryWork::class.java)
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val welcome = preferences.getBoolean("plan_welcome", false)
        try {
            preferences.edit().putBoolean("plan_welcome", true).commit()
            manager.enqueueUniqueWork(MemoryWork.NAME, androidx.work.ExistingWorkPolicy.REPLACE, delayed)
                .result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val page = openConnectionPage()
            completeSyntheticSignIn(null, page.pageVersion)
            compose.waitUntil(10_000) {
                val old = manager.getWorkInfoById(delayed.id).get(2, java.util.concurrent.TimeUnit.SECONDS)
                old == null || old.state == androidx.work.WorkInfo.State.CANCELLED
            }
        } finally {
            manager.cancelUniqueWork(MemoryWork.NAME).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            manager.cancelUniqueWork(MemoryWork.RECORD_NAME).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            preferences.edit().putBoolean("plan_welcome", welcome).commit()
        }
    }

    @Test fun firstSignInWelcomeReturnsToChatWhenConversationIsChosen() {
        createManagementPerson()
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val welcome = preferences.getBoolean("plan_welcome", false)
        try {
            preferences.edit().putBoolean("plan_welcome", false).commit()
            val page = openConnectionPage()
            completeSyntheticSignIn(null, page.pageVersion)
            compose.onNodeWithText("会話を始める").performClick()
            compose.onNodeWithContentDescription("メッセージ入力").assertIsDisplayed()
        } finally { preferences.edit().putBoolean("plan_welcome", welcome).commit() }
    }

    @Test fun backgroundNotificationsCanBeTurnedOffWithoutDisablingInitiative() {
        val triggers = InitiativePolicy.Trigger.values()
        val previousInitiative = triggers.associateWith { InitiativeWork.enabled(context, it) }
        val previousNotifications = InitiativeWork.notificationsEnabled(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            triggers.forEach { InitiativeWork.setEnabled(context, it, false) }
            InitiativeWork.setNotificationsEnabled(context, false)
            createManagementPerson()

            fun openInitiativeSettings() {
                compose.onNodeWithContentDescription("メニュー").performClick()
                compose.onNodeWithText("設定").performClick()
                compose.onNodeWithText("人物から話しかける").performClick()
            }

            openInitiativeSettings()
            compose.onNodeWithText("会話を開いたとき").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("会話の合間").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("アプリを閉じているとき").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("会話を開いたとき").performClick().assertIsOff()
            compose.onNodeWithText("バックグラウンド通知").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("バックグラウンド通知").performClick().assertIsOff()
            compose.activityRule.scenario.recreate()
            openInitiativeSettings()
            compose.onNodeWithText("会話を開いたとき").assertIsOff()
            compose.onNodeWithText("会話の合間").assertIsOn()
            compose.onNodeWithText("アプリを閉じているとき").assertIsOn()
            compose.onNodeWithText("バックグラウンド通知").assertIsOff()
            compose.activityRule.scenario.onActivity { activity ->
                activity.startActivity(android.content.Intent(activity, MainActivity::class.java)
                    .setAction(InitiativeWork.OPEN_CHAT))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription("メッセージ入力")).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            previousInitiative.forEach { (trigger, enabled) -> InitiativeWork.setEnabled(context, trigger, enabled) }
            InitiativeWork.setNotificationsEnabled(context, previousNotifications)
        }
    }

    @Test fun oldSignInResultCannotDiscardAnImageApiKeyDraft() {
        val page = openConnectionPage()
        val version = page.pageVersion
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("画像生成API").performClick()
        compose.onNodeWithContentDescription("画像生成用APIキー").performTextInput("sk-synthetic-unsaved-draft")
        completeSyntheticSignIn("以前の認証に対する合成エラー", version)
        compose.onNodeWithContentDescription("画像生成用APIキー").assertExists()
        compose.onNodeWithText("以前の認証に対する合成エラー", substring = true).assertDoesNotExist()
    }

    @Test fun managementCategoriesReturnToTheirParentAndPreserveTheChatDraft() {
        createManagementPerson("小春")
        compose.onNodeWithContentDescription("メッセージ入力").performTextInput("管理画面から戻る下書き")
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("管理").assertIsDisplayed()
        for (category in listOf("人物", "記憶", "設定", "バックアップ")) {
            compose.onNodeWithText(category).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("メッセージ入力").assertDoesNotExist()
        captureScreen("management-home.png")
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("会話と画像").assertIsDisplayed()
        compose.onNodeWithText("画像生成API").assertIsDisplayed()
        captureScreen("management-settings.png")
        compose.onNodeWithText("表示設定").performClick()
        compose.onNodeWithContentDescription("文字サイズ").assertExists()
        captureScreen("management-display.png")
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("画像生成API").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("記憶").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("短期記憶（直近30往復）")).fetchSemanticsNodes().isNotEmpty()
        }
        val recent = compose.onNodeWithText("短期記憶（直近30往復）").fetchSemanticsNode().boundsInRoot
        val current = compose.onNodeWithText("現在の状態").fetchSemanticsNode().boundsInRoot
        assertTrue(recent.top < current.top)
        compose.onNodeWithText("短期記憶（直近30往復）").performClick()
        compose.onNodeWithText("短期記憶の会話はまだありません。", substring = true).assertExists()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("中期記憶（0件）").assertExists()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("バックアップ").performClick()
        compose.onNodeWithText("端末からZIPを書き出す").assertIsDisplayed()
        captureScreen("management-backup.png")
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("メッセージ入力").assertTextContains("管理画面から戻る下書き")
    }

    @Test fun leavingLoadingMemoryDoesNotReopenItWhenTheReadCompletes() {
        createManagementPerson()
        val gate = MainActivity::class.java.getDeclaredField("storeGate").apply { isAccessible = true }.get(null)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = Thread {
            synchronized(gate) { entered.countDown(); release.await(15, java.util.concurrent.TimeUnit.SECONDS) }
        }
        blocking.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithContentDescription("メニュー").performClick()
            compose.onNodeWithText("記憶").performClick()
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithText("管理").assertIsDisplayed()
        } finally { release.countDown(); blocking.join(5_000) }
        val reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }
            .get(compose.activity) as java.util.concurrent.ExecutorService
        reader.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
        compose.waitForIdle()
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("短期記憶（直近30往復）").assertDoesNotExist()
    }

    @Test fun oldPersonsBackupFailureCannotReplaceTheCurrentPersonsStatus() {
        createManagementPerson()
        val people = PersonRegistry(context.filesDir)
        val other = people.stage(PersonDocument.named("別の合成人物"), null)
        try {
            val failed = MainActivity::class.java.getDeclaredMethod("syncFailed", PersonRegistry.Person::class.java,
                Exception::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            compose.onNodeWithContentDescription("メニュー").performClick()
            compose.onNodeWithText("バックアップ").performClick()
            compose.runOnIdle { failed.invoke(compose.activity, other, java.io.IOException("別人物の保存失敗"), true) }
            compose.waitForIdle()
            compose.onNodeWithText("別人物の保存失敗", substring = true).assertDoesNotExist()
            compose.onNodeWithText("この人物はGitHubに接続していません。", substring = true).assertExists()
            val status = MainActivity::class.java.getDeclaredField("gitStatus").apply { isAccessible = true }
            assertEquals("", status.get(compose.activity))
        } finally { people.discard(other) }
    }

    @Test fun historicalRebuildCompletionDoesNotReopenClosedMemory() {
        createManagementPerson()
        val person = PersonRegistry(context.filesDir).active()!!
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        add.invoke(null, "user".toByteArray(), "再整理を待つ合成会話".toByteArray())
        add.invoke(null, "assistant".toByteArray(), "会話の続き".toByteArray())
        compose.runOnUiThread {
            val connection = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(compose.activity)
            ChatGptConnection::class.java.getDeclaredField("state").apply { isAccessible = true }.set(connection,
                org.json.JSONObject().put("access_token", "synthetic-ui-only").put("refresh_token", "synthetic-ui-only")
                    .put("subject", "synthetic-ui-only").put("client_id", "synthetic-ui-only"))
        }
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("記憶").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("過去の記憶を階層に整理")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("過去の記憶を階層に整理").performClick()
        val worker = MainActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(compose.activity) as java.util.concurrent.ExecutorService
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        worker.submit { entered.countDown(); release.await(15, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithText("整理する").performClick()
            compose.onNodeWithContentDescription("戻る").performClick()
            compose.onNodeWithText("管理").assertIsDisplayed()
        } finally { release.countDown() }
        worker.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
        compose.waitForIdle()
        assertTrue(MemoryRebuild.pending(person.directory))
        compose.onNodeWithText("管理").assertIsDisplayed()
        compose.onNodeWithText("短期記憶（直近30往復）").assertDoesNotExist()
    }

    @Test fun profileShowsImportedAndUpdatedFactsWithTheirEvidence() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val markdown = PersonDocument.named(name).rawMarkdown.replace("- 名前: $name", "- 名前: $name\n- 身長: 165 cm")
        val document = PersonDocument.parse(markdown)
        val person = people.stage(document, null)
        assertEquals("", MainActivity.nativeSwitchPerson(person.directory.absolutePath.toByteArray(),
            document.importPayload(person.id).toString().toByteArray()))
        people.commit(person)
        compose.activityRule.scenario.recreate()
        val contextQuery = MainActivity::class.java.getDeclaredMethod("nativeContext", ByteArray::class.java).apply { isAccessible = true }
        val facts = org.json.JSONObject(contextQuery.invoke(null, byteArrayOf()) as String).getJSONArray("profile_facts")
        assertEquals("165 cm", facts.getJSONObject(0).getString("value"))
        val profile = "会話相手のプロフィールを開く"
        compose.onNodeWithContentDescription(profile).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("165 cm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("戻る").performClick()

        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        assertEquals(1L, add.invoke(null, "user".toByteArray(), "今も同じ？".toByteArray()))
        assertEquals(2L, add.invoke(null, "assistant".toByteArray(), "測り直したら166 cmだった".toByteArray()))
        assertEquals("", MainActivity.nativeApplyJob(1, """{"memories":[{"kind":"fact","actor":"assistant","subject":"friend","key":"profile/身長","text":"166 cm","scope":"shared","certainty":"explicit","source_ids":[2]}]}""".toByteArray()))
        compose.onNodeWithContentDescription(profile).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("166 cm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("身長").performClick()
        compose.onNodeWithText("測り直したら166 cmだった").assertIsDisplayed()
    }

    private fun openShortTermMemory() {
        val header = "会話相手のプロフィールを開く"
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(header) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(header).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("会話の記憶")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("会話の記憶").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("短期記憶（直近30往復）")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun confirmingHistoricalRebuildCreatesWorkAndShowsProgressWithoutChangingHistory() {
        assertTrue(!ChatGptConnection(context).hasSession())
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val person = people.stage(PersonDocument.named(name), null); people.commit(person)
        compose.activityRule.scenario.recreate()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        add.invoke(null, "user".toByteArray(), "京都旅行の初日はカフェに行った".toByteArray())
        add.invoke(null, "assistant".toByteArray(), "旅の話を聞いた".toByteArray())
        assertEquals("", MainActivity.nativeApplyJob(1, """{"memories":[],"continuity":{"threads":[],"episodes":[]}}""".toByteArray()))
        compose.activityRule.scenario.recreate()
        // Only the foreground guard sees a synthetic session; the worker remains signed out.
        compose.runOnUiThread {
            val connection = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(compose.activity)
            ChatGptConnection::class.java.getDeclaredField("state").apply { isAccessible = true }.set(connection,
                org.json.JSONObject().put("access_token", "synthetic-ui-only").put("refresh_token", "synthetic-ui-only")
                    .put("subject", "synthetic-ui-only").put("client_id", "synthetic-ui-only"))
        }
        openShortTermMemory()
        compose.onNodeWithText("過去の記憶を階層に整理").performClick()
        compose.onNodeWithText("整理する").assertIsDisplayed()
        assertTrue(!MemoryRebuild.pending(person.directory))
        compose.onNodeWithText("整理する").performClick()
        compose.waitUntil(10_000) { MemoryRebuild.pending(person.directory) }
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("過去の記憶を階層に整理中", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("過去の記憶を階層に整理中", substring = true).assertTextContains("0 / 2 発言", substring = true)
        val snapshot = org.json.JSONObject(MainActivity.nativeContext(byteArrayOf()))
        assertEquals(2L, snapshot.getJSONObject("memory_progress").getLong("total_messages"))
        assertEquals(0, snapshot.getJSONObject("continuity").getJSONArray("episodes").length())
    }

    @Test fun longPressSavesSceneAndMetaWithoutReplyAndRebuildProgressKeepsHistory() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val person = people.stage(PersonDocument.named(name), null); people.commit(person)
        compose.activityRule.scenario.recreate()
        val snapshot = MainActivity::class.java.getDeclaredMethod("nativeMessages", Int::class.javaPrimitiveType).apply { isAccessible = true }
        compose.onNodeWithContentDescription("メッセージ入力").performTextInput("雨が降り始め、二人は軒下にいる")
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("送信") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("送信").performTouchInput { longClick() }
        compose.onNodeWithText("情景として送る").performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("情景 · 返信なし")).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: AssertionError) {
            throw AssertionError("Note snapshot: ${snapshot.invoke(null, -1)}\nUI: ${compose.onRoot().printToString()}", failure)
        }
        compose.onNodeWithContentDescription("返答を入力中").assertDoesNotExist()
        compose.onNodeWithContentDescription("メッセージ入力").performTextInput("次は静かな雰囲気で進める")
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("送信") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("送信").performTouchInput { longClick() }
        compose.onNodeWithText("メタ発言として送る").performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("メタ · 返信なし")).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Exception) {
            throw AssertionError("Meta snapshot: ${snapshot.invoke(null, -1)}\nUI: ${compose.onRoot().printToString()}", failure)
        }
        val page = org.json.JSONObject(snapshot.invoke(null, -1) as String)
        assertEquals(2, page.getInt("total"))
        assertEquals("scene", page.getJSONArray("messages").getJSONObject(0).getString("annotation"))
        assertEquals("meta", page.getJSONArray("messages").getJSONObject(1).getString("annotation"))
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        add.invoke(null, "assistant".toByteArray(), "過去の合成返答".toByteArray())
        compose.activityRule.scenario.recreate()
        openShortTermMemory()
        compose.onNodeWithText("過去の記憶を階層に整理").assertExists()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("プロフィール").assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        MemoryRebuild(person.directory).request(3)
        openShortTermMemory()
        compose.onNodeWithText("過去の記憶を階層に整理中", substring = true).assertTextContains("0 / 3 発言", substring = true)
        assertEquals(3, org.json.JSONObject(snapshot.invoke(null, -1) as String).getInt("total"))
    }

    @Test fun paidImageApiHasItsOwnSettingsAndMaskedKeyField() {
        val keys = ConnectionStore(context, "image-api")
        val saved = keys.load()
        val subscription = ConnectionStore(context).load().toString()
        val fakeKey = "sk-synthetic_key_for_settings_test_only"
        try {
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("設定").performClick()
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
        compose.onNodeWithText("画像生成API").performClick()
        compose.onNodeWithContentDescription("画像生成用APIキー").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("APIキーを削除").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("画像生成用APIキーを削除しました。", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(!keys.load().has("api_key"))
        assertEquals(subscription, ConnectionStore(context).load().toString())
        } finally { if (saved.length() == 0) keys.delete() else keys.save(saved) }
    }

    @Test fun failedDraftSaveKeepsCurrentPersonAndEditor() {
        createManagementPerson()
        compose.waitUntil(10_000) { !MemoryWork.isRunning() }
        val currentField=MainActivity::class.java.getDeclaredField("activePerson").apply { isAccessible=true }
        val first=currentField.get(compose.activity) as PersonRegistry.Person
        val registry=MainActivity::class.java.getDeclaredField("people").apply { isAccessible=true }.get(compose.activity) as PersonRegistry
        val second=registry.stage(PersonDocument.named("Switch failure fixture-${UUID.randomUUID()}"),null)
        registry.commit(second);registry.activate(first.id)
        val ui=MainActivity::class.java.getDeclaredField("compose").apply { isAccessible=true }.get(compose.activity) as ComposeUi
        val attachment=AttachmentFiles.save(first.directory,"draft.txt","txt",java.io.ByteArrayInputStream("draft attachment".toByteArray()))
        val blocked=java.io.File(first.directory,"state.json.tmp")
        try {
            compose.onNodeWithContentDescription("メッセージ入力").performTextInput("unsaved transition canary")
            compose.runOnUiThread { ui.attachments=attachment.markdown() }
            check(blocked.mkdir());java.io.File(blocked,"hold").writeText("fixture")
            val switch=MainActivity::class.java.getDeclaredMethod("switchPerson",PersonRegistry.Person::class.java).apply { isAccessible=true }
            val executor=MainActivity::class.java.getDeclaredField("worker").apply { isAccessible=true }.get(compose.activity) as java.util.concurrent.ExecutorService
            compose.runOnUiThread { switch.invoke(compose.activity,second) }
            try { executor.submit {}.get(10,java.util.concurrent.TimeUnit.SECONDS) } catch(_:java.util.concurrent.CancellationException) { }
            compose.waitForIdle()
            assertEquals(first.id,PersonRegistry(context.filesDir).active()!!.id)
            assertEquals("unsaved transition canary",ui.draft)
            assertEquals(attachment.markdown(),ui.attachments)
        } finally {
            java.io.File(blocked,"hold").delete();blocked.delete()
            val latest=PersonRegistry(context.filesDir)
            if(latest.list().any { it.id==second.id }) latest.delete(second.id)
            (context.applicationContext as ConversationApplication).deleteKeys(second.id)
        }
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
        val bitmap = android.graphics.Bitmap.createBitmap(64, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"fixture.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ConversationTest-${java.util.UUID.randomUUID()}")
        }
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        try {
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
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
        } finally { context.contentResolver.delete(uri,null,null) }
    }

    @Test fun savedReplyKeepsItsTextWhileSnapshotAndPageChangeArePending() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        previousTextSize = preferences.getInt("text_size", 16)
        previousGap = preferences.getInt("message_gap", 12)
        val person = people.stage(PersonDocument.named(name), null); people.commit(person)
        compose.activityRule.scenario.recreate()
        val activity = compose.activity
        val ui = MainActivity::class.java.getDeclaredField("compose").apply { isAccessible = true }.get(activity) as ComposeUi
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        val page = MainActivity::class.java.getDeclaredField("pageFirst").apply { isAccessible = true }
        val busy = MainActivity::class.java.getDeclaredMethod("setBusy", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val refresh = MainActivity::class.java.getDeclaredMethod("refreshMessages", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val reader = MainActivity::class.java.getDeclaredField("reader").apply { isAccessible = true }.get(activity) as java.util.concurrent.ExecutorService
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.CountDownLatch(1)
        try {
            compose.runOnUiThread {
                add.invoke(null, "user".toByteArray(), "こんにちは".toByteArray())
                ui.setMessages("""{"first":0,"total":1,"messages":[{"id":1,"role":"user","text":"こんにちは"}]}""")
                busy.invoke(activity, true); ui.beginReply(); ui.streaming = "返答です"
            }
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("返答です")).fetchSemanticsNodes().isNotEmpty() }
            val body = compose.onNodeWithText("返答です").fetchSemanticsNode().id
            reader.execute { blocked.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertTrue(blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnUiThread {
                add.invoke(null, "assistant".toByteArray(), "返答です".toByteArray())
                page.setInt(activity, 0); refresh.invoke(activity, true); page.setInt(activity, -1)
            }
            compose.onNodeWithText("返答です").assertIsDisplayed()
            compose.runOnIdle { assertTrue(ui.sending); assertEquals("返答です", ui.streaming) }
            release.countDown()
            compose.waitUntil(10_000) { !ui.sending && ui.streaming.isEmpty() }
            assertEquals(body, compose.onNodeWithText("返答です").fetchSemanticsNode().id)
        } finally { release.countDown() }
    }

    @Test fun replyImmediatelyUpdatesMemoryDetailsBeforeExtractionAndSurvivesRecreation() {
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
            compose.onAllNodes(androidx.compose.ui.test.hasText("やったね！")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("嬉しい").assertIsDisplayed()
        openShortTermMemory()
        for (record in listOf("感情", "嬉しい", "理由", "勝ったと聞いた", "状況", "試合結果を一緒に喜んだ")) {
            compose.onNodeWithText(record).assertExists()
        }
        compose.activityRule.scenario.recreate()
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
        people.setDisplayName(person.id, "小春")
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
            val episodes = org.json.JSONArray()
            for (i in 1..30) episodes.put(org.json.JSONObject().put("key", "scene-$i")
                .put("text", "場面${i}の出来事").put("scene", "${i}日目のカフェ")
                .put("emotion", "そのときは安心した").put("scope", "shared")
                .put("source_ids", org.json.JSONArray().put(1).put(2)))
            episodes.getJSONObject(29).put("children", org.json.JSONArray().put(org.json.JSONObject()
                .put("key", "day1").put("text", "旅の初日の出来事").put("scene", "1日目")
                .put("emotion", "").put("scope", "shared").put("source_ids", org.json.JSONArray().put(1))
                .put("children", org.json.JSONArray().put(org.json.JSONObject().put("key", "morning")
                    .put("text", "青葉珈琲で悩みを話した").put("scene", "朝のカフェ").put("emotion", "安心した")
                    .put("scope", "shared").put("source_ids", org.json.JSONArray().put(1))))))
            val state = org.json.JSONObject("""{"threads":[],"episodes":[],"assistant_state":{"emotion":"ほっとして嬉しい","reason":"試合終了の報告","event":"一緒に喜んだ会話","scope":"shared","source_ids":[1,2]}}""")
                .put("episodes", episodes)
            val payload = org.json.JSONObject().put("memories", org.json.JSONArray()).put("continuity", state)
            assertEquals("", apply.invoke(null, 1L, payload.toString().toByteArray()))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("ほっとした。一緒に喜べて嬉しい")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("小春のプロフィールを見る").assertIsDisplayed()
        compose.onNodeWithText("ほっとして嬉しい").assertIsDisplayed()
        compose.onNodeWithText("試合終了の報告").assertDoesNotExist()
        compose.onNodeWithText("一緒に喜んだ会話").assertDoesNotExist()
        openShortTermMemory()
        compose.onNodeWithText("記憶").assertExists()
        for (record in listOf("感情", "ほっとして嬉しい", "理由", "試合終了の報告", "状況", "一緒に喜んだ会話")) {
            compose.onNodeWithText(record).assertExists()
        }
        compose.onNodeWithText("会話の記憶整理").assertExists()
        compose.onNodeWithText("2 / 2 発言", substring = true).assertExists()
        captureScreen("memory-overview.png")
        compose.onNodeWithText("短期記憶（直近30往復）").performClick()
        compose.onNodeWithText("短期記憶").assertExists()
        compose.onNodeWithText("あなた").assertExists()
        compose.onNodeWithText("試合が終わったよ").assertExists()
        compose.onNodeWithText("ほっとした。一緒に喜べて嬉しい").assertExists()
        captureScreen("memory-recent.png")
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("記憶")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("中期記憶（30件）").performClick()
        compose.onNodeWithText("中期記憶").assertExists()
        compose.onNodeWithText("30日目のカフェ").assertExists()
        compose.onNodeWithText("場面30の出来事", substring = true).assertTextContains("そのときは安心した", substring = true)
            .assertTextContains("会話内の体験", substring = true)
        compose.onNodeWithText("30日目のカフェ", substring = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("1日目")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1日目").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("朝のカフェ")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("朝のカフェ").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("青葉珈琲で悩みを話した", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("青葉珈琲で悩みを話した", substring = true).assertTextContains("安心した", substring = true)
        repeat(3) { compose.onNodeWithContentDescription("戻る").performClick(); compose.waitForIdle() }
        captureScreen("memory-scenes.png")
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("記憶")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("試合終了の報告", substring = true).assertDoesNotExist()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java)
            .apply { isAccessible = true }
        for (i in 0..31) {
            add.invoke(null, "user".toByteArray(), "recent-user-$i".toByteArray())
            add.invoke(null, "assistant".toByteArray(), "recent-reply-$i".toByteArray())
        }
        compose.activityRule.scenario.recreate()
        openShortTermMemory()
        compose.onNodeWithText("残り64発言", substring = true).assertExists()
        compose.onNodeWithText("短期記憶（直近30往復）").performClick()
        compose.onNodeWithText("recent-user-31").assertExists()
        compose.onNodeWithText("recent-reply-1").assertExists()
        compose.onNodeWithText("recent-user-1").assertDoesNotExist()
        compose.onNodeWithText("recent-user-0").assertDoesNotExist()
        compose.onNodeWithText("試合が終わったよ", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("記憶")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("戻る").performClick()
        context.getSharedPreferences("MainActivity", android.content.Context.MODE_PRIVATE).edit().putBoolean("memory_paused", true).commit()
        compose.activityRule.scenario.recreate()
        openShortTermMemory()
        compose.onNodeWithText("記憶の整理を続ける").assertDoesNotExist()
        compose.onNodeWithText("この整理のあと一時停止").assertDoesNotExist()
        assertTrue(org.json.JSONObject(MainActivity.nativePendingJob()).getLong("id") > 0)
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithContentDescription("メニュー").performClick()
        compose.onNodeWithText("人物").performClick()
        compose.onNodeWithText("人物アイコンを設定").performClick()
        compose.onNodeWithText("写真・画像から選ぶ").assertExists()
        compose.onNodeWithText("アイコン画像を外す").performClick()
        compose.waitUntil(10_000) { PersonRegistry(context.filesDir).icon(person.id).isEmpty() }
        repeat(3) { compose.onNodeWithContentDescription("戻る").performClick() }
        compose.onNodeWithText("ほっとして嬉しい").assertIsDisplayed()
        openShortTermMemory()
        compose.onNodeWithText("ほっとして嬉しい").assertExists()
    }

    @Test fun memoryStoreFinishesAfterTheActivityIsDestroyed() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        compose.activityRule.scenario.recreate()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        add.invoke(null, "user".toByteArray(), "カフェで旅の相談をした".toByteArray())
        add.invoke(null, "assistant".toByteArray(), "予算を決めよう".toByteArray())
        val store = MemoryStore(context, person.id)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        Thread {
            try {
                ChatSession(store).processMemories({ instructions, _, _ ->
                    assertEquals(ChatSession.EXTRACT, instructions)
                    started.countDown()
                    assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    """{"memories":[],"continuity":{"threads":[],"episodes":[{"key":"cafe","text":"カフェで旅の予算を相談した","emotion":"","scene":"カフェ、旅行相談","scope":"shared","source_ids":[1,2]}]}}"""
                }, 1)
            } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }.start()
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.activityRule.scenario.close()
        } finally { release.countDown() }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Background memory stopped with the Activity", it) }
        val snapshot = store.context("")
        assertEquals(2, snapshot.getJSONObject("memory_progress").getInt("processed_messages"))
        assertEquals("カフェで旅の予算を相談した", snapshot.getJSONObject("continuity").getJSONArray("episodes").getJSONObject(0).getString("text"))
    }

    @Test fun openMemoryReflectsBackgroundCommitWithoutReopening() {
        val people = PersonRegistry(context.filesDir)
        previousPerson = people.active()?.id
        val person = people.stage(PersonDocument.named(name), null)
        people.commit(person)
        compose.activityRule.scenario.recreate()
        val add = MainActivity::class.java.getDeclaredMethod("nativeAddMessage", ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        add.invoke(null, "user".toByteArray(), "家で相談した".toByteArray())
        add.invoke(null, "assistant".toByteArray(), "話を聞いた".toByteArray())
        compose.activityRule.scenario.recreate()
        openShortTermMemory()
        compose.onNodeWithText("中期記憶（0件）").assertExists()
        val store = MemoryStore(context, person.id)
        ChatSession(store).processMemories({ _, _, _ ->
            """{"memories":[],"continuity":{"threads":[],"episodes":[{"key":"home","text":"家で相談した","emotion":"","scene":"家、相談","scope":"shared","source_ids":[1,2]}]}}"""
        }, 1)
        MemoryWork.enqueue(context)
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("中期記憶（1件）")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("会話の記憶整理").assertExists()
        compose.onNodeWithText("2 / 2 発言", substring = true).assertExists()
    }

    @Test fun backgroundAndReplyConnectionsReloadTheLatestCredentials() {
        val saved = ConnectionStore(context).load()
        try {
            val background = ChatGptConnection(context)
            val reply = MainActivity::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(compose.activity)
            val current = org.json.JSONObject(saved.toString()).put("access_token", "synthetic-rotated-token")
            ConnectionStore(context).save(current)
            val reload = ChatGptConnection::class.java.getDeclaredMethod("reloadState").apply { isAccessible = true }
            val snapshot = ChatGptConnection::class.java.getDeclaredMethod("snapshot").apply { isAccessible = true }
            for (connection in listOf(reply, background)) {
                reload.invoke(connection)
                assertEquals("synthetic-rotated-token", (snapshot.invoke(connection) as org.json.JSONObject).getString("access_token"))
            }
        } finally { ConnectionStore(context).save(saved) }
    }

    @Test fun personHeaderShowsEmptyShortTermMemoryWithoutInventingDetails() {
        createManagementPerson()
        openShortTermMemory()
        assertEquals(3, compose.onAllNodes(androidx.compose.ui.test.hasText("未記録")).fetchSemanticsNodes().size)
        compose.onNodeWithText("中期記憶（0件）").performClick()
        compose.onNodeWithText("中期記憶はまだありません。", substring = true).assertExists()
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
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("表示設定").performClick()
        compose.onNodeWithContentDescription("文字サイズ")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(22f) }
        compose.onNodeWithContentDescription("メッセージの間隔")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        compose.onNodeWithContentDescription("戻る").performClick()
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
