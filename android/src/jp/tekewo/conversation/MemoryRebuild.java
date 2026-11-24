package jp.tekewo.conversation;

import android.content.ContentValues;
import android.database.Cursor;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
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
    private static final String RETRYING = "再試行待ちです";
    private static final int PROFILE_LIMIT = 2000;
    private static final int PROFILE_PAYLOAD_BYTES = 128 * 1024;
    static final class Progress {
        final String text;
        final boolean profile, paused, retrying;
        Progress(String text, boolean profile, boolean paused, boolean retrying) {
            this.text = text; this.profile = profile; this.paused = paused; this.retrying = retrying;
        }
    }
    interface Native {
        JSONObject context() throws Exception;
        JSONObject batch(long afterId, long throughId) throws Exception;
        default JSONObject profileBatch(long afterId, long throughId) throws Exception { return batch(afterId, throughId); }
        boolean publish() throws Exception;
        default boolean publishProfile(long throughId, JSONObject profile) throws Exception { throw invalid(); }
    }
    private final File file;
    private final Runnable changed;
    MemoryRebuild(File directory) { this(directory, () -> {}); }
    MemoryRebuild(File directory, Runnable changed) { file = new File(directory, FILE); this.changed = changed; }
    static boolean pending(File directory) { return new File(directory, FILE).isFile(); }
    boolean isPending() { return file.isFile(); }
    boolean isRunnable() {
        if (!isPending()) return false;
        try (SQLiteDatabase db = open()) { return !profileMode(db) || !isPaused(profileRun(db)); }
        catch (Exception error) { return isPending(); }
    }
    boolean isProfile() {
        if (!isPending()) return false;
        try (SQLiteDatabase db = open()) { return profileMode(db); }
        catch (Exception error) { return false; }
    }
    private SQLiteDatabase open() throws IOException {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw invalid();
        return EncryptedDatabase.open(file.getParentFile(),file,false);
    }
    void request(long boundary) throws Exception { request(boundary, false); }

    void request(long boundary, boolean profile) throws Exception {
        if (boundary < 1) throw new IOException("整理する過去の会話がまだありません。");
        synchronized (MemoryRebuild.class) {
            if (file.isFile()) {
                Boolean existingMode = null;
                try (SQLiteDatabase db = open()) { boolean isProfile = profileMode(db); validate(db, isProfile); existingMode = isProfile; }
                catch (Exception invalidCache) { if(invalidCache instanceof EncryptedDatabase.Failure) throw invalidCache; if (!SQLiteDatabase.deleteDatabase(file)) throw invalid(); }
                if (existingMode != null) {
                    if (existingMode != profile) throw new IOException("別の一括整理が進行中です。");
                    return;
                }
            }
            File temporary = new File(file.getParentFile(), FILE + "." + UUID.randomUUID() + ".tmp");
            try {
                try (SQLiteDatabase db = EncryptedDatabase.open(temporary.getParentFile(),temporary,false)) {
                    db.beginTransaction();
                    try {
                        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
                        db.execSQL("CREATE TABLE nodes(root_key TEXT NOT NULL,key TEXT NOT NULL,parent_key TEXT NOT NULL,depth INTEGER NOT NULL,node TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(root_key,key))");
                        put(db, "boundary", Long.toString(boundary)); put(db, "cursor", "0");
                        // An older app must reject a profile stage before attempting episode publication.
                        put(db, "roots", profile ? "{}" : "[]");
                        if (profile) { put(db, "mode", "profile"); put(db, "profile", "{}"); }
                        put(db, "request_id", UUID.randomUUID().toString());
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally { SQLiteDatabase.deleteDatabase(temporary); }
        }
    }
    String status() { return progress().text; }

    Progress progress() {
        if (!file.isFile()) return new Progress("", false, false, false);
        try (SQLiteDatabase db = EncryptedDatabase.open(file.getParentFile(),file,true)) {
            boolean profile = profileMode(db);
            validate(db, profile);
            String phase = "実行を待っています", error = "";
            try (Cursor rows = db.rawQuery("SELECT key,value FROM meta WHERE key IN ('phase','error')", null)) {
                while (rows.moveToNext()) { if (rows.getString(0).equals("phase")) phase = rows.getString(1); else error = rows.getString(1); }
            }
            boolean paused = profile && isPaused(profileRun(db));
            String title = profile ? (paused ? "プロフィール作成を一時停止中" : "プロフィールを作成中") : "過去の記憶を階層に整理中";
            String text = title + "\n" + meta(db, "cursor") + " / " + meta(db, "boundary") + " 発言";
            if (!paused) text += "\n" + phase + (error.isEmpty() ? "" : "\n" + error);
            return new Progress(text, profile, paused, !paused && phase.equals(RETRYING));
        } catch (Exception invalidCache) {
            String text = invalidCache instanceof EncryptedDatabase.Failure
                    ? "整理中のデータを復号できません。原本と作業データを保持して停止しています。復元キーを確認してください。"
                    : file.isFile() ? "過去の記憶の作業データを再作成して続けます。現在の記憶は保持しています。" : "";
            return new Progress(text, false, false, false);
        }
    }
    boolean setProfilePaused(boolean paused) throws Exception {
        synchronized (MainActivity.storeGate) {
            if (!file.isFile()) return false;
            try (SQLiteDatabase db = open()) {
                db.beginTransaction();
                try {
                    if (!profileMode(db)) return false;
                    long run = profileRun(db);
                    if (isPaused(run) == paused) return false;
                    put(db, "profile_run", Long.toString(run + 1));
                    put(db, "phase", "実行を待っています"); put(db, "error", "");
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
        }
        changed.run();
        return true;
    }
    void phase(String message) { report(message, ""); }
    void failed(String message) { report(RETRYING, message); }
    private void report(String phase, String error) {
        if (!file.isFile()) return;
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                if (isPaused(profileRun(db))) return;
                put(db, "phase", phase); put(db, "error", error); db.setTransactionSuccessful();
            }
            finally { db.endTransaction(); }
            android.util.Log.i("ConversationMemory", "Rebuild: " + phase);
            changed.run();
        } catch (Exception ignored) { android.util.Log.w("ConversationMemory", "Rebuild status could not be updated"); }
    }
    private static boolean profileMode(SQLiteDatabase db) throws IOException {
        try (Cursor row = db.rawQuery("SELECT value FROM meta WHERE key='mode'", null)) {
            if (!row.moveToFirst()) return false;
            if (!"profile".equals(row.getString(0))) throw invalid();
            return true;
        }
    }
    private static void validate(SQLiteDatabase db, boolean profile) throws Exception {
        long cursor = Long.parseLong(meta(db, "cursor")), boundary = Long.parseLong(meta(db, "boundary"));
        String roots = meta(db, "roots"), request = meta(db, "request_id");
        if (cursor < 0 || boundary < cursor
                || !UUID.fromString(request).toString().equals(request)) throw invalid();
        if (profile) {
            String fields = meta(db, "profile");
            if (!roots.equals("{}") || bytes(fields) > PROFILE_PAYLOAD_BYTES || new JSONObject(fields).length() > 8) throw invalid();
            return;
        }
        if (bytes(roots) > 65536 || new JSONArray(roots).length() > 30) throw invalid();
        try (Cursor schema = db.rawQuery("SELECT root_key,key,parent_key,depth,node,path FROM nodes LIMIT 0", null)) { schema.getColumnCount(); }
    }
    private static String meta(SQLiteDatabase db, String key) throws IOException {
        String value = optionalMeta(db, key, null);
        if (value == null) throw invalid();
        return value;
    }
    private static String optionalMeta(SQLiteDatabase db, String key, String fallback) {
        try (Cursor row = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            return row.moveToFirst() ? row.getString(0) : fallback;
        }
    }
    // Odd generations are paused. A quick pause/resume also invalidates the previous response.
    private static long profileRun(SQLiteDatabase db) { return Long.parseLong(optionalMeta(db, "profile_run", "0")); }
    private static boolean isPaused(long run) { return (run & 1) != 0; }
    private boolean stillCurrent(long run) throws IOException {
        if (!isPending()) return false;
        try (SQLiteDatabase db = open()) { return profileRun(db) == run; }
    }
    private static void put(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", new Object[]{key, value});
    }
    boolean step(Native source, ChatSession.Transport backend) throws Exception {
        if (!file.isFile()) return false;
        JSONObject context = source.context();
        long latest = context.getJSONObject("memory_progress").getLong("total_messages");
        long cursor, boundary;
        JSONArray roots = new JSONArray(), known = new JSONArray();
        JSONObject stagedProfile = new JSONObject();
        boolean profile = false;
        long run = 0;
        long splitThrough = 0;
        boolean correctingProfile = false;
        int batchLimit = 64;
        try (SQLiteDatabase db = open()) {
            profile = profileMode(db);
            validate(db, profile);
            cursor = Long.parseLong(meta(db, "cursor")); boundary = Long.parseLong(meta(db, "boundary"));
            if (cursor < 0 || boundary < cursor || latest < boundary) throw invalid();
            if (profile) {
                run = profileRun(db);
                if (isPaused(run)) return false;
                splitThrough = Long.parseLong(optionalMeta(db, "profile_split_through", "0"));
                batchLimit = splitThrough > cursor
                        ? Integer.parseInt(optionalMeta(db, "profile_split_limit", Integer.toString(PROFILE_LIMIT))) : PROFILE_LIMIT;
                if (batchLimit < 1 || batchLimit > PROFILE_LIMIT || splitThrough > boundary) throw invalid();
                correctingProfile = Long.parseLong(optionalMeta(db, "profile_retry_cursor", "-1")) == cursor;
                stagedProfile = new JSONObject(meta(db, "profile"));
            }
            else {
                roots = new JSONArray(meta(db, "roots"));
                try (Cursor rows = db.rawQuery("SELECT root_key,parent_key,depth,node FROM nodes ORDER BY rowid DESC LIMIT 24", null)) {
                while (rows.moveToNext()) known.put(new JSONObject(rows.getString(3)).put("root_key", rows.getString(0))
                        .put("parent_key", rows.getString(1)).put("depth", rows.getInt(2)));
                }
            }
        } catch (Exception invalidCache) {
            if(invalidCache instanceof EncryptedDatabase.Failure) throw invalidCache;
            if (!file.isFile()) return false;
            if (!SQLiteDatabase.deleteDatabase(file)) throw invalid();
            request(latest, profile);
            return true;
        }
        if (cursor == boundary) {
            phase(profile ? "プロフィールを保存しています" : "整理した階層を保存しています");
            boolean published;
            if (profile) synchronized (MainActivity.storeGate) {
                if (!stillCurrent(run)) return false;
                published = source.publishProfile(boundary, stagedProfile);
            } else published = source.publish();
            if (published) {
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
        long batchBoundary = Math.min(boundary, cursor + batchLimit);
        if (splitThrough > cursor) batchBoundary = Math.min(batchBoundary, splitThrough);
        JSONObject batch = profile ? source.profileBatch(cursor, batchBoundary) : source.batch(cursor, batchBoundary);
        JSONArray messages = batch.getJSONArray("messages");
        long through = batch.getLong("through_id");
        if (messages.length() < 1 || messages.length() > batchLimit || through <= cursor || through > batchBoundary
                || batch.getLong("boundary_id") != batchBoundary || through - cursor != messages.length()) throw invalid();
        Set<Long> allowed = new HashSet<>();
        Set<Long> assistantSources = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            long id = integer(message.get("id"));
            if (id != cursor + i + 1) throw invalid();
            allowed.add(id);
            if (profile && message.optString("role").equals("assistant") && !message.has("annotation")) assistantSources.add(id);
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
                .put("time_zone", java.util.TimeZone.getDefault().getID());
        JSONObject currentProfile = null;
        if (profile) {
            currentProfile = ChatSession.profileSections(context);
            for (String section : ChatSession.PROFILE_SECTIONS) {
                JSONObject staged = stagedProfile.optJSONObject(section);
                if (staged != null && staged.optLong("base_id", -1) != currentProfile.getJSONObject(section).optLong("revision"))
                    stagedProfile.remove(section);
            }
            data.put("profile", stagedProfile);
            data.put("current_profile", ChatSession.profileValues(currentProfile));
            if (correctingProfile) data.put("retry_guidance", "前回の応答は形式または出典を検証できませんでした。指定のJSONだけを返し、source_idは今回の本人の発言IDにしてください。裏付けがある項目だけ返してください。");
        }
        else data.put("continuity", new JSONObject().put("threads", new JSONArray()).put("episodes", roots))
                .put("known_nodes", known).put("evidence", evidence);
        String range = (cursor + 1) + "〜" + through + "発言：";
        phase(range + "AIの応答を待っています");
        long[] received = {0}, updated = {0};
        if (profile && !stillCurrent(run)) return false;
        String response;
        try { response = backend.request(profile ? ChatSession.REBUILD_PROFILE : ChatSession.REBUILD,
                new JSONArray().put(new JSONObject().put("role", "user").put("content", data.toString())), delta -> {
                    received[0] += delta.length();
                    long now = System.nanoTime();
                    if (updated[0] == 0 || now - updated[0] >= 1_000_000_000L) {
                        updated[0] = now; phase(range + "AIの応答を受信中（" + received[0] + "文字）");
                    }
                }); }
        catch (IOException error) {
            if (profile && !stillCurrent(run)) return false;
            if (profile && ChatGptConnection.isInputTooLarge(error) && reduceProfileBatch(run, through, messages.length())) return true;
            throw error;
        }
        phase(range + "結果を検証しています");
        if (profile) {
            if (!stillCurrent(run)) return false;
            try { validateProfile(stagedProfile, ChatSession.jsonPayload(response), assistantSources, currentProfile); }
            catch (IOException | org.json.JSONException error) {
                if (!recordProfileValidationFailure(run, cursor)) return false;
                throw new IOException("AIのプロフィール応答の形式または出典を確認できませんでした。同じ範囲でやり直します。");
            }
            if (!saveProfile(stagedProfile, through, run)) return false;
            phase("次の範囲の整理を準備しています");
            return true;
        }
        String answer = ChatSession.jsonPayload(response);
        if (bytes(answer) > 131072) throw invalid("整理結果の文章量が上限を超えています。");
        JSONObject result = new JSONObject(answer), continuity = result.getJSONObject("continuity");
        if (result.length() != 2 || result.getJSONArray("memories").length() != 0 || continuity.length() != 2
                || continuity.getJSONArray("threads").length() != 0) throw invalid("AIの応答が階層記憶用の形式になっていません。");
        JSONArray next = continuity.getJSONArray("episodes"), compact = new JSONArray();
        if (next.length() > 128) throw invalid("一度の更新件数が上限を超えています。");
        java.util.LinkedHashMap<String, JSONObject> summaries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < roots.length(); i++) summaries.put(roots.getJSONObject(i).getString("key"), roots.getJSONObject(i));
        Set<String> seen = new HashSet<>();
        int[] count = {0};
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                for (int i = 0; i < next.length(); i++) {
                    JSONObject node = next.getJSONObject(i);
                    String root = text(node, "key", 96, false), scope = text(node, "scope", 16, false);
                    summaries.put(root, saveNode(db, node, root, "", scope, "", 0, through, allowed, seen, count));
                }
                int skip = Math.max(0, summaries.size() - 30), index = 0;
                for (JSONObject summary : summaries.values()) if (index++ >= skip) compact.put(summary);
                if (bytes(compact.toString()) > 65536) throw invalid();
                put(db, "roots", compact.toString()); put(db, "cursor", Long.toString(through));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        phase("次の範囲の整理を準備しています");
        return true;
    }

    private static void validateProfile(JSONObject staged, String answer, Set<Long> allowed, JSONObject currentProfile) throws Exception {
        if (bytes(answer) > PROFILE_PAYLOAD_BYTES) throw invalid("プロフィールの整理結果が長すぎます。");
        JSONObject result = new JSONObject(answer), fields = result.getJSONObject("profile");
        if (result.length() != 1 || fields.length() > 8) throw invalid("プロフィールの項目が保存条件を満たしていません。");
        for (java.util.Iterator<String> labels = fields.keys(); labels.hasNext();) {
            String label = labels.next();
            boolean section = ChatSession.isProfileSection(label);
            switch (label) {
                case "名前": case "身長": case "体重": case "趣味": case "好きなもの": break;
                default: if (!section) throw invalid("未対応のプロフィール項目があります。");
            }
            JSONObject field = fields.getJSONObject(label);
            String value = text(field, "value", section ? ChatSession.PROFILE_SECTION_BYTES : label.equals("名前") ? 256 : 512, false);
            long id = integer(field.get("source_id"));
            if (field.length() != 2 || !allowed.contains(id)
                    || (!section && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\t') >= 0)))
                throw invalid("プロフィールの根拠が今回の本人の発言と一致しません。");
            if (section) field.put("base_id", currentProfile.getJSONObject(label).optLong("revision"));
            staged.put(label, field);
        }
        if (bytes(staged.toString()) > PROFILE_PAYLOAD_BYTES) throw invalid("プロフィールの整理結果が長すぎます。");
    }

    private boolean reduceProfileBatch(long run, long through, int attempted) throws IOException {
        if (attempted <= 1) return false;
        int limit = (attempted + 1) / 2;
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                if (profileRun(db) != run) return false;
                long splitThrough = Long.parseLong(optionalMeta(db, "profile_split_through", "0"));
                put(db, "profile_split_through", Long.toString(Math.max(through, splitThrough)));
                put(db, "profile_split_limit", Integer.toString(limit));
                put(db, "phase", "入力サイズ超過のため、この範囲を " + limit + "発言ずつ読み直します"); put(db, "error", "");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        changed.run();
        return true;
    }

    private boolean recordProfileValidationFailure(long run, long cursor) throws IOException {
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                if (profileRun(db) != run) return false;
                put(db, "profile_retry_cursor", Long.toString(cursor));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        return true;
    }

    private boolean saveProfile(JSONObject staged, long through, long run) throws IOException {
        try (SQLiteDatabase db = open()) {
            db.beginTransaction();
            try {
                if (profileRun(db) != run) return false;
                put(db, "profile", staged.toString());
                put(db, "cursor", Long.toString(through));
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
        if (depth > 3 || ++count[0] > 128 || input.length() != (input.has("children") ? 7 : 6)) throw invalid("階層の深さ・件数・項目が保存条件を満たしていません。");
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
            if (id < 1 || id > through || !allowed.contains(id) || !used.add(id)) throw invalid("出典が原文と一致しないか重複しています。");
        }
        try (Cursor old = db.rawQuery("SELECT parent_key,depth,node FROM nodes WHERE root_key=? AND key=?", new String[]{root, key})) {
            if (old.moveToFirst() && (!parent.equals(old.getString(0)) || depth != old.getInt(1)
                    || !scope.equals(new JSONObject(old.getString(2)).getString("scope")))) throw invalid("保存済みの親子関係や現実・創作の区分が変更されています。");
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
        if (!(value instanceof String)) throw invalid("記憶の項目が文章になっていません。");
        String text = (String)value;
        if ((!empty && text.trim().isEmpty()) || text.indexOf('\0') >= 0 || bytes(text) > maximum) throw invalid("記憶の文章量や形式が保存条件を満たしていません。");
        return text;
    }
    private static long integer(Object value) throws IOException {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw invalid();
        return ((Number)value).longValue();
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static IOException invalid() { return new IOException("過去の記憶の整理結果を検証できませんでした。原文と現在の記憶を保持して再試行します。"); }
    private static IOException invalid(String reason) { return new IOException(reason + " 原文と現在の記憶を保持して再試行します。"); }
}
