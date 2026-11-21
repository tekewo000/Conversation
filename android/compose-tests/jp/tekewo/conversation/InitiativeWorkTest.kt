package jp.tekewo.conversation

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import jp.tekewo.conversation.InitiativePolicy.Trigger

@RunWith(AndroidJUnit4::class)
class InitiativeWorkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun firstAssistantMessagePersistsAndReopeningDoesNotRepeatIt() = withPerson { person ->
        assertTrue(InitiativeWork.attempt(context, person.id, InitiativePolicy.Trigger.OPEN, { true }) { _, _, _ ->
            """{"message":"ねえ、少し話したいことがあるんだ。"}"""
        })

        assertEquals("", MainActivity.nativeSwitchPerson(byteArrayOf(), byteArrayOf()))
        val store = MemoryStore(context, person.id)
        val messages = store.context("").getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("assistant", messages.getJSONObject(0).getString("role"))
        assertEquals("ねえ、少し話したいことがあるんだ。", messages.getJSONObject(0).getString("content"))
        assertEquals(0L, PersonState.load(person.directory).getLong(InitiativePolicy.SENT_THROUGH))

        assertFalse(InitiativeWork.attempt(context, person.id, InitiativePolicy.Trigger.OPEN, { true }) { _, _, _ ->
            throw AssertionError("Reopening immediately must not request another opener")
        })
        assertEquals(1, store.context("").getJSONArray("messages").length())
    }

    @Test fun userMessageDuringGenerationPreventsStaleAssistantMessage() = withPerson { person ->
        assertFalse(InitiativeWork.attempt(context, person.id, InitiativePolicy.Trigger.OPEN, { true }) { _, _, _ ->
            synchronized(MainActivity.storeGate) {
                assertTrue(MainActivity.nativeAddMessage("user".toByteArray(), "先に話しかけたよ".toByteArray()) > 0)
            }
            """{"message":"すれ違った話題"}"""
        })

        val messages = MemoryStore(context, person.id).context("").getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("先に話しかけたよ", messages.getJSONObject(0).getString("content"))
        assertFalse(PersonState.load(person.directory).has(InitiativePolicy.SENT_THROUGH))
    }

    @Test fun legacySettingIsInheritedAndAnExplicitOffOnlyBlocksThatTrigger() = withPerson { person ->
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val keys = Trigger.values().map { it.preference } + "initiative_enabled"
        val previous = keys.associateWith { preferences.all[it] as? Boolean }
        try {
            val fixture = preferences.edit().putBoolean("initiative_enabled", true)
            Trigger.values().forEach { fixture.remove(it.preference) }
            fixture.commit()
            Trigger.values().forEach { assertTrue(InitiativeWork.enabled(context, it)) }
            InitiativeWork.setEnabled(context, Trigger.OPEN, false)
            assertFalse(InitiativeWork.attempt(context, person.id, Trigger.OPEN, { true }) { _, _, _ ->
                throw AssertionError("Disabled opening must not call the model")
            })
            assertTrue(InitiativeWork.enabled(context, Trigger.IDLE))
            assertTrue(InitiativeWork.attempt(context, person.id, Trigger.BACKGROUND, { true }) { _, _, _ ->
                """{"message":"また話したいことがあるんだ。"}"""
            })
        } finally {
            val restore = preferences.edit()
            previous.forEach { (key, value) -> if (value == null) restore.remove(key) else restore.putBoolean(key, value) }
            restore.commit()
        }
    }

    @Test fun backgroundNotificationHonorsItsSwitchAndHidesTheConversation() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun posted() = manager.activeNotifications.firstOrNull { it.notification.channelId == "initiative-messages" }
        val enabled = InitiativeWork.enabled(context, Trigger.BACKGROUND)
        val notifications = InitiativeWork.notificationsEnabled(context)
        try {
            InitiativeWork.setEnabled(context, Trigger.BACKGROUND, true)
            InitiativeWork.setNotificationsEnabled(context, false)
            InitiativeWork.notifyBackgroundMessage(context) { true }
            assertEquals(null, posted())

            InitiativeWork.setNotificationsEnabled(context, true)
            InitiativeWork.notifyBackgroundMessage(context) { false }
            assertEquals(null, posted())
            InitiativeWork.notifyBackgroundMessage(context) { true }
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (posted() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            val notice = requireNotNull(posted()).notification
            assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
            assertEquals("新しいメッセージが届きました", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(context.packageName, requireNotNull(notice.contentIntent).creatorPackage)
            InitiativeWork.setEnabled(context, Trigger.BACKGROUND, false)
            assertTrue(InitiativeWork.notificationsEnabled(context))
            InitiativeWork.notifyBackgroundMessage(context) { true }
            val canceledDeadline = SystemClock.uptimeMillis() + 3_000
            while (posted() != null && SystemClock.uptimeMillis() < canceledDeadline) SystemClock.sleep(50)
            assertEquals(null, posted())
        } finally {
            InitiativeWork.setNotificationsEnabled(context, false)
            InitiativeWork.setNotificationsEnabled(context, notifications)
            InitiativeWork.setEnabled(context, Trigger.BACKGROUND, enabled)
        }
    }

    private fun withPerson(test: (PersonRegistry.Person) -> Unit) {
        DataMigration.ensure(context)
        System.loadLibrary("conversation")
        val people = PersonRegistry(context.filesDir)
        val previousPerson = people.active()
        val settings = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val previousSetting = settings.all[Trigger.OPEN.preference] as? Boolean
        val person = people.stage(PersonDocument.named("InitiativeProbe-${UUID.randomUUID()}"), null)
        people.commit(person)
        try {
            InitiativeWork.setEnabled(context, Trigger.OPEN, true)
            test(person)
        } finally {
            val restore = settings.edit()
            if (previousSetting == null) restore.remove(Trigger.OPEN.preference)
            else restore.putBoolean(Trigger.OPEN.preference, previousSetting)
            restore.commit()
            synchronized(MainActivity.storeGate) {
                MainActivity.nativeSwitchPerson(byteArrayOf(), byteArrayOf())
                people.activate(previousPerson?.id ?: "")
                people.delete(person.id)
                (context.applicationContext as ConversationApplication).deleteKeys(person.id)
            }
        }
    }
}
