package jp.tekewo.conversation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class AttachmentMediaTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var person: File
    @Before fun createPerson() {
        person = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath(), "media-test-").toFile()
    }
    @After fun removePerson() { person.deleteRecursively() }
    private fun picture(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private fun video(): AttachmentFiles.Ref = InstrumentationRegistry.getInstrumentation().context.assets.open("red-video.mp4").use {
        AttachmentFiles.save(person, "赤い動画", "mp4", it)
    }
    @Test fun realImageAndVideoBecomeVisionInputAndGeneratedImageIsSaved() {
        val image = AttachmentFiles.save(person, "画像", "png", ByteArrayInputStream(picture()))
        val video = video()
        val turns = JSONArray().put(JSONObject().put("role", "user").put("content", "説明して\n${image.markdown()}\n${video.markdown()}"))
        val prepared = AttachmentMedia.input(person, turns).getJSONObject(0).getJSONArray("content")
        assertEquals(5, (0 until prepared.length()).count { prepared.getJSONObject(it).getString("type") == "input_image" })
        val frames = (0 until prepared.length()).map { prepared.getJSONObject(it) }.filter { it.optString("type") == "input_image" }
        frames.forEachIndexed { index, part ->
            val jpeg = java.util.Base64.getDecoder().decode(part.getString("image_url").substringAfter(','))
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertEquals(if (index == 0) 2f else 16f / 9f, bitmap.width.toFloat() / bitmap.height, 0.03f)
            val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            when (index) {
                1 -> assertTrue(Color.red(color) > Color.green(color) + 100)
                2 -> assertTrue(Color.green(color) > Color.red(color) + 80)
                3 -> assertTrue(Color.blue(color) > Color.red(color) + 100)
                4 -> assertTrue(Color.red(color) > 200 && Color.green(color) > 200 && Color.blue(color) > 200)
            }
            bitmap.recycle()
        }
        assertTrue((0 until prepared.length()).any { prepared.getJSONObject(it).optString("text").contains("音声は含みません") })
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertTrue(turns.getJSONObject(0).get("content") is String)
        val encoded = java.util.Base64.getEncoder().encodeToString(picture())
        val answer = AttachmentMedia.response(person, "![AIの画像](data:image/png;base64,$encoded)")
        val saved = AttachmentFiles.references(answer).single()
        assertArrayEquals(picture(), AttachmentFiles.read(person, saved))
        assertFalse(answer.contains("base64"))
        assertFalse(answer.contains(ReplyText.STATE_MARKER))
        assertEquals("![AIの画像](attachment:${saved.fileName})", ReplyText.visible(answer, true).trim())
        try { AttachmentMedia.download(person, "https://127.0.0.1/private.png", "private"); fail("Local network image URL accepted") }
        catch (_: java.io.IOException) { }
    }
    @Test fun attachmentOnlyDraftCanBeRemovedAndBothRolesDisplayImages() {
        val ref = AttachmentFiles.save(person, "緑の画像", "png", ByteArrayInputStream(picture()))
        lateinit var ui: ComposeUi
        var picked = 0
        compose.runOnUiThread {
            ui = ComposeUi(compose.activity, {}, {}, {})
            ui.enabled = true; ui.mediaDirectory = person; ui.onAttach = Runnable { picked++ }
            ui.attachments = ref.markdown()
        }
        compose.onNodeWithContentDescription("送信").assertIsEnabled()
        compose.onNodeWithContentDescription("緑の画像を取り外す").performClick()
        compose.onNodeWithContentDescription("送信").assertIsNotEnabled()
        compose.onNodeWithContentDescription("添付ファイルを追加").performClick()
        compose.runOnIdle { assertEquals(1, picked) }
        compose.runOnUiThread {
            ui.setMessages(JSONObject().put("first", 0).put("total", 2).put("messages", JSONArray()
                .put(JSONObject().put("id", 1).put("role", "user").put("text", ref.markdown()))
                .put(JSONObject().put("id", 2).put("role", "assistant").put("text", ref.markdown()))).toString())
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("緑の画像")).fetchSemanticsNodes().size == 2 }
        compose.onAllNodes(hasContentDescription("緑の画像")).fetchSemanticsNodes().forEach {
            assertEquals(2f, it.boundsInRoot.width / it.boundsInRoot.height, 0.03f)
        }
        compose.onAllNodes(hasContentDescription("緑の画像"))[0].performClick()
        compose.onNodeWithText("閉じる").performClick()
        compose.onNodeWithText("閉じる").assertDoesNotExist()
        compose.onNodeWithContentDescription("メニュー").assertIsDisplayed()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/conversation-attachments.png").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    }
    @Test fun allFourCurrentPdfAttachmentsAreIncluded() {
        val refs = (1..4).map { index ->
            AttachmentFiles.save(person, "$index.pdf", "pdf", ByteArrayInputStream("%PDF-1.4\nfixture $index".toByteArray()))
        }
        val turns = JSONArray().put(JSONObject().put("role", "user").put("content", refs.joinToString("\n") { it.markdown() }))
        val content = AttachmentMedia.input(person, turns).getJSONObject(0).getJSONArray("content")
        assertEquals(4, (0 until content.length()).count { content.getJSONObject(it).optString("type") == "input_file" })
        assertFalse((0 until content.length()).any { content.getJSONObject(it).optString("text").contains("過去の添付") })
    }
    @Test fun imageApiKeyStoreIsEncryptedAndSeparateFromSubscription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val subscription = ConnectionStore(context)
        val before = subscription.load().toString()
        val name = "image-test-${java.util.UUID.randomUUID()}"
        val image = ConnectionStore(context, name)
        val key = "sk-synthetic_key_for_encryption_test_only"
        try {
            image.save(JSONObject().put("api_key", key).put("model", ImageApi.DEFAULT_MODEL))
            assertEquals(key, image.load().getString("api_key"))
            assertEquals(before, subscription.load().toString())
            val encrypted = File(context.noBackupFilesDir, "$name-connection.v1").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(encrypted.contains(key))
            image.delete()
            assertFalse(image.load().has("api_key"))
            assertEquals(before, subscription.load().toString())
        } finally { image.delete() }
    }
    @Test fun transparentImagesKeepTheirAlpha() {
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT); bitmap.setPixel(0, 0, Color.RED)
        val ref = try { AttachmentMedia.saveBitmap(person, "透過画像", bitmap) } finally { bitmap.recycle() }
        val restored = AttachmentMedia.thumbnail(AttachmentFiles.read(person, ref))
        try {
            assertTrue(ref.fileName.endsWith(".png"))
            assertEquals(0, Color.alpha(restored.getPixel(16, 8)))
            assertEquals(255, Color.alpha(restored.getPixel(0, 0)))
        } finally { restored.recycle() }
    }
    @Test fun oversizedCurrentFilesFailButDoNotBlockLaterTextConversation() {
        val data = ByteArray(4 * 1024 * 1024)
        val refs = (1..4).map { index ->
            data[0] = index.toByte()
            AttachmentFiles.save(person, "$index.pdf", "pdf", ByteArrayInputStream(data))
        }.joinToString("\n") { it.markdown() }
        val turns = JSONArray().put(JSONObject().put("role", "user").put("content", refs))
        try { AttachmentMedia.input(person, turns); fail("Oversized current attachments accepted") }
        catch (_: java.io.IOException) { }
        turns.put(JSONObject().put("role", "user").put("content", "別の話を続けよう"))
        val prepared = AttachmentMedia.input(person, turns)
        assertEquals("別の話を続けよう", prepared.getJSONObject(1).getString("content"))
        val history = prepared.getJSONObject(0).getJSONArray("content")
        assertTrue((0 until history.length()).any { history.getJSONObject(it).optString("text").contains("再送していません") })
        assertTrue(prepared.toString().toByteArray().size < 16 * 1024 * 1024)
    }
    @Test fun videoOpensAndClosesItsPlayer() {
        val ref = video()
        compose.runOnUiThread {
            val ui = ComposeUi(compose.activity, {}, {}, {})
            ui.mediaDirectory = person
            ui.setMessages(JSONObject().put("first", 0).put("total", 1).put("messages", JSONArray()
                .put(JSONObject().put("id", 1).put("role", "user").put("text", ref.markdown()))).toString())
        }
        compose.onNodeWithContentDescription("動画を再生").performClick()
        fun playing(view: android.view.View): Boolean = (view as? android.widget.VideoView)?.isPlaying == true ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { playing(view.getChildAt(it)) })
        compose.waitUntil(10_000) { android.view.inspector.WindowInspector.getGlobalWindowViews().any { playing(it) } }
        compose.onNodeWithText("閉じる").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("動画を再生").assertIsDisplayed()
    }
}
