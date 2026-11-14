package jp.tekewo.conversation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PersonIconTest {
    @Test fun pickedImageIsCroppedToSmallSquareAndInvalidInputIsRejected() {
        val source = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val encoded = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, encoded)
        source.recycle()
        val png = PersonIcon.read(ByteArrayInputStream(encoded.toByteArray()))
        val icon = PersonIcon.decode(png)
        assertEquals(256, icon.width)
        assertEquals(256, icon.height)
        assertEquals(Color.BLUE, icon.getPixel(128, 128))
        assertTrue(png.size < 1024 * 1024)
        icon.recycle()
        try { PersonIcon.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))); fail("Invalid image accepted") }
        catch (_: IOException) {}
        try { PersonIcon.read(ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1))); fail("Oversized image accepted") }
        catch (_: IOException) {}
    }
}
