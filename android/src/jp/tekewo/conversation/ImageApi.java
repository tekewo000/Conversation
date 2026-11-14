package jp.tekewo.conversation;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;

/** Only explicit image generation uses this API-key client; chat stays on the subscription client. */
final class ImageApi {
    static final String DEFAULT_MODEL = "gpt-image-2.5-flare";
    private static final int RESPONSE_LIMIT = 16 * 1024 * 1024;
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1, work -> {
        Thread thread = new Thread(work, "Image API timeout"); thread.setDaemon(true); return thread;
    });
    static { DEADLINES.setRemoveOnCancelPolicy(true); }
    private final HttpRequestPool requests = new HttpRequestPool();
    void cancel() { requests.cancel(); }
    int generation() { return requests.generation(); }

    static void validateKey(String key) throws IOException {
        if (key == null || key.length() < 20 || key.length() > 4096 || !key.matches("sk-[A-Za-z0-9_-]+"))
            throw new IOException("OpenAI APIキーの形式を確認してください。");
    }
    static JSONObject body(String prompt, String model) throws Exception {
        if (prompt == null || prompt.trim().isEmpty() || prompt.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IOException("画像の説明を入力してください（64KiB以内）。");
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,100}")) throw new IOException("画像モデル名を確認してください。");
        return new JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
                .put("quality", "medium").put("size", "auto").put("output_format", "png");
    }
    static String image(String json) throws Exception {
        JSONArray data = new JSONObject(json).getJSONArray("data");
        if (data.length() != 1) throw new IOException("生成画像の数を確認できませんでした。");
        String encoded = data.getJSONObject(0).getString("b64_json");
        if (encoded.isEmpty() || encoded.length() > 14 * 1024 * 1024 || !encoded.matches("[A-Za-z0-9+/=]+"))
            throw new IOException("生成画像の形式またはサイズを確認できませんでした。");
        try {
            if (Base64.getDecoder().decode(encoded).length > 10 * 1024 * 1024) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IOException("生成画像を読み取れませんでした。"); }
        return "![AIの画像](data:image/png;base64," + encoded + ")";
    }
    static IOException failure(int status) {
        switch (status) {
            case 400: return new IOException("画像生成の要求が受け付けられませんでした。モデル名と説明文を確認してください。");
            case 401: return new IOException("画像生成用APIキーが無効です。設定し直してください。");
            case 403: return new IOException("画像モデルの利用権限を確認してください。APIの組織認証が必要な場合があります。");
            case 429: return new IOException("画像生成APIの利用枠・残高・課金設定を確認してください。");
            default: return new IOException("画像生成APIを利用できませんでした。時間をおいてお試しください。");
        }
    }
    String generate(String key, String model, String prompt, int attempt) throws IOException {
        if (!requests.isCurrent(attempt)) throw new IOException("処理をキャンセルしました。");
        validateKey(key);
        HttpURLConnection connection = null; ScheduledFuture<?> deadline = null;
        try {
            byte[] input = body(prompt, model).toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection)new URL("https://api.openai.com/v1/images/generations").openConnection();
            requests.register(connection, attempt);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(300000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(input.length);
            HttpURLConnection bound = connection;
            deadline = DEADLINES.schedule(bound::disconnect, 300, TimeUnit.SECONDS);
            try (OutputStream output = connection.getOutputStream()) { output.write(input); }
            int status = connection.getResponseCode();
            if (status != 200) throw failure(status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > RESPONSE_LIMIT) throw new IOException("生成画像が大きすぎます。");
                    bytes.write(buffer, 0, count);
                }
            }
            if (!requests.isCurrent(attempt)) throw new IOException("処理をキャンセルしました。");
            return image(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("生成画像を処理できませんでした。"); }
        finally { if (deadline != null) deadline.cancel(false); requests.finish(connection); }
    }
}
