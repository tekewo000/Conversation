package jp.tekewo.conversation;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import java.nio.charset.StandardCharsets;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class MainActivity extends Activity {
    private static native String nativeInit(byte[] storageDir, byte[] font, byte[] emojiFont);
    private static native void nativeSurfaceCreated();
    private static native void nativeResize(int width, int height, float density);
    private static native void nativeRender();
    private static native void nativeSetTypography(float pixels, float gapDp);
    private static native void nativeTouch(int action, float x, float y);
    private static native String nativeSend(byte[] text);
    private static native String nativeRecentTranscript();
    private static native void nativePause();

    private ChatSurface surface;
    private EditText input;
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
            String error = nativeInit(getFilesDir().getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                    asset("NotoSansJP-Regular.ttf"), asset("NotoEmoji-Regular.ttf"));
            ready = error.isEmpty();
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
        input.setTextSize(textSize);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setEnabled(ready);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton send = iconButton(true, "送信");
        send.setEnabled(ready);
        send.setOnClickListener(view -> sendMessage());
        LinearLayout.LayoutParams sendLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendLayout.leftMargin = dp(8);
        composer.addView(send, sendLayout);
        root.addView(composer);

        input.setText(getPreferences(MODE_PRIVATE).getString("draft", ""));
        applyTypography();
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
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        Dialog sheet = newSheet();
        LinearLayout body = sheetBody(sheet, "メニュー");
        String[] labels = {"会話", "記憶", "表示設定", "接続状態"};
        int[] icons = {0, 2, 4, 5};
        for (int i = 0; i < labels.length; ++i) {
            final int action = i;
            menuRow(body, labels[i], icons[i], () -> {
                sheet.dismiss();
                if (action == 0) surface.renderBurst();
                else if (action == 1) new AlertDialog.Builder(this).setTitle("記憶")
                        .setMessage("会話から記憶を自動で整理する機能は、まだ実装していません。現在は会話の原文を保存しています。")
                        .setPositiveButton("閉じる", null).show();
                else if (action == 2) showDisplaySettings();
                else new AlertDialog.Builder(this).setTitle("接続状態")
                        .setMessage("AIはまだ接続されていません。会話の全文は端末に保存されます。記憶の自動整理は未実装です。")
                        .setPositiveButton("閉じる", null).show();
            });
        }
        showSheet(sheet);
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
        input.setTextSize(textSize);
        nativeSetTypography(input.getTextSize(), messageGap);
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
        if (input != null) saveDisplaySettings();
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
