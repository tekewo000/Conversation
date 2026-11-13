package jp.tekewo.conversation;

import androidx.activity.ComponentActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.HashSet;
import org.json.JSONObject;

public final class MainActivity extends ComponentActivity {
    private static native String nativeInit(byte[] storageDir, byte[] font, byte[] emojiFont);
    private static native long nativeAddMessage(byte[] role, byte[] text);
    private static native String nativeLastError();
    private static native String nativeContext(byte[] query);
    private static native String nativePendingJob();
    private static native String nativeApplyJob(long id, byte[] payload);
    private static native String nativePendingPersona();
    private static native String nativeApplyPersona(long throughId, byte[] payload);
    private static native String nativeMemories(byte[] query);
    private static native String nativeExport();
    private static native String nativeRecentTranscript();
    private static native String nativeSwitchPerson(byte[] directory, byte[] payload);
    private static native String nativeArchiveDelta(long cursor);

    private static native String nativeMessages(int first);
    private ComposeUi compose;
    private int pageFirst = -1;
    private boolean scrollOnRefresh;
    private ChatGptConnection connection;
    private ChatSession session;
    private PersonRegistry people;
    private PersonRegistry.Person activePerson;
    private File pendingExportDirectory;
    private String gitStatus = "";
    private final android.os.Handler syncTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoSync = () -> queueGitSync(false);
    private static final ExecutorService gitWorker = Executors.newSingleThreadExecutor();
    private static final Set<String> syncingPeople = new HashSet<>();
    private static final Object storeGate = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private boolean busy;
    private volatile boolean changingPerson;
    private String connectionStatus = "";
    private volatile List<ChatRequest.Model> availableModels;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private void ui(Runnable action) {
        runOnUiThread(() -> { if (!closed && !isDestroyed() && !isFinishing()) action.run(); });
    }
    private boolean ready;
    private static final int DEFAULT_TEXT_SIZE = 16;
    private int textSize = DEFAULT_TEXT_SIZE;
    private int messageGap = 12;
    private static final int BACKGROUND = 0xff13171c;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        textSize = Math.max(16, Math.min(28, getPreferences(MODE_PRIVATE).getInt("text_size", DEFAULT_TEXT_SIZE)));
        messageGap = Math.max(8, Math.min(24, getPreferences(MODE_PRIVATE).getInt("message_gap", 12)));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        compose = new ComposeUi(this, this::showHistoryMenu, this::sendMessage, first -> {
            pageFirst = first; refreshMessages();
        });
        applyTypography();
        try {
            System.loadLibrary("conversation");
            synchronized (storeGate) {
                requireSuccess(nativeSwitchPerson(bytes(""), bytes("")));
                people = new PersonRegistry(getFilesDir());
                activePerson = people.active();
                if (!getPreferences(MODE_PRIVATE).edit().remove("draft").commit())
                    throw new java.io.IOException("以前の下書きを削除できませんでした。");
                requireSuccess(nativeInit(bytes(activePerson == null ? "" : activePerson.directory.getAbsolutePath()),
                        new byte[0], new byte[0]));
                ready = true;
            }
        } catch (Exception | UnsatisfiedLinkError error) {
            compose.setFailure("起動できませんでした。\n" + error.getMessage());
            return;
        }
        if (activePerson != null) compose.setDraft(getPreferences(MODE_PRIVATE).getString(draftKey(), ""));
        connection = new ChatGptConnection(this);
        session = new ChatSession(new ChatSession.Store() {
            public long add(String role, String text) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    long id = nativeAddMessage(bytes(role), bytes(text));
                    if (id <= 0) throw new java.io.IOException(nativeLastError());
                    return id;
                }
            }
            public JSONObject context(String query) throws Exception {
                synchronized (storeGate) { requireCurrentPerson(); return new JSONObject(nativeContext(bytes(query))); }
            }
            public JSONObject pending() throws Exception {
                synchronized (storeGate) { requireCurrentPerson(); return new JSONObject(nativePendingJob()); }
            }
            public void apply(long id, String payload) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    String error = nativeApplyJob(id, bytes(payload));
                    if (!error.isEmpty()) throw new java.io.IOException(error);
                }
            }
            public JSONObject pendingPersona() throws Exception {
                synchronized (storeGate) { requireCurrentPerson(); return new JSONObject(nativePendingPersona()); }
            }
            public void applyPersona(long throughId, String payload) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    String error = nativeApplyPersona(throughId, bytes(payload));
                    if (!error.isEmpty()) throw new java.io.IOException(error);
                }
            }
        });
        setBusy(false);
        refreshMessages();
        scheduleGitSync();
    }

    private void refreshMessages() {
        if (!ready || closed || changingPerson) return;
        final int requested = pageFirst;
        reader.execute(() -> {
            try {
                String snapshot;
                synchronized (storeGate) {
                    requireOpen();
                    if (changingPerson) return;
                    snapshot = nativeMessages(requested);
                    if (snapshot.isEmpty()) throw new java.io.IOException(nativeLastError());
                }
                ui(() -> {
                    if (pageFirst != requested || changingPerson) return;
                    try {
                        JSONObject page = new JSONObject(snapshot);
                        if (page.getInt("first") + page.getJSONArray("messages").length() >= page.getInt("total")) pageFirst = -1;
                        compose.setMessages(snapshot);
                        if (scrollOnRefresh) { compose.scrollToLatest(); scrollOnRefresh = false; }
                    } catch (org.json.JSONException error) { showError("会話の表示データを読み出せませんでした。"); }
                });
            } catch (Exception error) { ui(() -> showError(safeError(error))); }
        });
    }

    private void requireOpen() throws java.io.IOException {
        if (closed || isDestroyed() || isFinishing() || Thread.currentThread().isInterrupted())
            throw new java.io.IOException("処理をキャンセルしました。");
    }
    private void requireCurrentPerson() throws java.io.IOException {
        requireOpen();
        if (changingPerson) throw new java.io.IOException("人物を切り替えています。");
        if (activePerson == null) throw new java.io.IOException("人物を選んでください。");
    }
    private void setStreaming(String text) {
        ui(() -> { if (!changingPerson) compose.setStreaming(text); });
    }

    private String draftKey() { return "draft_" + activePerson.id; }
    private void saveDraft() {
        if (!changingPerson && compose != null && activePerson != null) getPreferences(MODE_PRIVATE).edit()
                .putString(draftKey(), compose.getDraft()).commit();
    }

    private void setBusy(boolean value) {
        busy = value;
        compose.setEnabled(ready && activePerson != null && !changingPerson);
        compose.setSending(value);
    }

    private ChatRequest selectedRequest() throws Exception {
        String selected = getPreferences(MODE_PRIVATE).getString("model_slug", "");
        List<ChatRequest.Model> models = availableModels;
        if (models == null) availableModels = models = connection.listModels();
        if (models.isEmpty()) throw new java.io.IOException("利用できるモデルがありません。接続状態を確認してください。");
        ChatRequest.Model model = models.get(0);
        for (ChatRequest.Model candidate : models) if (candidate.slug.equals(selected)) { model = candidate; break; }
        ChatRequest request = savedRequest(model);
        getPreferences(MODE_PRIVATE).edit().putString("model_slug", model.slug)
                .putString("reasoning_effort:" + model.slug, request.effort)
                .putBoolean("fast_mode:" + model.slug, request.fast).apply();
        return request;
    }

    private ChatRequest savedRequest(ChatRequest.Model model) {
        String effort = getPreferences(MODE_PRIVATE).getString("reasoning_effort:" + model.slug, "");
        if (!model.efforts.contains(effort)) effort = "";
        boolean fast = model.supportsFast && getPreferences(MODE_PRIVATE).getBoolean("fast_mode:" + model.slug, false);
        return new ChatRequest(model, effort, fast);
    }

    private void sendMessage() {
        if (busy || activePerson == null) return;
        if (!connection.hasSession()) { showConnection(); return; }
        String text = compose.getDraft();
        if (text.trim().isEmpty()) return;
        runReply(text, true);
    }

    private void runReply(String text, boolean persistUser) {
        setBusy(true);
        session.begin();
        connectionStatus = "応答を生成しています";
        AtomicBoolean saved = new AtomicBoolean(!persistUser);
        worker.execute(() -> {
            try {
                final ChatRequest[] request = {null};
                session.reply(text, persistUser, (instructions, turns, delta) -> {
                    if (request[0] == null) request[0] = selectedRequest();
                    return connection.request(request[0], instructions, turns, delta);
                }, new ChatSession.Listener() {
                    private long lastRender;
                    public void userSaved() {
                        saved.set(true);
                        ui(() -> {
                            pageFirst = -1; scrollOnRefresh = true;
                            if (compose.getDraft().equals(text)) {
                                compose.setDraft("");
                                getPreferences(MODE_PRIVATE).edit().remove(draftKey()).apply();
                            }
                            refreshMessages();
                        });
                    }
                    public void streaming(String full) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastRender > 45) { lastRender = now; ui(() -> compose.setStreaming(full)); }
                    }
                    public void replySaved() {
                        setStreaming("");
                        ui(() -> { refreshMessages(); connectionStatus = "記憶を整理しています"; });
                    }
                    public void memoryStatus(String error) {
                        ui(() -> connectionStatus = error == null ? "ChatGPTに接続しています" : error);
                    }
                });
            } catch (Exception error) {
                setStreaming("");
                if (session.isCancelled()) {
                    ui(() -> { connectionStatus = "応答を停止しました"; refreshMessages(); });
                    return;
                }
                final String detail = error instanceof org.json.JSONException
                        ? "応答の形式を処理できませんでした。" : safeError(error);
                ui(() -> {
                    connectionStatus = detail;
                    refreshMessages();
                    ComposeUi.Panel dialog = new ComposeUi.Panel("応答できませんでした");
                    dialog.message = detail + (saved.get() ? "\n送信済みの会話原文は保存されています。" : "");
                    dialog.rows.add(new ComposeUi.Row(detail.contains("利用上限") ? "利用状況を確認" : "再試行", () -> {
                        if (detail.contains("利用上限")) openUsage(); else if (!busy) runReply(text, !saved.get());
                    }));
                    compose.show(dialog);

                });
            } finally { ui(() -> { setBusy(false); scheduleGitSync(); }); }
        });
    }

    private String safeError(Exception error) {
        if (error instanceof java.io.IOException && error.getMessage() != null) return error.getMessage();
        return "処理に失敗しました。接続状態を確認して再試行してください。";
    }

    private void showError(String error) {
        ComposeUi.Panel dialog = new ComposeUi.Panel("処理を確認してください");
        dialog.message = error; compose.show(dialog);
    }

    private void openUsage() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")));
    }

    private void showHistoryMenu() {
        if (!ready) return;
        ComposeUi.Panel menu = new ComposeUi.Panel("メニュー");
        menu.sheet = true;
        menu.rows.add(new ComposeUi.Row("会話", () -> { pageFirst = -1; scrollOnRefresh = true; refreshMessages(); }, 0));
        menu.rows.add(new ComposeUi.Row("人物", this::showPeople, 2));
        menu.rows.add(new ComposeUi.Row("表示設定", this::showDisplaySettings, 4));
        menu.rows.add(new ComposeUi.Row("ChatGPT", this::showConnection, 5));
        if (activePerson != null) menu.rows.add(new ComposeUi.Row("Gitバックアップ", this::showGitBackup, 1));
        if (busy) menu.rows.add(new ComposeUi.Row("応答を停止", () -> {
            session.cancel(); connection.cancel(); setStreaming(""); refreshMessages();
        }, 6));
        compose.show(menu);
    }

    private void showConnection() {
        ComposeUi.Panel panel = new ComposeUi.Panel("ChatGPT");
        panel.sheet = true;
        panel.message = connection.hasSession()
                ? connection.accountLabel() + "\nChatGPTの利用枠を使用します。\n" + connectionStatus
                : "ChatGPTにログインして、このアプリでサブスクの利用枠を使います。\n" + connectionStatus;
        if (!connection.hasSession()) {
            panel.rows.add(new ComposeUi.Row("Continue with ChatGPT", () -> {
                if (busy) return;
                connectionStatus = "ブラウザでの認証を待っています";
                connection.signIn(error -> ui(() -> {
                    availableModels = null;
                    connectionStatus = error == null ? "ChatGPTに接続しました" : error;
                    if (error != null) showError(error);
                    else if (!getPreferences(MODE_PRIVATE).getBoolean("plan_welcome", false)) {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("plan_welcome", true).apply();
                        ComposeUi.Panel welcome = new ComposeUi.Panel("ChatGPTに接続しました");
                        welcome.message = "会話と記憶の整理にChatGPTの利用枠を使います。利用状況はメニューのChatGPTから確認できます。";
                        welcome.rows.add(new ComposeUi.Row("会話を始める", this::retryMemories));
                        compose.show(welcome);
                    } else retryMemories();
                }));
            }, 5));
        } else {
            String slug = getPreferences(MODE_PRIVATE).getString("model_slug", "");
            String name = slug.isEmpty() ? "既定" : slug;
            String effort = getPreferences(MODE_PRIVATE).getString("reasoning_effort:" + slug, "");
            boolean fast = getPreferences(MODE_PRIVATE).getBoolean("fast_mode:" + slug, false);
            List<ChatRequest.Model> models = availableModels;
            if (models != null) for (ChatRequest.Model model : models) if (model.slug.equals(slug)) {
                ChatRequest request = savedRequest(model);
                name = model.displayName; effort = request.effort; fast = request.fast; break;
            }
            panel.rows.add(new ComposeUi.Row("モデル · " + name, this::showModels, 4));
            panel.rows.add(new ComposeUi.Row("Effort · " + (effort.isEmpty() ? "既定" : effort), () -> showRequestOption(false), 4));
            panel.rows.add(new ComposeUi.Row("Fast · " + (fast ? "On" : "Off"), () -> showRequestOption(true), 4));
            panel.rows.add(new ComposeUi.Row("記憶の整理を再試行", this::retryMemories, 2));
        }
        panel.rows.add(new ComposeUi.Row(connection.hasSession() ? "ログアウト" : "保存した接続情報をリセット", () -> {
            if (busy) return;
            worker.execute(() -> {
                connection.signOut(); availableModels = null;
                ui(() -> { connectionStatus = connection.signOutStatus(); showConnection(); });
            });
        }, 6));
        panel.rows.add(new ComposeUi.Row("利用状況を確認", this::openUsage, 1));
        compose.show(panel);
    }

    private void showModels() {
        if (busy) return;
        worker.execute(() -> {
            try {
                List<ChatRequest.Model> models = connection.listModels();
                availableModels = models;
                String[] names = new String[models.size()];
                for (int i = 0; i < names.length; ++i) names[i] = models.get(i).displayName;
                String selected = selectedRequest().model.slug;
                int checked = 0;
                for (int i = 0; i < models.size(); i++) if (models.get(i).slug.equals(selected)) checked = i;
                final int selectedIndex = checked;
                ui(() -> {
                    if (busy || !connection.hasSession()) return;
                    ComposeUi.Panel panel = new ComposeUi.Panel("モデル · ChatGPTの利用枠");
                    panel.choices = true;
                    panel.onDismiss = this::showConnection;
                    for (int i = 0; i < models.size(); i++) {
                        final int index = i;
                        ComposeUi.Row row = new ComposeUi.Row(names[i], () -> {
                            if (busy) return;
                            getPreferences(MODE_PRIVATE).edit().putString("model_slug", models.get(index).slug).apply();
                            showConnection();
                        });
                        row.checked = i == selectedIndex; panel.rows.add(row);
                    }
                    compose.show(panel);
                });

            } catch (Exception error) { ui(() -> showError(safeError(error))); }
        });
    }

    private void showRequestOption(boolean fast) {
        if (busy) return;
        worker.execute(() -> {
            try {
                ChatRequest request = selectedRequest();
                ChatRequest.Model model = request.model;
                ui(() -> {
                    if (busy || !connection.hasSession()) return;
                    ComposeUi.Panel panel = new ComposeUi.Panel((fast ? "Fast" : "Effort") + " · " + model.displayName);
                    panel.choices = true;
                    panel.onDismiss = this::showConnection;
                    if (fast ? !model.supportsFast : model.efforts.isEmpty()) {
                        panel.message = model.displayName + (fast ? "ではFastを利用できません。" : "ではEffortを変更できません。");
                    } else {
                        List<String> values = new ArrayList<>();
                        if (fast) { values.add("Off"); values.add("On"); }
                        else { values.add(""); values.addAll(model.efforts); }
                        for (int i = 0; i < values.size(); i++) {
                            final int index = i;
                            String label = values.get(i).isEmpty() ? "既定（モデルに任せる）" : values.get(i);
                            ComposeUi.Row row = new ComposeUi.Row(label, () -> {
                                if (busy) return;
                                if (fast) getPreferences(MODE_PRIVATE).edit().putBoolean("fast_mode:" + model.slug, index == 1).apply();
                                else getPreferences(MODE_PRIVATE).edit().putString("reasoning_effort:" + model.slug, values.get(index)).apply();
                                showConnection();
                            });
                            row.checked = fast ? (request.fast ? i == 1 : i == 0) : values.get(i).equals(request.effort);
                            panel.rows.add(row);
                        }
                    }
                    compose.show(panel);
                });
            } catch (Exception error) { ui(() -> showError(safeError(error))); }
        });
    }

    private void retryMemories() {
        if (busy || activePerson == null || !connection.hasSession()) return;
        setBusy(true); session.begin(); connectionStatus = "記憶を整理しています";
        worker.execute(() -> {
            try {
                final ChatRequest request = selectedRequest();
                session.processMemories((instructions, turns, delta) -> connection.request(request, instructions, turns, delta));
                ui(() -> connectionStatus = "記憶の整理を進めました");
            } catch (Exception error) { ui(() -> connectionStatus = "記憶の整理は再試行待ちです。" + safeError(error)); }
            finally { ui(() -> { setBusy(false); scheduleGitSync(); }); }
        });
    }

    private void showPeople() {
        ComposeUi.Panel panel = new ComposeUi.Panel("人物");
        panel.sheet = true;
        panel.rows.add(new ComposeUi.Row("人物を追加", () -> {
            if (busy) { showError("応答と記憶の整理が終わってから追加してください。"); return; }
            showNewPerson();
        }, 2));
        for (PersonRegistry.Person person : people.list()) {
            ComposeUi.Row row = new ComposeUi.Row(person.name, () -> {
                if (busy) { showError("応答と記憶の整理が終わってから人物を切り替えてください。"); return; }
                switchPerson(person);
            }, 5);
            row.checked = activePerson != null && person.id.equals(activePerson.id); panel.rows.add(row);
        }
        panel.rows.add(new ComposeUi.Row("人物ファイルを読み込む", () -> {
            if (busy) { showError("応答と記憶の整理が終わってから読み込んでください。"); return; }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 82);
        }, 1));
        if (!people.list().isEmpty()) panel.rows.add(new ComposeUi.Row("人物を削除", () -> {
            if (busy) { showError("応答と記憶の整理が終わってから削除してください。"); return; }
            showDeletePeople();
        }, 6));
        compose.show(panel);
    }

    private void showNewPerson() {
        ComposeUi.Panel panel = new ComposeUi.Panel("人物を追加");
        panel.message = "名前だけで始められます。人物のことは会話しながら決めていけます。";
        ComposeUi.Field name = new ComposeUi.Field("名前", false); panel.fields.add(name);
        panel.confirmLabel = "作成";
        panel.confirm = () -> {
            try {
                PersonDocument document = PersonDocument.named(name.getValue());
                compose.hide(); createPerson(document, null, "");
            } catch (IllegalArgumentException error) { name.setError(error.getMessage()); }
        };
        compose.show(panel);
    }

    private void showDeletePeople() {
        ComposeUi.Panel panel = new ComposeUi.Panel("削除する人物");
        for (PersonRegistry.Person person : people.list()) panel.rows.add(new ComposeUi.Row(person.name, () -> {
            ComposeUi.Panel confirm = new ComposeUi.Panel(person.name + "を削除しますか？");
            confirm.message = "この端末の人物・会話・記憶・下書きを削除します。元には戻せません。\nGitHub上のバックアップと、書き出したZIPは残ります。";
            confirm.dismissOnClickOutside = false;
            confirm.confirmLabel = "人物を削除";
            confirm.confirm = () -> { compose.hide(); deletePerson(person); };
            compose.show(confirm);
        }, 5));
        compose.show(panel);
    }

    private void deletePerson(PersonRegistry.Person person) {
        if (busy) return;
        saveDraft(); changingPerson = true; setBusy(true);
        syncTimer.removeCallbacks(autoSync);
        worker.execute(() -> {
            Exception failure = null;
            try {
                synchronized (storeGate) {
                    requireOpen();
                    synchronized (syncingPeople) {
                        if (syncingPeople.contains(person.id))
                            throw new java.io.IOException("この人物のGit保存が終わってから削除してください。");
                        boolean selected = activePerson != null && person.id.equals(activePerson.id);
                        if (selected) {
                            requireSuccess(nativeSwitchPerson(bytes(""), bytes("")));
                        }
                        try { people.delete(person.id); }
                        catch (Exception error) {
                            if (selected && people.list().contains(person)) restorePerson();
                            throw error;
                        }
                    }
                }
            } catch (Exception error) { failure = error; }
            boolean removed = !people.list().contains(person);
            if (removed) {
                try {
                    if (person.repository != null) new GitHubTokenStore(this, person.id).clear();
                } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
                if (!getPreferences(MODE_PRIVATE).edit().remove("draft_" + person.id).commit() && failure == null)
                    failure = new java.io.IOException("下書きの削除を保存できませんでした。");
            }
            final String detail = failure == null ? null : failure instanceof IllegalArgumentException
                    ? failure.getMessage() : safeError(failure);
            ui(() -> {
                if (removed) {
                    if (detail == null) recreate();
                    else {
                        ComposeUi.Panel warning = new ComposeUi.Panel("削除後の処理を確認してください");
                        warning.message = detail; warning.onDismiss = this::recreate; compose.show(warning);

                    }
                } else {
                    changingPerson = false; setBusy(false); showError(detail); scheduleGitSync();
                }
            });
        });
    }

    private void switchPerson(PersonRegistry.Person person) {
        if ((activePerson != null && person.id.equals(activePerson.id)) || busy) return;
        saveDraft(); changingPerson = true; setBusy(true);
        syncTimer.removeCallbacks(autoSync);
        worker.execute(() -> {
            try {
                synchronized (storeGate) {
                    requireOpen();
                    requireSuccess(nativeSwitchPerson(bytes(person.directory.getAbsolutePath()), bytes("")));
                    try { people.activate(person.id); }
                    catch (Exception error) { restorePerson(); throw error; }
                }
                ui(this::recreate);
            } catch (Exception error) {
                ui(() -> { changingPerson = false; setBusy(false); showError(safeError(error)); scheduleGitSync(); });
            }
        });
    }

    private void restorePerson() throws Exception {
        String error = nativeSwitchPerson(bytes(activePerson == null ? "" : activePerson.directory.getAbsolutePath()), bytes(""));
        if (!error.isEmpty()) { ready = false; throw new java.io.IOException("元の人物を開き直せませんでした。アプリを再起動してください。"); }
    }
    private static void requireSuccess(String error) throws java.io.IOException {
        if (!error.isEmpty()) throw new java.io.IOException(error);
    }

    private void previewPerson(PersonDocument document) {
        if (busy) return;
        ComposeUi.Panel panel = new ComposeUi.Panel(document.name);
        panel.secure = true;
        panel.message = (document.profile.length() > 1600 ? document.profile.substring(0, 1600) + "…" : document.profile)
                + "\nこの人物専用の、空の非公開GitHubリポジトリを指定してください。"
                + "\nFine-grained token: このリポジトリの Contents を Read and write に設定。";
        ComposeUi.Field repository = new ComposeUi.Field("https://github.com/所有者/リポジトリ", false);
        ComposeUi.Field token = new ComposeUi.Field("この人物用のGitHubトークン", true);
        panel.fields.add(repository); panel.fields.add(token);
        panel.confirmLabel = "読み込んで作成";
        panel.confirm = () -> {
            String repo;
            try { repo = PersonRegistry.normalizeRepository(repository.getValue()); }
            catch (IllegalArgumentException error) { repository.setError(error.getMessage()); return; }
            String suppliedToken = token.getValue().trim(); token.setValue("");
            compose.hide(); createPerson(document, repo, suppliedToken);
        };
        compose.show(panel);
    }

    private void createPerson(PersonDocument document, String repository, String suppliedToken) {
        if (busy) return;
        saveDraft(); changingPerson = true; setBusy(true);
        syncTimer.removeCallbacks(autoSync);
        worker.execute(() -> {
            PersonRegistry.Person staged = null;
            boolean committed = false;
            boolean retainStaged = false;
            try {
                String token = suppliedToken;
                synchronized (storeGate) { requireOpen(); staged = people.stage(document, repository); }
                if (repository != null) {
                    if (token.isEmpty()) throw new java.io.IOException("GitHubトークンを入力してください。");
                    GitHubBackup client = new GitHubBackup(new GitHubBackup.HttpTransport(token));
                    GitHubBackup.Repository remote = client.verifyIdentity(new GitHubBackup.PersonIdentity(staged.id), repository);
                    synchronized (storeGate) {
                        requireOpen(); people.bindRemoteId(staged.id, remote.repoId);
                        new GitHubTokenStore(this, staged.id).set(token);
                    }
                }
                synchronized (storeGate) {
                    requireOpen();
                    requireSuccess(nativeSwitchPerson(bytes(staged.directory.getAbsolutePath()),
                            bytes(document.importPayload(staged.id).toString())));
                    try { people.commit(staged); committed = true; }
                    catch (Exception error) {
                        try { restorePerson(); }
                        catch (Exception restore) { retainStaged = true; throw restore; }
                        throw error;
                    }
                }
                ui(this::recreate);
            } catch (Exception error) {
                try {
                    if (staged != null && !committed && !retainStaged) {
                        if (repository != null) new GitHubTokenStore(this, staged.id).clear();
                        people.discard(staged);
                    }
                } catch (Exception cleanup) { error = cleanup; }
                final String detail = error instanceof IllegalArgumentException ? error.getMessage() : safeError(error);
                ui(() -> { changingPerson = false; setBusy(false); showError(detail); scheduleGitSync(); });
            }
        });
    }

    private void showGitBackup() {
        if (activePerson == null) return;
        ComposeUi.Panel panel = new ComposeUi.Panel("Gitバックアップ");
        panel.sheet = true;
        panel.message = activePerson.repository == null
                ? activePerson.name + "\n会話と記憶はこの端末に保存しています。ZIPに書き出して保管できます。"
                : activePerson.name + "\n" + activePerson.repository + "\n" + gitStatus
                  + "\n会話の原文・人物ファイル・出来事と記憶の更新履歴を非公開リポジトリへ保存します。";
        if (activePerson.repository != null) {
            panel.rows.add(new ComposeUi.Row("今すぐ保存", () -> queueGitSync(true), 1));
            panel.rows.add(new ComposeUi.Row("GitHubトークンを設定", this::editGitToken, 4));
        }
        panel.rows.add(new ComposeUi.Row("端末からZIPを書き出す", this::exportArchive, 1));
        compose.show(panel);
    }

    private void editGitToken() {
        final GitHubTokenStore tokens = new GitHubTokenStore(this, activePerson.id);
        ComposeUi.Panel panel = new ComposeUi.Panel("GitHubトークン");
        panel.secure = true;
        panel.message = "Contents: Read and write を許可したトークンを端末内に暗号化して保存します。";
        ComposeUi.Field token = new ComposeUi.Field("GitHubトークン", true); panel.fields.add(token);
        panel.confirmLabel = "保存";
        panel.confirm = () -> {
            String value = token.getValue().trim(); token.setValue(""); compose.hide();
            worker.execute(() -> {
                try { tokens.set(value); ui(() -> { gitStatus = "接続情報を保存しました"; queueGitSync(true); }); }
                catch (Exception error) { ui(() -> showError(safeError(error))); }
            });
        };
        panel.rows.add(new ComposeUi.Row("接続情報を削除", () -> worker.execute(() -> {
            try { tokens.clear(); ui(() -> gitStatus = "自動保存は接続情報の設定待ちです"); }
            catch (Exception error) { ui(() -> showError(safeError(error))); }
        })));
        compose.show(panel);
    }

    private void scheduleGitSync() {
        syncTimer.removeCallbacks(autoSync);
        if (!closed && !changingPerson && activePerson != null && activePerson.repository != null) syncTimer.postDelayed(autoSync, 120_000);
    }

    private void queueGitSync(boolean report) {
        if (!ready || closed || changingPerson || activePerson == null || activePerson.repository == null || isDestroyed()) return;
        final PersonRegistry.Person person = activePerson;
        gitStatus = "Gitに保存しています";
        // Capture on the chat worker; network work must never read the subsequently active database.
        worker.execute(() -> {
            synchronized (syncingPeople) {
                if (!syncingPeople.add(person.id)) {
                    if (report) ui(() -> showError("この人物のGit保存を進めています。"));
                    return;
                }
            }
            try {
                synchronized (storeGate) { requireCurrentPerson(); }
                String token = new GitHubTokenStore(this, person.id).get();
                if (token.isEmpty()) throw new java.io.IOException("GitHubトークンの設定待ちです。会話は端末に保存されています。");
                File stateFile = new File(person.directory, ".git-sync.json");
                if (stateFile.length() > 16384) throw new java.io.IOException("Git保存の状態ファイルが不正です。");
                GitHubBackup.SyncState prior = stateFile.exists() ? GitHubBackup.SyncState.fromJson(new JSONObject(
                        new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8))) : GitHubBackup.SyncState.empty();
                JSONObject delta;
                synchronized (storeGate) { requireCurrentPerson(); delta = new JSONObject(nativeArchiveDelta(prior.cursor)); }
                if (delta.has("error")) throw new java.io.IOException("保存する履歴を読み出せませんでした。");
                gitWorker.execute(() -> {
                    boolean more = false;
                    try {
                        GitHubBackup.SyncResult result = new GitHubBackup(new GitHubBackup.HttpTransport(token))
                                .sync(new GitHubBackup.PersonIdentity(person.id), person.repository, person.rawMarkdown, delta, prior, person.remoteId);
                        if (result.repoId != person.remoteId) throw new java.io.IOException("保存先のリポジトリIDが一致しません。");
                        File pending = new File(person.directory, ".git-sync.pending");
                        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(pending)) {
                            stream.write(bytes(result.toJson().toString())); stream.getFD().sync();
                        }
                        Files.move(pending.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        more = delta.optBoolean("has_more") || result.cursor < delta.optLong("through_cursor");
                        final boolean remaining = more;
                        ui(() -> { gitStatus = remaining ? "続きの履歴を保存しています" : "Gitへの保存が完了しました";
                            if (report && !remaining) showError("Gitへの保存が完了しました。"); });
                    } catch (Exception error) { syncFailed(error, report); }
                    finally {
                        synchronized (syncingPeople) { syncingPeople.remove(person.id); }
                        if (more) ui(() -> { if (activePerson.id.equals(person.id)) queueGitSync(report); });
                    }
                });
            } catch (Exception error) {
                synchronized (syncingPeople) { syncingPeople.remove(person.id); }
                syncFailed(error, report);
            }
        });
    }

    private void syncFailed(Exception error, boolean report) {
        String detail = safeError(error);
        ui(() -> { gitStatus = detail + "\n会話は端末に保存されています。";
            if (report) showError(gitStatus); scheduleGitSync(); });
    }

    private void exportArchive() {
        final File exportDirectory = new File(activePerson.directory, "export");
        worker.execute(() -> {
            String error = nativeExport();
            ui(() -> {
                if (!error.isEmpty()) { showError(error); return; }
                pendingExportDirectory = exportDirectory;
                Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Conversation-backup.zip");
                startActivityForResult(destination, 81);
            });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 82 && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri source = data.getData();
            worker.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(source);
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    if (stream == null) throw new java.io.IOException("人物ファイルを開けませんでした。");
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = stream.read(chunk)) != -1) {
                        if (buffer.size() + count > PersonDocument.MAX_BYTES)
                            throw new java.io.IOException("人物ファイルは2MiB以内にしてください。");
                        buffer.write(chunk, 0, count);
                    }
                    PersonDocument document = PersonDocument.parse(buffer.toByteArray());
                    ui(() -> previewPerson(document));
                } catch (Exception error) {
                    final String detail = error instanceof IllegalArgumentException ? error.getMessage() : safeError(error);
                    ui(() -> showError(detail));
                }
            });
            return;
        }
        if (request != 81 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        final File exportDirectory = pendingExportDirectory;
        worker.execute(() -> {
            try (java.io.OutputStream destinationStream = getContentResolver().openOutputStream(destination, "w")) {
                if (destinationStream == null) throw new java.io.IOException("Backup destination unavailable");
                if (exportDirectory == null) throw new java.io.IOException("Backup person unavailable");
                BackupArchive.write(exportDirectory, destinationStream);
            } catch (Exception error) { ui(() -> showError("バックアップを書き出せませんでした。保存先を確認してください。")); }
        });
    }

    @Override protected void onDestroy() {
        synchronized (storeGate) { closed = true; }
        syncTimer.removeCallbacks(autoSync);
        if (session != null) session.cancel();
        if (connection != null) connection.cancel();
        worker.shutdownNow();
        reader.shutdownNow();
        super.onDestroy();
    }

    private void applyTypography() {
        compose.setTextSize(textSize); compose.setMessageGap(messageGap);
    }

    private void saveDisplaySettings() {
        getPreferences(MODE_PRIVATE).edit().putInt("text_size", textSize).putInt("message_gap", messageGap).apply();
    }

    private void showDisplaySettings() {
        ComposeUi.Panel panel = new ComposeUi.Panel("表示設定");
        panel.sheet = true;
        panel.preview = true;
        ComposeUi.Setting font = new ComposeUi.Setting("文字サイズ", 16, 28, textSize, value -> {
            textSize = value; applyTypography(); saveDisplaySettings();
        });
        ComposeUi.Setting gap = new ComposeUi.Setting("メッセージの間隔", 8, 24, messageGap, value -> {
            messageGap = value; applyTypography(); saveDisplaySettings();
        });
        panel.sliders.add(font); panel.sliders.add(gap);
        ComposeUi.Row reset = new ComposeUi.Row("標準に戻す", () -> {
            textSize = DEFAULT_TEXT_SIZE; messageGap = 12;
            font.setValue(textSize); gap.setValue(messageGap); applyTypography(); saveDisplaySettings();
        }, 4);
        reset.dismiss = false; panel.rows.add(reset); compose.show(panel);
    }

    @Override public void onPause() {
        if (connection != null) connection.setForeground(false);
        if (compose != null) saveDisplaySettings();
        saveDraft();
        if (!busy && !changingPerson) queueGitSync(false);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && connection != null) connection.setForeground(true);
    }
}
