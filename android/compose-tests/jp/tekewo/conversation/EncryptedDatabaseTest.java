package jp.tekewo.conversation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.database.Cursor;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

@RunWith(AndroidJUnit4.class)
public class EncryptedDatabaseTest {
    @Test public void encryptsWalAndRejectsWrongKeysWithoutDeletingData() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File person = Files.createTempDirectory(context.getCacheDir().toPath(), "encrypted-db-").toFile();
        byte[] root = new byte[32]; Arrays.fill(root, (byte)37);
        ImageVault.Keys previous = ImageVault.keys;
        ImageVault.keys = new ImageVault.Keys() {
            public byte[] get(File dir, boolean create) { return root.clone(); }
            public void requireRecovery(File dir) { }
        };
        File database = new File(person, "conversation.sqlite3");
        try {
            try (SQLiteDatabase db = EncryptedDatabase.open(person,database,false)) {
                db.enableWriteAheadLogging();
                db.execSQL("CREATE VIRTUAL TABLE notes USING fts5(text)");
                db.execSQL("INSERT INTO notes VALUES(?)", new Object[]{"private-canary-334477"});
                try (Cursor rows = db.rawQuery("SELECT json_extract('{\"v\":42}', '$.v'),text FROM notes WHERE notes MATCH 'canary'", new String[0])) {
                    assertTrue(rows.moveToFirst()); assertEquals(42,rows.getInt(0));
                    assertEquals("private-canary-334477",rows.getString(1));
                }
                for (File file : person.listFiles())
                    assertFalse(new String(Files.readAllBytes(file.toPath()),StandardCharsets.ISO_8859_1).contains("private-canary-334477"));
            }
            byte[] before = Files.readAllBytes(database.toPath());
            root[0] ^= 1;
            try { EncryptedDatabase.open(person,database,false).close(); fail("wrong key accepted"); }
            catch (Exception expected) { assertTrue(database.isFile()); }
            assertArrayEquals(before,Files.readAllBytes(database.toPath()));
            root[0] ^= 1;
            try (SQLiteDatabase db = EncryptedDatabase.open(person,database,true);
                 Cursor rows = db.rawQuery("SELECT count(*) FROM notes",new String[0])) {
                assertTrue(rows.moveToFirst()); assertEquals(1,rows.getInt(0));
            }
        } finally {
            ImageVault.keys = previous;
            for (File file : person.listFiles()) Files.delete(file.toPath());
            Files.delete(person.toPath());
        }
    }
}
