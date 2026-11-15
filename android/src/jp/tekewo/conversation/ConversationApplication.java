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
                if (!create || hasEncryptedImages(person)) throw new IOException("画像の暗号鍵が見つかりません。復元キーが必要です。");
                byte[] key = ImageVault.newKey();
                try { state.put("key", Base64.getEncoder().encodeToString(key)); store.save(state); }
                finally { Arrays.fill(key, (byte)0); }
            }
            byte[] key = Base64.getDecoder().decode(state.getString("key"));
            if (key.length != 32) throw new IOException("画像の暗号鍵が不正です。");
            return key;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("画像の暗号鍵を読み込めませんでした。", error); }
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
            throw new IOException("画像は端末に暗号化して保存されています。バックアップ画面で画像の復元キーを別の場所に書き出してから、Git・ZIPへの保存を再実行してください。");
    }
    void writeRecovery(File person, OutputStream destination) throws IOException {
        byte[] key = null;
        try {
            try (OutputStream output = destination) {
                if (output == null) throw new IOException("復元キーの保存先を開けません。");
                key = get(person, true);
                ImageVault.recovery(person.getName(), key).store(output, "Keep separately from the image backup. Anyone with this key can decrypt your images.");
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
    void deleteKeys(String personId) throws IOException { new ConnectionStore(this, "images-" + personId).delete(); }
}
