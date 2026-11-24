package jp.tekewo.conversation

import net.zetetic.database.sqlcipher.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MemoryRebuildTest {
    private fun node(key: String, scene: String, source: Int) = JSONObject().put("key", key).put("scene", scene)
        .put("text", "$scene の出来事").put("emotion", "").put("scope", "shared").put("source_ids", JSONArray().put(source))
    private fun result(root: JSONObject) = JSONObject().put("memories", JSONArray()).put("continuity",
        JSONObject().put("threads", JSONArray()).put("episodes", JSONArray().put(root))).toString()

    @Test fun profileRebuildResumesAndRejectsAnUnsupportedSource() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "profile-rebuild-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val first = MemoryRebuild(directory)
            first.request(4, true)
            var published: JSONObject? = null
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 4))
                override fun batch(after: Long, through: Long): JSONObject {
                    val end = minOf(through, after + 2)
                    val messages = JSONArray()
                    for (id in after + 1..end) messages.put(JSONObject().put("id", id)
                        .put("role", if (id % 2L == 0L) "assistant" else "user").put("content", "原文$id"))
                    return JSONObject().put("messages", messages).put("through_id", end).put("boundary_id", through)
                }
                override fun publish(): Boolean = throw AssertionError("Profile rebuild must not publish episodes")
                override fun publishProfile(through: Long, profile: JSONObject): Boolean {
                    assertEquals(4L, through)
                    published = profile
                    return true
                }
            }
            assertTrue(first.step(source) { _, _, _ -> """{"profile":{"趣味":{"value":"読書","source_id":2}}}""" })
            val resumed = MemoryRebuild(directory)
            assertTrue(resumed.status().contains("2 / 4"))
            assertThrows(java.io.IOException::class.java) {
                resumed.step(source) { _, _, _ -> """{"profile":{"体重":{"value":"60 kg","source_id":3}}}""" }
            }
            assertTrue(resumed.status().contains("2 / 4"))
            assertTrue(resumed.step(source) { _, input, _ ->
                val data = JSONObject(input.getJSONObject(0).getString("content"))
                assertEquals("読書", data.getJSONObject("profile").getJSONObject("趣味").getString("value"))
                """{"profile":{"趣味":{"value":"読書、陶芸","source_id":4}}}"""
            })
            assertFalse(resumed.step(source) { _, _, _ -> throw AssertionError("Saved work should publish without another request") })
            assertEquals("読書、陶芸", published!!.getJSONObject("趣味").getString("value"))
            assertFalse(resumed.isPending())
        } finally { directory.deleteRecursively() }
    }

    @Test fun deltaUpdatesAndEmptyBatchesKeepPreviouslySavedExperiences() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-delta-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory); rebuild.request(6)
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 6))
                override fun batch(after: Long, through: Long): JSONObject {
                    val end = minOf(through, after + 2)
                    val messages = JSONArray()
                    for (id in after + 1..end) messages.put(JSONObject().put("id", id).put("role", "user").put("content", "原文$id"))
                    return JSONObject().put("messages", messages).put("through_id", end).put("boundary_id", through)
                }
                override fun publish(): Boolean = false
            }
            val first = JSONObject(result(node("trip", "旅行", 1)))
            first.getJSONObject("continuity").getJSONArray("episodes").put(node("consultation", "相談", 1))
            rebuild.step(source) { _, _, _ -> first.toString() }
            rebuild.step(source) { _, _, _ -> result(node("trip", "旅行の続き", 3)) }
            rebuild.step(source) { _, _, _ -> """{"memories":[],"continuity":{"threads":[],"episodes":[]}}""" }
            EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),true).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key='roots'", null).use { rows ->
                    rows.moveToFirst(); val roots = JSONArray(rows.getString(0)); assertEquals(2, roots.length())
                    assertEquals("旅行の続き", roots.getJSONObject(0).getString("scene"))
                    assertEquals("相談", roots.getJSONObject(1).getString("scene"))
                }
            }
            assertTrue(rebuild.status().contains("6 / 6"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun olderRootDetailsSurviveWhenOverviewIsLimitedToThirty() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-root-cap-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory); rebuild.request(1)
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 1))
                override fun batch(after: Long, through: Long) = JSONObject().put("messages", JSONArray().put(JSONObject().put("id", 1).put("role", "user").put("content", "三十一回の旅の話")))
                    .put("through_id", 1).put("boundary_id", through)
                override fun publish(): Boolean = false
            }
            val payload = JSONObject(result(node("trip-0", "旅0", 1)))
            for (index in 1..30) payload.getJSONObject("continuity").getJSONArray("episodes").put(node("trip-$index", "旅$index", 1))
            rebuild.step(source) { _, _, _ -> payload.toString() }
            EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),true).use { db ->
                db.rawQuery("SELECT value FROM meta WHERE key='roots'", null).use { rows -> rows.moveToFirst(); assertEquals(30, JSONArray(rows.getString(0)).length()) }
                db.rawQuery("SELECT count(*) FROM nodes", null).use { rows -> rows.moveToFirst(); assertEquals(31, rows.getInt(0)) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun longHistoryStartsWithASmallBatchAndReportsTheInFlightStage() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-bounded-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory); rebuild.request(130)
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 130))
                override fun batch(after: Long, through: Long): JSONObject {
                    val messages = JSONArray()
                    for (id in after + 1..through) messages.put(JSONObject().put("id", id).put("role", "user").put("content", "原文$id"))
                    return JSONObject().put("messages", messages).put("through_id", through).put("boundary_id", through)
                }
                override fun publish(): Boolean = throw AssertionError("An incomplete rebuild must not publish")
            }
            assertTrue(rebuild.step(source) { _, input, delta ->
                val data = JSONObject(input.getJSONObject(0).getString("content"))
                assertEquals(64, data.getJSONObject("job").getJSONArray("messages").length())
                assertTrue(rebuild.status().contains("1〜64"))
                delta.accept("応答の一部")
                assertTrue(rebuild.status().contains("受信"))
                result(node("trip", "旅行", 1))
            })
            assertTrue(rebuild.status().contains("64 / 130"))
            rebuild.failed("検証用エラー")
            assertTrue(rebuild.status().contains("再試行待ち"))
            assertTrue(rebuild.status().contains("検証用エラー"))
            assertTrue(rebuild.status().contains("64 / 130"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun resumingAnExplicitRebuildDoesNotStayBehindOldBackoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(ChatGptConnection(context).hasSession())
        val manager = androidx.work.WorkManager.getInstance(context)
        val delayed = androidx.work.OneTimeWorkRequest.Builder(MemoryWork::class.java).setInitialDelay(1, TimeUnit.HOURS).build()
        try {
            manager.enqueueUniqueWork(MemoryWork.NAME, androidx.work.ExistingWorkPolicy.REPLACE, delayed).result.get(5, TimeUnit.SECONDS)
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, manager.getWorkInfoById(delayed.id).get(5, TimeUnit.SECONDS)!!.state)
            MemoryWork.resume(context)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var blocked: Boolean
            do {
                val old = manager.getWorkInfoById(delayed.id).get(5, TimeUnit.SECONDS)
                blocked = old != null && !old.state.isFinished
                if (blocked) Thread.sleep(20)
            } while (blocked && android.os.SystemClock.uptimeMillis() < deadline)
            assertFalse(blocked)
            assertTrue(manager.getWorkInfosForUniqueWork(MemoryWork.NAME).get(5, TimeUnit.SECONDS)
                .any { it.id != delayed.id && it.state != androidx.work.WorkInfo.State.BLOCKED })
        } finally { manager.cancelUniqueWork(MemoryWork.NAME).result.get(5, TimeUnit.SECONDS) }
    }

    @Test fun newConversationRecordsAreNotBlockedByMaintenanceBackoff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(ChatGptConnection(context).hasSession())
        val manager = androidx.work.WorkManager.getInstance(context)
        val delayed = androidx.work.OneTimeWorkRequest.Builder(MemoryWork::class.java).setInitialDelay(1, TimeUnit.HOURS).build()
        try {
            manager.enqueueUniqueWork(MemoryWork.NAME, androidx.work.ExistingWorkPolicy.REPLACE, delayed).result.get(5, TimeUnit.SECONDS)
            MemoryWork.enqueue(context)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var records: List<androidx.work.WorkInfo>
            do {
                records = manager.getWorkInfosForUniqueWork("conversation-records").get(5, TimeUnit.SECONDS)
                if (records.isEmpty()) Thread.sleep(20)
            } while (records.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline)
            assertTrue(records.isNotEmpty() && records.none { it.state == androidx.work.WorkInfo.State.BLOCKED })
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, manager.getWorkInfoById(delayed.id).get(5, TimeUnit.SECONDS)!!.state)
        } finally {
            manager.cancelUniqueWork(MemoryWork.NAME).result.get(5, TimeUnit.SECONDS)
            manager.cancelUniqueWork("conversation-records").result.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun stagedBranchesSurviveRestartAndOmittedChildrenAndInvalidOutputRollsBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "rebuild-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 4L
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", total))
                override fun batch(after: Long, through: Long): JSONObject {
                    val end = minOf(through, after + 2)
                    val messages = JSONArray()
                    for (id in after + 1..end) messages.put(JSONObject().put("id", id).put("role", if (id % 2L == 1L) "user" else "assistant")
                        .put("content", "京都旅行の原文$id").put("created_at", "2026-11-15T00:00:00Z"))
                    return JSONObject().put("messages", messages).put("through_id", end).put("boundary_id", through)
                }
                override fun publish(): Boolean = EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),true).use { db ->
                    db.rawQuery("SELECT value FROM meta WHERE key='boundary'", null).use { rows -> rows.moveToFirst(); rows.getString(0).toLong() == total }
                }
            }
            val rebuild = MemoryRebuild(directory)
            rebuild.request(4)
            assertTrue(MemoryRebuild.pending(directory))
            val root = node("trip", "京都旅行", 1).put("children", JSONArray().put(node("day1", "1日目", 1)))
            assertTrue(rebuild.step(source) { _, _, _ -> result(root) })
            assertTrue(MemoryRebuild(directory).status().contains("2 / 4"))
            val bad = node("trip", "京都旅行", 999)
            try {
                MemoryRebuild(directory).step(source) { _, _, _ -> result(bad) }
                fail("Invented evidence must not advance a rebuild")
            } catch (expected: java.io.IOException) { }
            assertTrue(MemoryRebuild(directory).status().contains("2 / 4"))
            val revised = node("trip", "京都旅行", 3)
            assertTrue(MemoryRebuild(directory).step(source) { _, _, _ -> result(revised) })
            EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),true).use { db ->
                db.rawQuery("SELECT count(*) FROM nodes WHERE key='day1'", null).use { rows -> rows.moveToFirst(); assertEquals(1, rows.getInt(0)) }
            }
            total = 6
            assertTrue(MemoryRebuild(directory).step(source) { _, _, _ -> result(node("trip", "京都旅行", 5)) })
            assertFalse(MemoryRebuild(directory).step(source) { _, _, _ -> throw AssertionError("Finished work must not call the model") })
            assertFalse(MemoryRebuild.pending(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun statusDoesNotRecreateDeletedCacheAndIncompleteCacheCanBeRequestedAgain() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory)
            rebuild.request(2)
            assertTrue(MemoryRebuild.pending(directory))
            SQLiteDatabase.deleteDatabase(File(directory, MemoryRebuild.FILE))
            assertEquals("", rebuild.status())
            assertFalse(MemoryRebuild.pending(directory))
            EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),false).close()
            rebuild.request(2)
            assertTrue(rebuild.status().contains("0 / 2"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun committedRebuildIsCleanedBeforeNewMessagesCanChangeItsRequest() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-committed-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory); rebuild.request(2)
            EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),false).use { db ->
                db.execSQL("UPDATE meta SET value='2' WHERE key='cursor'")
            }
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 3))
                override fun batch(after: Long, through: Long): JSONObject = throw AssertionError("Committed work must not read or replay newer messages")
                override fun publish(): Boolean {
                    EncryptedDatabase.open(directory,File(directory,MemoryRebuild.FILE),true).use { db ->
                        db.rawQuery("SELECT value FROM meta WHERE key='boundary'", null).use { rows -> rows.moveToFirst(); assertEquals("2", rows.getString(0)) }
                    }
                    return true
                }
            }
            assertFalse(rebuild.step(source) { _, _, _ -> throw AssertionError("Committed work must not call a model again") })
            assertFalse(MemoryRebuild.pending(directory))
        } finally { directory.deleteRecursively() }
    }
}
