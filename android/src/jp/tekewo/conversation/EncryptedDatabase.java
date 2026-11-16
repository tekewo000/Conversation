package jp.tekewo.conversation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.security.MessageDigest;
import java.nio.file.StandardCopyOption;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import android.database.Cursor;

final class EncryptedDatabase {
    static { System.loadLibrary("sqlcipher"); }
    static final class Failure extends IOException {
        Failure(Throwable cause) { super("暗号化DBを開けません。データを消さずに復元キーを確認してください。",cause); }
    }
    static byte[] key(File person, boolean create) throws IOException {
        byte[] root = ImageVault.keys.get(person,create), derived = null;
        try {
            derived = DataVault.derive(root,person.getName(),"database");
            return Base64.getEncoder().encode(derived);
        } finally { Arrays.fill(root,(byte)0); if(derived!=null) Arrays.fill(derived,(byte)0); }
    }
    static SQLiteDatabase open(File person, File file, boolean readOnly) throws IOException {
        byte[] password = null;
        SQLiteDatabase database = null;
        try {
            if(Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().getParentFile().equals(person.getCanonicalFile()))
                throw new IOException("DBの保存先が不正です。");
            password=key(person,!file.exists());
            // SQLCipher retains this array for pooled connections during the database lifetime.
            database = SQLiteDatabase.openDatabase(file.getPath(),password.clone(),null,
                    readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.CREATE_IF_NECESSARY,
                    (broken, cause) -> { throw new IllegalStateException("Encrypted database was preserved.",cause); },null);
            try (Cursor rows=database.rawQuery("PRAGMA cipher_version",new String[0])) {
                if(!rows.moveToFirst() || rows.getString(0).isEmpty()) throw new IOException("SQLCipherを利用できません。");
            }
            database.rawExecSQL("PRAGMA temp_store=MEMORY");
            return database;
        } catch(Exception error) { if(database!=null) database.close(); throw new Failure(error); }
        finally { if(password!=null) Arrays.fill(password,(byte)0); }
    }
    static boolean plain(File file) throws IOException {
        if(!file.isFile()) return false;
        byte[] header=new byte[16];
        try(java.io.InputStream input=Files.newInputStream(file.toPath())) {
            return input.read(header)==16 && Arrays.equals(header,"SQLite format 3\0".getBytes(StandardCharsets.US_ASCII));
        }
    }
    static synchronized void migrate(File person,File file) throws IOException {
        DataFiles.path(person,file);
        if(!file.exists()) return;
        if(!plain(file)) { try(SQLiteDatabase db=open(person,file,true)) { validate(db); } return; }
        byte[] password=key(person,true);
        File encrypted=new File(file.getPath()+".encrypted"), marker=new File(file.getPath()+".migration");
        DataFiles.path(person,encrypted);
        try {
            if(encrypted.exists() && (!marker.isFile() || !Arrays.equals(DataFiles.read(person,marker),file.getName().getBytes(StandardCharsets.UTF_8))))
                throw new IOException("暗号化DBの移行先に別のデータがあります。");
            DataFiles.write(person,marker,file.getName().getBytes(StandardCharsets.UTF_8));
            try(SQLiteDatabase source=SQLiteDatabase.openDatabase(file.getPath(),(byte[])null,null,SQLiteDatabase.CREATE_IF_NECESSARY,
                    (broken,cause)->{throw new IllegalStateException("Original database preserved",cause);},null)) {
                validate(source);
                if(file.getName().equals("conversation.sqlite3")&&source.getVersion()>7) throw new IOException("Unsupported source schema version");
                try(Cursor checkpoint=source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",new String[0])) {
                    if(checkpoint.moveToFirst()&&checkpoint.getInt(0)!=0) throw new IOException("元のDBが別の処理で使われています。");
                }
                if(encrypted.exists()) Files.delete(encrypted.toPath());
                int version=source.getVersion();
                source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?",new Object[]{encrypted.getPath(),new String(password,StandardCharsets.US_ASCII)});
                try(Cursor result=source.rawQuery("SELECT sqlcipher_export('encrypted')",new String[0])) { result.moveToFirst(); }
                source.execSQL("DETACH DATABASE encrypted");
                try(SQLiteDatabase target=open(person,encrypted,false)) {
                    target.setVersion(version); validate(target); validate(source);
                    if(!MessageDigest.isEqual(fingerprint(source),fingerprint(target))) throw new IOException("旧DBと暗号化DBの内容が一致しません。");
                }
            }
            try(java.io.RandomAccessFile output=new java.io.RandomAccessFile(encrypted,"rw")) { output.getFD().sync(); }
            for(String suffix:new String[]{"-wal","-shm","-journal"}) {
                File extra=new File(file.getPath()+suffix); DataFiles.path(person,extra); Files.deleteIfExists(extra.toPath());
            }
            Files.move(encrypted.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(marker.toPath());
        } catch(Exception error) { throw new IOException("DBの暗号化移行を完了できません。元データを保持して再試行します。",error); }
        finally { Arrays.fill(password,(byte)0); }
    }
    static void validateRecovery(File person,File file,byte[] root) throws IOException {
        byte[] derived=DataVault.derive(root,person.getName(),"database"),password=Base64.getEncoder().encode(derived);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),password.clone(),null,SQLiteDatabase.OPEN_READONLY,
                (broken,cause)->{throw new IllegalStateException("Encrypted database preserved",cause);},null)) { validate(db); }
        catch(Exception error) { throw new Failure(error); }
        finally { Arrays.fill(derived,(byte)0);Arrays.fill(password,(byte)0); }
    }
    private static void validate(SQLiteDatabase db) throws IOException {
        try(Cursor rows=db.rawQuery("PRAGMA integrity_check",new String[0])) {
            if(!rows.moveToFirst() || !"ok".equals(rows.getString(0))) throw new IOException("DBの整合性を確認できません。");
        }
        try(Cursor rows=db.rawQuery("PRAGMA foreign_key_check",new String[0])) {
            if(rows.moveToFirst()) throw new IOException("DBの参照整合性を確認できません。");
        }
    }
    private static byte[] fingerprint(SQLiteDatabase db) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(Cursor tables=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata' ORDER BY name",new String[0])) {
            while(tables.moveToNext()) {
                String name=tables.getString(0);
                hash.update(DataVault.fields(name,tables.getString(1)));
                String table="\""+name.replace("\"","\"\"")+"\"";
                StringBuilder order=new StringBuilder();
                try(Cursor shape=db.rawQuery("SELECT * FROM "+table+" LIMIT 0",new String[0])) {
                    for(int column=1;column<=shape.getColumnCount();column++) { if(column>1) order.append(',');order.append(column); }
                }
                try(Cursor rows=db.rawQuery("SELECT * FROM "+table+" ORDER BY "+order,new String[0])) {
                    while(rows.moveToNext()) for(int i=0;i<rows.getColumnCount();i++) {
                        int type=rows.getType(i); hash.update((byte)type);
                        byte[] value=type==Cursor.FIELD_TYPE_NULL?new byte[0]:type==Cursor.FIELD_TYPE_BLOB?rows.getBlob(i):rows.getString(i).getBytes(StandardCharsets.UTF_8);
                        hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array()); hash.update(value);
                    }
                }
            }
        }
        return hash.digest();
    }
}
