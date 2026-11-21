package jp.tekewo.conversation;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import jp.tekewo.conversation.InitiativePolicy.Trigger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class InitiativeWork extends Worker {
    static final String OPEN_CHAT = "jp.tekewo.conversation.OPEN_INITIATIVE_CHAT";
    private static final String WORK_NAME = "conversation-initiative";
    private static final String CHANNEL = "initiative-messages";
    private static final int NOTIFICATION_ID = 41;
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicLong visibilityVersion = new AtomicLong();
    private static final ExecutorService foregroundWorker = Executors.newSingleThreadExecutor();
    private static volatile boolean appVisible;
    private volatile ChatGptConnection connection;

    public InitiativeWork(Context context, WorkerParameters parameters) { super(context, parameters); }

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context, Trigger trigger) {
        SharedPreferences preferences = settings(context);
        return preferences.getBoolean(trigger.preference, preferences.getBoolean("initiative_enabled", false));
    }

    static void setEnabled(Context context, Trigger trigger, boolean value) {
        settings(context).edit().putBoolean(trigger.preference, value).apply();
        if (!value && trigger == Trigger.BACKGROUND) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        }
    }

    static boolean notificationsEnabled(Context context) {
        return settings(context).getBoolean("initiative_notifications", false);
    }

    static void setNotificationsEnabled(Context context, boolean value) {
        settings(context).edit().putBoolean("initiative_notifications", value).apply();
        if (!value) context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    static void enterForeground(Context context) {
        appVisible = true;
        visibilityVersion.incrementAndGet();
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    static void leaveForeground(Context context, boolean schedule) {
        appVisible = false;
        visibilityVersion.incrementAndGet();
        if (!schedule || !enabled(context, Trigger.BACKGROUND)) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(InitiativeWork.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(Trigger.BACKGROUND.quietMillis, TimeUnit.MILLISECONDS).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    static void tryForeground(Context context, String personId, Trigger trigger, BooleanSupplier allowed, Runnable onSaved) {
        Context application = context.getApplicationContext();
        long version = visibilityVersion.get();
        BooleanSupplier stillVisible = () -> appVisible && visibilityVersion.get() == version && allowed.getAsBoolean();
        foregroundWorker.execute(() -> {
            try {
                if (attempt(application, personId, trigger, stillVisible, (instructions, input, delta) ->
                        request(application, new ChatGptConnection(application), trigger, stillVisible, instructions, input, delta))) {
                    onSaved.run();
                    MemoryWork.enqueue(application);
                }
            } catch (Exception error) { Log.w("ConversationInitiative", "Foreground initiative did not complete"); }
        });
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        long version = visibilityVersion.get();
        BooleanSupplier background = () -> !appVisible && visibilityVersion.get() == version && !isStopped();
        try {
            if (attempt(context, null, Trigger.BACKGROUND, background, (instructions, input, delta) -> {
                connection = new ChatGptConnection(context);
                return request(context, connection, Trigger.BACKGROUND, background, instructions, input, delta);
            })) {
                MemoryWork.enqueue(context);
                notifyBackgroundMessage(context, background);
            }
        } catch (Exception error) { Log.w("ConversationInitiative", "Background initiative did not complete"); }
        return Result.success();
    }

    static boolean attempt(Context context, String personId, Trigger trigger,
                           BooleanSupplier allowed, ChatSession.Transport backend) throws Exception {
        if (!enabled(context, trigger) || !allowed.getAsBoolean() || !running.compareAndSet(false, true)) return false;
        try {
            PersonRegistry.Person person;
            MemoryStore store;
            JSONObject contextData;
            InitiativePolicy history;
            synchronized (MainActivity.storeGate) {
                person = new PersonRegistry(context.getFilesDir()).active();
                if (person == null || (personId != null && !personId.equals(person.id))) return false;
                store = new MemoryStore(context, person.id);
                contextData = store.context("");
                history = new InitiativePolicy(contextData.getJSONArray("messages"));
                synchronized (PersonState.class) {
                    JSONObject state = PersonState.load(person.directory);
                    long now = System.currentTimeMillis();
                    if (!allowed.getAsBoolean() || !enabled(context, trigger) || !history.eligible(state, trigger, now)) return false;
                    PersonState.put(person.directory, InitiativePolicy.ATTEMPT_AT, Long.toString(now));
                }
            }
            String message = ChatSession.initiative(contextData, backend);
            if (message == null || !allowed.getAsBoolean() || !enabled(context, trigger)) return false;
            synchronized (MainActivity.storeGate) {
                InitiativePolicy latest = new InitiativePolicy(store.context("").getJSONArray("messages"));
                if (latest.revision != history.revision || !allowed.getAsBoolean() || !enabled(context, trigger)) return false;
                // Reserve before the native transaction so a process stop cannot send the same opener twice.
                String previous = PersonState.load(person.directory).optString(InitiativePolicy.SENT_THROUGH, null);
                PersonState.put(person.directory, InitiativePolicy.SENT_THROUGH, Long.toString(history.revision));
                boolean saved = false;
                try {
                    if (!allowed.getAsBoolean() || !enabled(context, trigger)) return false;
                    saved = MainActivity.nativeAddMessage("assistant".getBytes(StandardCharsets.UTF_8),
                            message.getBytes(StandardCharsets.UTF_8)) > 0;
                    if (!saved) throw new IOException(MainActivity.nativeLastError());
                    return true;
                } finally {
                    if (!saved) PersonState.put(person.directory, InitiativePolicy.SENT_THROUGH, previous);
                }
            }
        } finally { running.set(false); }
    }

    private static String request(Context context, ChatGptConnection connection, Trigger trigger, BooleanSupplier allowed,
                                  String instructions, JSONArray input, Consumer<String> delta) throws IOException {
        if (!allowed.getAsBoolean() || !enabled(context, trigger) || !connection.hasSession()) throw new IOException();
        List<ChatRequest.Model> models = connection.listModels();
        SharedPreferences settings = settings(context);
        String preferred = settings.getString("model_slug", "");
        ChatRequest.Model model = models.get(0);
        for (ChatRequest.Model candidate : models) if (candidate.slug.equals(preferred)) { model = candidate; break; }
        String effort = settings.getString("reasoning_effort:" + model.slug, "");
        if (!model.efforts.contains(effort)) effort = "";
        boolean fast = model.supportsFast && settings.getBoolean("fast_mode:" + model.slug, false);
        if (!allowed.getAsBoolean() || !enabled(context, trigger)) throw new IOException();
        return connection.request(new ChatRequest(model, effort, fast), instructions, input, delta, true);
    }

    static void notifyBackgroundMessage(Context context, BooleanSupplier background) {
        if (!background.getAsBoolean() || !enabled(context, Trigger.BACKGROUND) || !notificationsEnabled(context)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "人物からのメッセージ", NotificationManager.IMPORTANCE_DEFAULT));
        Intent intent = new Intent(context, MainActivity.class).setAction(OPEN_CHAT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openChat = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Conversation")
                .setContentText("新しいメッセージが届きました").setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(openChat).setAutoCancel(true).build());
        if (!background.getAsBoolean() || !enabled(context, Trigger.BACKGROUND) || !notificationsEnabled(context)) manager.cancel(NOTIFICATION_ID);
    }

    @Override public void onStopped() {
        ChatGptConnection active = connection;
        if (active != null) active.cancel();
    }
}
