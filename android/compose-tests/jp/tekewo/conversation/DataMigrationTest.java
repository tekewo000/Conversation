package jp.tekewo.conversation;

import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DataMigrationTest {
    @Test public void authenticatedTemporaryWritesResumeBeforeMigration() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PersonRegistry people=new PersonRegistry(context.getFilesDir());String previous=people.active()==null?"":people.active().id;
        PersonRegistry.Person person=people.stage(PersonDocument.named("Interrupted file fixture"),null);people.commit(person);
        try {
            Files.delete(new File(person.directory,".data-migrated-v1").toPath());
            byte[] root=ImageVault.keys.get(person.directory,false);
            Files.write(new File(person.directory,"state.json.tmp").toPath(),DataVault.seal(root,person.id,"file","state.json","{\"draft\":\"interrupted draft\"}".getBytes("UTF-8")));
            Files.write(new File(person.directory,".data-migrated-v1.tmp").toPath(),DataVault.seal(root,person.id,"file",".data-migrated-v1",new byte[]{1}));
            byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            Files.write(new File(person.directory,"icon.png").toPath(),png);
            Files.write(new File(person.directory,"image-123.pending").toPath(),ImageVault.seal(root,person.id,"icon.png",png));
            DataMigration.ensure(context);
            assertEquals("interrupted draft",PersonState.load(person.directory).getString("draft"));
            assertArrayEquals(png,ImageVault.read(person.directory,new File(person.directory,"icon.png")));
            assertFalse(new File(person.directory,"state.json.tmp").exists());assertFalse(new File(person.directory,"image-123.pending").exists());
        } finally { people.delete(person.id);((ConversationApplication)context.getApplicationContext()).deleteKeys(person.id);if(!previous.isEmpty()) people.activate(previous); }
    }
    @Test public void missingKeysNeverDeleteEncryptedWorkingDatabases() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File person=Files.createTempDirectory(context.getCacheDir().toPath(),"missing-key-").toFile();
        ImageVault.Keys previous=ImageVault.keys;
        File working=new File(person,MemoryRebuild.FILE),cache=new File(person,".semantic-cache.sqlite3");
        try {
            MemoryRebuild rebuild=new MemoryRebuild(person);rebuild.request(4);
            try(net.zetetic.database.sqlcipher.SQLiteDatabase db=EncryptedDatabase.open(person,cache,false)) { db.execSQL("CREATE TABLE vectors(id INTEGER PRIMARY KEY,vector BLOB)"); }
            byte[] before=Files.readAllBytes(working.toPath()),cached=Files.readAllBytes(cache.toPath());
            ImageVault.keys=new ImageVault.Keys() {
                public byte[] get(File directory,boolean create) throws IOException { throw new IOException("Test key unavailable"); }
                public void requireRecovery(File directory) { }
            };
            try { rebuild.request(4);fail("missing key accepted"); } catch(IOException expected) { }
            assertTrue("Encrypted rebuild was deleted",working.isFile());assertArrayEquals(before,Files.readAllBytes(working.toPath()));
            try { EncryptedDatabase.open(person,cache,false);fail("missing key accepted"); } catch(EncryptedDatabase.Failure expected) { }
            assertArrayEquals(cached,Files.readAllBytes(cache.toPath()));
        } finally {
            ImageVault.keys=previous;((ConversationApplication)context.getApplicationContext()).deleteKeys(person.getName());
            try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) { for(Path path:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path); }
        }
    }
    @Test public void unidentifiedLegacyPeopleAndRootTemporariesStopStartup() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(int variant=0;variant<3;variant++) {
            File root=Files.createTempDirectory(base.getCacheDir().toPath(),"orphan-data-").toFile();
            Context isolated=new android.content.ContextWrapper(base) {
                @Override public File getFilesDir() { return root; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return base.getSharedPreferences(root.getName()+name,mode); }
            };
            File retained;
            if(variant==0) { retained=new File(root,"people/"+java.util.UUID.randomUUID()+"/person.md");retained.getParentFile().mkdirs(); }
            else retained=new File(root,variant==1?"people-registry.json.tmp":"conversation.sqlite3-wal");
            byte[] body="unregistered private canary".getBytes("UTF-8");Files.write(retained.toPath(),body);
            try {
                assertTrue("Unidentified data was excluded from inventory",DataMigration.needed(isolated));
                try { DataMigration.ensure(isolated);fail("unidentified data accepted"); } catch(IOException expected) { }
                assertArrayEquals(body,Files.readAllBytes(retained.toPath()));
            } finally { try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) { for(Path path:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path); } }
        }
    }
    @Test public void legacyPreferencesMigrateEvenWhenThePersonAlreadyUsesEncryption() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PersonRegistry people=new PersonRegistry(context.getFilesDir());String previous=people.active()==null?"":people.active().id;
        PersonRegistry.Person person=people.stage(PersonDocument.named("Encrypted legacy preference fixture"),null);people.commit(person);
        android.content.SharedPreferences preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
        try {
            preferences.edit().putString("draft_"+person.id,"legacy draft canary").putString("attachments_"+person.id,"legacy attachments").commit();
            assertTrue(DataMigration.needed(context));DataMigration.ensure(context);
            assertEquals("legacy draft canary",PersonState.load(person.directory).getString("draft"));
            assertEquals("legacy attachments",PersonState.load(person.directory).getString("attachments"));
            assertFalse(preferences.contains("draft_"+person.id));assertFalse(preferences.contains("attachments_"+person.id));
        } finally {
            preferences.edit().remove("draft_"+person.id).remove("attachments_"+person.id).commit();people.delete(person.id);((ConversationApplication)context.getApplicationContext()).deleteKeys(person.id);
            if(!previous.isEmpty()) people.activate(previous);
        }
    }
    @Test public void legacyDraftConflictDoesNotOverwriteEitherCopy() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PersonRegistry people=new PersonRegistry(context.getFilesDir());
        String previous=people.active()==null?"":people.active().id;
        PersonRegistry.Person person=people.stage(PersonDocument.named("Draft migration fixture"),null);people.commit(person);
        android.content.SharedPreferences preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
        try {
            PersonState.put(person.directory,"draft","newer encrypted draft");
            preferences.edit().putString("draft_"+person.id,"older plaintext draft").commit();
            try { DataMigration.ensure(context);fail("conflicting draft silently overwritten"); } catch(IOException expected) { }
            assertEquals("newer encrypted draft",PersonState.load(person.directory).getString("draft"));
            assertEquals("older plaintext draft",preferences.getString("draft_"+person.id,""));
        } finally {
            preferences.edit().remove("draft_"+person.id).commit();people.delete(person.id);((ConversationApplication)context.getApplicationContext()).deleteKeys(person.id);
            if(!previous.isEmpty()) people.activate(previous);
        }
    }
    @Test public void recoveryKeyRestoresMissingWrappedKeyAndRejectsWrongKey() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConversationApplication app=(ConversationApplication)context.getApplicationContext();
        File person=new File(context.getFilesDir(),"people/"+java.util.UUID.randomUUID()); assertTrue(person.mkdirs());
        byte[] root=app.get(person,true);
        try {
            DataFiles.write(person,new File(person,"person.md"),"key recovery canary".getBytes("UTF-8"));
            app.deleteKeys(person.getName());
            try { app.get(person,true); fail("missing key was replaced"); } catch(IOException expected) { }
            java.util.Properties wrong=ImageVault.recovery(person.getName(),new byte[32]);
            try { app.importRecovery(wrong); fail("wrong recovery key accepted"); } catch(IOException expected) { }
            app.importRecovery(ImageVault.recovery(person.getName(),root));
            assertArrayEquals(root,app.get(person,false));
            assertEquals("key recovery canary",new String(DataFiles.read(person,new File(person,"person.md")),"UTF-8"));
        } finally { app.deleteKeys(person.getName());for(File file:person.listFiles()) Files.delete(file.toPath());Files.delete(person.toPath()); }
    }
    @Test public void coreReadsEncryptedDatabaseWithTheSameDerivedKey() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PersonRegistry people=new PersonRegistry(context.getFilesDir());
        PersonDocument doc=PersonDocument.named("Core encrypted fixture");
        PersonRegistry.Person person=people.stage(doc,null); people.commit(person);
        try {
            System.loadLibrary("conversation");
            assertEquals("",MainActivity.nativeSwitchPerson(person.directory.getPath().getBytes("UTF-8"),doc.importPayload(person.id).toString().getBytes("UTF-8")));
            assertFalse(MainActivity.nativeContext(new byte[0]).isEmpty());
        } finally { MainActivity.nativeSwitchPerson(new byte[0],new byte[0]); people.delete(person.id); ((ConversationApplication)context.getApplicationContext()).deleteKeys(person.id); }
    }
    @Test public void encryptedAttachmentsRemainSeekableWithoutPlainCacheFiles() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        PersonRegistry people=new PersonRegistry(context.getFilesDir());
        PersonRegistry.Person person=people.stage(PersonDocument.named("Provider fixture"),null); people.commit(person);
        try {
            byte[] body="abcdef-private-provider-canary".getBytes("UTF-8");
            AttachmentFiles.Ref ref=AttachmentFiles.save(person.directory,"test.txt","txt",new ByteArrayInputStream(body));
            try(android.os.ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(AttachmentProvider.uri(context,person.directory,ref),"r");
                FileInputStream input=new FileInputStream(fd.getFileDescriptor())) {
                android.system.Os.lseek(fd.getFileDescriptor(),7,android.system.OsConstants.SEEK_SET);
                byte[] part=new byte[7]; assertEquals(7,input.read(part)); assertEquals("private",new String(part,"UTF-8"));
            }
            assertTrue(DataVault.encrypted(Files.readAllBytes(AttachmentFiles.resolve(person.directory,ref.fileName).toPath())));
        } finally { people.delete(person.id); ((ConversationApplication)context.getApplicationContext()).deleteKeys(person.id); }
    }
    @Test public void migratesPlainDatabaseIncludingWalAndResumes() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File person=Files.createTempDirectory(context.getFilesDir().toPath(),"data-migration-").toFile();
        File file=new File(person,"conversation.sqlite3");
        File before=new File(person,"before.sqlite3");
        android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(before,null);
        try {
            old.enableWriteAheadLogging();
            old.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY,content TEXT)");
            old.execSQL("CREATE TABLE config(key TEXT PRIMARY KEY,value TEXT) WITHOUT ROWID");
            old.execSQL("INSERT INTO config VALUES('version','4')");
            old.execSQL("INSERT INTO messages VALUES(1,'migration-private-canary')"); old.setVersion(7);
            Files.copy(before.toPath(),file.toPath());
            Files.copy(new File(before.getPath()+"-wal").toPath(),new File(file.getPath()+"-wal").toPath());
            old.close();
            for(File item:person.listFiles()) if(item.getName().startsWith("before.sqlite3")) Files.delete(item.toPath());
            ((ConversationApplication)context.getApplicationContext()).get(person,true);
            DataFiles.write(person,new File(file.getPath()+".migration"),file.getName().getBytes("UTF-8"));
            Files.write(new File(file.getPath()+".encrypted").toPath(),new byte[]{1,2,3});
            EncryptedDatabase.migrate(person,file);
            EncryptedDatabase.migrate(person,file);
            try(net.zetetic.database.sqlcipher.SQLiteDatabase db=EncryptedDatabase.open(person,file,true);
                Cursor rows=db.rawQuery("SELECT content FROM messages",new String[0])) {
                assertEquals(7,db.getVersion()); assertTrue(rows.moveToFirst()); assertEquals("migration-private-canary",rows.getString(0));
            }
            for(File item:person.listFiles()) assertFalse(new String(Files.readAllBytes(item.toPath()),"ISO-8859-1").contains("migration-private-canary"));
        } finally {
            if(old.isOpen()) old.close();
            ((ConversationApplication)context.getApplicationContext()).deleteKeys(person.getName());
            for(File item:person.listFiles()) Files.delete(item.toPath()); Files.delete(person.toPath());
        }
    }
}
