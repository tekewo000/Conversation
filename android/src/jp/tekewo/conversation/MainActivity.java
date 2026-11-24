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
import jp.tekewo.conversation.InitiativePolicy.Trigger;
import org.json.JSONObject;

public final class MainActivity extends ComponentActivity {
    private static native String nativeInit(byte[] storageDir, byte[] font, byte[] emojiFont);
    static native long nativeAddMessage(byte[] role, byte[] text);
    private static native long nativeAddReply(byte[] text, byte[] state);
    private static native long nativeAddNote(byte[] kind, byte[] text);
    static native String nativeRebuildBatch(long afterId, long throughId);
    static native String nativePublishRebuild();
    static native String nativeLastError();
    static native String nativeContext(byte[] query);
    private static native String nativeContextHybrid(byte[] query, long[] candidates);
    private static native String nativeEmbeddingBatch(long afterId);
    static native String nativePendingJob();
    static native String nativeApplyJob(long id, byte[] payload);
    static native String nativePendingPersona();
    static native String nativeApplyPersona(long throughId, byte[] payload);
    private static native String nativeMemories(byte[] query);
    private static native String nativeEpisodeChildren(byte[] query);
    private static native String nativeExport(BackupArchive writer);
    private static native String nativeRecentTranscript();
    static native String nativeSwitchPerson(byte[] directory, byte[] payload, byte[] key);
    static String nativeSwitchPerson(byte[] directory, byte[] payload) {
        byte[] key = null;
        try {
            key = directory.length == 0 ? new byte[0] : EncryptedDatabase.key(new File(new String(directory,StandardCharsets.UTF_8)),true);
            return nativeSwitchPerson(directory,payload,key);
        } catch(Exception error) { return error.getMessage(); }
        finally { if(key!=null) java.util.Arrays.fill(key,(byte)0); }
    }
    private static native String nativeArchiveDelta(long cursor);

