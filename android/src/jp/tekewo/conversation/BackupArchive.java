package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class BackupArchive implements AutoCloseable {
    final File directory,person;
    private final byte[] key;
    private final JSONObject manifest=new JSONObject();
    private JSONObject entries=new JSONObject();
    private final Set<String> attachments=new LinkedHashSet<>();
    BackupArchive(File person,File export) throws Exception {
        ImageVault.keys.requireRecovery(person); this.person=person; key=ImageVault.keys.get(person,false);
        DataFiles.path(person,export);
        if(!export.isDirectory() && !export.mkdir()) throw new IOException("バックアップ先を作成できません。");
        directory=new File(export,"snapshot-"+UUID.randomUUID());
        if(!directory.mkdir()) throw new IOException("バックアップ先を作成できません。");
        manifest.put("format","conversation-zip-v3").put("complete",false);
    }
    public void writeChunk(String path,byte[] data) throws Exception {
        if(path.startsWith("messages-"))
            for(AttachmentFiles.Ref ref:AttachmentFiles.references(new String(data,StandardCharsets.UTF_8))) attachments.add(ref.fileName);
        Map<String,byte[]> input=new LinkedHashMap<>(); input.put(path,data);
        persist(EncryptedArchive.encode(key,person.getName(),input,manifest,entries)); entries=manifest.getJSONObject("files");
    }
    void finish() throws Exception {
        for(String name:attachments) writeChunk("media/"+name,AttachmentFiles.read(person,new AttachmentFiles.Ref("",name)));
        File icon=new File(person,"icon.png");
        if(icon.exists()) writeChunk("icon.png",ImageVault.read(person,icon));
        for(String name:new String[]{"person.md","profile.md","person.json","display-name.json"}) {
            File file=new File(person,name); if(file.exists()) writeChunk(name,DataFiles.read(person,file));
        }
        manifest.put("complete",true);
        persist(EncryptedArchive.encode(key,person.getName(),Collections.emptyMap(),manifest,entries));
    }
    private void persist(Map<String,byte[]> files) throws IOException {
        for(Map.Entry<String,byte[]> file:files.entrySet()) {
            File target=new File(directory,file.getKey()); DataFiles.path(person,target);
            if(!target.getParentFile().isDirectory() && !target.getParentFile().mkdir()) throw new IOException("バックアップ先を開けません。");
            File pending=new File(target.getPath()+".pending"); DataFiles.path(person,pending);
            try(FileOutputStream output=new FileOutputStream(pending)) { output.write(file.getValue()); output.getFD().sync(); }
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static void write(File directory,OutputStream destination) throws Exception {
        File person=directory.getParentFile().getParentFile();
        byte[] key=ImageVault.keys.get(person,false);
        try {
            JSONObject manifest=BackupRestore.verify(key,person.getName(),directory);
            Set<String> files=new LinkedHashSet<>(); files.add("format.json"); files.add("archive.enc");
            JSONObject entries=manifest.getJSONObject("files");
            for(Iterator<String> names=entries.keys();names.hasNext();) files.add(entries.getJSONObject(names.next()).getString("file"));
            try(ZipOutputStream zip=new ZipOutputStream(destination)) {
                for(String name:files) {
                    byte[] bytes=BackupRestore.readFile(directory,name);
                    zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
                }
            }
        } finally { Arrays.fill(key,(byte)0); }
    }
    @Override public void close() { Arrays.fill(key,(byte)0); }
}
