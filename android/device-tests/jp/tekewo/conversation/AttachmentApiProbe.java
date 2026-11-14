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
        Bundle result = new Bundle(); Activity activity = null; java.io.File mediaDirectory = null;
        String phase = "startup";
        try {
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(), "jp.tekewo.conversation.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            Field field = activity.getClass().getDeclaredField("connection"); field.setAccessible(true);
            Object connection = field.get(activity);
            phase = "model-selection";
            String slug = activity.getPreferences(Activity.MODE_PRIVATE).getString("model_slug", "");
            Object request;
            if (slug.isEmpty()) {
                Method select = activity.getClass().getDeclaredMethod("selectedRequest"); select.setAccessible(true);
                request = select.invoke(activity);
            } else {
                Class<?> modelType = Class.forName("jp.tekewo.conversation.ChatRequest$Model");
                Constructor<?> modelConstructor = modelType.getDeclaredConstructor(JSONObject.class); modelConstructor.setAccessible(true);
                Object model = modelConstructor.newInstance(new JSONObject().put("slug", slug).put("display_name", slug));
                Class<?> requestType = Class.forName("jp.tekewo.conversation.ChatRequest");
                Constructor<?> requestConstructor = requestType.getDeclaredConstructor(modelType, String.class, boolean.class); requestConstructor.setAccessible(true);
                request = requestConstructor.newInstance(model, "", false);
            }
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
            phase = "generated-image-followup";
            mediaDirectory = java.nio.file.Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "media-probe-").toFile();
            Class<?> media = Class.forName("jp.tekewo.conversation.AttachmentMedia");
            Method response = media.getDeclaredMethod("response", java.io.File.class, String.class); response.setAccessible(true);
            Method input = media.getDeclaredMethod("input", java.io.File.class, JSONArray.class); input.setAccessible(true);
            String generated = (String)response.invoke(null, mediaDirectory,
                    "![AIの画像](data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()) + ")");
            JSONArray history = new JSONArray()
                    .put(new JSONObject().put("role", "user").put("content", "HELLOと書いた画像を作って"))
                    .put(new JSONObject().put("role", "assistant").put("content", generated))
                    .put(new JSONObject().put("role", "user").put("content", "さっきの画像に書かれた英単語だけを返してください。"));
            answer = (String)send.invoke(connection, request, "画像の文字を読んで短く返してください。",
                    input.invoke(null, mediaDirectory, history), (Consumer<String>)delta -> {}, false);
            if (!answer.toUpperCase(java.util.Locale.ROOT).contains("HELLO")) throw new AssertionError("Followup did not identify the generated image word");
            result.putString("followup", "MEDIA_FOLLOWUP_PASS");
            result.putString("stream", "MEDIA_API_PASS: subscription vision and generated-image followup; no conversation rows created\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
            String status = "";
            try { Field field = error.getClass().getDeclaredField("status"); field.setAccessible(true); status = " status=" + field.get(error); }
            catch (ReflectiveOperationException ignored) { }
            result.putString("stream", "MEDIA_API_FAIL phase=" + phase + status + " " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (mediaDirectory != null) {
                java.io.File attachments = new java.io.File(mediaDirectory, "media");
                java.io.File[] files = attachments.listFiles();
                if (files != null) for (java.io.File file : files) file.delete();
                attachments.delete(); mediaDirectory.delete();
            }
            if (activity != null) { Activity current = activity; runOnMainSync(current::finish); }
        }
    }
}
