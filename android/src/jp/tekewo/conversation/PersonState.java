package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;

final class PersonState {
    static synchronized JSONObject load(File person) throws IOException {
        File file=new File(person,"state.json");
        if(!file.exists()) return new JSONObject();
        try { return new JSONObject(new String(DataFiles.read(person,file),StandardCharsets.UTF_8)); }
        catch(Exception error) { throw new IOException("保存した下書き・作業状態を読み込めません。",error); }
    }
    static synchronized void save(File person,JSONObject state) throws IOException {
        DataFiles.write(person,new File(person,"state.json"),state.toString().getBytes(StandardCharsets.UTF_8));
    }
    static synchronized void put(File person,String key,String value) throws IOException {
        try { JSONObject state=load(person); state.put(key,value); save(person,state); }
        catch(org.json.JSONException error) { throw new IOException(error); }
    }
}
