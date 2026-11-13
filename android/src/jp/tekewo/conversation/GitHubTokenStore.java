package jp.tekewo.conversation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local credentials; never placed under a person's archive directory. */
final class GitHubTokenStore {
    private final String alias;
    private final byte[] aad;
    private final AtomicFile file;
    GitHubTokenStore(Context context, String personId) {
        if (!UUID.fromString(personId).toString().equals(personId)) throw new IllegalArgumentException("Invalid person ID.");
        alias = "conversation.github.credentials.v1." + personId;
        aad = ("Conversation.GitHub.v1." + personId).getBytes(StandardCharsets.US_ASCII);
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "github-token." + personId + ".v1"));
    }

    synchronized String get() throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return "";
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 8192) throw new IOException();
                output.write(buffer, 0, count);
            }
            byte[] encoded = output.toByteArray();
            if (encoded.length < 30 || encoded[0] != 1) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, encoded, 1, 12));
            cipher.updateAAD(aad);
            return new String(cipher.doFinal(encoded, 13, encoded.length - 13), StandardCharsets.UTF_8);
        } catch (Exception e) { throw failure(); }
    }

    synchronized void set(String token) throws IOException {
        FileOutputStream output = null;
        try {
            if (token == null || token.isEmpty() || token.length() > 4096 || !token.matches("[A-Za-z0-9_]+")) throw new IOException();
            byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException();
            byte[] ciphertext = cipher.doFinal(plaintext);
            output = file.startWrite(); output.write(1); output.write(iv); output.write(ciphertext);
            file.finishWrite(output); output = null;
            if (!MessageDigest.isEqual(plaintext, get().getBytes(StandardCharsets.UTF_8))) throw new IOException();
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw failure();
        }
    }

    synchronized void clear() throws IOException {
        file.delete();
        try {
            if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()) throw new IOException();
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias);
        } catch (Exception e) { throw failure(); }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(alias)) return (SecretKey)store.getKey(alias, null);
        if (!create) throw new IOException();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    private static IOException failure() { return new IOException("GitHub トークンを安全に保存・読込できませんでした。再設定してください。"); }
}
