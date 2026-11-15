package jp.tekewo.conversation;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Rebuild cache only. The original history is published by Core after complete validation. */
final class MemoryRebuild {
    static final String FILE = ".memory-rebuild.sqlite3";
    interface Native {
        JSONObject context() throws Exception;
        JSONObject batch(long afterId, long throughId) throws Exception;
        boolean publish() throws Exception;
    }
    private final File file;
    MemoryRebuild(File directory) { file = new File(directory, FILE); }
    static boolean pending(File directory) { return new File(directory, FILE).isFile(); }
    private SQLiteDatabase open() throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw invalid();
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    }
    void request(long boundary) throws Exception {
        if (boundary < 1) throw new IOException("整理する過去の会話がまだありません。");
        synchronized (MemoryRebuild.class) {
            if (file.isFile()) {
                try (SQLiteDatabase db = open()) { validate(db); return; }
                catch (Exception invalidCache) { if (!SQLiteDatabase.deleteDatabase(file)) throw invalid(); }
            }
            File temporary = new File(file.getParentFile(), FILE + "." + UUID.randomUUID() + ".tmp");
            try {
                try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(temporary, null)) {
                    db.beginTransaction();
                    try {
                        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                        db.execSQL("CREATE TABLE nodes(root_key TEXT NOT NULL,key TEXT NOT NULL,parent_key TEXT NOT NULL,depth INTEGER NOT NULL,node TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(root_key,key))");
                        put(db, "boundary", Long.toString(boundary)); put(db, "cursor", "0"); put(db, "roots", "[]");
                        put(db, "request_id", UUID.randomUUID().toString());
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally { SQLiteDatabase.deleteDatabase(temporary); }
        }
    }
    String status() throws Exception {
        if (!file.isFile()) return "";
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            validate(db);
            return "過去の記憶を階層に整理中\n" + meta(db, "cursor") + " / " + meta(db, "boundary") + " 発言";
        } catch (Exception invalidCache) { return file.isFile() ? "過去の記憶の作業データを再作成して続けます。現在の記憶は保持しています。" : ""; }
    }
    private static void validate(SQLiteDatabase db) throws Exception {
        long cursor = Long.parseLong(meta(db, "cursor")), boundary = Long.parseLong(meta(db, "boundary"));
        String roots = meta(db, "roots"), request = meta(db, "request_id");
        if (cursor < 0 || boundary < cursor || bytes(roots) > 65536 || new JSONArray(roots).length() > 30
                || !UUID.fromString(request).toString().equals(request)) throw invalid();
        try (Cursor schema = db.rawQuery("SELECT root_key,key,parent_key,depth,node,path FROM nodes LIMIT 0", null)) { schema.getColumnCount(); }
    }
    private static String meta(SQLiteDatabase db, String key) throws IOException {
        try (Cursor row = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            if (!row.moveToFirst()) throw invalid();
            return row.getString(0);
        }
    }
    private static void put(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", new Object[]{key, value});
    }
    boolean step(Native source, ChatSession.Transport backend) throws Exception {
        if (!file.isFile()) return false;
        JSONObject context = source.context();
        long latest = context.getJSONObject("memory_progress").getLong("total_messages");
        long cursor, boundary;
        JSONArray roots, known = new JSONArray();
        try (SQLiteDatabase db = open()) {
            validate(db);
            cursor = Long.parseLong(meta(db, "cursor")); boundary = Long.parseLong(meta(db, "boundary"));
            if (cursor < 0 || boundary < cursor || latest < boundary) throw invalid();
            roots = new JSONArray(meta(db, "roots"));
            try (Cursor rows = db.rawQuery("SELECT root_key,parent_key,depth,node FROM nodes ORDER BY rowid DESC LIMIT 24", null)) {
                while (rows.moveToNext()) known.put(new JSONObject(rows.getString(3)).put("root_key", rows.getString(0))
                        .put("parent_key", rows.getString(1)).put("depth", rows.getInt(2)));
            }
        } catch (Exception invalidCache) {
            if (!file.isFile()) return false;
            if (!SQLiteDatabase.deleteDatabase(file)) throw invalid();
            request(latest);
            return true;
        }
        if (cursor == boundary) {
            if (source.publish()) {
                if (!SQLiteDatabase.deleteDatabase(file)) throw new IOException("整理結果は保存されました。作業データの後片付けを再試行します。");
                return false;
            }
            latest = source.context().getJSONObject("memory_progress").getLong("total_messages");
            if (latest <= boundary) throw invalid();
        }
        if (latest > boundary) {
            boundary = latest;
            try (SQLiteDatabase db = open()) { put(db, "boundary", Long.toString(boundary)); }
        }
        JSONObject batch = source.batch(cursor, boundary);
        JSONArray messages = batch.getJSONArray("messages");
        long through = batch.getLong("through_id");
        if (messages.length() < 1 || messages.length() > 4096 || through <= cursor || through > boundary
                || batch.getLong("boundary_id") != boundary || through - cursor != messages.length()) throw invalid();
        Set<Long> allowed = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            long id = integer(messages.getJSONObject(i).get("id"));
            if (id != cursor + i + 1) throw invalid();
            allowed.add(id);
        }
        Set<Long> originals = new java.util.LinkedHashSet<>();
        collectSources(known, allowed, originals); collectSources(roots, allowed, originals);
        JSONArray evidence = new JSONArray();
        int evidenceBytes = 0;
        for (long id : originals) {
            if (id > cursor || evidence.length() >= 54) continue;
            JSONArray rows = source.batch(id - 1, id).getJSONArray("messages");
            if (rows.length() != 1 || rows.getJSONObject(0).getLong("id") != id) throw invalid();
            JSONObject original = rows.getJSONObject(0);
            String content = original.getString("content");
            if (content.codePointCount(0, content.length()) > 1024)
                original.put("content", content.substring(0, content.offsetByCodePoints(0, 1024))).put("truncated", true);
            int size = bytes(original.toString());
            if (evidenceBytes + size > 20000) break;
            evidenceBytes += size;
            evidence.put(original);
        }
        JSONObject data = new JSONObject().put("job", batch).put("person", context.optJSONObject("person"))
                .put("time_zone", java.util.TimeZone.getDefault().getID())
                .put("continuity", new JSONObject().put("threads", new JSONArray()).put("episodes", roots))
                .put("known_nodes", known).put("evidence", evidence);
        String answer = ChatSession.jsonPayload(backend.request(ChatSession.REBUILD,
                new JSONArray().put(new JSONObject().put("role", "user").put("content", data.toString())), ignored -> {}));
        if (bytes(answer) > 131072) throw invalid();
        JSONObject result = new JSONObject(answer), continuity = result.getJSONObject("continuity");
        if (result.length() != 2 || result.getJSONArray("memories").length() != 0 || continuity.length() != 2
                || continuity.getJSONArray("threads").length() != 0) throw invalid();
        JSONArray next = continuity.getJSONArray("episodes"), compact = new JSONArray();
        if (next.length() > 30 || roots.length() > 0 && next.length() == 0) throw invalid();
        Set<String> seen = new HashSet<>();
        int[] count = {0};
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                for (int i = 0; i < next.length(); i++) {
                    JSONObject node = next.getJSONObject(i);
                    String root = text(node, "key", 96, false), scope = text(node, "scope", 16, false);
                    compact.put(saveNode(db, node, root, "", scope, "", 0, through, allowed, seen, count));
                }
                if (bytes(compact.toString()) > 65536) throw invalid();
                put(db, "roots", compact.toString()); put(db, "cursor", Long.toString(through));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return true;
    }
    private static void collectSources(JSONArray nodes, Set<Long> allowed, Set<Long> originals) throws Exception {
        for (int i = 0; i < nodes.length(); i++) {
            JSONArray ids = nodes.getJSONObject(i).getJSONArray("source_ids");
            for (int j = 0; j < ids.length(); j++) allowed.add(integer(ids.get(j)));
            if (ids.length() > 0) originals.add(integer(ids.get(0)));
        }
    }
    private static JSONObject saveNode(SQLiteDatabase db, JSONObject input, String root, String parent, String scope, String ancestor,
            int depth, long through, Set<Long> allowed, Set<String> seen, int[] count) throws Exception {
        if (depth > 3 || ++count[0] > 128 || input.length() != (input.has("children") ? 7 : 6)) throw invalid();
        String key = text(input, "key", 96, false), description = text(input, "text", 1536, false);
        String emotion = text(input, "emotion", 768, true), scene = text(input, "scene", depth == 0 ? 768 : 256, true);
        String kind = text(input, "scope", 16, false);
        if ((!scope.equals("real") && !scope.equals("fiction") && !scope.equals("shared")) || !scope.equals(kind)
                || !seen.add(root + "\0" + key)) throw invalid();
        JSONArray ids = input.getJSONArray("source_ids");
        if (ids.length() < 1 || ids.length() > 8) throw invalid();
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            long id = integer(ids.get(i));
            if (id < 1 || id > through || !allowed.contains(id) || !used.add(id)) throw invalid();
        }
        try (Cursor old = db.rawQuery("SELECT parent_key,depth,node FROM nodes WHERE root_key=? AND key=?", new String[]{root, key})) {
            if (old.moveToFirst() && (!parent.equals(old.getString(0)) || depth != old.getInt(1)
                    || !scope.equals(new JSONObject(old.getString(2)).getString("scope")))) throw invalid();
        }
        String path = depth == 0 ? scene : ancestor + " / " + scene;
        JSONObject node = new JSONObject().put("key", key).put("text", description).put("emotion", emotion)
                .put("scene", scene).put("scope", scope).put("source_ids", ids);
        ContentValues row = new ContentValues();
        row.put("root_key", root); row.put("key", key); row.put("parent_key", parent); row.put("depth", depth);
        row.put("node", node.toString()); row.put("path", path);
        if (db.insertWithOnConflict("nodes", null, row, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw invalid();
        if (input.has("children")) {
            JSONArray children = input.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) saveNode(db, children.getJSONObject(i), root, key, scope, path,
                    depth + 1, through, allowed, seen, count);
        }
        return node;
    }
    private static String text(JSONObject node, String key, int maximum, boolean empty) throws Exception {
        Object value = node.get(key);
        if (!(value instanceof String)) throw invalid();
        String text = (String)value;
        if ((!empty && text.trim().isEmpty()) || text.indexOf('\0') >= 0 || bytes(text) > maximum) throw invalid();
        return text;
    }
    private static long integer(Object value) throws IOException {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        return ((Number)value).longValue();
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static IOException invalid() { return new IOException("過去の記憶の整理結果を検証できませんでした。原文と現在の記憶を保持して再試行します。"); }
}
