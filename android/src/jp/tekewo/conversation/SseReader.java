package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class SseReader {
    private static final int LIMIT = 2 * 1024 * 1024;
    private SseReader() {}

    static String readAuto(InputStream input, Consumer<String> onDelta) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(64);
        int first = buffered.read(), skipped = 0;
        if (first == 0xef) {
            if (buffered.read() != 0xbb || buffered.read() != 0xbf) throw new StreamFailure("応答の文字コードが不正です。");
            first = buffered.read();
        }
        while ((first == ' ' || first == '\n' || first == '\r' || first == '\t') && ++skipped < 48) first = buffered.read();
        buffered.reset();
        if (first == '{') return readJson(buffered, onDelta);
        if (first == 'd' || first == 'e' || first == ':' || first == 'r') return read(buffered, onDelta);
        throw new StreamFailure("AIの応答本文の形式を確認できませんでした。");
    }

    static String readJson(InputStream input, Consumer<String> onDelta) throws IOException {
        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096]; int count;
        while ((count = reader.read(buffer)) != -1) {
            if (text.length() + count > LIMIT) throw limit();
            text.append(buffer, 0, count);
        }
        try {
            JSONObject response = new JSONObject(text.toString());
            if (!"response".equals(response.optString("object")) || !"completed".equals(response.optString("status")))
                throw new StreamFailure("AIの応答の完了を確認できませんでした。");
            return event(new JSONObject().put("type", "response.completed").put("response", response).toString(),
                    new StringBuilder(), onDelta);
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new StreamFailure("AIの応答形式を読み取れませんでした。"); }
    }

    static String read(InputStream input, Consumer<String> onDelta) throws IOException {
        PushbackReader reader = new PushbackReader(new InputStreamReader(input,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)), 1);
        StringBuilder event = new StringBuilder(), output = new StringBuilder();
        String line;
        boolean first = true;
        while ((line = line(reader)) != null) {
            if (first && line.startsWith("\ufeff")) line = line.substring(1);
            first = false;
            if (line.isEmpty()) {
                if (event.length() == 0) continue;
                String result = event(event.toString(), output, onDelta);
                if (result != null) return result;
                event.setLength(0);
            } else if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                if (event.length() + data.length() + 1 > LIMIT) throw limit();
                event.append(data).append('\n');
            }
        }
        throw new StreamFailure("応答が完了する前に接続が切れました。もう一度送信してください。");
    }

    private static String event(String data, StringBuilder output, Consumer<String> onDelta) throws IOException {
        try {
            if ("[DONE]".equals(data.trim())) throw new StreamFailure("応答の完了を確認できませんでした。");
            JSONObject event = new JSONObject(data);
            String type = event.getString("type");
            if ("response.output_text.delta".equals(type) || "response.refusal.delta".equals(type)) {
                String delta = event.getString("delta");
                if (output.length() + delta.length() > LIMIT) throw limit();
                output.append(delta);
                onDelta.accept(delta);
            } else if ("response.completed".equals(type)) {
                JSONObject response = event.getJSONObject("response");
                if (!"completed".equals(response.optString("status"))) throw new StreamFailure("応答が完了しませんでした。");
                StringBuilder complete = new StringBuilder();
                JSONArray items = response.optJSONArray("output");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONArray content = items.getJSONObject(i).optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject part = content.getJSONObject(j);
                        if ("output_text".equals(part.optString("type"))) complete.append(part.getString("text"));
                        if ("refusal".equals(part.optString("type"))) complete.append(part.getString("refusal"));
                        if (complete.length() > LIMIT) throw limit();
                    }
                }
                if (complete.length() == 0) complete.append(output);
                if (complete.length() == 0) throw new StreamFailure("テキストの応答がありませんでした。");
                String result = complete.toString();
                if (result.startsWith(output.toString()) && result.length() > output.length()) onDelta.accept(result.substring(output.length()));
                return result;
            } else if ("response.failed".equals(type) || "error".equals(type)) {
                JSONObject response = event.optJSONObject("response");
                JSONObject error = response == null ? event.optJSONObject("error") : response.optJSONObject("error");
                String code = error == null ? event.optString("code") : error.optString("code");
                if (code.startsWith("subscription_sharing_usage_")) throw new StreamFailure("ChatGPT の利用上限に達したか、現在利用できません。ChatGPT の使用状況を確認してください。");
                throw new StreamFailure("ChatGPT が応答を完了できませんでした。時間をおいてお試しください。");
            } else if ("response.incomplete".equals(type)) throw new StreamFailure("応答が途中で終了しました。もう一度送信してください。");
            return null;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new StreamFailure("ChatGPT の応答を読み取れませんでした。"); }
    }

    private static String line(PushbackReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value == '\r') {
                int next = reader.read();
                if (next != -1 && next != '\n') reader.unread(next);
                return line.toString();
            }
            if (line.length() == LIMIT) throw limit();
            line.append((char)value);
        }
        return line.length() == 0 ? null : line.toString();
    }
    static final class StreamFailure extends IOException {
        StreamFailure(String message) { super(message); }
    }
    private static IOException limit() { return new StreamFailure("応答が長すぎます。会話を短くしてお試しください。"); }
}
