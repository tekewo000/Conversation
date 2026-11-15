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
    static final String RECORD_NAME = "conversation-records";
    private static final AtomicBoolean maintenanceRunning = new AtomicBoolean();
    private static final AtomicBoolean recordsRunning = new AtomicBoolean();
    private volatile ChatGptConnection connection;
    private volatile ChatSession session;
    private volatile MemoryRebuild rebuild;
    private boolean rebuilding;
    private String operation = "記憶の整理";

    public MemoryWork(Context context, WorkerParameters parameters) { super(context, parameters); }
    private static SharedPreferences settings(Context context) { return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE); }
    static boolean isRunning() { return recordsRunning.get() || maintenanceRunning.get(); }
    static String status(Context context) {
        SharedPreferences preferences = settings(context);
        String records = preferences.getString("memory_status", "");
        if (records.isEmpty()) records = preferences.getString("memory_phase", "");
        String persona = preferences.getString("persona_status", "");
        return records.isEmpty() ? persona : persona.isEmpty() ? records : records + "\n" + persona;
    }
    static void enqueue(Context context) {
        schedule(context, true, recordsRunning.get() ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.REPLACE);
        schedule(context, false, ExistingWorkPolicy.KEEP);
    }
    static void resume(Context context) {
        schedule(context, true, recordsRunning.get() ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.REPLACE);
        schedule(context, false, maintenanceRunning.get() ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.REPLACE);
    }
    static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(NAME);
        manager.cancelUniqueWork(RECORD_NAME);
        settings(context).edit().putString("memory_phase", "").apply();
    }
    private static void schedule(Context context, boolean records, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MemoryWork.class)
                .setInputData(new Data.Builder().putBoolean("records", records).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(records ? RECORD_NAME : NAME, policy, request);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        boolean records = getInputData().getBoolean("records", false);
        operation = records ? "会話の記憶整理" : "人物像の振り返り";
        AtomicBoolean running = records ? recordsRunning : maintenanceRunning;
        if (!running.compareAndSet(false, true)) return Result.retry();
        try {
            connection = new ChatGptConnection(context);
            if (!connection.hasSession()) return Result.success();
            PersonRegistry.Person person;
            synchronized (MainActivity.storeGate) { person = new PersonRegistry(context.getFilesDir()).active(); }
            if (person == null) return Result.success();
            MemoryStore store = new MemoryStore(context, person.id);
            rebuild = new MemoryRebuild(person.directory, this::updated);
            rebuilding = !records && MemoryRebuild.pending(person.directory);
            if (rebuilding) operation = "過去の記憶の再整理";
            JSONObject batch = records ? store.pending() : new JSONObject();
            if (records ? batch.optLong("id", 0) <= 0 : !rebuilding && store.pendingPersona().optLong("through_id", 0) <= 0) {
                preferencesClear(context, records);
                return Result.success();
            }
            int batchMessages = batch.optJSONArray("messages") == null ? 0 : batch.getJSONArray("messages").length();
            long started = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i("ConversationMemory", rebuilding ? "Starting historical rebuild" : records ? "Starting records messages=" + batchMessages : "Starting persona review");
            if (rebuilding) rebuild.phase("AIへの接続を確認しています");
            else phase(records, operation + "：AIへの接続を確認しています");
            List<ChatRequest.Model> models = connection.listModels();
            if (models.isEmpty()) throw new IOException("利用できるモデルがありません。");
            SharedPreferences preferences = settings(context);
            ChatRequest.Model model = models.get(0);
            for (ChatRequest.Model candidate : models) if (candidate.slug.equals(preferences.getString("model_slug", ""))) model = candidate;
            String effort = preferences.getString("reasoning_effort:" + model.slug, "");
            if (!model.efforts.contains(effort)) effort = "";
            ChatRequest request = new ChatRequest(model, effort, model.supportsFast && preferences.getBoolean("fast_mode:" + model.slug, false));
            session = new ChatSession(store);
            updated();
            ChatSession.Transport backend = (instructions, input, delta) -> {
                if (isStopped()) throw new IOException("記憶の整理が中断されました。");
                operation = instructions.equals(ChatSession.REFLECT) ? "人物像の振り返り" : instructions.equals(ChatSession.REBUILD) ? "過去の記憶の再整理" : "会話の記憶整理";
                if (!rebuilding) phase(records, operation + (records ? "：" + batchMessages + "発言の差分を整理しています" : "：会話を振り返っています"));
                final long[] lastUpdate = {0};
                final int[] received = {0};
                String response = connection.request(request, instructions, input, chunk -> {
                    delta.accept(chunk);
                    if (!rebuilding) {
                        received[0] += chunk.length();
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastUpdate[0] >= 1000) {
                            lastUpdate[0] = now;
                            phase(records, operation + "：応答を受信しています（" + received[0] + "文字）");
                        }
                    }
                }, true, instructions.equals(ChatSession.REFLECT) ? 120 : 300);
                if (!rebuilding) phase(records, operation + "：出典を確認して保存しています");
                return response;
            };
            boolean more;
            if (records) {
                session.extractMemories(backend, 1);
                more = store.pending().optLong("id", 0) > 0;
                preferences.edit().putString("memory_status", "").putString("memory_phase", "").apply();
                schedule(context, false, ExistingWorkPolicy.APPEND_OR_REPLACE);
            } else if (rebuilding) {
                more = rebuild.step(new MemoryRebuild.Native() {
                    public JSONObject context() throws Exception { return store.context(""); }
                    public JSONObject batch(long afterId, long throughId) throws Exception { return store.rebuildBatch(afterId, throughId); }
                    public boolean publish() throws Exception { return store.publishRebuild(); }
                }, backend) || store.pendingPersona().optLong("through_id", 0) > 0;
            } else {
                session.processMemories(backend, 0);
                more = rebuild.isPending() || store.pendingPersona().optLong("through_id", 0) > 0;
                preferences.edit().putString("persona_status", "").apply();
            }
            JSONObject progress = store.context("").getJSONObject("memory_progress");
            android.util.Log.i("ConversationMemory", "Completed " + (records ? "records" : rebuilding ? "rebuild" : "persona")
                    + " processed=" + progress.getLong("processed_messages") + " total=" + progress.getLong("total_messages")
                    + " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - started));
            updated();
            if (more && !isStopped()) schedule(context, records, ExistingWorkPolicy.APPEND_OR_REPLACE);
            return Result.success();
        } catch (Exception error) {
            if (!records && !rebuilding && rebuild != null && rebuild.isPending() && !isStopped()) {
                rebuild.phase("一括整理を先に進めます");
                schedule(context, false, ExistingWorkPolicy.APPEND_OR_REPLACE);
                return Result.success();
            }
            String message = operation + "は自動で再試行します。" + (error instanceof IOException ? error.getMessage() : error instanceof org.json.JSONException ? "AIの応答が必要な記憶形式になっていません。" : "");
            if (rebuilding && rebuild != null) rebuild.failed(message);
            else settings(context).edit().putString(records ? "memory_status" : "persona_status", message).apply();
            return Result.retry();
        } finally { running.set(false); }
    }

    private void updated() {
        setProgressAsync(new Data.Builder().putLong("updated", System.currentTimeMillis()).build());
    }
    private static void preferencesClear(Context context, boolean records) {
        SharedPreferences.Editor editor = settings(context).edit().putString(records ? "memory_status" : "persona_status", "");
        if (records) editor.putString("memory_phase", "");
        editor.apply();
    }
    private void phase(boolean records, String value) {
        SharedPreferences.Editor editor = settings(getApplicationContext()).edit();
        if (records) editor.putString("memory_status", "").putString("memory_phase", value);
        else editor.putString("persona_status", value);
        editor.apply();
        updated();
    }
    @Override public void onStopped() {
        if (session != null) session.cancel();
        if (connection != null) connection.cancel();
    }
}
