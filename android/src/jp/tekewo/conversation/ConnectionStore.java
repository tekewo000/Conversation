package jp.tekewo.conversation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConnectionStore {
    private final String alias;
    private final byte[] aad;
    private final AtomicFile file;

    ConnectionStore(Context context) {
        this(context, "chatgpt");
    }

    ConnectionStore(Context context, String name) {
        if (!name.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid credential store name");
        alias = "conversation." + name + ".credentials.v1";
        aad = ("Conversation." + (name.equals("chatgpt") ? "ChatGPT" : name) + ".v1").getBytes(StandardCharsets.US_ASCII);
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), name + "-connection.v1"));
    }

    synchronized JSONObject load() throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return new JSONObject();
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 256 * 1024) throw new IOException();
                output.write(buffer, 0, count);
            }
            byte[] encoded = output.toByteArray();
            if (encoded.length < 30 || encoded[0] != 1) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, encoded, 1, 12));
            cipher.updateAAD(aad);
            return new JSONObject(new String(cipher.doFinal(encoded, 13, encoded.length - 13), StandardCharsets.UTF_8));
        } catch (Exception e) { throw failure(); }
    }

    synchronized void save(JSONObject state) throws IOException {
        FileOutputStream output = null;
        try {
            byte[] plaintext = state.toString().getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > 240 * 1024) throw new IOException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(aad);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IOException();
            byte[] ciphertext = cipher.doFinal(plaintext);
            output = file.startWrite();
            output.write(1); output.write(iv); output.write(ciphertext);
            file.finishWrite(output);
            output = null;
            // AtomicFile reports some disk failures only through logs; confirm the replacement before accepting rotation.
            if (!MessageDigest.isEqual(plaintext, load().toString().getBytes(StandardCharsets.UTF_8))) throw new IOException();
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw failure();
        }
    }

    synchronized void delete() throws IOException {
        file.delete();
        try {
            if (file.getBaseFile().exists()) throw new IOException();
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            keys.deleteEntry(alias);
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

    private IOException failure() {
        return new IOException(alias.equals("conversation.chatgpt.credentials.v1")
                ? "ChatGPT の接続情報を安全に保存・読込できませんでした。サインアウトして再接続してください。"
                : "画像生成APIの接続情報を安全に保存・読込できませんでした。再設定してください。");
    }
}
