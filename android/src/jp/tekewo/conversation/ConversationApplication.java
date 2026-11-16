package jp.tekewo.conversation;

import android.app.Application;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

public final class ConversationApplication extends Application implements ImageVault.Keys {
    @Override public void onCreate() { super.onCreate(); ImageVault.keys = this; }

    private ConnectionStore store(File person) throws IOException {
        if (!person.isDirectory() || Files.isSymbolicLink(person.toPath())) throw new IOException("人物の保存先が見つかりません。");
        return new ConnectionStore(this, "images-" + person.getName());
    }
    @Override public synchronized byte[] get(File person, boolean create) throws IOException {
        try {
            ConnectionStore store = store(person);
            JSONObject state = store.load();
            if (!state.has("key")) {
                if (!create || hasEncryptedData(person)) throw new IOException("データの暗号鍵が見つかりません。復元キーが必要です。");
                byte[] key = ImageVault.newKey();
                try { state.put("key", Base64.getEncoder().encodeToString(key)); store.save(state); }
                finally { Arrays.fill(key, (byte)0); }
            }
            byte[] key = Base64.getDecoder().decode(state.getString("key"));
            if (key.length != 32) throw new IOException("データの暗号鍵が不正です。");
            File marker=new File(person,".data-key-v1");
            if(!marker.exists()) try(FileOutputStream output=new FileOutputStream(marker)) { output.write(1); output.getFD().sync(); }
            return key;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("データの暗号鍵を読み込めませんでした。", error); }
    }
    private boolean hasEncryptedData(File person) throws IOException {
        if(new File(person,".data-key-v1").exists()) return true;
        try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(person.toPath())) {
            for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths::iterator) {
                if(Files.isSymbolicLink(path)) throw new IOException("データの保存先が不正です。");
                File file=path.toFile(); if(!file.isFile()) continue;
                byte[] header=new byte[5];
                try(InputStream input=new FileInputStream(file)) {
                    if(input.read(header)==5 && (DataVault.encrypted(header)||ImageVault.encrypted(header))) return true;
                }
                if((file.getName().endsWith(".sqlite3")||file.getName().endsWith(".encrypted")) && !EncryptedDatabase.plain(file)) return true;
            }
        }
        return hasEncryptedImages(person);
    }
    private boolean hasEncryptedImages(File person) throws IOException {
        List<File> files = new ArrayList<>(); files.add(new File(person, "icon.png"));
        File media = new File(person, "media");
        if (Files.isSymbolicLink(media.toPath())) throw new IOException("画像の保存先が不正です。");
        if (media.isDirectory()) {
            File[] children = media.listFiles();
            if (children == null) throw new IOException("画像の保存先を読み込めません。");
            files.addAll(Arrays.asList(children));
        }
        for (File file : files) if (ImageVault.image(file.getName()) && file.isFile()) {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("画像の保存先が不正です。");
            try (InputStream input = new FileInputStream(file)) {
                byte[] header = new byte[5];
                if (input.read(header) == header.length && ImageVault.encrypted(header)) return true;
            }
        }
        File export = new File(person, "export");
        if (export.exists()) {
            if (Files.isSymbolicLink(export.toPath())) throw new IOException("画像の保存先が不正です。");
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(export.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>)paths::iterator) {
                    if (Files.isSymbolicLink(path)) throw new IOException("画像の保存先が不正です。");
                    String name = path.getFileName().toString();
                    if (name.endsWith(".enc") && ImageVault.image(name.substring(0, name.length() - 4))) return true;
                }
            }
        }
        return false;
    }
    @Override public synchronized void requireRecovery(File person) throws IOException {
        if (!store(person).load().optBoolean("recovery_saved"))
            throw new IOException("データは端末に暗号化して保存されています。バックアップ画面で復元キーを別の場所に書き出してから、Git・ZIPへの保存を再実行してください。");
    }
    void writeRecovery(File person, OutputStream destination) throws IOException {
        byte[] key = null;
        try {
            try (OutputStream output = destination) {
                if (output == null) throw new IOException("復元キーの保存先を開けません。");
                key = get(person, true);
                ImageVault.recovery(person.getName(), key).store(output, "Keep separately from backups. Anyone with this key can decrypt all personal data.");
            }
            synchronized (this) {
                byte[] current = get(person, false);
                try { if (!MessageDigest.isEqual(key, current)) throw new IOException("画像の暗号鍵が変わりました。もう一度書き出してください。"); }
                finally { Arrays.fill(current, (byte)0); }
                ConnectionStore store = store(person); JSONObject state = store.load();
                try { state.put("recovery_saved", true); } catch (Exception error) { throw new IOException(error); }
                store.save(state);
            }
        } finally { if (key != null) Arrays.fill(key, (byte)0); }
    }
    synchronized void importRecovery(Properties data) throws IOException {
        byte[] key=ImageVault.recoveryKey(data);
        try {
            String id=data.getProperty("person_id");
            try { if(!UUID.fromString(id).toString().equals(id)) throw new IOException("人物IDが不正です。"); }
            catch(IllegalArgumentException error) { throw new IOException("人物IDが不正です。"); }
            File person=new File(getFilesDir(),"people/"+id);
            if(!person.isDirectory()||Files.isSymbolicLink(person.toPath())) throw new IOException("この端末に復元キーの人物が見つかりません。");
            boolean encrypted=false,verified=false;
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(person.toPath())) {
                for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)paths::iterator) {
                    File file=path.toFile(); if(!file.isFile()) continue;
                    String relative=DataFiles.path(person,file); if(relative.startsWith("export/")) continue;
                    byte[] header=new byte[5];try(InputStream input=new FileInputStream(file)) { input.read(header); }
                    if(!DataVault.encrypted(header)&&!ImageVault.encrypted(header)) continue;
                    encrypted=true;
                    if(file.length()>DataVault.MAX_BYTES+DataVault.OVERHEAD) continue;
                    try {
                        byte[] bytes=Files.readAllBytes(path);
                        if(DataVault.encrypted(bytes)) DataVault.open(key,id,"file",relative,bytes);
                        else ImageVault.open(key,id,file.getName(),bytes);
                        verified=true; break;
                    } catch(IOException mismatch) { }
                }
            }
            File database=new File(person,"conversation.sqlite3");
            if(!verified&&database.isFile()&&!EncryptedDatabase.plain(database)) {
                encrypted=true;
                try { EncryptedDatabase.validateRecovery(person,database,key);verified=true; } catch(IOException mismatch) { }
            }
            if(encrypted&&!verified) throw new IOException("この復元キーでは人物データを復号できません。元のデータは保持しています。");
            JSONObject state=new JSONObject().put("key",Base64.getEncoder().encodeToString(key)).put("recovery_saved",true);
            store(person).save(state);
            byte[] loaded=get(person,false);try { if(!MessageDigest.isEqual(key,loaded)) throw new IOException("復元キーを照合できません。"); } finally { Arrays.fill(loaded,(byte)0); }
        } catch(IOException error) { throw error; }
        catch(Exception error) { throw new IOException("復元キーを読み込めませんでした。",error); }
        finally { Arrays.fill(key,(byte)0); }
    }
    void deleteKeys(String personId) throws IOException {
        new ConnectionStore(this,"images-"+personId).delete();
        new ConnectionStore(this,"git-move-"+personId).delete();
    }
}
