package jp.tekewo.conversation;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Saved memory access without an Activity lifetime. */
final class MemoryStore implements ChatSession.Store {
    private final Context context;
    private final String personId;

    MemoryStore(Context context, String personId) throws Exception {
        this.context = context.getApplicationContext(); this.personId = personId;
        System.loadLibrary("conversation");
        synchronized (MainActivity.storeGate) {
            PersonRegistry.Person person = current();
            String error = MainActivity.nativeSwitchPerson(bytes(person.directory.getAbsolutePath()), new byte[0]);
            if (!error.isEmpty()) throw new IOException(error);
        }
    }

    private PersonRegistry.Person current() throws Exception {
        PersonRegistry.Person person = new PersonRegistry(context.getFilesDir()).active();
        if (person == null || !person.id.equals(personId)) throw new IOException("人物が変わりました。");
        return person;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject read(String value) throws Exception {
        if (value.isEmpty()) throw new IOException(MainActivity.nativeLastError());
        return new JSONObject(value);
    }
    public long add(String role, String text) throws IOException { throw new IOException("記憶整理から会話は追加できません。"); }
    public long addReply(String text, String state) throws IOException { throw new IOException("記憶整理から返答は追加できません。"); }
    public JSONObject context(String query) throws Exception {
        synchronized (MainActivity.storeGate) { current(); return read(MainActivity.nativeContext(bytes(query))); }
    }
    public JSONObject pending() throws Exception {
        synchronized (MainActivity.storeGate) { current(); return read(MainActivity.nativePendingJob()); }
    }
    public void apply(long id, String payload) throws Exception {
        synchronized (MainActivity.storeGate) {
            current(); String error = MainActivity.nativeApplyJob(id, bytes(payload));
            if (!error.isEmpty()) throw new IOException(error);
        }
    }
    public JSONObject pendingPersona() throws Exception {
        synchronized (MainActivity.storeGate) { current(); return read(MainActivity.nativePendingPersona()); }
    }
    public void applyPersona(long throughId, String payload) throws Exception {
        synchronized (MainActivity.storeGate) {
            current(); String error = MainActivity.nativeApplyPersona(throughId, bytes(payload));
            if (!error.isEmpty()) throw new IOException(error);
        }
    }
}
