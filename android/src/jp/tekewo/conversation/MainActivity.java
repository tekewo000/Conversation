package jp.tekewo.conversation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static native String nativeInit(byte[] storageDir, byte[] font, byte[] emojiFont);
    private static native void nativeSurfaceCreated();
    private static native void nativeResize(int width, int height, float density);
    private static native void nativeRender();
    private static native void nativeTouch(int action, float x, float y);
    private static native String nativeSend(byte[] text);
    private static native String nativeTranscript(boolean memories);
    private static native String[] nativeMessageTexts();
    private static native String nativeRemember(int index);
    private static native void nativePause();

    private ChatSurface surface;
    private EditText input;
    private boolean ready;
    private static final int BACKGROUND = Color.rgb(245, 245, 240);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

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

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("入力・保存プレビュー");
        title.setTextColor(Color.rgb(40, 50, 55));
        title.setTextSize(14);
        title.setPadding(dp(16), dp(6), dp(8), dp(6));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button history = new Button(this);
        history.setText("一覧");
        history.setContentDescription("履歴と記憶をテキストで読む・コピーする");
        history.setOnClickListener(view -> showHistoryMenu());
        header.addView(history, new LinearLayout.LayoutParams(dp(76), dp(48)));
        root.addView(header);

        try {
            System.loadLibrary("conversation");
            String error = nativeInit(getFilesDir().getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                    asset("NotoSansJP-Regular.ttf"), asset("NotoEmoji-Regular.ttf"));
            ready = error.isEmpty();
            if (!ready) showError(error);
        } catch (Exception | UnsatisfiedLinkError error) {
            TextView failure = new TextView(this);
            failure.setText("起動できませんでした。\n" + error.getMessage());
            failure.setTextColor(Color.rgb(80, 30, 25));
            failure.setPadding(dp(16), dp(16), dp(16), dp(16));
            root.addView(failure);
            return;
        }

        surface = new ChatSurface();
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(10), dp(6), dp(10), dp(8));
        input = new EditText(this);
        input.setId(0x434f4e);
        // Drafts live in app storage, outside Android's size-limited activity-state transaction.
        input.setSaveEnabled(false);
        input.setTextColor(Color.rgb(32, 42, 47));
        input.setHintTextColor(Color.rgb(95, 110, 120));
        input.setHint("メッセージを書く（この版はAI未接続）");
        input.setTextSize(17);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setEnabled(ready);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        Button send = new Button(this);
        send.setText("保存");
        send.setContentDescription("メッセージを端末に保存");
        send.setMinHeight(dp(48));
        send.setEnabled(ready);
        send.setOnClickListener(view -> sendMessage());
        composer.addView(send, new LinearLayout.LayoutParams(dp(76), -2));
        root.addView(composer);

        input.setText(getPreferences(MODE_PRIVATE).getString("draft", ""));
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

    private void sendMessage() {
        String text = input.getText().toString();
        String error = nativeSend(text.getBytes(StandardCharsets.UTF_8));
        if (!error.isEmpty()) {
            showError(error);
            return;
        }
        BaseInputConnection.removeComposingSpans(input.getText());
        input.setText("");
        getPreferences(MODE_PRIVATE).edit().remove("draft").apply();
        input.announceForAccessibility("メッセージを保存しました");
        surface.renderBurst();
    }

    private void showError(String error) {
        new AlertDialog.Builder(this).setTitle("保存を確認してください")
                .setMessage(error).setPositiveButton("閉じる", null).show();
    }

    private void showHistoryMenu() {
        if (!ready) return;
        new AlertDialog.Builder(this).setTitle("履歴と記憶")
                .setItems(new String[]{"会話を読む・コピー", "記憶を読む・コピー", "会話を記憶に残す"}, (dialog, which) -> {
                    if (which < 2) showTranscript(which == 1);
                    else {
                        String[] messages = nativeMessageTexts();
                        if (messages.length == 0) { showError("まだ会話がありません。"); return; }
                        new AlertDialog.Builder(this).setTitle("記憶に残す会話を選ぶ")
                                .setItems(messages, (selection, index) -> {
                                    String error = nativeRemember(index);
                                    if (!error.isEmpty()) showError(error);
                                    else {
                                        input.announceForAccessibility("記憶に保存しました");
                                        surface.renderBurst();
                                    }
                                }).setNegativeButton("閉じる", null).show();
                    }
                }).setNegativeButton("閉じる", null).show();
    }

    private void showTranscript(boolean memories) {
        String transcript = nativeTranscript(memories);
        TextView text = new TextView(this);
        text.setText(transcript.isEmpty() ? "まだ記録がありません。" : transcript);
        text.setTextSize(17);
        text.setTextIsSelectable(true);
        text.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(memories ? "記憶" : "会話")
                .setView(scroll).setPositiveButton("閉じる", null)
                .setNeutralButton("コピー", (dialog, which) -> {
                    try {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("Conversation", transcript));
                    } catch (RuntimeException error) {
                        showError("一度にコピーできない長さです。必要な部分を選択してコピーしてください。");
                    }
                }).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onPause() {
        if (input != null) getPreferences(MODE_PRIVATE).edit()
                .putString("draft", input.getText().toString()).commit();
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
        if (focused && surface != null) surface.renderBurst();
    }

    private final class ChatSurface extends GLSurfaceView implements GLSurfaceView.Renderer {
        ChatSurface() {
            super(MainActivity.this);
            setContentDescription("会話履歴と記憶帳");
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
