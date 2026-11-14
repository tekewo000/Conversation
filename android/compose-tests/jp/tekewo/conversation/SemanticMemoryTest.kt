package jp.tekewo.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SemanticMemoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun localJapaneseEmbeddingAndUSearchRankAParaphrase() {
        val files = RagModels.ensure(context) { }
        LocalEmbedder(files).use { embedder ->
            assertArrayEquals(longArrayOf(1,3481,46843,287,271,25467,49579,271,255,174,167,143,2),
                NativeRag.tokenize(embedder.tokenizer, "検索クエリ: 雨の日の過ごし方 😀".toByteArray(StandardCharsets.UTF_8)))
            val index = NativeRag.openIndex()
            try {
                NativeRag.put(index, 1, embedder.embed("検索文書: 雨が強い日は外出を控えて家で過ごすのが好きです。"))
                NativeRag.put(index, 2, embedder.embed("検索文書: 卵と小麦粉でケーキを作るのが趣味です。"))
                val query = embedder.embed("検索クエリ: 土砂降りのときはどうする？")
                assertEquals(256, query.size)
                assertEquals(1.0, query.sumOf { it.toDouble()*it }, 0.0001)
                assertEquals(1L, NativeRag.search(index, query)[0])
                NativeRag.remove(index, 1)
                assertFalse(NativeRag.search(index, query).contains(1))
            } finally { NativeRag.closeIndex(index) }
        }
    }

    @Test fun embeddingCacheSurvivesRecreationAndIsIsolatedByPerson() {
        val directory = File(context.filesDir, "semantic-test-${java.util.UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val source = SemanticMemory.Source { after ->
            val items = JSONArray()
            if (after == 0L) items.put(JSONObject().put("id", 1).put("supersedes_id", 0).put("current", true)
                .put("actor", "user").put("subject", "user").put("scope", "real").put("kind", "fact")
                .put("text", "強い雨の日は家にいるのが好きです。"))
            JSONObject().put("items", items).put("through_id", 1).put("has_more", false)
        }
        fun open(folder: File, data: SemanticMemory.Source): SemanticMemory {
            val ready = CountDownLatch(1)
            val lastStatus = java.util.concurrent.atomic.AtomicReference("")
            val memory = SemanticMemory(context, folder, data) {
                lastStatus.set(it)
                if (it == "意味検索の準備ができました" || it.contains("再試行待ち")) ready.countDown()
            }
            memory.refresh()
            assertTrue("Local RAG did not become ready", ready.await(120, TimeUnit.SECONDS))
            assertEquals("意味検索の準備ができました", lastStatus.get())
            return memory
        }
        try {
            open(directory, source).use { assertEquals(1L, it.search("土砂降りならどう過ごす？")[0]) }
            open(directory, source).use { assertEquals(1L, it.search("土砂降りならどう過ごす？")[0]) }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(directory, ".semantic-cache.sqlite3"), null).use {
                it.execSQL("UPDATE vectors SET vector=zeroblob(1024)")
            }
            open(directory, source).use { assertEquals(1L, it.search("土砂降りならどう過ごす？")[0]) }
            val updated = SemanticMemory.Source { after ->
                val items = JSONArray()
                if (after == 1L) items.put(JSONObject().put("id", 2).put("supersedes_id", 1).put("current", true)
                    .put("actor", "user").put("subject", "user").put("scope", "real").put("kind", "fact")
                    .put("text", "今は雨の日も外へ出て散歩をするのが好きです。"))
                JSONObject().put("items", items).put("through_id", 2).put("has_more", false)
            }
            open(directory, updated).use {
                assertFalse(it.search("土砂降りならどう過ごす？").contains(1))
                assertEquals(2L, it.search("雨天でも散歩したい？")[0])
            }
            val other = File(directory, "other"); assertTrue(other.mkdir())
            open(other, SemanticMemory.Source { JSONObject().put("items", JSONArray()).put("through_id", 0).put("has_more", false) })
                .use { assertTrue(it.search("土砂降りならどう過ごす？").isEmpty()) }
        } finally { directory.deleteRecursively() }
    }
}
