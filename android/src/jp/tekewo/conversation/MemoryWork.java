package jp.tekewo.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class MemoryWork extends Worker {
    static final String NAME = "conversation-memory";
    private static final AtomicBoolean running = new AtomicBoolean();
    private volatile ChatGptConnection connection;
    private volatile ChatSession session;

    public MemoryWork(Context context, WorkerParameters parameters) { super(context, parameters); }
    private static SharedPreferences settings(Context context) { return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE); }
    static boolean isRunning() { return running.get(); }
    static String status(Context context) { return settings(context).getString("memory_status", ""); }
    static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoryWork.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        if (!running.compareAndSet(false, true)) return Result.retry();
        try {
            connection = new ChatGptConnection(context);
            if (!connection.hasSession()) return Result.success();
            PersonRegistry.Person person;
            synchronized (MainActivity.storeGate) { person = new PersonRegistry(context.getFilesDir()).active(); }
            if (person == null) return Result.success();
            MemoryStore store = new MemoryStore(context, person.id);
            MemoryRebuild rebuild = new MemoryRebuild(person.directory);
            JSONObject batch = store.pending();
            boolean normal = batch.optLong("id", 0) > 0 || store.pendingPersona().optLong("through_id", 0) > 0;
            if (!normal && !MemoryRebuild.pending(person.directory)) return Result.success();
            int batchMessages = batch.optJSONArray("messages") == null ? 0 : batch.getJSONArray("messages").length();
            long started = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i("ConversationMemory", "Starting batch messages=" + batchMessages);
            List<ChatRequest.Model> models = connection.listModels();
            if (models.isEmpty()) throw new IOException("利用できるモデルがありません。");
            SharedPreferences preferences = settings(context);
            ChatRequest.Model model = models.get(0);
            for (ChatRequest.Model candidate : models) if (candidate.slug.equals(preferences.getString("model_slug", ""))) model = candidate;
            String effort = preferences.getString("reasoning_effort:" + model.slug, "");
            if (!model.efforts.contains(effort)) effort = "";
            ChatRequest request = new ChatRequest(model, effort, model.supportsFast && preferences.getBoolean("fast_mode:" + model.slug, false));
            session = new ChatSession(store);
            setProgressAsync(new Data.Builder().putLong("updated", System.currentTimeMillis()).build()).get();
            // One extraction plus reflection stays inside WorkManager's execution deadline.
            ChatSession.Transport backend = (instructions, input, delta) -> {
                if (isStopped()) throw new IOException("記憶の整理が中断されました。");
                if (instructions.equals(ChatSession.REFLECT)) publishProgress(store);
                return connection.request(request, instructions, input, delta, true,
                        instructions.equals(ChatSession.REFLECT) ? 120 : 300);
            };
            boolean more;
            if (normal) more = session.processMemories(backend, 1) || MemoryRebuild.pending(person.directory);
            else more = rebuild.step(new MemoryRebuild.Native() {
                public JSONObject context() throws Exception { return store.context(""); }
                public JSONObject batch(long afterId, long throughId) throws Exception {
                    return store.rebuildBatch(afterId, throughId);
                }
                public boolean publish() throws Exception {
                    return store.publishRebuild();
                }
            }, backend);
            preferences.edit().putString("memory_status", "").apply();
            JSONObject progress = store.context("").getJSONObject("memory_progress");
            android.util.Log.i("ConversationMemory", "Completed batch messages=" + batchMessages
                    + " processed=" + progress.getLong("processed_messages") + " total=" + progress.getLong("total_messages")
                    + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - started));
            setProgressAsync(new Data.Builder().putLong("processed", progress.getLong("processed_messages"))
                    .putLong("updated", System.currentTimeMillis()).build()).get();
            if (more && !isStopped()) enqueue(context);
            return Result.success();
        } catch (Exception error) {
            String message = "記憶の整理は自動で再試行します。" + (error instanceof IOException ? error.getMessage() : "");
            settings(context).edit().putString("memory_status", message).apply();
            return Result.retry();
        } finally { running.set(false); }
    }

    private void publishProgress(MemoryStore store) throws Exception {
        JSONObject progress = store.context("").getJSONObject("memory_progress");
        setProgressAsync(new Data.Builder().putLong("processed", progress.getLong("processed_messages"))
                .putLong("updated", System.currentTimeMillis()).build()).get();
    }

    @Override public void onStopped() {
        if (session != null) session.cancel();
        if (connection != null) connection.cancel();
    }
}
