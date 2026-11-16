package jp.tekewo.conversation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DataVaultTest {
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("accepted unauthenticated data"); }
        catch (IOException expected) { }
    }
    static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)Integer.parseInt(text.substring(2*i, 2*i+2), 16);
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        byte[] ikm = new byte[22]; Arrays.fill(ikm, (byte)11);
        assert Arrays.equals(DataVault.hkdf(ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9")),
                hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"));
        byte[] root = new byte[32], body = "秘密の会話 😀".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = DataVault.seal(root, "person", "file", "日本語.txt", body);
        assert !Arrays.equals(encoded, DataVault.seal(root, "person", "file", "日本語.txt", body));
        assert Arrays.equals(body, DataVault.open(root, "person", "file", "日本語.txt", encoded));
        assert DataVault.open(root, "person", "file", "empty", DataVault.seal(root,"person","file","empty",new byte[0])).length == 0;
        rejects(() -> DataVault.open(root, "other", "file", "日本語.txt", encoded));
        rejects(() -> DataVault.open(root, "person", "backup", "日本語.txt", encoded));
        rejects(() -> DataVault.open(root, "person", "file", "other.txt", encoded));
        rejects(() -> DataVault.open(new byte[31], "person", "file", "日本語.txt", encoded));
        byte[] changed = encoded.clone(); changed[changed.length-1] ^= 1;
        rejects(() -> DataVault.open(root, "person", "file", "日本語.txt", changed));
        rejects(() -> DataVault.open(root, "person", "file", "日本語.txt", Arrays.copyOf(encoded, encoded.length-1)));
        assert !Arrays.equals(DataVault.derive(root,"ab","c"), DataVault.derive(root,"a","bc"));
        System.out.println("PASS: RFC5869, data encryption, domain/path/person separation, empty data, nonce and tamper rejection");
    }
}
