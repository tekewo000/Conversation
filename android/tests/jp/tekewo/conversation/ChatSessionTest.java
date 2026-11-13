package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ChatSessionTest {
    static class Store implements ChatSession.Store {
        final List<String> roles = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        boolean pending = true, reject;
        int applied;
        public long add(String role, String text) { roles.add(role); texts.add(text); return roles.size(); }
        public JSONObject context(String query) throws Exception {
            JSONArray turns = new JSONArray();
            for (int i = 0; i < roles.size(); ++i) turns.put(new JSONObject().put("id", i + 1)
                    .put("role", roles.get(i)).put("content", texts.get(i)).put("created_at", "2026-11-13T14:00:00Z"));
            return new JSONObject().put("messages", turns).put("memories", new JSONArray());
        }
        public JSONObject pending() throws Exception {
            return pending ? new JSONObject().put("id", 1).put("messages", context("").getJSONArray("messages")) : new JSONObject();
        }
        public void apply(long job, String payload) throws Exception {
            if (reject) throw new IOException("Invalid source");
            assert job == 1 && payload.equals("{\"memories\":[]}");
            applied++; pending = false;
        }
    }
    static final class Listener implements ChatSession.Listener {
        int users, replies;
        String stream, warning;
        public void userSaved() { users++; }
        public void streaming(String text) { stream = text; }
        public void replySaved() { replies++; }
        public void memoryStatus(String error) { warning = error; }
    }
    public static void main(String[] arguments) throws Exception {
        String emoji = new String(new int[]{0x1f3be}, 0, 1);
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 100; ++i) longQuery.append(emoji);
        String bounded = ChatSession.query(longQuery.toString());
        assert bounded.codePointCount(0, bounded.length()) == 64;
        assert bounded.getBytes("UTF-8").length == 256;
        assert ChatSession.jsonPayload("```json\n{\"memories\":[]}\n```").equals("{\"memories\":[]}");
        try { ChatSession.jsonPayload("{} trailing"); throw new AssertionError(); } catch (IOException expected) {}

        Store ok = new Store(); Listener events = new Listener(); ChatSession session = new ChatSession(ok);
        session.reply("またゲームしよう", true, (instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) {
                JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
                assert data.getJSONObject("job").getJSONArray("messages").length() == 2;
                assert data.getJSONObject("job").getJSONArray("messages").getJSONObject(0).getString("role").equals("user");
                return "{\"memories\":[]}";
            }
            assert ok.roles.size() == 1;
            delta.accept("やろう"); delta.accept("！"); return "やろう！";
        }, events);
        assert ok.roles.size() == 2 && ok.roles.get(1).equals("assistant");
        assert events.users == 1 && events.replies == 1 && events.stream.equals("やろう！");
        assert ok.applied == 1 && !ok.pending && events.warning == null;

        Store failed = new Store(); Listener failure = new Listener();
        try {
            new ChatSession(failed).reply("こんにちは", true, (instructions, input, delta) -> {
                delta.accept("途中"); throw new IOException("network interrupted");
            }, failure);
            throw new AssertionError();
        } catch (IOException expected) {}
        assert failed.roles.size() == 1 && failure.replies == 0 && failed.applied == 0;

        Store cancelled = new Store(); ChatSession cancel = new ChatSession(cancelled);
        try {
            cancel.reply("中断", true, (instructions, input, delta) -> {
                delta.accept("途中"); cancel.cancel(); return "確定させない";
            }, new Listener());
            throw new AssertionError();
        } catch (IOException expected) {}
        assert cancelled.roles.size() == 1;

        Store invalidMemory = new Store(); invalidMemory.reject = true; Listener warning = new Listener();
        new ChatSession(invalidMemory).reply("保存", true, (instructions, input, delta) ->
                instructions.equals(ChatSession.EXTRACT) ? "{\"memories\":[]}" : "返答", warning);
        assert invalidMemory.roles.size() == 2 && invalidMemory.pending && warning.warning != null;

        Store retry = new Store(); retry.add("user", "再試行");
        new ChatSession(retry).reply("再試行", false, (instructions, input, delta) ->
                instructions.equals(ChatSession.EXTRACT) ? "{\"memories\":[]}" : "返答", new Listener());
        assert retry.roles.size() == 2;
        Store truncatedContext = new Store() {
            @Override public JSONObject context(String query) throws Exception {
                JSONObject result = super.context(query);
                JSONArray turns = result.getJSONArray("messages");
                for (int i = 0; i < turns.length(); ++i) {
                    JSONObject turn = turns.getJSONObject(i);
                    String text = turn.getString("content");
                    if (text.length() > 512) turn.put("content", text.substring(0, 512));
                }
                return result;
            }
        };
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 800; ++i) longMessage.append("あ");
        longMessage.append("最後の質問");
        new ChatSession(truncatedContext).reply(longMessage.toString(), true, (instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) return "{\"memories\":[]}";
            assert input.getJSONObject(input.length() - 1).getString("content").endsWith("最後の質問");
            assert input.getJSONObject(input.length() - 1).getString("content").startsWith("[発言の記録時刻:");
            return "全文に返答";
        }, new Listener());
        System.out.println("PASS: chat completion, cancellation, raw evidence, memory retry and query bounds");
    }
}
