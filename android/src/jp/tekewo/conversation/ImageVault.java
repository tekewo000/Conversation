package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The Android app supplies Keystore-wrapped keys; recovery uses only the Java standard library. */
public final class ImageVault {
    static final int OVERHEAD = 33;
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final byte[] MAGIC = {'C', 'I', 'M', 'G', 1};
    interface Keys {
        byte[] get(File person, boolean create) throws IOException;
        void requireRecovery(File person) throws IOException;
    }
    static volatile Keys keys = new Keys() {
        public byte[] get(File person, boolean create) throws IOException { throw new IOException("画像の暗号鍵を利用できません。"); }
        public void requireRecovery(File person) throws IOException { throw new IOException("画像の復元キーを利用できません。"); }
    };
    static boolean image(String name) { return name.matches("[0-9a-f]{64}\\.(jpg|png|webp|gif)") || name.equals("icon.png"); }
    static byte[] newKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
    static boolean encrypted(byte[] bytes) {
        if (bytes.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (bytes[i] != MAGIC[i]) return false;
        return true;
    }
    static void validate(byte[] encoded) throws IOException {
        if (!encrypted(encoded) || encoded.length <= OVERHEAD || encoded.length > MAX_BYTES + OVERHEAD)
            throw new IOException("暗号化された画像の形式が不正です。");
    }
    static byte[] seal(byte[] key, String person, String name, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("画像が空か、大きすぎます。");
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, person, name, nonce);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(MAGIC); output.write(nonce); output.write(cipher.doFinal(bytes));
            return output.toByteArray();
        } catch (GeneralSecurityException error) { throw new IOException("画像を暗号化できませんでした。", error); }
    }
    static byte[] open(byte[] key, String person, String name, byte[] encoded) throws IOException {
        validate(encoded);
        try {
            return cipher(Cipher.DECRYPT_MODE, key, person, name, Arrays.copyOfRange(encoded, 5, 17))
                    .doFinal(encoded, 17, encoded.length - 17);
        } catch (GeneralSecurityException error) { throw new IOException("画像を復号できません。復元キーまたはデータを確認してください。", error); }
    }
    private static Cipher cipher(int mode, byte[] key, String person, String name, byte[] nonce) throws GeneralSecurityException {
        if (key.length != 32) throw new InvalidKeyException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("Conversation.image.v1\n" + person + "\n" + name).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    static byte[] read(File person, File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("画像ファイルが不正です。");
        long length = Files.size(file.toPath());
        if (length <= 0 || length > MAX_BYTES + OVERHEAD)
            throw new IOException("画像ファイルが不正です。");
        byte[] data = Files.readAllBytes(file.toPath());
        if (!encrypted(data)) return data;
        byte[] key = keys.get(person, false);
        try { return open(key, person.getName(), file.getName(), data); }
        finally { Arrays.fill(key, (byte)0); }
    }
    static byte[] encode(File person, String name, byte[] bytes) throws IOException {
        byte[] key = keys.get(person, true);
        try { return seal(key, person.getName(), name, bytes); }
        finally { Arrays.fill(key, (byte)0); }
    }
    static synchronized void protect(File person, File file, byte[] bytes) throws IOException {
        byte[] existing = Files.readAllBytes(file.toPath());
        if (encrypted(existing)) return;
        byte[] encoded = encode(person, file.getName(), bytes);
        File pending = File.createTempFile("image-", ".pending", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) { output.write(encoded); output.getFD().sync(); }
            byte[] key = keys.get(person, false);
            try {
                if (!MessageDigest.isEqual(bytes, open(key, person.getName(), file.getName(), Files.readAllBytes(pending.toPath()))))
                    throw new IOException("暗号化画像の検証に失敗しました。");
            } finally { Arrays.fill(key, (byte)0); }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
    static byte[] backup(File person, File file) throws IOException {
        keys.requireRecovery(person);
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length() <= OVERHEAD || file.length() > MAX_BYTES + OVERHEAD)
            throw new IOException("バックアップの画像が不正です。");
        byte[] bytes = Files.readAllBytes(file.toPath()); validate(bytes);
        byte[] key = keys.get(person, false);
        try { open(key, person.getName(), file.getName(), bytes); }
        finally { Arrays.fill(key, (byte)0); }
        return bytes;
    }
    static Properties recovery(String person, byte[] key) {
        Properties data = new Properties();
        data.setProperty("format", "conversation-images-v1");
        data.setProperty("person_id", person);
        data.setProperty("key", Base64.getEncoder().encodeToString(key));
        return data;
    }
    static byte[] recoveryKey(Properties data) throws IOException {
        try {
            if (!"conversation-images-v1".equals(data.getProperty("format")) || !data.getProperty("person_id", "").matches("[a-zA-Z0-9-]{1,80}"))
                throw new IOException("復元キーファイルの形式が不正です。");
            byte[] key = Base64.getDecoder().decode(data.getProperty("key", ""));
            if (key.length != 32) throw new IOException("復元キーの長さが不正です。");
            return key;
        } catch (IllegalArgumentException error) { throw new IOException("復元キーの形式が不正です。", error); }
    }
    static void migrateExports(File person) throws IOException {
        File export = new File(person, "export");
        if (!export.exists()) return;
        if (Files.isSymbolicLink(export.toPath())) throw new IOException("バックアップの保存先が不正です。");
        IOException failure = null;
        try (java.util.stream.Stream<Path> paths = Files.walk(export.toPath())) {
            for (Path path : (Iterable<Path>)paths::iterator) {
                try {
                    if (Files.isSymbolicLink(path)) throw new IOException("バックアップの保存先が不正です。");
                    File file = path.toFile();
                    if (!file.isFile() || !image(file.getName())) continue;
                    byte[] bytes = read(person, file);
                    File target = new File(file.getPath() + ".enc");
                    if (target.exists()) {
                        if (Files.isSymbolicLink(target.toPath()) || target.length() > MAX_BYTES + OVERHEAD) throw new IOException("画像の移行先が不正です。");
                        byte[] key = keys.get(person, false);
                        try {
                            if (!MessageDigest.isEqual(bytes, open(key, person.getName(), file.getName(), Files.readAllBytes(target.toPath()))))
                                throw new IOException("画像の移行先の内容が異なります。データを保持して停止しました。");
                        } finally { Arrays.fill(key, (byte)0); }
                        Files.delete(file.toPath());
                    } else {
                        protect(person, file, bytes);
                        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (IOException error) { if (failure == null) failure = error; }
            }
        }
        if (failure != null) throw failure;
    }
    /** java ImageVault.java recovery.key encrypted-directory new-output-directory */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IOException("Usage: java ImageVault.java recovery.key encrypted-directory new-output-directory");
        File recovery = new File(args[0]), source = new File(args[1]), target = new File(args[2]);
        if (recovery.length() == 0 || recovery.length() > 4096 || !source.isDirectory() || target.exists())
            throw new IOException("Use an existing key and source directory, and a new output directory.");
        if (Files.isSymbolicLink(source.toPath()) || target.getCanonicalFile().toPath().startsWith(source.getCanonicalFile().toPath()))
            throw new IOException("Output must be outside the source directory.");
        Properties data = new Properties();
        try (InputStream input = new FileInputStream(recovery)) { data.load(input); }
        byte[] key = recoveryKey(data);
        try {
            if (!target.mkdirs()) throw new IOException("Cannot create output directory.");
            restore(key, data.getProperty("person_id"), source, target);
        } finally { Arrays.fill(key, (byte)0); }
    }
    private static void restore(byte[] key, String person, File source, File target) throws IOException {
        File[] files = source.listFiles();
        if (files == null) throw new IOException("Cannot list source directory.");
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symbolic links are not accepted.");
            if (file.isDirectory()) {
                File nested = new File(target, file.getName());
                if (!nested.mkdir()) throw new IOException("Cannot create output directory.");
                restore(key, person, file, nested);
            } else if (file.getName().endsWith(".enc") && image(file.getName().substring(0, file.getName().length() - 4))) {
                if (file.length() > MAX_BYTES + OVERHEAD) throw new IOException("Image is too large.");
                String name = file.getName().substring(0, file.getName().length() - 4);
                byte[] bytes = open(key, person, name, Files.readAllBytes(file.toPath()));
                Files.write(new File(target, name).toPath(), bytes, StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
