package jp.tekewo.conversation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.Window;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import org.json.JSONArray;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static native String nativeInit(byte[] storageDir, byte[] font, byte[] emojiFont);
    private static native void nativeSurfaceCreated();
    private static native void nativeResize(int width, int height, float density);
    private static native void nativeRender();
    private static native void nativeSetTypography(float pixels, float gapDp);
    private static native void nativeTouch(int action, float x, float y);
    private static native long nativeAddMessage(byte[] role, byte[] text);
    private static native String nativeLastError();
    private static native String nativeContext(byte[] query);
    private static native String nativePendingJob();
    private static native String nativeApplyJob(long id, byte[] payload);
    private static native String nativeMemories(byte[] query);
    private static native String nativeExport();
    private static native void nativeJumpToMessage(long id);
    private static native void nativeSetStreaming(byte[] text);
    private static native String nativeRecentTranscript();
    private static native void nativePause();
    private static native String nativeSwitchPerson(byte[] directory, byte[] payload);
    private static native String nativeArchiveDelta(long cursor);

    private ChatSurface surface;
    private EditText input;
    private ImageButton send;
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
    private int textSize = 20;
    private int messageGap = 12;
    private static final int BACKGROUND = Color.rgb(19, 23, 28);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        textSize = Math.max(16, Math.min(28, getPreferences(MODE_PRIVATE).getInt("text_size", 20)));
        messageGap = Math.max(8, Math.min(24, getPreferences(MODE_PRIVATE).getInt("message_gap", 12)));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right;
                bottom = Math.max(bars.bottom, keyboard.bottom);
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return windowInsets;
        });
        setContentView(root);

        try {
            System.loadLibrary("conversation");
            String error;
            synchronized (storeGate) {
                people = new PersonRegistry(getFilesDir());
                activePerson = people.active();
                error = nativeInit(bytes(activePerson.directory.getAbsolutePath()),
                        asset("NotoSansJP-Regular.ttf"), asset("NotoEmoji-Regular.ttf"));
                ready = error.isEmpty();
            }
            if (!ready) showError(error);
        } catch (Exception | UnsatisfiedLinkError error) {
            TextView failure = new TextView(this);
            failure.setText("起動できませんでした。\n" + error.getMessage());
            failure.setTextColor(Color.rgb(255, 140, 125));
            failure.setPadding(dp(16), dp(16), dp(16), dp(16));
            root.addView(failure);
            return;
        }

        surface = new ChatSurface();
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(8), dp(6), dp(12), dp(10));
        ImageButton menu = iconButton(false, "メニュー");
        menu.setOnClickListener(view -> showHistoryMenu());
        composer.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        input = new EditText(this);
        input.setId(0x434f4e);
        // Drafts live in app storage, outside Android's size-limited activity-state transaction.
        input.setSaveEnabled(false);
        input.setTextColor(Color.rgb(230, 238, 242));
        input.setHintTextColor(Color.rgb(156, 171, 181));
        input.setContentDescription("メッセージ入力");
        input.setBackground(roundBackground(Color.rgb(28, 34, 41), 24));
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        input.setMinHeight(dp(48));
        input.setTextSize(20);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setVerticalScrollBarEnabled(false);
        input.setHorizontalScrollBarEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setEnabled(ready);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        send = iconButton(true, "送信");
        send.setEnabled(ready);
        send.setOnClickListener(view -> sendMessage());
        LinearLayout.LayoutParams sendLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendLayout.leftMargin = dp(8);
        composer.addView(send, sendLayout);
        root.addView(composer);

        input.setText(getPreferences(MODE_PRIVATE).getString(draftKey(), ""));
        applyTypography();
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
        });
        scheduleGitSync();
    }

    private void requireOpen() throws java.io.IOException {
        if (closed || isDestroyed() || isFinishing() || Thread.currentThread().isInterrupted())
            throw new java.io.IOException("処理をキャンセルしました。");
    }
    private void requireCurrentPerson() throws java.io.IOException {
        requireOpen();
        if (changingPerson) throw new java.io.IOException("人物を切り替えています。");
    }
    private void setStreaming(String text) {
        synchronized (storeGate) {
            if (!closed && !changingPerson && !isFinishing() && !Thread.currentThread().isInterrupted())
                nativeSetStreaming(bytes(text));
        }
    }

    private String draftKey() { return activePerson.id.equals(PersonRegistry.LEGACY_ID) ? "draft" : "draft_" + activePerson.id; }
    private void saveDraft() {
        if (!changingPerson && input != null && activePerson != null) getPreferences(MODE_PRIVATE).edit()
                .putString(draftKey(), input.getText().toString()).commit();
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream stream = getAssets().open(name);
             ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) data.write(buffer, 0, count);
            return data.toByteArray();
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        input.setEnabled(ready && !changingPerson);
        send.setEnabled(ready && !value);
        send.setAlpha(value ? 0.45f : 1.0f);
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
        if (busy) return;
        if (!connection.hasSession()) { showConnection(); return; }
        String text = input.getText().toString();
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
                            if (input.getText().toString().equals(text)) {
                                BaseInputConnection.removeComposingSpans(input.getText());
                                input.setText("");
                                getPreferences(MODE_PRIVATE).edit().remove(draftKey()).apply();
                            }
                            surface.renderBurst();
                        });
                    }
                    public void streaming(String full) {
                        setStreaming(full);
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastRender > 45) { lastRender = now; ui(() -> surface.requestRender()); }
                    }
                    public void replySaved() {
                        setStreaming("");
                        ui(() -> { surface.renderBurst(); connectionStatus = "記憶を整理しています"; });
                    }
                    public void memoryStatus(String error) {
                        ui(() -> connectionStatus = error == null ? "ChatGPTに接続しています" : error);
                    }
                });
            } catch (Exception error) {
                setStreaming("");
                if (session.isCancelled()) {
                    ui(() -> { connectionStatus = "応答を停止しました"; surface.renderBurst(); });
                    return;
                }
                final String detail = error instanceof org.json.JSONException
                        ? "応答の形式を処理できませんでした。" : safeError(error);
                ui(() -> {
                    connectionStatus = detail;
                    surface.renderBurst();
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("応答できませんでした")
                            .setMessage(detail + (saved.get() ? "\n送信済みの会話原文は保存されています。" : ""));
                    if (detail.contains("利用上限")) dialog.setPositiveButton("利用状況を確認", (view, which) ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))));
                    else dialog.setPositiveButton("再試行", (view, which) -> runReply(text, !saved.get()));
                    dialog.setNegativeButton("閉じる", null).show();
                });
            } finally { ui(() -> { setBusy(false); scheduleGitSync(); }); }
        });
    }

    private String safeError(Exception error) {
        if (error instanceof java.io.IOException && error.getMessage() != null) return error.getMessage();
        return "処理に失敗しました。接続状態を確認して再試行してください。";
    }

    private void showError(String error) {
        new AlertDialog.Builder(this).setTitle("処理を確認してください")
                .setMessage(error).setPositiveButton("閉じる", null).show();
    }

    private void showHistoryMenu() {
        if (!ready) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        Dialog sheet = newSheet();
        LinearLayout body = sheetBody(sheet, "メニュー");
        String[] labels = busy ? new String[]{"会話", "人物", "表示設定", "ChatGPT", "Gitバックアップ", "応答を停止"}
                : new String[]{"会話", "人物", "表示設定", "ChatGPT", "Gitバックアップ"};
        int[] icons = {0, 2, 4, 5, 1, 6};
        for (int i = 0; i < labels.length; ++i) {
            final int action = i;
            menuRow(body, labels[i], icons[i], () -> {
                sheet.dismiss();
                if (action == 0) surface.renderBurst();
                else if (action == 1) showPeople();
                else if (action == 2) showDisplaySettings();
                else if (action == 3) showConnection();
                else if (action == 4) showGitBackup();
                else {
                    session.cancel(); connection.cancel();
                    setStreaming(""); surface.renderBurst();
                }
            });
        }
        showSheet(sheet);
    }

    private void showConnection() {
        Dialog dialog = newSheet();
        LinearLayout body = sheetBody(dialog, "ChatGPT");
        TextView status = sheetText(connection.hasSession()
                ? connection.accountLabel() + "\nChatGPTの利用枠を使用します。\n" + connectionStatus
                : "ChatGPTにログインして、このアプリでサブスクの利用枠を使います。\n" + connectionStatus, 17);
        status.setPadding(dp(12), dp(12), dp(12), dp(20));
        body.addView(status);
        if (!connection.hasSession()) {
            menuRow(body, "Continue with ChatGPT", 5, () -> {
                if (busy) return;
                dialog.dismiss();
                connectionStatus = "ブラウザでの認証を待っています";
                connection.signIn(error -> ui(() -> {
                    availableModels = null;
                    connectionStatus = error == null ? "ChatGPTに接続しました" : error;
                    if (error != null) new AlertDialog.Builder(this).setTitle("ChatGPTに接続できませんでした")
                            .setMessage(error).setPositiveButton("閉じる", null).show();
                    else {
                        if (!getPreferences(MODE_PRIVATE).getBoolean("plan_welcome", false)) {
                            getPreferences(MODE_PRIVATE).edit().putBoolean("plan_welcome", true).apply();
                            new AlertDialog.Builder(this).setTitle("ChatGPTに接続しました")
                                    .setMessage("会話と記憶の整理にChatGPTの利用枠を使います。利用状況はメニューのChatGPTから確認できます。")
                                    .setPositiveButton("会話を始める", (view, which) -> retryMemories()).show();
                        } else retryMemories();
                    }
                }));
            });
        } else {
            String slug = getPreferences(MODE_PRIVATE).getString("model_slug", "");
            String name = slug.isEmpty() ? "既定" : slug;
            String effort = getPreferences(MODE_PRIVATE).getString("reasoning_effort:" + slug, "");
            boolean fast = getPreferences(MODE_PRIVATE).getBoolean("fast_mode:" + slug, false);
            List<ChatRequest.Model> models = availableModels;
            if (models != null) for (ChatRequest.Model model : models) if (model.slug.equals(slug)) {
                ChatRequest request = savedRequest(model);
                name = model.displayName; effort = request.effort; fast = request.fast;
                break;
            }
            menuRow(body, "モデル · " + name, 4, () -> { if (!busy) { dialog.dismiss(); showModels(); } });
            menuRow(body, "Effort · " + (effort.isEmpty() ? "既定" : effort), 4,
                    () -> { if (!busy) { dialog.dismiss(); showRequestOption(false); } });
            menuRow(body, "Fast · " + (fast ? "On" : "Off"), 4,
                    () -> { if (!busy) { dialog.dismiss(); showRequestOption(true); } });
            menuRow(body, "記憶の整理を再試行", 2, () -> { dialog.dismiss(); retryMemories(); });
            menuRow(body, "ログアウト", 6, () -> {
                if (busy) return;
                dialog.dismiss();
                worker.execute(() -> {
                    connection.signOut(); availableModels = null;
                    ui(() -> { connectionStatus = connection.signOutStatus(); showConnection(); });
                });
            });
        }
        if (!connection.hasSession()) menuRow(body, "保存した接続情報をリセット", 6, () -> {
            if (busy) return;
            dialog.dismiss();
            worker.execute(() -> {
                connection.signOut(); availableModels = null;
                ui(() -> { connectionStatus = connection.signOutStatus(); showConnection(); });
            });
        });
        menuRow(body, "利用状況を確認", 1, () -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://chatgpt.com/settings/usage"))));
        showSheet(dialog);
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
                    new AlertDialog.Builder(this).setTitle("モデル · ChatGPTの利用枠")
                            .setSingleChoiceItems(names, selectedIndex, (dialog, index) -> {
                                if (busy) return;
                                getPreferences(MODE_PRIVATE).edit().putString("model_slug", models.get(index).slug).apply();
                                dialog.dismiss(); showConnection();
                            }).setNegativeButton("戻る", (dialog, which) -> showConnection()).show();
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
                    if (fast ? !model.supportsFast : model.efforts.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle(fast ? "Fast" : "Effort")
                                .setMessage(model.displayName + (fast ? "ではFastを利用できません。" : "ではEffortを変更できません。"))
                                .setPositiveButton("戻る", (dialog, which) -> showConnection()).show();
                        return;
                    }
                    List<String> values = new ArrayList<>();
                    values.add(""); values.addAll(model.efforts);
                    String[] labels = fast ? new String[]{"Off", "On"} : values.toArray(new String[0]);
                    if (!fast) labels[0] = "既定（モデルに任せる）";
                    int checked = fast ? (request.fast ? 1 : 0) : values.indexOf(request.effort);
                    new AlertDialog.Builder(this).setTitle((fast ? "Fast" : "Effort") + " · " + model.displayName)
                            .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                                if (busy) return;
                                if (fast) getPreferences(MODE_PRIVATE).edit().putBoolean("fast_mode:" + model.slug, index == 1).apply();
                                else getPreferences(MODE_PRIVATE).edit().putString("reasoning_effort:" + model.slug, values.get(index)).apply();
                                dialog.dismiss(); showConnection();
                            }).setNegativeButton("戻る", (dialog, which) -> showConnection()).show();
                });
            } catch (Exception error) { ui(() -> showError(safeError(error))); }
        });
    }

    private void retryMemories() {
        if (busy || !connection.hasSession()) return;
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
        Dialog dialog = newSheet();
        LinearLayout body = sheetBody(dialog, "人物");
        menuRow(body, "人物を追加", 2, () -> {
            if (busy) { showError("応答と記憶の整理が終わってから追加してください。"); return; }
            dialog.dismiss(); showNewPerson();
        });
        for (PersonRegistry.Person person : people.list()) {
            menuRow(body, person.name + (person.id.equals(activePerson.id) ? "  ✓" : ""), 5, () -> {
                if (busy) { showError("応答と記憶の整理が終わってから人物を切り替えてください。"); return; }
                dialog.dismiss(); switchPerson(person);
            });
        }
        menuRow(body, "人物ファイルを読み込む", 1, () -> {
            if (busy) { showError("応答と記憶の整理が終わってから読み込んでください。"); return; }
            dialog.dismiss();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), 82);
        });
        if (people.list().size() > 1) menuRow(body, "人物を削除", 6, () -> {
            if (busy) { showError("応答と記憶の整理が終わってから削除してください。"); return; }
            dialog.dismiss(); showDeletePeople();
        });
        showSheet(dialog);
    }

    private void showNewPerson() {
        EditText name = sheetInput("名前", false);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("人物を追加")
                .setMessage("名前だけで始められます。人物のことは会話しながら決めていけます。")
                .setView(name).setPositiveButton("作成", null).setNegativeButton("キャンセル", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                PersonDocument document = PersonDocument.named(name.getText().toString());
                dialog.dismiss(); createPerson(document, null, "");
            } catch (IllegalArgumentException error) { name.setError(error.getMessage()); }
        }));
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        name.requestFocus(); dialog.show();
    }

    private void showDeletePeople() {
        List<PersonRegistry.Person> removable = people.list();
        removable.removeIf(person -> person.id.equals(PersonRegistry.LEGACY_ID));
        String[] names = new String[removable.size()];
        for (int i = 0; i < names.length; i++) names[i] = removable.get(i).name;
        new AlertDialog.Builder(this).setTitle("削除する人物")
                .setItems(names, (view, index) -> {
                    PersonRegistry.Person person = removable.get(index);
                    AlertDialog confirm = new AlertDialog.Builder(this).setTitle(person.name + "を削除しますか？")
                            .setMessage("この端末の人物・会話・記憶・下書きを削除します。元には戻せません。\nGitHub上のバックアップと、書き出したZIPは残ります。")
                            .setPositiveButton("人物を削除", (dialog, which) -> deletePerson(person))
                            .setNegativeButton("キャンセル", null).create();
                    confirm.setCanceledOnTouchOutside(false);
                    confirm.show(); confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
                }).setNegativeButton("キャンセル", null).show();
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
                        boolean selected = person.id.equals(activePerson.id);
                        if (selected) {
                            PersonRegistry.Person legacy = people.list().get(0);
                            requireSuccess(nativeSwitchPerson(bytes(legacy.directory.getAbsolutePath()), bytes("")));
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
                        AlertDialog warning = new AlertDialog.Builder(this).setTitle("削除後の処理を確認してください")
                                .setMessage(detail).setPositiveButton("閉じる", null).create();
                        warning.setOnDismissListener(dialog -> recreate()); warning.show();
                    }
                } else {
                    changingPerson = false; setBusy(false); showError(detail); scheduleGitSync();
                }
            });
        });
    }

    private void switchPerson(PersonRegistry.Person person) {
        if (person.id.equals(activePerson.id) || busy) return;
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
        String error = nativeSwitchPerson(bytes(activePerson.directory.getAbsolutePath()), bytes(""));
        if (!error.isEmpty()) { ready = false; throw new java.io.IOException("元の人物を開き直せませんでした。アプリを再起動してください。"); }
    }
    private static void requireSuccess(String error) throws java.io.IOException {
        if (!error.isEmpty()) throw new java.io.IOException(error);
    }

    private EditText sheetInput(String hint, boolean secret) {
        EditText edit = new EditText(this);
        edit.setVerticalScrollBarEnabled(false);
        edit.setHorizontalScrollBarEnabled(false);
        edit.setHint(hint); edit.setContentDescription(hint); edit.setTextColor(Color.rgb(230, 238, 242));
        edit.setHintTextColor(Color.rgb(156, 171, 181)); edit.setTextSize(17);
        edit.setSingleLine(true); edit.setSaveEnabled(false);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI));
        return edit;
    }

    private void previewPerson(PersonDocument document) {
        if (busy) return;
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView preview = sheetText(document.profile.length() > 1600
                ? document.profile.substring(0, 1600) + "…" : document.profile, 17);
        body.addView(preview);
        body.addView(sheetText("この人物専用の、空の非公開GitHubリポジトリを指定してください。", 16));
        EditText repository = sheetInput("https://github.com/所有者/リポジトリ", false);
        EditText token = sheetInput("この人物用のGitHubトークン", true);
        body.addView(repository); body.addView(token);
        body.addView(sheetText("Fine-grained token: このリポジトリの Contents を Read and write に設定。", 15));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(document.name).setView(scroll)
                .setPositiveButton("読み込んで作成", null).setNegativeButton("キャンセル", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String repo;
            try { repo = PersonRegistry.normalizeRepository(repository.getText().toString()); }
            catch (IllegalArgumentException error) { repository.setError(error.getMessage()); return; }
            String suppliedToken = token.getText().toString().trim(); token.setText("");
            dialog.dismiss(); createPerson(document, repo, suppliedToken);
        }));
        dialog.show();
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
        Dialog dialog = newSheet();
        LinearLayout body = sheetBody(dialog, "Gitバックアップ");
        TextView status = sheetText(activePerson.repository == null
                ? activePerson.name + "\n会話と記憶はこの端末に保存しています。ZIPに書き出して保管できます。"
                : activePerson.name + "\n" + activePerson.repository + "\n" + gitStatus
                  + "\n会話の原文・人物ファイル・出来事と記憶の更新履歴を非公開リポジトリへ保存します。", 17);
        status.setPadding(dp(12), dp(12), dp(12), dp(20)); body.addView(status);
        if (activePerson.repository != null) {
            menuRow(body, "今すぐ保存", 1, () -> { dialog.dismiss(); queueGitSync(true); });
            menuRow(body, "GitHubトークンを設定", 4, () -> { dialog.dismiss(); editGitToken(); });
        }
        menuRow(body, "端末からZIPを書き出す", 1, () -> { dialog.dismiss(); exportArchive(); });
        showSheet(dialog);
    }

    private void editGitToken() {
        final GitHubTokenStore tokens = new GitHubTokenStore(this, activePerson.id);
        EditText token = sheetInput("GitHubトークン", true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("GitHubトークン")
                .setMessage("Contents: Read and write を許可したトークンを端末内に暗号化して保存します。")
                .setView(token).setPositiveButton("保存", (view, which) -> {
                    String value = token.getText().toString().trim(); token.setText("");
                    worker.execute(() -> {
                        try { tokens.set(value); ui(() -> { gitStatus = "接続情報を保存しました"; queueGitSync(true); }); }
                        catch (Exception error) { ui(() -> showError(safeError(error))); }
                    });
                }).setNeutralButton("接続情報を削除", (view, which) -> worker.execute(() -> {
                    try { tokens.clear(); ui(() -> gitStatus = "自動保存は接続情報の設定待ちです"); }
                    catch (Exception error) { ui(() -> showError(safeError(error))); }
                })).setNegativeButton("キャンセル", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show();
    }

    private void scheduleGitSync() {
        syncTimer.removeCallbacks(autoSync);
        if (!closed && !changingPerson && activePerson.repository != null) syncTimer.postDelayed(autoSync, 120_000);
    }

    private void queueGitSync(boolean report) {
        if (!ready || closed || changingPerson || activePerson.repository == null || isDestroyed()) return;
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
        super.onDestroy();
    }

    private Dialog newSheet() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private LinearLayout sheetBody(Dialog dialog, String title) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(20));
        GradientDrawable background = roundBackground(Color.rgb(28, 34, 41), 0);
        float radius = dp(28);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        body.setBackground(background);
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            view.setPadding(dp(16), dp(12), dp(16), dp(20) + bottom);
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = sheetText(title, 21);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(12), dp(8), dp(8), dp(8));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(this);
        close.setImageDrawable(new MenuIcon(6));
        close.setContentDescription("閉じる");
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setPadding(dp(14), dp(14), dp(14), dp(14));
        close.setOnClickListener(view -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(header);
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int limit = Math.round(getResources().getDisplayMetrics().heightPixels * 0.85f);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(body);
        FrameLayout backdrop = new FrameLayout(this);
        backdrop.setOnClickListener(view -> dialog.dismiss());
        scroll.setClickable(true);
        backdrop.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dialog.setContentView(backdrop);
        return body;
    }

    private void showSheet(Dialog dialog) {
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.55f);
        window.setGravity(Gravity.BOTTOM);
        window.setWindowAnimations(android.R.style.Animation_InputMethod);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        dialog.show();
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        window.getDecorView().requestApplyInsets();
    }

    private TextView sheetText(String value, float size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(230, 238, 242));
        return text;
    }

    private void menuRow(LinearLayout body, String title, int icon, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setMinimumHeight(dp(56));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        row.setContentDescription(title);
        ImageView image = new ImageView(this);
        image.setImageDrawable(new MenuIcon(icon));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = sheetText(title, Math.max(18, textSize - 2));
        label.setPadding(dp(20), dp(8), 0, dp(8));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void applyTypography() {
        float pixels = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                textSize, getResources().getDisplayMetrics());
        nativeSetTypography(pixels, messageGap);
        if (surface != null) surface.renderBurst();
    }

    private void saveDisplaySettings() {
        getPreferences(MODE_PRIVATE).edit().putInt("text_size", textSize).putInt("message_gap", messageGap).apply();
    }

    private void showDisplaySettings() {
        Dialog dialog = newSheet();
        LinearLayout body = sheetBody(dialog, "表示設定");
        TextView preview = sheetText("こんにちは。\n今日の続きから話そう。", textSize);
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview.setBackground(roundBackground(Color.rgb(39, 49, 57), 16));
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(-1, -2);
        previewLayout.setMargins(dp(12), dp(12), dp(12), dp(20));
        body.addView(preview, previewLayout);
        SeekBar font = settingSlider(body, "文字サイズ", 16, 28, textSize, value -> {
            textSize = value;
            preview.setTextSize(value);
            applyTypography();
        });
        SeekBar gap = settingSlider(body, "メッセージの間隔", 8, 24, messageGap, value -> {
            messageGap = value;
            applyTypography();
        });
        menuRow(body, "標準に戻す", 4, () -> { font.setProgress(4); gap.setProgress(4); });
        dialog.setOnDismissListener(ignored -> saveDisplaySettings());
        showSheet(dialog);
    }

    private SeekBar settingSlider(LinearLayout body, String title, int min, int max, int value,
                                  java.util.function.IntConsumer onChange) {
        TextView label = sheetText(title + "  " + value, 17);
        label.setPadding(dp(12), dp(10), dp(12), dp(4));
        body.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        slider.setContentDescription(title);
        slider.setPadding(dp(12), dp(12), dp(12), dp(12));
        slider.setMinimumHeight(dp(48));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                label.setText(title + "  " + (min + progress));
                onChange.accept(min + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { saveDisplaySettings(); }
        });
        body.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
        return slider;
    }

    private void showRecentConversation() {
        TextView text = sheetText(nativeRecentTranscript(), textSize);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("最近の会話")
                .setView(scroll).setPositiveButton("閉じる", null).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private ImageButton iconButton(boolean send, String label) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(label);
        button.setImageDrawable(new ControlIcon(send));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        if (send) button.setBackground(roundBackground(Color.rgb(130, 197, 176), 24));
        return button;
    }

    private static final class ControlIcon extends Drawable {
        private final boolean send;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ControlIcon(boolean send) { this.send = send; }
        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setColor(send ? Color.rgb(16, 30, 27) : Color.rgb(222, 232, 239));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (send) {
                Path arrow = new Path();
                arrow.moveTo(6, 11); arrow.lineTo(12, 5); arrow.lineTo(18, 11);
                canvas.drawPath(arrow, paint);
                canvas.drawLine(12, 5, 12, 20, paint);
            } else {
                canvas.drawLine(4, 6, 20, 6, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                canvas.drawLine(4, 18, 20, 18, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class MenuIcon extends Drawable {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MenuIcon(int kind) { this.kind = kind; }
        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setColor(Color.rgb(166, 196, 188));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (kind == 0) {
                Path p = new Path(); p.moveTo(5, 4); p.lineTo(19, 4); p.lineTo(21, 6);
                p.lineTo(21, 15); p.lineTo(19, 17); p.lineTo(9, 17); p.lineTo(4, 21);
                p.lineTo(4, 6); p.close(); canvas.drawPath(p, paint);
            } else if (kind == 1) {
                canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint);
                for (int y = 8; y <= 16; y += 4) canvas.drawLine(9, y, 15, y, paint);
            } else if (kind == 2 || kind == 3) {
                Path p = new Path(); p.moveTo(6, 3); p.lineTo(18, 3); p.lineTo(18, 21);
                p.lineTo(12, 17); p.lineTo(6, 21); p.close(); canvas.drawPath(p, paint);
                if (kind == 3) { canvas.drawLine(9, 9, 15, 9, paint); canvas.drawLine(12, 6, 12, 12, paint); }
            } else if (kind == 4) {
                for (int i = 0; i < 3; ++i) {
                    int y = 6 + i * 6, x = i == 1 ? 15 : 9;
                    canvas.drawLine(4, y, x - 2, y, paint); canvas.drawLine(x + 2, y, 20, y, paint);
                    canvas.drawCircle(x, y, 2, paint);
                }
            } else if (kind == 5) {
                canvas.drawCircle(12, 12, 8, paint);
                canvas.drawLine(12, 11, 12, 16, paint); canvas.drawPoint(12, 7.5f, paint);
            } else {
                canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    @Override public void onPause() {
        if (connection != null) connection.setForeground(false);
        if (input != null) saveDisplaySettings();
        saveDraft();
        if (!busy && !changingPerson) queueGitSync(false);
        if (surface != null) {
            nativePause();
            surface.onPause();
        }
        super.onPause();
    }

    @Override public void onResume() {
        super.onResume();
        if (surface != null) {
            surface.onResume();
            surface.renderBurst();
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && connection != null) connection.setForeground(true);
        if (focused && surface != null) surface.renderBurst();
    }

    private final class ChatSurface extends GLSurfaceView implements GLSurfaceView.Renderer {
        ChatSurface() {
            super(MainActivity.this);
            setContentDescription("会話。ダブルタップで最近の会話をテキスト表示");
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                        showRecentConversation();
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
            setEGLContextClientVersion(3);
            setPreserveEGLContextOnPause(false);
            setRenderer(this);
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }

        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            nativeSurfaceCreated();
        }

        @Override public void onSurfaceChanged(GL10 ignored, int width, int height) {
            nativeResize(width, height, getResources().getDisplayMetrics().density);
            renderBurst();
        }

        @Override public void onDrawFrame(GL10 ignored) { nativeRender(); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL) return true;
            if (action == MotionEvent.ACTION_DOWN) {
                clearFocus();
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(getWindowToken(), 0);
            }
            float x = event.getX(), y = event.getY();
            queueEvent(() -> nativeTouch(action, x, y));
            renderBurst();
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        void renderBurst() {
            requestRender();
            for (int delay : new int[]{16, 32, 48, 80}) postDelayed(this::requestRender, delay);
        }
    }
}
