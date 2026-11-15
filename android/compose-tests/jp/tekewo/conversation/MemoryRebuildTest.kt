package jp.tekewo.conversation

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryRebuildTest {
    private fun node(key: String, scene: String, source: Int) = JSONObject().put("key", key).put("scene", scene)
        .put("text", "$scene の出来事").put("emotion", "").put("scope", "shared").put("source_ids", JSONArray().put(source))
    private fun result(root: JSONObject) = JSONObject().put("memories", JSONArray()).put("continuity",
        JSONObject().put("threads", JSONArray()).put("episodes", JSONArray().put(root))).toString()

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
                override fun publish(): Boolean = SQLiteDatabase.openDatabase(File(directory, MemoryRebuild.FILE).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
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
            SQLiteDatabase.openDatabase(File(directory, MemoryRebuild.FILE).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
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
            SQLiteDatabase.openOrCreateDatabase(File(directory, MemoryRebuild.FILE), null).close()
            rebuild.request(2)
            assertTrue(rebuild.status().contains("0 / 2"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun committedRebuildIsCleanedBeforeNewMessagesCanChangeItsRequest() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rebuild-committed-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rebuild = MemoryRebuild(directory); rebuild.request(2)
            SQLiteDatabase.openDatabase(File(directory, MemoryRebuild.FILE).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE meta SET value='2' WHERE key='cursor'")
            }
            val source = object : MemoryRebuild.Native {
                override fun context() = JSONObject().put("memory_progress", JSONObject().put("total_messages", 3))
                override fun batch(after: Long, through: Long): JSONObject = throw AssertionError("Committed work must not read or replay newer messages")
                override fun publish(): Boolean {
                    SQLiteDatabase.openDatabase(File(directory, MemoryRebuild.FILE).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
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
