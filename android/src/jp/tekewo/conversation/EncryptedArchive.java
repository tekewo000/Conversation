package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class EncryptedArchive {
    static final byte[] FORMAT="{\"format\":\"conversation-encrypted-v1\"}\n".getBytes(StandardCharsets.UTF_8);
    interface Read { byte[] get(String name) throws Exception; }
    static void validatePath(String path) throws IOException {
        if(path==null || path.isEmpty() || path.length()>1024 || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0')>=0)
            throw new IOException("Invalid archive path");
        for(String part:path.split("/",-1)) if(part.isEmpty()||part.equals(".")||part.equals("..")) throw new IOException("Invalid archive path");
    }
    static Map<String,byte[]> encode(byte[] key,String person,Map<String,byte[]> plain,JSONObject manifest,JSONObject previous) throws Exception {
        JSONObject entries=new JSONObject(previous.toString()); Map<String,byte[]> output=new LinkedHashMap<>();
        for(Map.Entry<String,byte[]> file:plain.entrySet()) {
            String path=file.getKey(); if(path.equals("archive.json")) continue;
            validatePath(path); String sha=GitArchiveFiles.sha256(file.getValue());
            JSONObject prior=entries.optJSONObject(path);
            if(prior!=null && sha.equals(prior.optString("sha256"))) continue;
            String target="objects/"+DataVault.id(key,person,"backup",path+"\n"+sha)+".enc";
            byte[] encoded=DataVault.seal(key,person,"backup",path,file.getValue());
            entries.put(path,new JSONObject().put("file",target).put("size",file.getValue().length).put("sha256",sha).put("encrypted_sha256",GitArchiveFiles.sha256(encoded)));
            output.put(target,encoded);
        }
        manifest.put("files",entries);
        byte[] raw=manifest.toString().getBytes(StandardCharsets.UTF_8);
        // shortcut: one authenticated index is limited to 1 MiB; segment it if archives exceed this bound.
        if(raw.length>1024*1024) throw new IOException("暗号化バックアップの索引が上限を超えています。端末の原本は保持しています。");
        output.put("archive.enc",DataVault.seal(key,person,"backup","archive.json",raw)); output.put("format.json",FORMAT);
        return output;
    }
    static JSONObject manifest(byte[] key,String person,byte[] data) throws IOException {
        if(data==null || data.length>1024*1024+DataVault.OVERHEAD) throw new IOException("Invalid encrypted manifest");
        try { return new JSONObject(new String(DataVault.open(key,person,"backup","archive.json",data),StandardCharsets.UTF_8)); }
        catch(JSONException error) { throw new IOException("Invalid encrypted manifest",error); }
    }
    static byte[] read(byte[] key,String person,String path,JSONObject entry,Read source) throws Exception {
        validatePath(path);
        String file=entry.getString("file");
        if(!file.matches("objects/[0-9a-f]{64}\\.enc")) throw new IOException("Invalid encrypted object path");
        byte[] bytes=source.get(file);
        if(bytes==null || !GitArchiveFiles.sha256(bytes).equals(entry.getString("encrypted_sha256"))) throw new IOException("Missing or altered encrypted object");
        byte[] plain=DataVault.open(key,person,"backup",path,bytes);
        if(plain.length!=GitArchiveFiles.integer(entry,"size") || !GitArchiveFiles.sha256(plain).equals(entry.getString("sha256"))) throw new IOException("Archive object does not match");
        return plain;
    }
}
