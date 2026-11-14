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
    private static native long nativeAddReply(byte[] text, byte[] state);
    private static native String nativeLastError();
    private static native String nativeContext(byte[] query);
    private static native String nativeContextHybrid(byte[] query, long[] candidates);
    private static native String nativeEmbeddingBatch(long afterId);
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
    private static native String nativeEmotion();
    private ComposeUi compose;
    private int pageFirst = -1;
    private boolean scrollOnRefresh;
    private ChatGptConnection connection;
    private ConnectionStore imageCredentials;
    private final ImageApi imageApi = new ImageApi();
    private ChatSession session, memorySession;
    private volatile SemanticMemory semanticMemory;
    private String semanticStatus = "意味検索を準備しています";
    private final MemoryWorker memoryWorker = new MemoryWorker();
    private String memoryStatus = "";
    private PersonRegistry people;
    private PersonRegistry.Person activePerson;
    private File pendingExportDirectory;
    private String pendingIconPersonId = "";
    private String pendingAttachmentPersonId = "";
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
        if (state != null) pendingIconPersonId = state.getString("icon_person", "");
        if (state != null) pendingAttachmentPersonId = state.getString("attachment_person", "");
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
        compose.setOnAttach(this::showAttachments);
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
        if (activePerson != null) compose.setAttachments(getPreferences(MODE_PRIVATE).getString("attachments_" + activePerson.id, ""));
        if (activePerson != null) compose.setGenerateImages(getPreferences(MODE_PRIVATE).getBoolean("image_mode_" + activePerson.id, false));
        compose.setPersonName(activePerson == null ? "" : activePerson.name);
        compose.setOnIcon(this::showMemory);
        refreshPersonIcon();
        connection = new ChatGptConnection(this);
        imageCredentials = new ConnectionStore(this, "image-api");
        ChatSession.Store store = new ChatSession.Store() {
            public long add(String role, String text) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    long id = nativeAddMessage(bytes(role), bytes(text));
                    if (id <= 0) throw new java.io.IOException(nativeLastError());
                    return id;
                }
            }
            public long addReply(String text, String state) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    long id = nativeAddReply(bytes(text), bytes(state));
                    if (id <= 0) throw new java.io.IOException(nativeLastError());
                    return id;
                }
            }
            public JSONObject context(String query) throws Exception {
                synchronized (storeGate) { requireCurrentPerson(); return new JSONObject(nativeContext(bytes(query))); }
            }
            public JSONObject replyContext(String text) throws Exception {
                long[] ids = semanticMemory == null ? new long[0] : semanticMemory.search(text);
                synchronized (storeGate) {
                    requireCurrentPerson();
                    String result = nativeContextHybrid(bytes(ChatSession.query(text)), ids);
                    if (result.isEmpty()) throw new java.io.IOException(nativeLastError());
                    return new JSONObject(result);
                }
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
        };
        session = new ChatSession(store);
        memorySession = new ChatSession(store);
        setBusy(false);
        refreshMessages();
        startSemanticMemory();
        scheduleGitSync();
    }

    private void startSemanticMemory() {
        if (semanticMemory != null) semanticMemory.close();
        if (activePerson == null || changingPerson || closed) { semanticMemory = null; return; }
        final String personId = activePerson.id;
        semanticMemory = new SemanticMemory(this, activePerson.directory, afterId -> {
            synchronized (storeGate) {
                requireCurrentPerson();
                if (!personId.equals(activePerson.id)) throw new java.io.IOException("人物が変わりました。");
                String result = nativeEmbeddingBatch(afterId);
                if (result.isEmpty()) throw new java.io.IOException(nativeLastError());
                return new JSONObject(result);
            }
        }, status -> ui(() -> semanticStatus = status));
        semanticMemory.refresh();
    }

    private void refreshMessages() {
        refreshMessages(false);
    }

    private void refreshMessages(boolean replySaved) {
        if (!ready || closed || changingPerson) return;
        final int requested = pageFirst;
        reader.execute(() -> {
            try {
                String snapshot, emotion;
                synchronized (storeGate) {
                    requireOpen();
                    if (changingPerson) return;
                    snapshot = nativeMessages(requested);
                    if (snapshot.isEmpty()) throw new java.io.IOException(nativeLastError());
                    emotion = nativeEmotion();
                }
                JSONObject page = new JSONObject(snapshot);
                page.put("assistant_emotion", emotion);
                org.json.JSONArray rows = page.getJSONArray("messages");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    if ("assistant".equals(row.getString("role"))) row.put("text", ReplyText.visible(row.getString("text"), true));
                }
                final String visibleSnapshot = page.toString();
                final boolean latest = page.getInt("first") + rows.length() >= page.getInt("total");
                ui(() -> {
                    if (changingPerson) return;
                    if (pageFirst != requested) {
                        if (replySaved) refreshMessages(true);
                        return;
                    }
                    if (latest) pageFirst = -1;
                    compose.setMessages(visibleSnapshot, replySaved);
                    if (replySaved) setBusy(false);
                    if (scrollOnRefresh) { compose.scrollToLatest(); scrollOnRefresh = false; }
                });
            } catch (Exception error) { ui(() -> {
                if (replySaved) { compose.cancelReply(); setBusy(false); }
                showError(safeError(error));
            }); }
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
                .putString(draftKey(), compose.getDraft())
                .putBoolean("image_mode_" + activePerson.id, compose.getGenerateImages())
                .putString("attachments_" + activePerson.id, compose.getAttachments()).commit();
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

    private void showAttachments() {
        if (busy || activePerson == null) return;
        ComposeUi.Panel panel = new ComposeUi.Panel("追加"); panel.sheet = true;
        panel.rows.add(new ComposeUi.Row("画像・動画を選ぶ", () -> chooseAttachments(true)));
        panel.rows.add(new ComposeUi.Row("PDF・テキストを選ぶ", () -> chooseAttachments(false)));
        panel.rows.add(new ComposeUi.Row("画像を生成（有料API）", () -> compose.setGenerateImages(true)));
        panel.message = "動画は4枚の代表フレームをAIに送ります。音声は含みません。";
        compose.show(panel);
    }

    private void chooseAttachments(boolean images) {
        if (activePerson == null || busy) return;
        pendingAttachmentPersonId = activePerson.id;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, images ? new String[]{"image/*", "video/mp4", "video/webm", "video/quicktime"} : new String[]{"application/pdf", "text/plain"});
        try { startActivityForResult(picker, 84); }
        catch (android.content.ActivityNotFoundException error) { pendingAttachmentPersonId = ""; showError("ファイル選択を開けませんでした。"); }
    }

    private String draftMessage() {
        return compose.getAttachments().isEmpty() ? compose.getDraft() : compose.getDraft() + "\n\n" + compose.getAttachments();
    }

    private void sendMessage() {
        if (busy || activePerson == null) return;
        if (compose.getGenerateImages()) {
            if (!compose.getAttachments().isEmpty()) { showError("画像生成は入力した説明文から行います。添付を取り外すか、画像生成を解除して通常送信してください。"); return; }
            try {
                if (imageCredentials.load().optString("api_key").isEmpty()) { showImageApiSettings(); return; }
            } catch (Exception error) { showError(safeError(error)); return; }
        } else if (!connection.hasSession()) { showConnection(); return; }
        String text = draftMessage();
        if (text.trim().isEmpty()) return;
        runReply(text, true, compose.getGenerateImages());
    }

    private void runReply(String text, boolean persistUser, boolean images) {
        final PersonRegistry.Person person = activePerson;
        final int imageAttempt = images ? imageApi.generation() : 0;
        setBusy(true);
        compose.beginReply();
        session.begin();
        connectionStatus = images ? "画像を生成しています（有料API）" : "応答を生成しています";
        AtomicBoolean saved = new AtomicBoolean(!persistUser);
        worker.execute(() -> {
            final ChatRequest[] request = {null};
            AtomicBoolean replied = new AtomicBoolean();
            try {
                session.reply(text, persistUser, (instructions, turns, delta) -> {
                    if (images) {
                        setStreaming("画像を生成しています（有料API）…");
                        JSONObject settings = imageCredentials.load();
                        return AttachmentMedia.response(person.directory, imageApi.generate(settings.optString("api_key"),
                                settings.optString("model", ImageApi.DEFAULT_MODEL), text, imageAttempt));
                    }
                    if (request[0] == null) request[0] = selectedRequest();
                    return AttachmentMedia.response(person.directory,
                            connection.request(request[0], instructions, AttachmentMedia.input(person.directory, turns), delta));
                }, new ChatSession.Listener() {
                    private long lastRender;
                    public void userSaved() {
                        saved.set(true);
                        ui(() -> {
                            pageFirst = -1; scrollOnRefresh = true;
                            if (draftMessage().equals(text)) {
                                compose.setDraft("");
                                compose.setAttachments(""); compose.setGenerateImages(false);
                                getPreferences(MODE_PRIVATE).edit().remove(draftKey()).remove("attachments_" + person.id).remove("image_mode_" + person.id).apply();
                            }
                            refreshMessages();
                        });
                    }
                    public void streaming(String full) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastRender > 45) { lastRender = now; ui(() -> compose.setStreaming(full)); }
                    }
                    public void replySaved() {
                        replied.set(true);
                        ui(() -> { refreshMessages(true); connectionStatus = "ChatGPTに接続しています"; });
                    }
                });
            } catch (Exception error) {
                ui(() -> compose.cancelReply());
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
                    dialog.rows.add(new ComposeUi.Row(detail.contains("利用上限") ? "利用状況を確認" : images ? "画像を再生成（有料）" : "再試行", () -> {
                        if (detail.contains("利用上限")) openUsage(); else if (!busy) runReply(text, !saved.get(), images);
                    }));
                    compose.show(dialog);

                });
            } finally { ui(() -> {
                if (!replied.get()) setBusy(false);
                scheduleGitSync();
                if (replied.get()) queueMemories(request[0]);
            }); }
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
        menu.rows.add(new ComposeUi.Row("画像生成API", this::showImageApiSettings, 5));
        if (activePerson != null) menu.rows.add(new ComposeUi.Row("Gitバックアップ", this::showGitBackup, 1));
        if (busy) menu.rows.add(new ComposeUi.Row("応答を停止", () -> {
            session.cancel(); connection.cancelReply(); imageApi.cancel(); compose.cancelReply(); refreshMessages();
        }, 6));
        compose.show(menu);
    }

    private void showConnection() {
        ComposeUi.Panel panel = new ComposeUi.Panel("ChatGPT");
        panel.sheet = true;
        panel.message = connection.hasSession()
                ? connection.accountLabel() + "\nChatGPTの利用枠を使用します。\n" + connectionStatus
                        + "\n" + (memoryWorker.isRunning() ? "記憶をバックグラウンドで整理しています。会話は続けられます。" : memoryStatus)
                        + "\n" + semanticStatus
                : "ChatGPTにログインして、このアプリでサブスクの利用枠を使います。\n" + connectionStatus + "\n" + semanticStatus;
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

    private void showImageApiSettings() {
        if (busy) return;
        JSONObject loaded; boolean unreadable = false;
        try { loaded = imageCredentials.load(); }
        catch (Exception error) { loaded = new JSONObject(); unreadable = true; }
        final JSONObject current = loaded;
        ComposeUi.Panel panel = new ComposeUi.Panel("画像生成API"); panel.secure = true;
        panel.message = "会話・画像認識はChatGPTのサブスク、画像生成だけOpenAI APIの従量課金を使います。\n入力した説明文だけを送信し、過去の会話や添付は送りません。\n"
                + (unreadable ? "保存したAPI設定を読み込めません。再設定するか削除してください。" : current.optString("api_key").isEmpty() ? "APIキーは未設定です。" : "APIキーは設定済みです。空欄なら現在のキーを保ちます。");
        ComposeUi.Field key = new ComposeUi.Field("画像生成用APIキー", true);
        ComposeUi.Field model = new ComposeUi.Field("画像モデル", false);
        model.setValue(current.optString("model", ImageApi.DEFAULT_MODEL));
        panel.fields.add(key); panel.fields.add(model); panel.confirmLabel = "保存";
        panel.confirm = () -> {
            String token = key.getValue().trim(), selectedModel = model.getValue().trim(); key.setValue("");
            if (token.isEmpty()) token = current.optString("api_key");
            final String selectedKey = token;
            try { ImageApi.validateKey(token); ImageApi.body("設定確認", selectedModel); }
            catch (Exception error) { showError(safeError(error)); return; }
            compose.hide();
            worker.execute(() -> {
                try {
                    imageCredentials.save(new JSONObject().put("api_key", selectedKey).put("model", selectedModel));
                    ui(() -> showError("画像生成APIの設定を保存しました。＋の「画像を生成（有料API）」から利用できます。"));
                } catch (Exception error) { ui(() -> showError("画像生成APIの設定を保存できませんでした。")); }
            });
        };
        panel.rows.add(new ComposeUi.Row("APIキーを削除", () -> worker.execute(() -> {
            try { imageCredentials.delete(); ui(() -> showError("画像生成用APIキーを削除しました。ChatGPTの接続は維持しています。")); }
            catch (Exception error) { ui(() -> showError("画像生成用APIキーを削除できませんでした。")); }
        })));
        panel.rows.add(new ComposeUi.Row("API料金を確認", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openai.com/api/pricing/")))));
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

    private void retryMemories() { queueMemories(null); if (semanticMemory != null) semanticMemory.refresh(); }

    private void queueMemories(ChatRequest selected) {
        if (closed || changingPerson || activePerson == null || !connection.hasSession()) return;
        memoryWorker.execute(() -> {
            try {
                requireOpen();
                memorySession.begin();
                final ChatRequest request = selected == null ? selectedRequest() : selected;
                memorySession.processMemories((instructions, turns, delta) -> connection.request(request, instructions, turns, delta, true));
                ui(() -> { memoryStatus = ""; refreshMessages(); scheduleGitSync(); });
            } catch (Exception error) {
                ui(() -> { memoryStatus = "記憶の整理は再試行待ちです。原文は保存されています。" + safeError(error); refreshMessages(); scheduleGitSync(); });
            } finally { if (semanticMemory != null) semanticMemory.refresh(); }
        });
    }

    private void showPeople() {
        ComposeUi.Panel panel = new ComposeUi.Panel("人物");
        panel.sheet = true;
        if (activePerson != null) panel.rows.add(new ComposeUi.Row("人物アイコンを設定", this::showPersonIcon, 2));
        panel.rows.add(new ComposeUi.Row("人物を追加", () -> {
            if (busy || memoryWorker.isRunning()) { showError("応答と記憶の整理が終わってから追加してください。"); return; }
            showNewPerson();
        }, 2));
        for (PersonRegistry.Person person : people.list()) {
            ComposeUi.Row row = new ComposeUi.Row(person.name, () -> {
                if (busy || memoryWorker.isRunning()) { showError("応答と記憶の整理が終わってから人物を切り替えてください。"); return; }
                switchPerson(person);
            }, 5);
            row.checked = activePerson != null && person.id.equals(activePerson.id); panel.rows.add(row);
        }
        panel.rows.add(new ComposeUi.Row("人物ファイルを読み込む", () -> {
            if (busy || memoryWorker.isRunning()) { showError("応答と記憶の整理が終わってから読み込んでください。"); return; }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 82);
        }, 1));
        if (!people.list().isEmpty()) panel.rows.add(new ComposeUi.Row("人物を削除", () -> {
            if (busy || memoryWorker.isRunning()) { showError("応答と記憶の整理が終わってから削除してください。"); return; }
            showDeletePeople();
        }, 6));
        compose.show(panel);
    }

    private void refreshPersonIcon() {
        final PersonRegistry.Person person = activePerson;
        compose.setMediaDirectory(person == null ? null : person.directory);
        if (person == null) { compose.setPersonIcon(null); return; }
        reader.execute(() -> {
            try {
                byte[] image = people.icon(person.id);
                final android.graphics.Bitmap bitmap = image.length == 0 ? null : PersonIcon.decode(image);
                ui(() -> { if (activePerson != null && person.id.equals(activePerson.id)) compose.setPersonIcon(bitmap); });
            } catch (Exception error) { ui(() -> { compose.setPersonIcon(null); showError(safeError(error)); }); }
        });
    }

    private void showMemory() {
        final PersonRegistry.Person person = activePerson;
        if (person == null || changingPerson) return;
        reader.execute(() -> {
            try {
                final JSONObject snapshot;
                synchronized (storeGate) {
                    requireCurrentPerson();
                    if (!person.id.equals(activePerson.id)) return;
                    String result = nativeContext(bytes(""));
                    if (result.isEmpty()) throw new java.io.IOException(nativeLastError());
                    snapshot = new JSONObject(result);
                }
                ui(() -> {
                    if (changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
                    JSONObject continuity = snapshot.optJSONObject("continuity");
                    JSONObject current = continuity == null ? null : continuity.optJSONObject("assistant_state");
                    JSONObject state = current == null ? new JSONObject() : current;
                    org.json.JSONArray episodes = continuity == null ? null : continuity.optJSONArray("episodes");
                    ComposeUi.Panel panel = new ComposeUi.Panel("記憶"); panel.sheet = true;
                    panel.message = "感情\n" + state.optString("emotion", "未記録")
                            + "\n\n理由\n" + state.optString("reason", "未記録")
                            + "\n\n状況\n" + state.optString("event", "未記録");
                    panel.rows.add(new ComposeUi.Row("短期記憶（直近30往復）", () -> showMemoryHistory(person, snapshot, false)));
                    panel.rows.add(new ComposeUi.Row("中期記憶（" + (episodes == null ? 0 : episodes.length()) + "件）",
                            () -> showMemoryHistory(person, snapshot, true)));
                    panel.rows.add(new ComposeUi.Row("人物アイコンを設定", this::showPersonIcon, 2));
                    compose.show(panel);
                });
            } catch (Exception error) { ui(() -> showError(safeError(error))); }
        });
    }

    private void showMemoryHistory(PersonRegistry.Person person, JSONObject snapshot, boolean middleTerm) {
        if (changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
        ComposeUi.Panel panel = new ComposeUi.Panel(middleTerm ? "中期記憶" : "短期記憶"); panel.sheet = true;
        JSONObject continuity = snapshot.optJSONObject("continuity");
        org.json.JSONArray entries = middleTerm
                ? (continuity == null ? null : continuity.optJSONArray("episodes")) : snapshot.optJSONArray("messages");
        StringBuilder text = new StringBuilder();
        if (entries == null || entries.length() == 0) {
            text.append(middleTerm ? "中期記憶はまだありません。会話を整理すると、場面ごとの記憶がここに表示されます。"
                    : "短期記憶の会話はまだありません。");
        } else {
            text.append(middleTerm ? "直近30件までの場面を、新しいものから表示します。"
                    : "直近30往復（最大61発言）を、新しいものから表示します。");
            int first = Math.max(0, entries.length() - (middleTerm ? 30 : 61));
            for (int i = entries.length() - 1; i >= first; i--) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                text.append("\n\n");
                if (middleTerm) {
                    String scene = entry.optString("scene");
                    text.append(scene.isEmpty() ? "場面の情報は未記録" : scene).append("\n").append(entry.optString("text"));
                    String emotion = entry.optString("emotion");
                    if (!emotion.isEmpty()) text.append("\n気持ち：").append(emotion);
                    String scope = entry.optString("scope");
                    text.append("\n").append(scope.equals("fiction") ? "創作の場面" : scope.equals("real") ? "現実の出来事" : "会話内の体験");
                } else {
                    boolean user = entry.optString("role").equals("user");
                    text.append(user ? "あなた" : person.name).append("\n");
                    String content = entry.optString("content");
                    text.append(user ? content : ReplyText.visible(content, true));
                    if (entry.optBoolean("truncated")) text.append("\n（長文のため一部を表示）");
                }
            }
        }
        panel.message = text.toString();
        panel.onDismiss = this::showMemory;
        compose.show(panel);
    }

    private void showPersonIcon() {
        if (activePerson == null) return;
        ComposeUi.Panel panel = new ComposeUi.Panel("人物アイコン"); panel.sheet = true;
        panel.rows.add(new ComposeUi.Row("写真・画像から選ぶ", () -> {
            pendingIconPersonId = activePerson.id;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 83);
        }, 1));
        panel.rows.add(new ComposeUi.Row("名前のアイコンに戻す", () -> {
            final PersonRegistry.Person person = activePerson;
            worker.execute(() -> {
                try { people.setIcon(person.id, null); ui(() -> { refreshPersonIcon(); scheduleGitSync(); }); }
                catch (Exception error) { ui(() -> showError(safeError(error))); }
            });
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
        if (busy || memoryWorker.isRunning()) return;
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
                            if (semanticMemory != null) semanticMemory.close();
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
                if (!getPreferences(MODE_PRIVATE).edit().remove("draft_" + person.id).remove("attachments_" + person.id).remove("image_mode_" + person.id).commit() && failure == null)
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
                    changingPerson = false; setBusy(false); startSemanticMemory(); showError(detail); scheduleGitSync();
                }
            });
        });
    }

    private void switchPerson(PersonRegistry.Person person) {
        if ((activePerson != null && person.id.equals(activePerson.id)) || busy || memoryWorker.isRunning()) return;
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
        if (busy || memoryWorker.isRunning()) return;
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
        if (busy || memoryWorker.isRunning()) return;
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
                byte[] icon = people.icon(person.id);
                if (icon.length > 0) delta.put("icon_png_base64", java.util.Base64.getEncoder().encodeToString(icon));
                AttachmentFiles.addToDelta(person.directory, delta);
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
        final PersonRegistry.Person person = activePerson;
        final File exportDirectory = new File(activePerson.directory, "export");
        worker.execute(() -> {
            String error = nativeExport();
            if (error.isEmpty()) {
                try { BackupArchive.addIcon(exportDirectory, people.icon(person.id)); AttachmentFiles.backup(person.directory, exportDirectory); }
                catch (Exception failure) { error = safeError(failure); }
            }
            final String exportError = error;
            ui(() -> {
                if (!exportError.isEmpty()) { showError(exportError); return; }
                pendingExportDirectory = exportDirectory;
                Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Conversation-backup.zip");
                startActivityForResult(destination, 81);
            });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 84) {
            final PersonRegistry.Person person = activePerson;
            final String selectedPerson = pendingAttachmentPersonId; pendingAttachmentPersonId = "";
            if (result != RESULT_OK || data == null || person == null) return;
            if (!person.id.equals(selectedPerson)) { showError("選択中の人物が変わりました。もう一度添付してください。"); return; }
            List<Uri> sources = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) sources.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) sources.add(data.getData());
            final String previous = compose.getAttachments();
            if (sources.size() + AttachmentFiles.references(previous).size() > 4) { showError("添付は1回4件までです。"); return; }
            setBusy(true);
            worker.execute(() -> {
                try {
                    StringBuilder attachments = new StringBuilder(previous);
                    for (Uri source : sources) {
                        AttachmentFiles.Ref ref = AttachmentMedia.importUri(this, person.directory, source);
                        if (attachments.length() > 0) attachments.append('\n');
                        attachments.append(ref.markdown());
                    }
                    ui(() -> { if (activePerson != null && activePerson.id.equals(person.id)) { compose.setAttachments(attachments.toString()); saveDraft(); } });
                } catch (Exception error) { ui(() -> showError(safeError(error))); }
                finally { ui(() -> setBusy(false)); }
            });
            return;
        }
        if (request == 83) {
            final String personId = pendingIconPersonId; pendingIconPersonId = "";
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            if (activePerson == null || !activePerson.id.equals(personId)) {
                showError("画像を選ぶ人物が変わりました。人物アイコンからもう一度選んでください。"); return;
            }
            final Uri source = data.getData();
            worker.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(source)) {
                    people.setIcon(personId, PersonIcon.read(stream));
                    ui(() -> { refreshPersonIcon(); scheduleGitSync(); });
                } catch (Exception error) { ui(() -> showError(safeError(error))); }
            });
            return;
        }
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

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("icon_person", pendingIconPersonId);
        state.putString("attachment_person", pendingAttachmentPersonId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        synchronized (storeGate) { closed = true; }
        syncTimer.removeCallbacks(autoSync);
        if (session != null) session.cancel();
        if (memorySession != null) memorySession.cancel();
        memoryWorker.close();
        if (semanticMemory != null) semanticMemory.close();
        if (connection != null) connection.cancel();
        imageApi.cancel();
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
