package jp.tekewo.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

final class DataMigration {
    private static final String[] LEGACY={"conversation.sqlite3","conversation.sqlite3-wal","conversation.sqlite3-shm","conversation.sqlite3-journal","notebook.json","export"};
    private static Set<String> roster(File root) throws Exception {
        File index=new File(root,"people-registry.json");Set<String> ids=new LinkedHashSet<>();
        if(!index.exists()) return ids;
        if(!index.isFile()||Files.isSymbolicLink(index.toPath())||index.length()>1024*1024) throw new IOException("人物一覧が不正です。");
        JSONObject json=new JSONObject(new String(Files.readAllBytes(index.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        if(json.getInt("version")!=1&&json.getInt("version")!=2) throw new IOException("未知の人物一覧です。");
        org.json.JSONArray people=json.getJSONArray("people");
        for(int i=0;i<people.length();i++) {
            String id=people.getJSONObject(i).getString("id");
            if(!UUID.fromString(id).toString().equals(id)||!ids.add(id)) throw new IOException("人物IDが不正です。");
        }
        return ids;
    }
    private static void inventory(File root,Set<String> ids) throws IOException {
        File folder=new File(root,"people");
        if(!folder.exists()) { if(!ids.isEmpty()) throw new IOException("人物の保存先がありません。");return; }
        if(!folder.isDirectory()||Files.isSymbolicLink(folder.toPath())) throw new IOException("人物の保存先が不正です。");
        File[] entries=folder.listFiles();if(entries==null) throw new IOException("人物の保存先を確認できません。");
        for(File entry:entries) if(!entry.isDirectory()||Files.isSymbolicLink(entry.toPath())||!ids.contains(entry.getName()))
            throw new IOException("未登録の旧人物データがあります。内容を保持して移行を停止しました。");
    }
    static boolean needed(Context context) {
        try {
            File root=context.getFilesDir(), index=new File(root,"people-registry.json");
            for(String key:context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).getAll().keySet())
                if(key.equals("draft")||key.startsWith("draft_")||key.startsWith("attachments_")||key.equals("memory_status")||key.equals("memory_phase")||key.equals("persona_status")) return true;
            for(String name:LEGACY) if(new File(root,name).exists()) return true;
            if(new File(root,"people-registry.json.tmp").exists()) return true;
            Set<String> ids=roster(root);inventory(root,ids);
            if(!index.exists()) return false;
            JSONObject json=new JSONObject(new String(Files.readAllBytes(index.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(json.getInt("version")!=2) return true;
            for(String id:ids) {
                File person=new File(root,"people/"+id);
                if(!new File(person,".data-migrated-v1").isFile()) return true;
                try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) {
                    if(paths.anyMatch(path->path.getFileName().toString().endsWith(".tmp")||path.getFileName().toString().matches("image-[A-Za-z0-9-]+\\.pending"))) return true;
                }
            }
            return false;
        } catch(Exception error) { return true; }
    }
    static void ensure(Context context) throws Exception {
        synchronized(MainActivity.storeGate) {
            File root=context.getFilesDir();
            for(String name:LEGACY)
                if(new File(root,name).exists()) throw new IOException("人物に紐づかない旧データが残っています。データを保持して移行を停止しました。");
            PersonRegistry people;
            synchronized(PersonRegistry.class) {
                Set<String> ids=roster(root);inventory(root,ids);
                File index=new File(root,"people-registry.json"),temporary=new File(root,"people-registry.json.tmp");
                if(!index.exists()&&temporary.exists()) throw new IOException("未確定の旧人物一覧があります。原本を保持して停止しました。");
                for(String id:ids) DataFiles.recoverInterrupted(new File(root,"people/"+id));
                people=new PersonRegistry(root);
                if(temporary.exists()) {
                    if(!temporary.isFile()||Files.isSymbolicLink(temporary.toPath())||temporary.length()>1024*1024) throw new IOException("未確定の人物一覧を確認できません。");
                    // The committed registry is valid and every person directory is accounted for.
                    Files.delete(temporary.toPath());
                }
            }
            SharedPreferences preferences=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
            if(people.active()==null) {
                for(String key:new String[]{"draft","memory_status","memory_phase","persona_status"})
                    if(!preferences.getString(key,"").isEmpty()) throw new IOException("人物に紐づかない旧下書き・作業状態が残っています。内容を保持して移行を停止しました。");
                if(!preferences.edit().remove("draft").remove("memory_status").remove("memory_phase").remove("persona_status").commit()) throw new IOException("旧設定の移行を確定できません。");
            }
            for(PersonRegistry.Person person:people.list()) {
                File marker=new File(person.directory,".data-migrated-v1");
                boolean migrated=marker.exists();
                if(migrated) DataFiles.read(person.directory,marker); else migratePerson(person.directory);
                JSONObject state=PersonState.load(person.directory);
                SharedPreferences.Editor remove=preferences.edit();
                boolean changed=false;
                for(String name:new String[]{"draft","attachments"}) {
                    String legacy=name+"_"+person.id;
                    if(preferences.contains(legacy)) { merge(state,name,preferences.getString(legacy,"")); remove.remove(legacy);changed=true; }
                }
                if(people.active()!=null && people.active().id.equals(person.id)) {
                    for(String name:new String[]{"memory_status","memory_phase","persona_status","draft"})
                        if(preferences.contains(name)) { merge(state,name,preferences.getString(name,"")); remove.remove(name);changed=true; }
                }
                if(migrated&&!changed) continue;
                PersonState.save(person.directory,state);
                if(!remove.commit()) throw new IOException("旧設定の移行を確定できません。暗号化した内容は保持しています。");
                DataFiles.write(person.directory,marker,new byte[]{1});
            }
            for(String key:preferences.getAll().keySet())
                if(key.startsWith("draft_")||key.startsWith("attachments_")) throw new IOException("元の人物が見つからない旧下書き・添付情報を保持して移行を停止しました。");
        }
    }
    private static void merge(JSONObject state,String name,String value) throws Exception {
        String current=state.optString(name);
        if(!current.isEmpty()&&!value.isEmpty()&&!current.equals(value)) throw new IOException("新旧の下書き・作業状態が異なります。両方を保持して移行を停止しました。");
        if(current.isEmpty()) state.put(name,value);
    }
    static void migratePerson(File person) throws Exception {
        for(String name:new String[]{"conversation.sqlite3",".semantic-cache.sqlite3",MemoryRebuild.FILE})
            EncryptedDatabase.migrate(person,new File(person,name));
        byte[] key=ImageVault.keys.get(person,true);
        try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                if(Files.isSymbolicLink(path)) throw new IOException("移行するデータの保存先が不正です。");
                File file=path.toFile(); if(!file.isFile()) continue;
                String name=file.getName();
                if(name.equals(".data-key-v1") || name.endsWith(".sqlite3")) continue;
                if(name.endsWith("-wal")||name.endsWith("-shm")||name.endsWith("-journal")||name.endsWith(".encrypted"))
                    throw new IOException("未完了のDB作業データが残っています。原本を保持して再試行してください。");
                byte[] header=new byte[5];
                try(InputStream input=new FileInputStream(file)) { input.read(header); }
                if(ImageVault.encrypted(header)) {
                    String original=name.endsWith(".enc")?name.substring(0,name.length()-4):name;
                    if(file.length()>ImageVault.MAX_BYTES+ImageVault.OVERHEAD) throw new IOException("画像が大きすぎます。");
                    ImageVault.open(key,person.getName(),original,Files.readAllBytes(path));
                } else if(ImageVault.image(name)) {
                    byte[] bytes=ImageVault.read(person,file); ImageVault.protect(person,file,bytes);
                } else DataFiles.migrate(person,file);
            }
        } finally { Arrays.fill(key,(byte)0); }
    }
}
