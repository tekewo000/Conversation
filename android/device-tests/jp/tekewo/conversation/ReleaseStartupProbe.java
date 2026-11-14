package jp.tekewo.conversation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Exercise the installed, minified APK rather than classes bundled with the probe. */
public final class ReleaseStartupProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            ClassLoader loader = context.getClassLoader();
            Class<?> models = loader.loadClass("jp.tekewo.conversation.RagModels");
            Method ensure = models.getDeclaredMethod("ensure", Context.class, Consumer.class); ensure.setAccessible(true);
            Object assets = ensure.invoke(null, context, (Consumer<String>)status -> {});
            Class<?> type = loader.loadClass("jp.tekewo.conversation.LocalEmbedder");
            Constructor<?> constructor = type.getDeclaredConstructor(assets.getClass()); constructor.setAccessible(true);
            Object embedder = constructor.newInstance(assets);
            try {
                Method embed = type.getDeclaredMethod("embed", String.class); embed.setAccessible(true);
                float[] vector = (float[])embed.invoke(embedder, "検索クエリ: 雨の日の過ごし方");
                if (vector.length != 256) throw new AssertionError("Invalid release embedding dimensions");
                double norm = 0;
                for (float value : vector) { if (!Float.isFinite(value)) throw new AssertionError("Invalid release embedding value"); norm += (double)value * value; }
                if (Math.abs(norm - 1) > 0.001) throw new AssertionError("Invalid release embedding normalization");
            } finally { ((AutoCloseable)embedder).close(); }
            Activity activity = startActivitySync(new Intent().setClassName(context.getPackageName(), "jp.tekewo.conversation.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            if (activity.isFinishing() || activity.isDestroyed()) throw new AssertionError("Release activity closed during startup");
            java.lang.reflect.Field person = activity.getClass().getDeclaredField("activePerson"); person.setAccessible(true);
            if (person.get(activity) != null) {
                java.lang.reflect.Field status = activity.getClass().getDeclaredField("semanticStatus"); status.setAccessible(true);
                java.util.concurrent.atomic.AtomicReference<String> current = new java.util.concurrent.atomic.AtomicReference<>("");
                long deadline = android.os.SystemClock.uptimeMillis() + 60000;
                do {
                    runOnMainSync(() -> {
                        try { current.set((String)status.get(activity)); }
                        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
                    });
                    if ("意味検索の準備ができました".equals(current.get())) break;
                    if (current.get().contains("再試行待ち")) throw new AssertionError("Release background retrieval failed");
                    Thread.sleep(100);
                } while (android.os.SystemClock.uptimeMillis() < deadline);
                if (!"意味検索の準備ができました".equals(current.get())) throw new AssertionError("Release background retrieval did not become ready");
                Method pending = activity.getClass().getDeclaredMethod("nativePendingJob"); pending.setAccessible(true);
                org.json.JSONObject batch = new org.json.JSONObject((String)pending.invoke(null));
                if (batch.optLong("id") > 0) {
                    org.json.JSONArray messages = batch.getJSONArray("messages");
                    if (messages.length() < 1 || messages.length() > 16 || batch.getLong("first_job_id") > batch.getLong("id"))
                        throw new AssertionError("Invalid pending memory batch");
                    if (batch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                        throw new AssertionError("Memory batch exceeds the source transport budget");
                    for (int i = 1; i < messages.length(); i++)
                        if (messages.getJSONObject(i).getLong("id") != messages.getJSONObject(i - 1).getLong("id") + 1)
                            throw new AssertionError("Memory batch skipped original messages");
                    sendStatus(0, status("MEMORY_BATCH_PASS: " + messages.length() + " contiguous messages; no AI request made\n"));
                }
                Method snapshot = activity.getClass().getDeclaredMethod("nativeContext", byte[].class); snapshot.setAccessible(true);
                org.json.JSONObject progress = new org.json.JSONObject((String)snapshot.invoke(null, new byte[0])).getJSONObject("memory_progress");
                long processed = progress.getLong("processed_messages"), total = progress.getLong("total_messages");
                if (processed < 0 || total < processed) throw new AssertionError("Invalid memory progress");
                sendStatus(0, status("MEMORY_PROGRESS_PASS: " + processed + "/" + total + " messages processed\n"));
            }
            runOnMainSync(activity::finish);
            result.putString("stream", "RELEASE_STARTUP_PASS: installed Release model inference and Activity startup\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", "RELEASE_STARTUP_FAIL\n" + trace);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static Bundle status(String text) { Bundle value = new Bundle(); value.putString("stream", text); return value; }
}
