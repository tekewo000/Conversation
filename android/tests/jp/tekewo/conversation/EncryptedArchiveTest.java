package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class EncryptedArchiveTest {
    public static void main(String[] args) throws Exception {
        byte[] key=new byte[32]; String id="person";
        Map<String,byte[]> plain=new LinkedHashMap<>(); plain.put("person.md","private profile".getBytes("UTF-8"));
        plain.put("events/one.jsonl","private conversation".getBytes("UTF-8"));
        JSONObject manifest=new JSONObject().put("format","test"), entries=new JSONObject();
        Map<String,byte[]> encoded=EncryptedArchive.encode(key,id,plain,manifest,entries);
        assert encoded.containsKey("archive.enc") && !encoded.containsKey("person.md");
        for(byte[] bytes:encoded.values()) assert !new String(bytes,"ISO-8859-1").contains("private");
        JSONObject decoded=EncryptedArchive.manifest(key,id,encoded.get("archive.enc"));
        assert Arrays.equals(plain.get("person.md"),EncryptedArchive.read(key,id,"person.md",decoded.getJSONObject("files").getJSONObject("person.md"),encoded::get));
        Map<String,byte[]> unchanged=EncryptedArchive.encode(key,id,plain,new JSONObject().put("format","test"),decoded.getJSONObject("files"));
        assert unchanged.size()==2 : unchanged.keySet();
        byte[] damaged=encoded.get("archive.enc").clone(); damaged[damaged.length-1]^=1;
        DataVaultTest.rejects(()->EncryptedArchive.manifest(key,id,damaged));
        DataVaultTest.rejects(()->EncryptedArchive.read(key,id,"person.md",decoded.getJSONObject("files").getJSONObject("person.md"),p->null));
        DataVaultTest.rejects(()->EncryptedArchive.validatePath("../secret"));
        DataVaultTest.rejects(()->EncryptedArchive.validatePath("C:/secret"));
        System.out.println("PASS: encrypted archive, immutable objects, reused content, authenticated manifest and missing/path rejection");
    }
}
