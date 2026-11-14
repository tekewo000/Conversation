package jp.tekewo.conversation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Rebuildable per-person vectors; original memories and source validation stay in Odin. */
final class SemanticMemory implements Closeable {
    interface Source { JSONObject batch(long afterId) throws Exception; }
    private final Context context;
    private final File cacheFile;
    private final Source source;
    private final Consumer<String> status;
    private final Object gate = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Conversation semantic memory"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    private SQLiteDatabase cache;
    private LocalEmbedder embedder;
    private long index, cursor;

    SemanticMemory(Context context, File personDirectory, Source source, Consumer<String> status) {
        this.context = context.getApplicationContext(); this.source = source; this.status = status;
        cacheFile = new File(personDirectory, ".semantic-cache.sqlite3");
    }
    synchronized void refresh() { if (!closed) worker.execute(this::update); }
    long[] search(String text) {
        LocalEmbedder model;
        synchronized (gate) { if (closed || index == 0 || embedder == null) return new long[0]; model = embedder; }
        try {
            float[] query = model.embed("検索クエリ: " + text);
            synchronized (gate) { return closed || index == 0 ? new long[0] : NativeRag.search(index, query); }
        } catch (Exception error) { if (!closed) status.accept("意味検索は再試行待ちです。通常の検索で会話を続けられます。"); return new long[0]; }
    }
    private void initialize() throws Exception {
        if (embedder == null) {
            RagModels.Assets files = RagModels.ensure(context, status);
            if (closed) return;
            embedder = new LocalEmbedder(files);
        }
        synchronized (gate) {
            if (closed || cache != null) return;
            try { openCache(); }
            catch (Exception error) {
                releaseCache();
                SQLiteDatabase.deleteDatabase(cacheFile);
                openCache();
            }
        }
    }
    private String meta(String key) {
        try (Cursor rows = cache.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) { return rows.moveToFirst() ? rows.getString(0) : ""; }
    }
    private void openCache() throws Exception {
        cache = SQLiteDatabase.openOrCreateDatabase(cacheFile, null);
        cache.execSQL("CREATE TABLE IF NOT EXISTS vectors(id INTEGER PRIMARY KEY,vector BLOB NOT NULL CHECK(length(vector)=1024))");
        cache.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        if (!RagModels.VERSION.equals(meta("model"))) {
            cache.beginTransaction();
            try {
                cache.execSQL("DELETE FROM vectors"); cache.execSQL("DELETE FROM meta");
                cache.execSQL("INSERT INTO meta VALUES('model',?)", new Object[]{RagModels.VERSION});
                cache.setTransactionSuccessful();
            } finally { cache.endTransaction(); }
        }
        String saved = meta("cursor"); cursor = saved.isEmpty() ? 0 : Long.parseLong(saved);
        if (cursor < 0) throw new IOException("Invalid semantic cursor");
        long loaded = NativeRag.openIndex();
        try (Cursor rows = cache.rawQuery("SELECT id,vector FROM vectors ORDER BY id", null)) {
            while (rows.moveToNext()) NativeRag.put(loaded, rows.getLong(0), decode(rows.getBlob(1)));
        } catch (Exception error) { NativeRag.closeIndex(loaded); throw error; }
        index = loaded;
    }
    private static float[] decode(byte[] bytes) throws IOException {
        if (bytes.length != 1024) throw new IOException("Invalid cached embedding");
        float[] values = new float[256]; ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values); return values;
    }
    private void update() {
        if (closed) return;
        try {
            initialize();
            while (!closed) {
                JSONObject batch = source.batch(cursor);
                JSONArray items = batch.getJSONArray("items");
                long through = batch.getLong("through_id"), previous = cursor;
                if (items.length() > 16 || through < cursor) throw new IOException("Invalid embedding batch");
                for (int i = 0; i < items.length(); i++) {
                    long id = items.getJSONObject(i).getLong("id");
                    if (id <= previous || id > through) throw new IOException("Invalid embedding order");
                    previous = id;
                }
                if (previous != through) throw new IOException("Incomplete embedding batch");
                if (items.length() == 0) { status.accept("意味検索の準備ができました"); return; }
                for (int i = 0; i < items.length() && !closed; i++) {
                    JSONObject item = items.getJSONObject(i);
                    long id = item.getLong("id"), replaced = item.getLong("supersedes_id");
                    boolean current = item.getBoolean("current");
                    float[] vector = current ? embedder.embed("検索文書: " + item.getString("actor") + " / " + item.getString("subject")
                        + " / " + item.getString("scope") + " / " + item.getString("text")) : null;
                    synchronized (gate) {
                        if (closed) return;
                        cache.beginTransaction();
                        try {
                            if (replaced > 0) { cache.delete("vectors", "id=?", new String[]{Long.toString(replaced)}); NativeRag.remove(index, replaced); }
                            if (current) {
                                ByteBuffer bytes = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN); bytes.asFloatBuffer().put(vector);
                                ContentValues values = new ContentValues(); values.put("id", id); values.put("vector", bytes.array());
                                if (cache.insertWithOnConflict("vectors", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new IOException("Could not cache embedding");
                                NativeRag.put(index, id, vector);
                            } else { cache.delete("vectors", "id=?", new String[]{Long.toString(id)}); NativeRag.remove(index, id); }
                            cache.execSQL("INSERT OR REPLACE INTO meta VALUES('cursor',?)", new Object[]{Long.toString(id)});
                            cache.setTransactionSuccessful();
                        } finally { cache.endTransaction(); }
                        cursor = id;
                    }
                }
                if (!batch.getBoolean("has_more")) { status.accept("意味検索の準備ができました"); return; }
            }
        } catch (Exception error) {
            synchronized (gate) { releaseCache(); }
            if (!closed) status.accept("意味検索は再試行待ちです。通常の検索で会話を続けられます。");
        }
    }
    private void releaseCache() {
        if (index != 0) { NativeRag.closeIndex(index); index = 0; }
        if (cache != null) { cache.close(); cache = null; }
    }
    @Override public synchronized void close() {
        synchronized (gate) { if (closed) return; closed = true; releaseCache(); }
        worker.execute(() -> { if (embedder != null) { embedder.close(); embedder = null; } });
        worker.shutdown();
    }
}
