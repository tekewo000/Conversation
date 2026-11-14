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
    static boolean isPaused(Context context) { return settings(context).getBoolean("memory_paused", false); }
    static String status(Context context) { return settings(context).getString("memory_status", ""); }
    static void pause(Context context) { settings(context).edit().putBoolean("memory_paused", true).apply(); }
    static void resume(Context context) {
        settings(context).edit().putBoolean("memory_paused", false).apply();
        if (!isRunning()) WorkManager.getInstance(context).cancelUniqueWork(NAME);
        enqueue(context);
    }
    static void enqueue(Context context) {
        if (isPaused(context)) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoryWork.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        if (isPaused(context)) return Result.success();
        if (!running.compareAndSet(false, true)) return Result.retry();
        try {
            connection = new ChatGptConnection(context);
            if (!connection.hasSession()) return Result.success();
            PersonRegistry.Person person;
            synchronized (MainActivity.storeGate) { person = new PersonRegistry(context.getFilesDir()).active(); }
            if (person == null) return Result.success();
            MemoryStore store = new MemoryStore(context, person.id);
            JSONObject batch = store.pending();
            if (batch.optLong("id", 0) == 0 && store.pendingPersona().optLong("through_id", 0) == 0) return Result.success();
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
            boolean more = session.processMemories((instructions, input, delta) -> {
                if (isStopped() || isPaused(context)) throw new IOException("記憶の整理を一時停止しました。");
                if (instructions.equals(ChatSession.REFLECT)) publishProgress(store);
                return connection.request(request, instructions, input, delta, true,
                        instructions.equals(ChatSession.EXTRACT) ? 300 : 120);
            }, 1);
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
            String message = isPaused(context) ? "記憶の整理を一時停止しました。"
                    : "記憶の整理は自動で再試行します。" + (error instanceof IOException ? error.getMessage() : "");
            settings(context).edit().putString("memory_status", message).apply();
            return isPaused(context) ? Result.success() : Result.retry();
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
