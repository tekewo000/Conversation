package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned authenticated bytes shared by Android and the offline recovery tools. */
public final class DataVault {
    static final byte[] MAGIC = {'C','D','A','T',1};
    static final int OVERHEAD = 33;
    static final int MAX_BYTES = 64 * 1024 * 1024;
    static byte[] fields(String... fields) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 65536) throw new IOException("暗号化の識別情報が長すぎます。");
                data.writeInt(bytes.length); data.write(bytes);
            }
        }
        return output.toByteArray();
    }
    static byte[] hkdf(byte[] input, byte[] salt, byte[] info) throws IOException {
        byte[] prk = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt,"HmacSHA256")); prk = mac.doFinal(input);
            mac.init(new SecretKeySpec(prk,"HmacSHA256")); mac.update(info); mac.update((byte)1);
            return mac.doFinal();
        } catch (GeneralSecurityException error) { throw new IOException("暗号鍵を導出できません。",error); }
        finally { if (prk != null) Arrays.fill(prk,(byte)0); }
    }
    static byte[] derive(byte[] root, String person, String purpose) throws IOException {
        if (root == null || root.length != 32) throw new IOException("復元キーが不正です。");
        return hkdf(root,"Conversation.data.v1".getBytes(StandardCharsets.UTF_8),fields(person,purpose));
    }
    static boolean encrypted(byte[] bytes) {
        if (bytes.length < MAGIC.length) return false;
        for (int i=0;i<MAGIC.length;i++) if(bytes[i]!=MAGIC[i]) return false;
        return true;
    }
    static Cipher cipher(int mode, byte[] root, String person, String purpose, String path, byte[] nonce) throws IOException {
        byte[] key = derive(root,person,purpose);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(fields("Conversation.data.v1",person,purpose,path)); return cipher;
        } catch (GeneralSecurityException error) { throw new IOException("データの暗号処理を開始できません。",error); }
        finally { Arrays.fill(key,(byte)0); }
    }
    static byte[] seal(byte[] root,String person,String purpose,String path,byte[] data) throws IOException {
        if(data.length>MAX_BYTES) throw new IOException("暗号化するデータが大きすぎます。");
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(MAGIC); output.write(nonce);
            output.write(cipher(Cipher.ENCRYPT_MODE,root,person,purpose,path,nonce).doFinal(data));
            return output.toByteArray();
        } catch (GeneralSecurityException error) { throw new IOException("データを暗号化できません。",error); }
    }
    static byte[] open(byte[] root,String person,String purpose,String path,byte[] data) throws IOException {
        if(!encrypted(data) || data.length<OVERHEAD || data.length>MAX_BYTES+OVERHEAD)
            throw new IOException("暗号化データの形式が不正です。");
        try {
            return cipher(Cipher.DECRYPT_MODE,root,person,purpose,path,Arrays.copyOfRange(data,5,17))
                    .doFinal(data,17,data.length-17);
        } catch (GeneralSecurityException error) { throw new IOException("データを復号できません。キーまたはデータを確認してください。",error); }
    }
    static String id(byte[] root,String person,String purpose,String path) throws IOException {
        byte[] key = derive(root,person,purpose+"-path");
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for(byte value:mac.doFinal(fields(path))) out.append(String.format(Locale.ROOT,"%02x",value & 255));
            return out.toString();
        } catch(GeneralSecurityException error) { throw new IOException(error); }
        finally { Arrays.fill(key,(byte)0); }
    }
}
