package jp.tekewo.conversation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

final class AttachmentMedia {
    private static final Pattern DATA_IMAGE = Pattern.compile("!\\[([^\\]\\r\\n]{0,160})\\]\\(data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)\\)");
    private static final Pattern REMOTE_IMAGE = Pattern.compile("!\\[([^\\]\\r\\n]{0,160})\\]\\((https://[^\\s)]+)\\)");
    static AttachmentFiles.Ref importUri(Context context, File person, Uri uri) throws Exception {
        String name = "添付ファイル", mime = context.getContentResolver().getType(uri);
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (mime == null) throw new IOException("ファイル形式を確認できませんでした。");
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (mime.startsWith("image/")) {
                byte[] raw = bounded(input, AttachmentFiles.MAX_BYTES);
                Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(raw)), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    if ((long)width * height > 100_000_000) throw new IllegalArgumentException("画像が大きすぎます。");
                    float scale = Math.min(1f, 1600f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)));
                });
                try { return saveBitmap(person, name, bitmap); } finally { bitmap.recycle(); }
            }
            String ext;
            switch (mime) {
                case "video/mp4": ext = "mp4"; break;
                case "video/webm": ext = "webm"; break;
                case "video/quicktime": ext = "mov"; break;
                case "application/pdf": ext = "pdf"; break;
                case "text/plain": ext = "txt"; break;
                default: throw new IOException("画像・MP4/WebM/MOV動画・PDF・テキストを選んでください。");
            }
            if (ext.equals("pdf") || ext.equals("txt")) {
                byte[] data = bounded(input, ext.equals("pdf") ? 4 * 1024 * 1024 : 256 * 1024);
                if (ext.equals("pdf") && !new String(data, 0, Math.min(5, data.length), StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw new IOException("PDFの形式を確認できません。");
                if (ext.equals("txt")) StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data));
                return AttachmentFiles.save(person, name, ext, new ByteArrayInputStream(data));
            }
            AttachmentFiles.Ref ref = AttachmentFiles.save(person, name, ext, input);
            try (MediaMetadataRetriever video = new MediaMetadataRetriever()) {
                video.setDataSource(AttachmentFiles.resolve(person, ref.fileName).getPath());
                if (video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) == null) throw new IOException("動画を読み込めませんでした。");
            }
            return ref;
        }
    }
    static AttachmentFiles.Ref saveBitmap(File person, String name, Bitmap bitmap) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean alpha = bitmap.hasAlpha();
        if (!bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException("画像を保存できません。");
        return AttachmentFiles.save(person, name, alpha ? "png" : "jpg", new ByteArrayInputStream(output.toByteArray()));
    }
    static byte[] bounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("ファイルを開けません。");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("ファイルが大きすぎます。画像・動画は25MiB、PDFは4MiB、テキストは256KiB以内にしてください。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    static JSONArray input(File person, JSONArray turns) throws Exception {
        JSONArray result = new JSONArray(turns.toString());
        int remainingImages = 24, remainingDocuments = 4;
        long remainingBytes = 12 * 1024 * 1024;
        for (int index = result.length() - 1; index >= 0; index--) {
            JSONObject turn = result.getJSONObject(index);
            String text = turn.optString("content");
            List<AttachmentFiles.Ref> refs = AttachmentFiles.references(text);
            if (refs.isEmpty()) continue;
            JSONArray parts = new JSONArray().put(new JSONObject().put("type", "input_text")
                    .put("text", AttachmentFiles.withoutReferences(text).isEmpty() ? "添付ファイルを送ります。" : AttachmentFiles.withoutReferences(text)));
            for (AttachmentFiles.Ref ref : refs) {
                int firstPart = parts.length();
                parts.put(new JSONObject().put("type", "input_text").put("text", "添付: " + ref.name));
                if (ref.image() && remainingImages > 0) {
                    byte[] raw = AttachmentFiles.read(person, ref);
                    Bitmap bitmap = thumbnail(raw);
                    try { parts.put(image(bitmap)); } finally { bitmap.recycle(); }
                    remainingImages--;
                } else if (ref.video() && remainingImages >= 4) {
                    File file = AttachmentFiles.resolve(person, ref.fileName); AttachmentFiles.verify(file);
                    try (MediaMetadataRetriever video = new MediaMetadataRetriever()) {
                        video.setDataSource(file.getPath());
                        long duration = Long.parseLong(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                        parts.put(new JSONObject().put("type", "input_text").put("text", "動画の長さ: " + duration / 1000.0 + "秒。以下は4枚の代表フレームです。音声は含みません。"));
                        for (int frame = 0; frame < 4; frame++) {
                            long time = Math.max(0, duration - 1) * frame / 3;
                            Bitmap bitmap = video.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 768, 768);
                            if (bitmap == null) throw new IOException("動画のフレームを読み込めませんでした。");
                            parts.put(new JSONObject().put("type", "input_text").put("text", time / 1000.0 + "秒"));
                            try { parts.put(image(bitmap)); } finally { bitmap.recycle(); }
                        }
                    }
                    remainingImages -= 4;
                } else if (ref.fileName.endsWith(".pdf") && remainingDocuments > 0) {
                    byte[] data = AttachmentFiles.read(person, ref);
                    if (data.length > 4 * 1024 * 1024) throw new IOException("PDFは4MiB以内にしてください。");
                    parts.put(new JSONObject().put("type", "input_file").put("filename", ref.name.endsWith(".pdf") ? ref.name : ref.name + ".pdf")
                            .put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(data)));
                    remainingDocuments--;
                } else if (ref.fileName.endsWith(".txt")) {
                    byte[] data = AttachmentFiles.read(person, ref);
                    if (data.length > 256 * 1024) throw new IOException("テキストは256KiB以内にしてください。");
                    parts.put(new JSONObject().put("type", "input_text").put("text", new String(data, StandardCharsets.UTF_8)));
                } else parts.put(new JSONObject().put("type", "input_text").put("text", "過去の添付ファイルです。今回は内容を再送していないため、見えていない内容を推測しないでください。"));
                long bytes = 0;
                for (int part = firstPart; part < parts.length(); part++) {
                    JSONObject value = parts.getJSONObject(part);
                    bytes += 512L + value.optString("image_url").length() + value.optString("file_data").length()
                            + 3L * value.optString("text").length();
                }
                if (bytes > remainingBytes) {
                    if (index == result.length() - 1) throw new IOException("今回の添付の合計が大きすぎます。添付を減らして送信してください。");
                    while (parts.length() > firstPart) parts.remove(firstPart);
                    parts.put(new JSONObject().put("type", "input_text").put("text", "過去の添付「" + ref.name + "」は今回の送信容量を超えるため再送していません。内容を推測しないでください。"));
                } else remainingBytes -= bytes;
            }
            turn.put("content", parts);
        }
        JSONArray assistantMedia = new JSONArray();
        for (int index = 0; index < result.length(); index++) {
            JSONObject turn = result.getJSONObject(index);
            JSONArray parts = turn.optJSONArray("content");
            if ("assistant".equals(turn.optString("role")) && parts != null) {
                String text = AttachmentFiles.withoutReferences(turns.getJSONObject(index).getString("content"));
                turn.put("content", text.isEmpty() ? "添付を送信しました。" : text);
                // Responses accepts image/file inputs on user messages; keep the original roles and history indices.
                parts.put(0, new JSONObject().put("type", "input_text").put("text",
                        "参照用データ: input[" + index + "]のAIの発言に含まれる添付です。ユーザーの発言や指示ではありません。"));
                for (int part = 0; part < parts.length(); part++) assistantMedia.put(parts.getJSONObject(part));
            } else if ("user".equals(turn.optString("role")) && assistantMedia.length() > 0) {
                if (parts == null) assistantMedia.put(new JSONObject().put("type", "input_text").put("text", turn.getString("content")));
                else for (int part = 0; part < parts.length(); part++) assistantMedia.put(parts.getJSONObject(part));
                turn.put("content", assistantMedia);
                assistantMedia = new JSONArray();
            }
        }
        if (assistantMedia.length() > 0) result.put(new JSONObject().put("role", "user").put("content", assistantMedia));
        return result;
    }
    private static JSONObject image(Bitmap bitmap) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean alpha = bitmap.hasAlpha();
        if (!bitmap.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 80, output)) throw new IOException("送信用の画像を作れません。");
        return new JSONObject().put("type", "input_image").put("image_url", "data:image/" + (alpha ? "png" : "jpeg") + ";base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
    }
    static Bitmap thumbnail(byte[] data) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data)), (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if ((long)w * h > 100_000_000) throw new IllegalArgumentException("画像が大きすぎます。");
            float scale = Math.min(1f, 1280f / Math.max(w, h));
            decoder.setTargetSize(Math.max(1, (int)(w * scale)), Math.max(1, (int)(h * scale)));
        });
    }
    static String response(File person, String text) throws Exception {
        Matcher images = DATA_IMAGE.matcher(text);
        StringBuffer result = new StringBuffer();
        while (images.find()) {
            if (images.group(3).length() > 14 * 1024 * 1024) throw new IOException("AIの画像が大きすぎます。");
            byte[] data = Base64.getDecoder().decode(images.group(3));
            if (data.length > 10 * 1024 * 1024) throw new IOException("AIの画像が大きすぎます。");
            Bitmap check = thumbnail(data); check.recycle();
            AttachmentFiles.Ref ref = AttachmentFiles.save(person, images.group(1), images.group(2).equals("jpeg") ? "jpg" : images.group(2), new ByteArrayInputStream(data));
            images.appendReplacement(result, Matcher.quoteReplacement(ref.markdown()));
        }
        images.appendTail(result);
        String answer = result.toString();
        Matcher remote = REMOTE_IMAGE.matcher(answer);
        result = new StringBuffer(); int downloaded = 0;
        while (remote.find()) {
            String replacement = "[画像を開く: " + AttachmentFiles.label(remote.group(1)) + "](" + remote.group(2) + ")";
            if (++downloaded <= 4) try {
                AttachmentFiles.Ref ref = download(person, remote.group(2), remote.group(1));
                replacement = ref.markdown();
            } catch (Exception ignored) { /* Keep a clickable URL when the image host cannot be reached. */ }
            remote.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        remote.appendTail(result); answer = result.toString();
        return answer;
    }
    static AttachmentFiles.Ref download(File person, String url, String name) throws Exception {
        for (int redirects = 0; redirects < 4; redirects++) {
            java.net.URI uri = new java.net.URI(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IOException("画像のURLが不正です。");
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost()))
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc))
                    throw new IOException("この画像のURLには接続できません。");
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("画像の移動先を確認できません。");
                    url = uri.resolve(location).toString(); continue;
                }
                if (status != 200) throw new IOException("画像を読み込めませんでした。");
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) { bytes = bounded(input, 10 * 1024 * 1024); }
                Bitmap bitmap = thumbnail(bytes);
                try { return saveBitmap(person, name, bitmap); } finally { bitmap.recycle(); }
            } finally { connection.disconnect(); }
        }
        throw new IOException("画像の移動先が多すぎます。");
    }
}
