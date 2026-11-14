package jp.tekewo.conversation;

import android.app.*;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import org.json.*;
import java.lang.reflect.*;
import java.util.function.Consumer;

/** Uses only a synthetic image and prompt; never creates conversation rows or reads credentials. */
public final class AttachmentApiProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Activity activity = null;
        String phase = "startup";
        try {
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(), "jp.tekewo.conversation.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            Field field = activity.getClass().getDeclaredField("connection"); field.setAccessible(true);
            Object connection = field.get(activity);
            Method select = activity.getClass().getDeclaredMethod("selectedRequest"); select.setAccessible(true);
            Object request = select.invoke(activity);
            Method send = connection.getClass().getMethod("request", request.getClass(), String.class, JSONArray.class, Consumer.class, boolean.class);
            Bitmap bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(); paint.setColor(Color.BLACK); paint.setTextSize(48); paint.setAntiAlias(true);
            canvas.drawText("HELLO", 24, 80, paint);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", "画像に書かれた英単語だけを返してください。"))
                    .put(new JSONObject().put("type", "input_image").put("image_url", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())));
            phase = "vision";
            String answer = (String)send.invoke(connection, request, "画像の文字を読んで短く返してください。", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)), (Consumer<String>)delta -> {}, false);
            if (!answer.toUpperCase(java.util.Locale.ROOT).contains("HELLO")) throw new AssertionError("Vision response did not identify the synthetic word");
            result.putString("vision", "MEDIA_VISION_PASS");
            result.putString("stream", "MEDIA_API_PASS: real subscription vision input; no conversation rows created\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            result.putString("stream", "MEDIA_API_FAIL phase=" + phase + " " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally { if (activity != null) { Activity current = activity; runOnMainSync(current::finish); } }
    }
}
