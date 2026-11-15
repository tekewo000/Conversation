package jp.tekewo.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ImageSecurityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as ConversationApplication
    private lateinit var person: File
    private val png by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 16, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        try { ByteArrayOutputStream().apply { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    @Before fun createPerson() {
        person = Files.createTempDirectory(context.filesDir.toPath(), "secure-images-").toFile()
    }
    @After fun removePerson() { app.deleteKeys(person.name); person.deleteRecursively() }

    @Test fun generatedAndPickedImagesAreEncryptedAndStillReachVision() {
        val picked = AttachmentFiles.save(person, "顔写真", "png", ByteArrayInputStream(png))
        val answer = AttachmentMedia.response(person, "![生成画像](data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(png)})")
        val generated = AttachmentFiles.references(answer).single()
        for (ref in listOf(picked, generated)) {
            assertTrue(ImageVault.encrypted(Files.readAllBytes(AttachmentFiles.resolve(person, ref.fileName).toPath())))
            assertArrayEquals(png, AttachmentFiles.read(person, ref))
        }
        val turns = JSONArray().put(JSONObject().put("role", "user").put("content", picked.markdown()))
        val input = AttachmentMedia.input(person, turns).getJSONObject(0).getJSONArray("content")
        assertTrue((0 until input.length()).any { input.getJSONObject(it).optString("type") == "input_image" })
        val second = app.get(person, false)
        assertArrayEquals(app.get(person, false), second)
        val wrapped = File(context.noBackupFilesDir, "images-${person.name}-connection.v1").readBytes()
        assertFalse(String(wrapped, Charsets.ISO_8859_1).contains(java.util.Base64.getEncoder().encodeToString(second)))
    }

    @Test fun backupRequiresSuccessfulSeparateRecoveryExportAndSurvivesLostDeviceKey() {
        val ref = AttachmentFiles.save(person, "写真", "png", ByteArrayInputStream(png))
        val file = AttachmentFiles.resolve(person, ref.fileName)
        try { ImageVault.backup(person, file); fail("Backup allowed without recovery key") } catch (_: IOException) { }
        try {
            app.writeRecovery(person, object : OutputStream() { override fun write(value: Int) { throw IOException("Disk full") } })
            fail("Failed recovery export accepted")
        } catch (_: IOException) { }
        try { ImageVault.backup(person, file); fail("Failed key export enabled backup") } catch (_: IOException) { }
        val recovery = ByteArrayOutputStream()
        app.writeRecovery(person, recovery)
        val sealed = ImageVault.backup(person, file)
        val properties = java.util.Properties().apply { load(ByteArrayInputStream(recovery.toByteArray())) }
        assertArrayEquals(png, ImageVault.open(ImageVault.recoveryKey(properties), person.name, ref.fileName, sealed))
        app.deleteKeys(person.name)
        try { AttachmentFiles.read(person, ref); fail("Missing key accepted") } catch (_: IOException) { }
        try { app.get(person, true); fail("Lost key silently replaced") } catch (_: IOException) { }
        assertArrayEquals(png, ImageVault.open(ImageVault.recoveryKey(properties), person.name, ref.fileName, sealed))
    }

    @Test fun corruptWrappedKeyNeverCreatesAReplacement() {
        val ref = AttachmentFiles.save(person, "写真", "png", ByteArrayInputStream(png))
        val stored = File(context.noBackupFilesDir, "images-${person.name}-connection.v1")
        val original = stored.readBytes()
        val damaged = original.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        stored.writeBytes(damaged)
        try { app.get(person, true); fail("Corrupt key was replaced") } catch (_: IOException) { }
        assertArrayEquals(damaged, stored.readBytes())
        stored.writeBytes(original)
        assertArrayEquals(png, AttachmentFiles.read(person, ref))
    }
}