    private static native String nativeMessages(int first);
    private static native String nativeEmotion();
    private ComposeUi compose;
    private int pageFirst = -1;
    private boolean scrollOnRefresh;
    private ChatGptConnection connection;
    private ConnectionStore imageCredentials;
    private final ImageApi imageApi = new ImageApi();
    private ChatSession session;
    private volatile SemanticMemory semanticMemory;
    private String semanticStatus = "意味検索を準備しています";
    private String memoryStatus = "";
    private int memoryView;
    private int episodeRequest;
    private PersonRegistry people;
    private PersonRegistry.Person activePerson;
    private File pendingExportDirectory;
    private String pendingIconPersonId = "";
    private String pendingRecoveryPersonId = "";
    private String pendingAttachmentPersonId = "";
    private String gitStatus = "";
    private final android.os.Handler syncTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoSync = () -> queueGitSync(false);
    private final Runnable initiativeOnOpen = () -> tryInitiative(Trigger.OPEN);
    private final Runnable initiativeOnIdle = () -> tryInitiative(Trigger.IDLE);
    private volatile long initiativeEpoch;
    private boolean resumed, draftSavedOnPause;
    private static final ExecutorService gitWorker = Executors.newSingleThreadExecutor();
    private static final Set<String> syncingPeople = new HashSet<>();
    static final Object storeGate = new Object();
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
    private static final int REQUEST_INITIATIVE_NOTIFICATIONS = 85;
    private int textSize = DEFAULT_TEXT_SIZE;
    private int messageGap = 12;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingIconPersonId = state.getString("icon_person", "");
        if (state != null) pendingRecoveryPersonId = state.getString("recovery_person", "");
        if (state != null) pendingAttachmentPersonId = state.getString("attachment_person", "");
        textSize = Math.max(16, Math.min(28, getPreferences(MODE_PRIVATE).getInt("text_size", DEFAULT_TEXT_SIZE)));
        messageGap = Math.max(8, Math.min(24, getPreferences(MODE_PRIVATE).getInt("message_gap", 12)));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        int windowBackground = getColor(R.color.conversation_window_background);
        getWindow().setStatusBarColor(windowBackground);
        getWindow().setNavigationBarColor(windowBackground);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        compose = new ComposeUi(this, this::showHistoryMenu, this::sendMessage, first -> {
            pageFirst = first; armInitiative(false); refreshMessages();
        });
        compose.setOnPageChanged(key -> {
            memoryView = key.equals("memory") ? 1 : key.equals("recent") ? 2 : key.equals("episodes") ? 3
                    : key.startsWith("episode:") ? 4 : 0;
            episodeRequest++;
            armInitiative(key.isEmpty());
        });
        compose.setOnInitiativeAvailabilityChanged(available -> {
            if (!available || !syncTimer.hasCallbacks(initiativeOnOpen)) armInitiative(false);
        });
        applyTypography();
        compose.setOnAttach(this::showAttachments);
        compose.setEnabled(false);
        if(!DataMigration.needed(this)) { initializeConversation(); return; }
        worker.execute(() -> {
            try { DataMigration.ensure(this); ui(this::initializeConversation); }
            catch(Exception error) { ui(() -> showRecovery(safeError(error))); }
        });
    }
    private void initializeConversation() {
        try {
            System.loadLibrary("conversation");
            synchronized (storeGate) {
                requireSuccess(nativeSwitchPerson(bytes(""), bytes("")));
                people = new PersonRegistry(getFilesDir());
                activePerson = people.active();
                requireSuccess(nativeSwitchPerson(bytes(activePerson == null ? "" : activePerson.directory.getAbsolutePath()),new byte[0]));
                ready = true;
            }
        } catch (Exception | UnsatisfiedLinkError error) {
            showRecovery(error.getMessage());
            return;
        }
        if (activePerson != null) compose.setDraft(readPersonState().optString("draft"));
        worker.execute(() -> {
            List<Exception> failures = new ArrayList<>();
            for (PersonRegistry.Person person : people.list()) {
                try { AttachmentFiles.migrateImages(person.directory); } catch (Exception error) { failures.add(error); }
                try { people.icon(person.id); } catch (Exception error) { failures.add(error); }
                try { ImageVault.migrateExports(person.directory); } catch (Exception error) { failures.add(error); }
            }
            if (!failures.isEmpty()) ui(() -> showError("一部の既存画像を暗号化できませんでした。対象のデータは保持し、ほかの画像の移行は続けました。データを消さずに再試行してください。\n" + safeError(failures.get(0))));
        });
        if (activePerson != null) compose.setAttachments(readPersonState().optString("attachments"));
        if (activePerson != null) compose.setGenerateImages(getPreferences(MODE_PRIVATE).getBoolean("image_mode_" + activePerson.id, false));
        compose.setPersonName(activePerson == null ? "" : activePerson.displayName);
        compose.setOnIcon(this::showProfile);
        compose.setOnSendNote(this::showNoteMenu);
        refreshPersonIcon();
        connection = new ChatGptConnection(this);
        imageCredentials = new ConnectionStore(this, "image-api");
        ChatSession.Store store = new ChatSession.Store() {
            public long addNote(String kind, String text) throws Exception {
                synchronized (storeGate) {
                    requireCurrentPerson();
                    long id = nativeAddNote(bytes(kind), bytes(text));
                    if (id <= 0) throw new java.io.IOException(nativeLastError());
                    return id;
                }
            }
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
                String search = ChatSession.needsRecallContext(text) ? ChatSession.recallQuery(text, context("")) : text;
                long[] ids = semanticMemory == null ? new long[0] : semanticMemory.search(search);
                synchronized (storeGate) {
                    requireCurrentPerson();
                    String result = nativeContextHybrid(bytes(ChatSession.query(search)), ids);
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
        setBusy(false);
        refreshMessages();
        startSemanticMemory();
        for (String name : new String[]{MemoryWork.NAME, MemoryWork.RECORD_NAME})
        androidx.work.WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(name).observe(this, work -> {
            memoryStatus = MemoryWork.status(this);
            refreshMessages();
            if (semanticMemory != null) semanticMemory.refresh();
            if (memoryView != 0) refreshMemoryView();
            else if (compose.isPageVisible("profile", compose.getPageVersion())) refreshProfile();
            else if (compose.isPageVisible("management", compose.getPageVersion())) showHistoryMenu();
        });
        queueMemories(true);
        scheduleGitSync();
        armInitiative(true);
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
                    if (latest && pageFirst != -1) { pageFirst = -1; armInitiative(false); }
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
    private JSONObject readPersonState() {
        try { return PersonState.load(activePerson.directory); }
        catch(Exception error) { ready=false; showError(safeError(error)); return new JSONObject(); }
    }
    private void clearDraft(PersonRegistry.Person person) {
        try { PersonState.put(person.directory,"draft",""); PersonState.put(person.directory,"attachments",""); }
        catch(java.io.IOException error) { ready=false; showError(safeError(error)); }
    }
    private boolean saveDraft() {
        if(changingPerson||compose==null||activePerson==null) return true;
        try {
            synchronized (PersonState.class) {
                JSONObject state=PersonState.load(activePerson.directory);
                state.put("draft",compose.getDraft()).put("attachments",compose.getAttachments());
                PersonState.save(activePerson.directory,state);
            }
            getPreferences(MODE_PRIVATE).edit().putBoolean("image_mode_"+activePerson.id,compose.getGenerateImages()).apply();
            return true;
        } catch(Exception error) { showError("下書きを保存できません。入力はこの画面に保持しています。\n"+safeError(error));return false; }
    }

    private void setBusy(boolean value) {
        busy = value;
        compose.setEnabled(ready && activePerson != null && !changingPerson);
        compose.setSending(value);
        armInitiative(false);
        if (compose.isPageVisible("management", compose.getPageVersion())) showHistoryMenu();
    }

    private boolean canTryInitiative() {
        return resumed && hasWindowFocus() && ready && !busy && !closed && !changingPerson && activePerson != null
                && pageFirst == -1 && compose.getInitiativeAvailable()
                && connection != null && connection.hasSession();
    }

    private void armInitiative(boolean opening) {
        initiativeEpoch++;
        syncTimer.removeCallbacks(initiativeOnOpen);
        syncTimer.removeCallbacks(initiativeOnIdle);
        if (!canTryInitiative()) return;
        if (opening && InitiativeWork.enabled(this, Trigger.OPEN)) syncTimer.postDelayed(initiativeOnOpen, 750);
        if (InitiativeWork.enabled(this, Trigger.IDLE)) syncTimer.postDelayed(initiativeOnIdle, Trigger.IDLE.quietMillis);
    }

    private void tryInitiative(Trigger trigger) {
        if (!canTryInitiative() || !InitiativeWork.enabled(this, trigger)) return;
        long epoch = initiativeEpoch;
        String personId = activePerson.id;
        InitiativeWork.tryForeground(this, personId, trigger, () -> epoch == initiativeEpoch && compose.getInitiativeAvailable(), () -> ui(() -> {
            if (changingPerson || activePerson == null || !personId.equals(activePerson.id)) return;
            if (pageFirst == -1 && compose.isPageVisible("", compose.getPageVersion())) scrollOnRefresh = true;
            refreshMessages();
            scheduleGitSync();
        }));
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

    private void showNoteMenu() {
        if (busy || activePerson == null) return;
        if (compose.getDraft().trim().isEmpty()) { showError("情景やメタ発言を入力してください。"); return; }
        if (!compose.getAttachments().isEmpty()) { showError("情景・メタ発言は文章だけで送れます。添付を取り外してください。"); return; }
        ComposeUi.Panel panel = new ComposeUi.Panel("返信なしで送る"); panel.sheet = true;
        panel.message = "人物のセリフとは別の補足として保存し、次の返答と記憶の整理に反映します。";
        panel.rows.add(new ComposeUi.Row("情景として送る", () -> sendNote("scene")));
        panel.rows.add(new ComposeUi.Row("メタ発言として送る", () -> sendNote("meta")));
        compose.show(panel);
    }

    private void sendNote(String kind) {
        if (busy || activePerson == null) return;
        final String text = compose.getDraft();
        if (text.trim().isEmpty() || !compose.getAttachments().isEmpty()) return;
        final PersonRegistry.Person person = activePerson;
        setBusy(true); session.begin();
        worker.execute(() -> {
            try {
                session.note(kind, text);
                MemoryWork.enqueue(getApplicationContext());
                ui(() -> {
                    if (compose.getDraft().equals(text)) {
                        compose.setDraft("");
                        try { PersonState.put(activePerson.directory,"draft",""); } catch(java.io.IOException error) { ready=false; showError(safeError(error)); }
                    }
                    pageFirst = -1; scrollOnRefresh = true;
                    refreshMessages(); scheduleGitSync();
                });
            } catch (Exception error) { ui(() -> showError(safeError(error))); }
            finally { ui(() -> { if (activePerson != null && person.id.equals(activePerson.id)) setBusy(false); }); }
        });
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
                                clearDraft(person);
                                getPreferences(MODE_PRIVATE).edit().remove("image_mode_" + person.id).apply();
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
                if (replied.get()) queueMemories(false);
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
        if(!ready||people==null) { showRecovery("保存データを開いてから利用してください。");return; }
        if (!ready) return;
        ComposeUi.Panel menu = page("管理", "management", true);
        menu.onResume = this::showHistoryMenu;
        menu.rows.add(entry("記憶", MemoryWork.isRunning() ? "会話を整理しています" : "最近の会話と、体験の記録", 3, this::showMemory));
        menu.rows.add(entry("人物", activePerson == null ? "名前だけでも始められます" : "会話相手の切り替え・管理", 2, this::showPeople));
        menu.rows.add(entry("設定", connection.hasSession() ? "ChatGPT接続済み" : "接続・応答・表示・画像生成", 4, this::showSettings));
        menu.rows.add(entry("バックアップ", activePerson == null || activePerson.repository == null ? "端末の保存データ・ZIP書き出し" : "GitHubへの保存・ZIP書き出し", 1, this::showGitBackup));
        ComposeUi.Row latest = entry("最新の会話へ", "チャットの最新の発言を表示", 0, () -> {
            compose.closePages(); pageFirst = -1; scrollOnRefresh = true; refreshMessages();
        });
        latest.section = "会話";
        menu.rows.add(latest);
        if (busy) {
            ComposeUi.Row stop = new ComposeUi.Row("応答を停止", () -> {
                session.cancel(); connection.cancelReply(); imageApi.cancel(); compose.cancelReply(); refreshMessages();
            }, 6);
            stop.section = latest.section;
            menu.rows.add(stop);
        }
        compose.showRoot(menu);
    }

    private ComposeUi.Panel page(String title, String key, boolean person) {
        ComposeUi.Panel panel = new ComposeUi.Panel(title);
        panel.key = key;
        if (person) panel.subtitle = activePerson == null ? "人物が選択されていません" : activePerson.displayName;
        return panel;
    }

    private static ComposeUi.Row entry(String label, String description, int icon, Runnable action) {
        ComposeUi.Row row = new ComposeUi.Row(label, action, icon);
        row.description = description;
        return row;
    }

    private static ComposeUi.Row section(String title, ComposeUi.Row row) {
        row.section = title;
        return row;
    }

    private void refreshManagementStatus() {
        String key = compose.getPageKey();
        if (!compose.isPageVisible(key, compose.getPageVersion())) return;
        if (key.equals("backup")) showGitBackup();
        else if (key.equals("management")) showHistoryMenu();
    }

    private void showSettings() {
        ComposeUi.Panel panel = page("設定", "settings", false);
        panel.onResume = this::showSettings;
        panel.featuredRows.add(entry("ChatGPT", connection.hasSession() ? "接続済み · アカウントと利用状況" : "未接続 · ログインして会話を始める", 5, this::showConnection));
        panel.rows.add(section("会話と画像", entry("モデルと応答", "モデル・Effort・Fast", 0, this::showResponseSettings)));
        panel.rows.add(section("会話と画像", entry("画像生成API", "画像生成だけに使う有料API", 3, this::showImageApiSettings)));
        panel.rows.add(section("会話と画像", entry("人物から話しかける", "自発メッセージ・通知", -1, this::showInitiativeSettings)));
        panel.rows.add(section("読みやすさ", entry("表示設定", "文字サイズ・メッセージの間隔", 4, this::showDisplaySettings)));
        panel.rows.add(section("アプリ情報", new ComposeUi.Row("ライセンス",()->{
            ComposeUi.Panel licenses=page("ライセンス","licenses",false);
            try(java.io.InputStream input=getAssets().open("data-encryption-licenses.txt")) { licenses.message=new String(AttachmentMedia.bounded(input,65536),StandardCharsets.UTF_8); }
            catch(Exception error) { licenses.message="ライセンスを読み込めませんでした。"; }
            compose.showPage(licenses);
        })));
        compose.showPage(panel);
    }

    private void showInitiativeSettings() {
        ComposeUi.Panel panel = page("人物から話しかける", "initiative", false);
        panel.onResume = this::showInitiativeSettings;
        panel.message = "自発メッセージの生成にはChatGPTの利用枠を使います。";
        panel.rows.add(initiativeSwitch("会話を開いたとき", "初回、または直近の発言から30分以上空いたとき", Trigger.OPEN));
        panel.rows.add(initiativeSwitch("会話の合間", "会話を表示したまま5分以上操作がないとき", Trigger.IDLE));
        panel.rows.add(initiativeSwitch("アプリを閉じているとき", "アプリを閉じてから4時間以降", Trigger.BACKGROUND));
        ComposeUi.Row notifications = entry("バックグラウンド通知",
                "アプリを閉じている間に人物からメッセージが届いたときに通知します。", -1, () -> {
            boolean enabled = !InitiativeWork.notificationsEnabled(this);
            InitiativeWork.setNotificationsEnabled(this, enabled);
            showInitiativeSettings();
            if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_INITIATIVE_NOTIFICATIONS);
        });
        notifications.toggle = true;
        notifications.checked = InitiativeWork.notificationsEnabled(this);
        panel.rows.add(notifications);
        compose.showPage(panel);
    }

    private ComposeUi.Row initiativeSwitch(String title, String description, Trigger trigger) {
        ComposeUi.Row row = entry(title, description, -1, () -> {
            InitiativeWork.setEnabled(this, trigger, !InitiativeWork.enabled(this, trigger));
            showInitiativeSettings();
        });
        row.toggle = true;
        row.checked = InitiativeWork.enabled(this, trigger);
        return row;
    }

    private void showResponseSettings() {
        ComposeUi.Panel panel = page("モデルと応答", "response", false);
        panel.onResume = this::showResponseSettings;
        panel.message = "会話と記憶の整理に使うモデルを選びます。変更できる項目はモデルによって異なります。";
        if (connection.hasSession()) addRequestSettings(panel);
        else panel.rows.add(entry("ChatGPTに接続", "ログイン後にモデルを選べます", 5, this::showConnection));
        compose.showPage(panel);
    }

    private void refreshResponseSettings() {
        if (compose.getPageKey().equals("response")) showResponseSettings();
        else if (compose.getPageKey().equals("connection")) showConnection();
    }

    private void showConnection() {
        ComposeUi.Panel panel = page("ChatGPT", "connection", false);
        panel.onResume = this::showConnection;
        panel.message = connection.hasSession()
                ? connection.accountLabel() + "\nChatGPTの利用枠を使用します。\n" + connectionStatus
                : "ChatGPTにログインして、このアプリでサブスクの利用枠を使います。\n" + connectionStatus;
        if (!connection.hasSession()) {
            panel.rows.add(new ComposeUi.Row("Continue with ChatGPT", () -> {
                if (busy) return;
                connectionStatus = "ブラウザでの認証を待っています";
                final int version = compose.getPageVersion();
                connection.signIn(error -> ui(() -> finishSignIn(error, version)));
            }, 5));
        } else addRequestSettings(panel);
        ComposeUi.Row signOut = new ComposeUi.Row(connection.hasSession() ? "ログアウト" : "保存した接続情報をリセット", () -> {
            if (busy) return;
            MemoryWork.cancel(this);
            final int version = compose.getPageVersion();
            worker.execute(() -> {
                connection.signOut(); availableModels = null;
                ui(() -> { connectionStatus = connection.signOutStatus();
                    if (compose.isPageVisible("connection", version)) showConnection(); });
            });
        }, 6);
        signOut.destructive = true;
        signOut.section = "接続の管理";
        panel.rows.add(new ComposeUi.Row("利用状況を確認", this::openUsage, 1));
        panel.rows.add(signOut);
        compose.showPage(panel);
    }

    private void finishSignIn(String error, int version) {
        availableModels = null;
        connectionStatus = error == null ? "ChatGPTに接続しました" : error;
        if (!compose.isPageVisible("connection", version)) {
            if (error == null) retryMemories();
            return;
        }
        if (error != null) { showError(error); return; }
        showConnection();
        if (!getPreferences(MODE_PRIVATE).getBoolean("plan_welcome", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("plan_welcome", true).apply();
            ComposeUi.Panel welcome = new ComposeUi.Panel("ChatGPTに接続しました");
            welcome.message = "会話と記憶の整理にChatGPTの利用枠を使います。利用状況は設定のChatGPTから確認できます。";
            welcome.rows.add(new ComposeUi.Row("会話を始める", () -> { compose.closePages(); retryMemories(); }));
            compose.show(welcome);
        } else retryMemories();
    }

    private void addRequestSettings(ComposeUi.Panel panel) {
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
        final int version = compose.getPageVersion();
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
                    if (busy || !connection.hasSession() || version != compose.getPageVersion()) return;
                    ComposeUi.Panel panel = new ComposeUi.Panel("モデル · ChatGPTの利用枠");
                    panel.choices = true;
                    panel.onDismiss = this::refreshResponseSettings;
                    for (int i = 0; i < models.size(); i++) {
                        final int index = i;
                        ComposeUi.Row row = new ComposeUi.Row(names[i], () -> {
                            if (busy) return;
                            getPreferences(MODE_PRIVATE).edit().putString("model_slug", models.get(index).slug).apply();
                            refreshResponseSettings();
                        });
                        row.checked = i == selectedIndex; panel.rows.add(row);
                    }
                    compose.show(panel);
                });

            } catch (Exception error) { ui(() -> { if (version == compose.getPageVersion()) showError(safeError(error)); }); }
        });
    }

    private void showRequestOption(boolean fast) {
        if (busy) return;
        final int version = compose.getPageVersion();
        worker.execute(() -> {
            try {
                ChatRequest request = selectedRequest();
                ChatRequest.Model model = request.model;
                ui(() -> {
                    if (busy || !connection.hasSession() || version != compose.getPageVersion()) return;
                    ComposeUi.Panel panel = new ComposeUi.Panel((fast ? "Fast" : "Effort") + " · " + model.displayName);
                    panel.choices = true;
                    panel.onDismiss = this::refreshResponseSettings;
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
                                refreshResponseSettings();
                            });
                            row.checked = fast ? (request.fast ? i == 1 : i == 0) : values.get(i).equals(request.effort);
                            panel.rows.add(row);
                        }
                    }
                    compose.show(panel);
                });
            } catch (Exception error) { ui(() -> { if (version == compose.getPageVersion()) showError(safeError(error)); }); }
        });
    }

    private void retryMemories() { queueMemories(true); if (semanticMemory != null) semanticMemory.refresh(); }

    private void queueMemories(boolean resume) {
        if (closed || changingPerson || activePerson == null || !connection.hasSession()) return;
        if (resume || MemoryRebuild.pending(activePerson.directory)) MemoryWork.resume(this); else MemoryWork.enqueue(this);
    }

    private void showPeople() {
        ComposeUi.Panel panel = page("人物", "people", false);
        panel.onResume = this::showPeople;
        if (activePerson != null) {
            panel.rows.add(section("現在の相手を編集", new ComposeUi.Row("名前を編集", this::showEditDisplayName, 4)));
            panel.rows.add(section("現在の相手を編集", new ComposeUi.Row("人物アイコンを設定", this::showPersonIcon, 2)));
        }
        panel.rows.add(section("追加と読み込み", new ComposeUi.Row("人物を追加", () -> {
            if (busy || MemoryWork.isRunning()) { showError("応答と記憶の整理が終わってから追加してください。"); return; }
            showNewPerson();
        }, 2)));
        panel.rows.add(section("追加と読み込み", new ComposeUi.Row("人物ファイルを読み込む", () -> {
            if (busy || MemoryWork.isRunning()) { showError("応答と記憶の整理が終わってから読み込んでください。"); return; }
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 82);
        }, 1)));
        List<PersonRegistry.Person> roster = people.list();
        for (PersonRegistry.Person person : roster) {
            ComposeUi.Row row = new ComposeUi.Row(person.displayName, () -> {
                if (busy || MemoryWork.isRunning()) { showError("応答と記憶の整理が終わってから人物を切り替えてください。"); return; }
                switchPerson(person);
            }, 5);
            row.checked = activePerson != null && person.id.equals(activePerson.id);
            if (row.checked) row.description = "現在の会話相手";
            panel.rows.add(section("会話の相手", row));
        }
        if (!roster.isEmpty()) {
            ComposeUi.Row delete = new ComposeUi.Row("人物を削除", () -> {
                if (busy || MemoryWork.isRunning()) { showError("応答と記憶の整理が終わってから削除してください。"); return; }
                showDeletePeople();
            }, 6);
            delete.destructive = true;
            panel.rows.add(section("削除", delete));
        }
        compose.showPage(panel);
    }

    private void showEditDisplayName() {
        final PersonRegistry.Person person=activePerson;
        if(person==null||changingPerson) return;
        ComposeUi.Panel panel=new ComposeUi.Panel("名前を編集");panel.secure=true;
        panel.message="人物欄や会話画面に表示する名前を変更します。";
        ComposeUi.Field name=new ComposeUi.Field("表示名",false);name.setValue(person.displayName);panel.fields.add(name);
        panel.confirmLabel="保存";
        panel.confirm=()->{
            final String value;
            try { value=PersonDocument.named(name.getValue()).name; }
            catch(IllegalArgumentException error) { showError(error.getMessage());return; }
            compose.hide();
            final int version=compose.getPageVersion();
            worker.execute(()->{
                try {
                    synchronized(storeGate) { requireCurrentPerson();if(!person.id.equals(activePerson.id)) throw new java.io.IOException("人物が変わりました。");people.setDisplayName(person.id,value); }
                    ui(()->{
                        if(activePerson==null||!person.id.equals(activePerson.id)) return;
                        compose.setPersonName(person.displayName);
                        if(compose.isPageVisible("people",version)) showPeople();
                        queueGitSync(false);
                    });
                } catch(Exception error) { ui(()->showError(safeError(error))); }
            });
        };
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

    private void showProfile() {
        ComposeUi.Panel loading = page("プロフィール", "profile", true);
        loading.loading = true;
        loading.onResume = this::refreshProfile;
        compose.showPage(loading);
        refreshProfile();
    }

    private void refreshProfile() {
        PersonRegistry.Person person = activePerson;
        int version = compose.getPageVersion();
        if (person == null || changingPerson || !compose.isPageVisible("profile", version)) return;
        reader.execute(() -> {
            try {
                JSONObject snapshot;
                synchronized (storeGate) {
                    if (activePerson == null || !person.id.equals(activePerson.id)) return;
                    requireCurrentPerson();
                    String result = nativeContext(bytes(""));
                    if (result.isEmpty()) throw new java.io.IOException(nativeLastError());
                    snapshot = new JSONObject(result);
                }
                java.util.Map<String, JSONObject> facts = new java.util.HashMap<>();
                org.json.JSONArray rows = snapshot.optJSONArray("profile_facts");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject fact = rows.getJSONObject(i);
                    facts.put(fact.getString("label"), fact);
                }
                ui(() -> {
                    if (!compose.isPageVisible("profile", version) || changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
                    ComposeUi.Panel panel = page("プロフィール", "profile", true);
                    panel.onResume = this::refreshProfile;
                    JSONObject name = facts.get("名前");
                    if (name != null) panel.subtitle = name.optString("value", person.name);
                    for (String label : new String[]{"名前", "身長", "体重", "趣味", "好きなもの"}) {
                        JSONObject fact = facts.get(label);
                        String value = fact == null ? (label.equals("名前") ? person.name : "未設定") : fact.optString("value");
                        ComposeUi.Row row = entry(label, value, -1, () -> showProfileFact(label, value, fact));
                        row.enabled = fact != null || label.equals("名前");
                        panel.rows.add(row);
                    }
                    panel.rows.add(section("記録", entry("会話の記憶", "会話と体験を読む", 3, this::showMemory)));
                    compose.showPage(panel);
                });
            } catch (Exception error) { ui(() -> {
                if (!compose.isPageVisible("profile", version)) return;
                ComposeUi.Panel failed = page("プロフィール", "profile", true);
                failed.message = safeError(error);
                failed.onResume = this::refreshProfile;
                compose.showPage(failed);
            }); }
        });
    }

    private void showProfileFact(String label, String value, JSONObject fact) {
        ComposeUi.Panel panel = page(label, "profile-detail", true);
        panel.blocks.add(new ComposeUi.Block("現在のプロフィール", value));
        if (fact == null || fact.optBoolean("imported")) {
            panel.blocks.add(new ComposeUi.Block("出典", "引き継ぎプロフィール"));
        } else {
            String updated = fact.optString("updated_at");
            if (!updated.isEmpty()) panel.blocks.add(new ComposeUi.Block("反映元の発言日時",
                    java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.parse(updated))));
            String source = ReplyText.visible(fact.optString("source"), true);
            if (!source.isBlank()) panel.blocks.add(new ComposeUi.Block("本人の発言", source));
        }
        compose.showPage(panel);
    }

    private void showMemory() {
        ComposeUi.Panel loading = page("記憶", "memory", true);
        loading.onResume = this::refreshMemoryView;
        if (activePerson == null) {
            loading.message = "人物を追加すると、会話と記憶を確認できます。";
            loading.rows.add(entry("人物を追加・読み込む", "人物の管理へ", 2, this::showPeople));
        } else loading.loading = true;
        compose.showPage(loading);
        memoryView = 1;
        refreshMemoryView();
    }

    private void refreshMemoryView() {
        final int requestedView = memoryView;
        if (requestedView == 0 || requestedView == 4) return;
        final PersonRegistry.Person person = activePerson;
        if (person == null || changingPerson) return;
        final int version = compose.getPageVersion();
        final String key = compose.getPageKey();
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
                final boolean rebuilding = MemoryRebuild.pending(person.directory);
                final String rebuildStatus = rebuilding ? new MemoryRebuild(person.directory).status() : "";
                ui(() -> {
                    if (!compose.isPageVisible(key, version) || requestedView != memoryView || changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
                    if (requestedView != 1) { showMemoryHistory(person, snapshot, requestedView == 3); return; }
                    JSONObject continuity = snapshot.optJSONObject("continuity");
                    JSONObject current = continuity == null ? null : continuity.optJSONObject("assistant_state");
                    JSONObject state = current == null ? new JSONObject() : current;
                    org.json.JSONArray episodes = continuity == null ? null : continuity.optJSONArray("episodes");
                    ComposeUi.Panel panel = page("記憶", "memory", true);
                    panel.onResume = this::refreshMemoryView;
                    panel.memoryState = new ComposeUi.MemoryState(state.optString("emotion"),
                            state.optString("reason"), state.optString("event"));
                    JSONObject progress = snapshot.optJSONObject("memory_progress");
                    if (progress != null) {
                        long processed = progress.optLong("processed_messages"), total = progress.optLong("total_messages");
                        panel.blocks.add(new ComposeUi.Block("会話の記憶整理", processed + " / " + total + " 発言（残り" + Math.max(0, total - processed) + "発言）"));
                    }
                    if (rebuilding) panel.blocks.add(new ComposeUi.Block("過去の会話の再整理", rebuildStatus));
                    if (!memoryStatus.isEmpty()) panel.blocks.add(new ComposeUi.Block("処理状況", memoryStatus));
                    panel.featuredRows.add(entry("短期記憶（直近30往復）", "最近の会話を原文で読む", 0, () -> showMemoryHistory(person, snapshot, false)));
                    panel.featuredRows.add(entry("中期記憶（" + (episodes == null ? 0 : episodes.length()) + "件）",
                            "体験を開いて、日・場面・出来事をたどる", 3, () -> showMemoryHistory(person, snapshot, true)));
                    if (!rebuilding) {
                        ComposeUi.Row rebuild = entry("過去の記憶を階層に整理", "ChatGPTの利用枠を使い、過去の会話を読み直します", 4, this::requestMemoryRebuild);
                        rebuild.section = "整理の操作"; panel.rows.add(rebuild);
                    }
                    if (!semanticStatus.isEmpty()) panel.blocks.add(new ComposeUi.Block("端末内の検索", semanticStatus));
                    compose.showPage(panel);
                });
            } catch (Exception error) { ui(() -> {
                if (!compose.isPageVisible(key, version)) return;
                ComposeUi.Panel failed = page(requestedView == 1 ? "記憶" : requestedView == 2 ? "短期記憶" : "中期記憶", key, true);
                failed.message = safeError(error);
                failed.rows.add(entry("再読み込み", "保存済みの記憶をもう一度読みます", 4, this::refreshMemoryView));
                failed.onResume = this::refreshMemoryView;
                compose.showPage(failed);
            }); }
        });
    }

    private void requestMemoryRebuild() {
        if (activePerson == null) return;
        if (!connection.hasSession()) { showConnection(); return; }
        final PersonRegistry.Person person = activePerson;
        memoryView = 0;
        ComposeUi.Panel panel = new ComposeUi.Panel("過去の記憶を階層に整理"); panel.sheet = true;
        panel.message = "過去の会話を順に読み直し、旅行・日・場面などの階層にまとめます。\n原文と現在の記憶は保持し、完了後に新しい概要へ切り替えます。\nChatGPTの利用枠を使い、会話量に応じて時間がかかります。";
        panel.confirmLabel = "整理する";
        panel.onDismiss = this::showMemory;
        panel.confirm = () -> {
            compose.hide();
            final int version = compose.getPageVersion();
            final String origin = compose.getPageKey();
            worker.execute(() -> {
                try {
                    synchronized (storeGate) {
                        requireCurrentPerson();
                        if (!person.id.equals(activePerson.id)) return;
                        JSONObject snapshot = new JSONObject(nativeContext(bytes("")));
                        long total = snapshot.getJSONObject("memory_progress").getLong("total_messages");
                        new MemoryRebuild(person.directory).request(total);
                    }
                    MemoryWork.resume(getApplicationContext());
                    ui(() -> {
                        if (activePerson != null && person.id.equals(activePerson.id) && compose.isPageVisible(origin, version)) showMemory();
                        else refreshManagementStatus();
                    });
                } catch (Exception error) { ui(() -> {
                    if (compose.isPageVisible(origin, version)) showError(safeError(error));
                }); }
            });
        };
        compose.show(panel);
    }

    private void showMemoryHistory(PersonRegistry.Person person, JSONObject snapshot, boolean middleTerm) {
        memoryView = middleTerm ? 3 : 2;
        if (changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
        ComposeUi.Panel panel = page(middleTerm ? "中期記憶" : "短期記憶", middleTerm ? "episodes" : "recent", true);
        panel.onResume = this::refreshMemoryView;
        JSONObject continuity = snapshot.optJSONObject("continuity");
        org.json.JSONArray entries = middleTerm
                ? (continuity == null ? null : continuity.optJSONArray("episodes")) : snapshot.optJSONArray("messages");
        StringBuilder text = new StringBuilder();
        if (entries == null || entries.length() == 0) {
            text.append(middleTerm ? "中期記憶はまだありません。会話を整理すると、体験ごとの記憶がここに表示されます。"
                    : "短期記憶の会話はまだありません。");
        } else {
            text.append(middleTerm ? "直近30件までの体験です。開くと、日・場面・出来事の詳細を確認できます。"
                    : "直近30往復（最大61発言）を、新しいものから表示します。");
            int first = Math.max(0, entries.length() - (middleTerm ? 30 : 61));
            for (int i = entries.length() - 1; i >= first; i--) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                if (middleTerm) {
                    String description = episodeDescription(entry);
                    int split = description.indexOf('\n');
                    panel.rows.add(entry(description.substring(0, split), description.substring(split + 1), 3,
                            () -> showEpisodeNode(person, entry.optString("key"), entry, 0)));
                } else {
                    boolean user = entry.optString("role").equals("user");
                    String annotation = entry.optString("annotation");
                    String speaker = annotation.equals("scene") ? "情景（返信なし）" : annotation.equals("meta") ? "メタ（返信なし）" : user ? "あなた" : person.displayName;
                    String content = entry.optString("content");
                    String body = user ? content : ReplyText.visible(content, true);
                    if (entry.optBoolean("truncated")) body += "\n（長文のため一部を表示）";
                    panel.blocks.add(new ComposeUi.Block(speaker, body));
                }
            }
        }
        panel.message = text.toString();
        compose.showPage(panel);
    }

    private static String episodeDescription(JSONObject entry) {
        String scene = entry.optString("scene");
        return (scene.isEmpty() ? "体験" : scene) + "\n" + episodeSummary(entry);
    }

    private static String episodeSummary(JSONObject entry) {
        String scope = entry.optString("scope");
        String text = entry.optString("text");
        String emotion = entry.optString("emotion");
        if (!emotion.isEmpty()) text += "\n気持ち：" + emotion;
        return text + "\n" + (scope.equals("fiction") ? "創作の場面" : scope.equals("real") ? "現実の出来事" : "会話内の体験");
    }

    private void showEpisodeNode(PersonRegistry.Person person, String rootKey, JSONObject node, long afterId) {
        String key = "episode:" + new org.json.JSONArray().put(rootKey).put(node.optString("key")).put(afterId);
        String scene = node.optString("scene");
        String title = scene.isEmpty() ? "体験の詳細" : scene;
        Runnable reload = () -> showEpisodeNode(person, rootKey, node, afterId);
        if (!compose.getPageKey().equals(key)) {
            ComposeUi.Panel loading = page(title, key, true);
            loading.loading = true; loading.onResume = reload; compose.showPage(loading);
        }
        final int version = compose.getPageVersion();
        int requested = ++episodeRequest;
        reader.execute(() -> {
            try {
                final JSONObject page;
                synchronized (storeGate) {
                    requireCurrentPerson();
                    if (!person.id.equals(activePerson.id)) return;
                    JSONObject query = new JSONObject().put("root_key", rootKey).put("parent_key", node.getString("key"))
                            .put("scope", node.getString("scope")).put("after_id", afterId);
                    String result = nativeEpisodeChildren(bytes(query.toString()));
                    if (result.isEmpty()) throw new java.io.IOException(nativeLastError());
                    page = new JSONObject(result);
                }
                ui(() -> {
                    if (!compose.isPageVisible(key, version) || memoryView != 4 || requested != episodeRequest || changingPerson || activePerson == null || !person.id.equals(activePerson.id)) return;
                    ComposeUi.Panel panel = page(title, key, true);
                    panel.onResume = reload;
                    panel.message = episodeSummary(node);
                    org.json.JSONArray children = page.optJSONArray("episodes");
                    if (children != null) for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.optJSONObject(i);
                        if (child == null) continue;
                        String label = child.optString("scene");
                        panel.rows.add(entry(label.isEmpty() ? "出来事" : label, child.optString("text"), 3,
                                () -> showEpisodeNode(person, rootKey, child, 0)));
                    }
                    if (page.optBoolean("has_more")) panel.rows.add(new ComposeUi.Row("次の詳細を表示",
                            () -> showEpisodeNode(person, rootKey, node, page.optLong("after_id"))));
                    compose.showPage(panel);
                });
            } catch (Exception error) { ui(() -> {
                if (!compose.isPageVisible(key, version) || requested != episodeRequest) return;
                ComposeUi.Panel failed = page("体験を読み込めませんでした", key, true);
                failed.message = safeError(error); failed.onResume = reload;
                failed.rows.add(entry("再読み込み", "体験の詳細をもう一度読みます", 4, reload));
                compose.showPage(failed);
            }); }
        });
    }

    private void showPersonIcon() {
        if (activePerson == null) return;
        ComposeUi.Panel panel = page("人物アイコン", "person-icon", true);
        panel.message = "この人物の会話に表示するアイコンを選びます。";
        panel.rows.add(new ComposeUi.Row("写真・画像から選ぶ", () -> {
            pendingIconPersonId = activePerson.id;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 83);
        }, 1));
        panel.rows.add(new ComposeUi.Row("アイコン画像を外す", () -> {
            final PersonRegistry.Person person = activePerson;
            worker.execute(() -> {
                try { people.setIcon(person.id, null); ui(() -> { refreshPersonIcon(); scheduleGitSync(); }); }
                catch (Exception error) { ui(() -> showError(safeError(error))); }
            });
        }, 6));
        compose.showPage(panel);
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
        ComposeUi.Panel panel = page("削除する人物", "delete-people", false);
        panel.message = "削除する人物を選ぶと、削除内容を確認できます。";
        for (PersonRegistry.Person person : people.list()) panel.rows.add(new ComposeUi.Row(person.displayName, () -> {
            ComposeUi.Panel confirm = new ComposeUi.Panel(person.displayName + "を削除しますか？");
            confirm.message = "この端末の人物・会話・記憶・下書きを削除します。元には戻せません。\nGitHub上のバックアップと、書き出したZIPは残ります。";
            confirm.dismissOnClickOutside = false;
            confirm.confirmLabel = "人物を削除";
            confirm.confirm = () -> { compose.hide(); deletePerson(person); };
            compose.show(confirm);
        }, 5));
        compose.showPage(panel);
    }

    private void deletePerson(PersonRegistry.Person person) {
        if (busy || MemoryWork.isRunning()) return;
        if(!saveDraft()) return; changingPerson = true; setBusy(true);
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
                    ((ConversationApplication)getApplication()).deleteKeys(person.id);
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
        if ((activePerson != null && person.id.equals(activePerson.id)) || busy || MemoryWork.isRunning()) return;
        if(!saveDraft()) return; changingPerson = true; setBusy(true);
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
        if (busy || MemoryWork.isRunning()) return;
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
        if (busy || MemoryWork.isRunning()) return;
        if(!saveDraft()) return; changingPerson = true; setBusy(true);
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
                    try(GitHubBackup client = new GitHubBackup(new GitHubBackup.HttpTransport(token),ImageVault.keys.get(staged.directory,false))) {
                        GitHubBackup.Repository remote = client.verifyIdentity(new GitHubBackup.PersonIdentity(staged.id), repository);
                        synchronized (storeGate) {
                            requireOpen(); people.bindRemoteId(staged.id, remote.repoId);
                            new GitHubTokenStore(this, staged.id).set(token);
                        }
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
        ComposeUi.Panel panel = page("バックアップ", "backup", true);
        panel.onResume = this::showGitBackup;
        if (activePerson == null) {
            panel.message = "人物を追加すると、その人物の会話と記憶を書き出せます。";
            panel.rows.add(entry("人物を追加・読み込む", "人物の管理へ", 2, this::showPeople));
            compose.showPage(panel); return;
        }
        panel.blocks.add(new ComposeUi.Block("この端末", "会話と記憶は、この人物の端末内データに保存されています。"));
        panel.blocks.add(new ComposeUi.Block("GitHubへの保存", activePerson.repository == null
                ? "この人物はGitHubに接続していません。ZIPに書き出して保管できます。"
                : activePerson.repository + "\n" + (gitStatus.isEmpty() ? "保存状況は、次回のGit保存時に更新されます。" : gitStatus)));
        if (activePerson.repository != null) {
            panel.featuredRows.add(entry("今すぐ保存", "この人物のバックアップをGitHubへ送る", 1, () -> queueGitSync(true)));
            panel.rows.add(section("GitHubの接続", new ComposeUi.Row("GitHubトークンを設定", this::editGitToken, 4)));
        }
        panel.rows.add(section("GitHubの接続", new ComposeUi.Row("別のGit保存先へ移行", this::showGitMigration)));
        panel.rows.add(section("復元キー", new ComposeUi.Row("データの復元キーを読み込む", this::chooseRecovery)));
        panel.featuredRows.add(entry("端末からZIPを書き出す", "会話・記憶・人物のデータをまとめて保管", 1, this::exportArchive));
        panel.blocks.add(new ComposeUi.Block("データの保護", "会話・記憶・人物情報・下書き・全添付・バックアップを暗号化して保存します。写真で使っていた復元キーを引き続き使います。キーはバックアップと別の安全な場所に保管してください。"));
        panel.blocks.add(new ComposeUi.Block("画像の送信と書き出し", "会話で参照する画像はOpenAIに送信します。生成時は指示文をOpenAIに送信します。画像の「保存」はダウンロードへの通常画像の書き出しです。以前のGit履歴や書き出し済みファイルは今回の暗号化では消えません。"));
        panel.rows.add(section("復元キー", entry("データの復元キーを書き出す", "紛失すると、端末を失った際にデータを復元できません", 4, this::exportImageRecovery)));
        compose.showPage(panel);
    }

    private void exportImageRecovery() {
        if (activePerson == null) return;
        pendingRecoveryPersonId = activePerson.id;
        Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Conversation-" + activePerson.id + "-data.key");
        startActivityForResult(destination, 85);
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
            try { tokens.clear(); ui(() -> { gitStatus = "自動保存は接続情報の設定待ちです"; refreshManagementStatus(); }); }
            catch (Exception error) { ui(() -> showError(safeError(error))); }
        })));
        compose.show(panel);
    }

    private void chooseRecovery() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),86);
    }
    private void showRecovery(String message) {
        ready=false;compose.setEnabled(false);
        ComposeUi.Panel panel=new ComposeUi.Panel("保存データを開けません");panel.secure=true;
        panel.message=message+"\n元のデータは保持しています。写真で使っていた復元キーも読み込めます。";
        panel.rows.add(new ComposeUi.Row("復元キーを読み込む",this::chooseRecovery));
        panel.rows.add(new ComposeUi.Row("再試行",this::recreate));compose.show(panel);
    }
    private void showGitMigration() {
        if(activePerson==null||busy||MemoryWork.isRunning()) { showError("現在の処理が終わってから移行してください。");return; }
        ComposeUi.Panel panel=new ComposeUi.Panel("Git保存先の移行");panel.secure=true;
        panel.message="PCの migrate-git-backup.ps1 で全履歴の移行を完了してから、新しい非公開リポジトリを指定してください。途中で止まった移行も同じ保存先で再開できます。元のGitは削除しません。";
        ComposeUi.Field repo=new ComposeUi.Field("移行先リポジトリ",false), token=new ComposeUi.Field("移行先のGitHubトークン",true);
        try { File pending=new File(activePerson.directory,".git-rebind.json");if(pending.exists()) repo.setValue(new JSONObject(new String(DataFiles.read(activePerson.directory,pending),StandardCharsets.UTF_8)).getString("repo")); }
        catch(Exception error) { showError(safeError(error));return; }
        panel.fields.add(repo);panel.fields.add(token);panel.confirmLabel="検証して移行・再開";
        panel.confirm=()->{String target=repo.getValue().trim(),credential=token.getValue().trim();token.setValue("");compose.hide();moveGit(target,credential);};
        compose.show(panel);
    }
    private void moveGit(String input,String credential) {
        final PersonRegistry.Person person=activePerson;
        if(person==null||busy||MemoryWork.isRunning()) return;
        final String target;
        try { target=PersonRegistry.normalizeRepository(input); } catch(Exception error) { showError(safeError(error));return; }
        if(!saveDraft()) return;changingPerson=true;setBusy(true);syncTimer.removeCallbacks(autoSync);
        worker.execute(()->{
            File pending=new File(person.directory,".git-rebind.json");
            ConnectionStore temporary=new ConnectionStore(this,"git-move-"+person.id);
            try {
                synchronized(syncingPeople) { if(syncingPeople.contains(person.id)) throw new java.io.IOException("現在のGit保存が終わってから移行してください。"); }
                ImageVault.keys.requireRecovery(person.directory);
                if(!credential.isEmpty()) temporary.save(new JSONObject().put("token",credential));
                String token=temporary.load().optString("token");
                if(token.isEmpty()&&target.equals(person.repository)) token=new GitHubTokenStore(this,person.id).get();
                if(token.isEmpty()) throw new java.io.IOException("移行先のGitHubトークンを設定してください。");
                JSONObject state=pending.exists()?new JSONObject(new String(DataFiles.read(person.directory,pending),StandardCharsets.UTF_8)):new JSONObject();
                if(state.has("repo")&&!target.equals(state.getString("repo"))) throw new java.io.IOException("別の保存先への移行が途中です。同じ保存先で再開してください。");
                try(GitHubBackup client=new GitHubBackup(new GitHubBackup.HttpTransport(token),ImageVault.keys.get(person.directory,false))) {
                    GitHubBackup.SyncState prior;
                    if(state.has("sync")) prior=GitHubBackup.SyncState.fromJson(state.getJSONObject("sync"));
                    else {
                        prior=client.verifyMigration(new GitHubBackup.PersonIdentity(person.id),target,person.remoteId);
                        state.put("repo",target).put("old_repository_id",person.remoteId).put("sync",prior.toJson());
                        DataFiles.write(person.directory,pending,bytes(state.toString()));
                    }
                    while(true) {
                        JSONObject delta;
                        synchronized(storeGate) { requireOpen();delta=new JSONObject(nativeArchiveDelta(prior.cursor)); }
                        if(new File(person.directory,"display-name.json").isFile()) delta.put("display_name",person.displayName);
                        byte[] icon=people.icon(person.id);
                        if(icon.length>0) delta.put("encrypted_icon_base64",java.util.Base64.getEncoder().encodeToString(ImageVault.backup(person.directory,new File(person.directory,"icon.png"))));
                        AttachmentFiles.addToDelta(person.directory,delta);
                        GitHubBackup.SyncResult saved=client.sync(new GitHubBackup.PersonIdentity(person.id),target,person.rawMarkdown,delta,prior,prior.repoId);
                        prior=saved;state.put("sync",saved.toJson());DataFiles.write(person.directory,pending,bytes(state.toString()));
                        if(!delta.optBoolean("has_more")&&saved.cursor>=delta.getLong("through_cursor")) break;
                    }
                    synchronized(storeGate) {
                        requireOpen();new GitHubTokenStore(this,person.id).set(token);
                        people.rebindRepository(person.id,target,prior.repoId);
                        DataFiles.write(person.directory,new File(person.directory,".git-sync.json"),bytes(prior.toJson().toString()));
                        temporary.delete();Files.delete(pending.toPath());
                    }
                }
                ui(()->{changingPerson=false;recreate();});
            } catch(Exception error) { ui(()->{changingPerson=false;setBusy(false);showError(safeError(error)+"\n元のデータと移行状態は保持しています。");}); }
        });
    }

    private void scheduleGitSync() {
        syncTimer.removeCallbacks(autoSync);
        if (!closed && !changingPerson && activePerson != null && activePerson.repository != null) syncTimer.postDelayed(autoSync, 120_000);
    }

    private void queueGitSync(boolean report) {
        if (!ready || closed || changingPerson || activePerson == null || activePerson.repository == null || isDestroyed()) return;
        final PersonRegistry.Person person = activePerson;
        if(new File(person.directory,".git-rebind.json").exists()) { gitStatus="Git保存先の移行を再開してください。";if(report) showError(gitStatus);return; }
        gitStatus = "Gitに保存しています";
        refreshManagementStatus();
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
                ImageVault.keys.requireRecovery(person.directory);
                File stateFile = new File(person.directory, ".git-sync.json");
                if (stateFile.length() > 16384) throw new java.io.IOException("Git保存の状態ファイルが不正です。");
                GitHubBackup.SyncState prior = stateFile.exists() ? GitHubBackup.SyncState.fromJson(new JSONObject(
                        new String(DataFiles.read(person.directory,stateFile), StandardCharsets.UTF_8))) : GitHubBackup.SyncState.empty();
                JSONObject delta;
                synchronized (storeGate) { requireCurrentPerson(); delta = new JSONObject(nativeArchiveDelta(prior.cursor)); }
                if (delta.has("error")) throw new java.io.IOException("保存する履歴を読み出せませんでした。");
                if(new File(person.directory,"display-name.json").isFile()) delta.put("display_name",person.displayName);
                byte[] icon = people.icon(person.id);
                if (icon.length > 0) delta.put("encrypted_icon_base64", java.util.Base64.getEncoder().encodeToString(
                        ImageVault.backup(person.directory, new File(person.directory, "icon.png"))));
                AttachmentFiles.addToDelta(person.directory, delta);
                gitWorker.execute(() -> {
                    boolean more = false;
                    try(GitHubBackup client=new GitHubBackup(new GitHubBackup.HttpTransport(token),ImageVault.keys.get(person.directory,false))) {
                        GitHubBackup.SyncResult result=client.sync(new GitHubBackup.PersonIdentity(person.id), person.repository, person.rawMarkdown, delta, prior, person.remoteId);
                        if (result.repoId != person.remoteId) throw new java.io.IOException("保存先のリポジトリIDが一致しません。");
                        DataFiles.write(person.directory,stateFile,bytes(result.toJson().toString()));
                        more = delta.optBoolean("has_more") || result.cursor < delta.optLong("through_cursor");
                        final boolean remaining = more;
                        ui(() -> {
                            if (activePerson == null || !activePerson.id.equals(person.id)) return;
                            gitStatus = remaining ? "続きの履歴を保存しています" : "Gitへの保存が完了しました";
                            refreshManagementStatus();
                            if (report && !remaining && compose.getPageKey().equals("backup")) showError("Gitへの保存が完了しました。"); });
                    } catch (Exception error) { syncFailed(person, error, report); }
                    finally {
                        synchronized (syncingPeople) { syncingPeople.remove(person.id); }
                        if (more) ui(() -> { if (activePerson != null && activePerson.id.equals(person.id)) queueGitSync(report); });
                    }
                });
            } catch (Exception error) {
                synchronized (syncingPeople) { syncingPeople.remove(person.id); }
                syncFailed(person, error, report);
            }
        });
    }

    private void syncFailed(PersonRegistry.Person person, Exception error, boolean report) {
        String detail = safeError(error);
        ui(() -> {
            if (activePerson == null || !activePerson.id.equals(person.id)) return;
            gitStatus = detail + "\n会話は端末に保存されています。";
            refreshManagementStatus();
            if (report && compose.getPageKey().equals("backup")) showError(gitStatus); scheduleGitSync(); });
    }

    private void exportArchive() {
        final PersonRegistry.Person person=activePerson;
        if(person==null) return;
        worker.execute(() -> {
            final File completed;
            try(BackupArchive writer=new BackupArchive(person.directory,new File(person.directory,"export"))) {
                synchronized(storeGate) { requireCurrentPerson(); requireSuccess(nativeExport(writer)); }
                writer.finish(); completed=writer.directory;
            } catch(Exception error) { ui(() -> showError(safeError(error))); return; }
            ui(() -> {
                pendingExportDirectory=completed;
                Intent destination=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Conversation-backup.zip");
                startActivityForResult(destination,81);
            });
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==86) {
            if(result!=RESULT_OK||data==null||data.getData()==null) return;
            final Uri uri=data.getData();
            worker.execute(()->{
                try(java.io.InputStream input=getContentResolver().openInputStream(uri)) {
                    byte[] bytes=AttachmentMedia.bounded(input,4096);
                    java.util.Properties recovery=new java.util.Properties();recovery.load(new java.io.ByteArrayInputStream(bytes));
                    ((ConversationApplication)getApplication()).importRecovery(recovery);ui(this::recreate);
                } catch(Exception error) { ui(()->showRecovery(safeError(error))); }
            });return;
        }
        if (request == 85) {
            final String id = pendingRecoveryPersonId; pendingRecoveryPersonId = "";
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            final PersonRegistry.Person person = activePerson;
            if (person == null || !person.id.equals(id)) { showError("人物が変わりました。もう一度復元キーを書き出してください。"); return; }
            final Uri destination = data.getData();
            worker.execute(() -> {
                try {
                    ((ConversationApplication)getApplication()).writeRecovery(person.directory, getContentResolver().openOutputStream(destination, "w"));
                    ui(() -> { showError("データの復元キーを書き出しました。バックアップと別の場所に保管してください。"); scheduleGitSync(); });
                } catch (Exception error) { ui(() -> showError(safeError(error))); }
            });
            return;
        }
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
        state.putString("recovery_person", pendingRecoveryPersonId);
        state.putString("attachment_person", pendingAttachmentPersonId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        synchronized (storeGate) { closed = true; }
        armInitiative(false);
        syncTimer.removeCallbacks(autoSync);
        if (session != null) session.cancel();
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
        ComposeUi.Panel panel = page("表示設定", "display", false);
        panel.message = "チャットの読みやすさを調整します。変更は自動で保存されます。";
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
        reset.dismiss = false; panel.rows.add(reset); compose.showPage(panel);
    }

    @Override protected void onStart() {
        super.onStart();
        InitiativeWork.enterForeground(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (InitiativeWork.OPEN_CHAT.equals(intent.getAction())) {
            compose.closePages();
            pageFirst = -1;
            scrollOnRefresh = true;
            refreshMessages();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_INITIATIVE_NOTIFICATIONS) return;
        if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
            InitiativeWork.setNotificationsEnabled(this, false);
        if (compose.isPageVisible("initiative", compose.getPageVersion())) showInitiativeSettings();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshMessages();
        armInitiative(true);
    }

    @Override public void onPause() {
        resumed = false;
        armInitiative(false);
        if (connection != null) connection.setForeground(false);
        if (compose != null) saveDisplaySettings();
        draftSavedOnPause = saveDraft();
        if (!busy && !changingPerson) queueGitSync(false);
        super.onPause();
    }

    @Override protected void onStop() {
        InitiativeWork.leaveForeground(this, ready && activePerson != null && !changingPerson
                && !isChangingConfigurations() && draftSavedOnPause && connection != null && connection.hasSession());
        super.onStop();
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (InitiativeWork.enabled(this, Trigger.OPEN) || InitiativeWork.enabled(this, Trigger.IDLE)) armInitiative(false);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && connection != null) connection.setForeground(true);
        armInitiative(focused);
    }
}
