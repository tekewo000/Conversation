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
        JSONObject person, persona = new JSONObject().put("revision", 0).put("through_id", 0).put("fields", new JSONArray());
        JSONObject personaJob = new JSONObject();
        int applied, personaApplied;
        long personaThrough;
        String personaPayload;
        public long add(String role, String text) { roles.add(role); texts.add(text); return roles.size(); }
        public JSONObject context(String query) throws Exception {
            JSONArray turns = new JSONArray();
            for (int i = 0; i < roles.size(); ++i) turns.put(new JSONObject().put("id", i + 1)
                    .put("role", roles.get(i)).put("content", texts.get(i)).put("created_at", "2026-11-13T14:00:00Z"));
            return new JSONObject().put("messages", turns).put("memories", new JSONArray()).put("person", person).put("persona", persona);
        }
        public JSONObject pending() throws Exception {
            return pending ? new JSONObject().put("id", 1).put("messages", context("").getJSONArray("messages")) : new JSONObject();
        }
        public void apply(long job, String payload) throws Exception {
            if (reject) throw new IOException("Invalid source");
            assert job == 1 && payload.equals("{\"memories\":[]}");
            applied++; pending = false;
        }
        public JSONObject pendingPersona() { return personaJob; }
        public void applyPersona(long throughId, String payload) throws Exception {
            new JSONObject(payload).getJSONArray("changes");
            personaThrough = throughId; personaPayload = payload; personaApplied++; personaJob = new JSONObject();
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
        String[] profiles = {"一人称は私。静かに短く話す。", "一人称は俺。快活に率直に話す。"};
        for (int person = 0; person < profiles.length; ++person) {
            final String profile = profiles[person], otherProfile = profiles[1 - person];
            Store selected = new Store();
            selected.person = new JSONObject().put("id", "person-" + person).put("name", "人物" + person).put("profile", profile);
            new ChatSession(selected).reply("無関係な新しい話題", true, (instructions, input, delta) -> {
                if (instructions.equals(ChatSession.EXTRACT)) {
                    JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
                    assert data.getJSONObject("person").getString("profile").equals(profile);
                    assert data.getJSONObject("job").getJSONArray("messages").length() == 2;
                    assert instructions.contains("reported_handover") && instructions.contains("source_ids");
                    return "{\"memories\":[]}";
                }
                assert instructions.contains(profile) && !instructions.contains(otherProfile);
                assert instructions.contains("新しい明示的な発言") && instructions.contains("更新済みの記憶を優先");
                assert !instructions.startsWith("あなたはユーザーと継続して会話する親しい友達です。");
                assert input.length() == 2; // Reference data plus this real user message only.
                assert input.getJSONObject(1).getString("content").endsWith("無関係な新しい話題");
                assert new JSONObject(input.getJSONObject(0).getString("content").split("\\n", 2)[1]).getJSONArray("memories").length() == 0;
                return "人物に沿った返答";
            }, new Listener());
            assert selected.applied == 1;
        }
        currentPersonaReachesReplyAndExtraction();
        reflectionRunsWithoutMemoryJob();
        reflectionWaitsForThreeMemoryBatches();
        reflectionFailurePreservesReplyAndRetry();
        cancelledReflectionDoesNotCommit();
        rejectedReflectionStaysPending();
        System.out.println("PASS: chat completion, cancellation, raw evidence, memory retry, query bounds, persona context and reflection retry");
    }

    private static void currentPersonaReachesReplyAndExtraction() throws Exception {
        Store selected = new Store();
        selected.persona = new JSONObject().put("revision", 3).put("through_id", 48).put("fields", new JSONArray()
                .put(new JSONObject().put("field", "speaking_style").put("text", "率直で短く話す。")
                        .put("reason", "繰り返し自然に短く応答した。")
                        .put("source_ids", new JSONArray().put(1).put(2).put(5).put(6).put(9).put(10)
                                .put(13).put(14).put(17).put(18).put(25).put(26))
                        .put("revision", 3).put("through_id", 48)
                        .put("updated_at", "2026-11-14T01:00:00Z")));
        int[] requests = {0, 0};
        new ChatSession(selected).reply("人物像とは無関係な話題", true, (instructions, input, delta) -> {
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content").replaceFirst("^[^\\n]*\\n", ""));
            JSONObject persona = data.getJSONObject("persona");
            assert persona.getInt("revision") == 3;
            assert persona.getJSONArray("fields").getJSONObject(0).getString("text").equals("率直で短く話す。");
            if (instructions.equals(ChatSession.EXTRACT)) {
                requests[1]++;
                return "{\"memories\":[]}";
            }
            requests[0]++;
            return "返答";
        }, new Listener());
        assert requests[0] == 1 && requests[1] == 1;
    }

    private static JSONObject reflectionJob() throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("id", 1).put("role", "user").put("content", "前の会話の原文")
                        .put("created_at", "2026-11-13T01:00:00Z").put("truncated", false))
                .put(new JSONObject().put("id", 2).put("role", "assistant").put("content", "前の応答の見えている部分")
                        .put("created_at", "2026-11-13T01:00:01Z").put("truncated", true));
        for (int id = 25; id <= 36; ++id) messages.put(new JSONObject().put("id", id)
                .put("role", id % 2 == 1 ? "user" : "assistant").put("content", "繰り返した会話の原文" + id)
                .put("created_at", "2026-11-14T01:00:00Z").put("truncated", false));
        return new JSONObject().put("through_id", 36).put("after_id", 24)
                .put("person", new JSONObject().put("id", "test-person").put("name", "りん").put("profile", "静かに話す。"))
                .put("persona", new JSONObject().put("revision", 0).put("through_id", 24).put("fields", new JSONArray()))
                .put("messages", messages).put("memories", new JSONArray().put(new JSONObject()
                        .put("id", 1).put("subject", "friend").put("scope", "shared").put("kind", "interpretation")
                        .put("key", "cooperative-play").put("actor", "assistant").put("certainty", "inferred")
                        .put("text", "二人で遊ぶやりとりが続いた。").put("source_ids", new JSONArray().put(1).put(2))))
                .put("policy", new JSONObject()
                        .put("fast_fields", new JSONArray().put("preferences").put("relationship"))
                        .put("slow_fields", new JSONArray().put("outlook").put("speaking_style").put("disposition"))
                        .put("fast_min_each_role", 2).put("slow_min_each_role", 6).put("slow_min_span", 24)
                        .put("slow_cooldown", 24).put("max_source_ids", 32).put("text_max_bytes", 1024)
                        .put("reason_max_bytes", 512));
    }

    private static void reflectionRunsWithoutMemoryJob() throws Exception {
        Store store = new Store(); store.pending = false; store.personaJob = reflectionJob();
        int[] reflected = {0};
        new ChatSession(store).processMemories((instructions, input, delta) -> {
            assert !instructions.equals(ChatSession.EXTRACT);
            assert input.length() == 1;
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
            assert data.getLong("through_id") == 36 && data.getLong("after_id") == 24;
            assert data.getJSONObject("person").getString("name").equals("りん");
            assert data.getJSONObject("persona").getInt("revision") == 0;
            assert data.getJSONObject("policy").getJSONArray("fast_fields").length() == 2;
            assert data.getJSONObject("policy").getInt("slow_min_each_role") == 6;
            assert data.getJSONObject("policy").getInt("slow_min_span") == 24;
            JSONArray messages = data.getJSONArray("messages");
            assert messages.length() == 14;
            assert messages.getJSONObject(0).getString("content").equals("前の会話の原文");
            assert messages.getJSONObject(1).getBoolean("truncated");
            assert messages.getJSONObject(2).getLong("id") == 25;
            assert messages.getJSONObject(13).getLong("id") == 36;
            assert data.getJSONArray("memories").getJSONObject(0).getJSONArray("source_ids").getLong(1) == 2;
            reflected[0]++;
            return "```json\n{\"changes\":[]}\n```";
        });
        assert reflected[0] == 1 : "personality review was skipped when memory queue was empty";
        assert store.personaApplied == 1 && store.personaThrough == 36;
        assert store.personaPayload.equals("{\"changes\":[]}");
        assert store.personaJob.length() == 0 : "empty changes must advance the review checkpoint";
    }

    private static void reflectionWaitsForThreeMemoryBatches() throws Exception {
        Store store = new Store() {
            @Override public void apply(long id, String payload) { assert id == 1; applied++; }
        };
        store.personaJob = reflectionJob();
        store.add("user", "今回の会話");
        int[] extracted = {0}, reflected = {0};
        new ChatSession(store).processMemories((instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) { extracted[0]++; return "{\"memories\":[]}"; }
            assert extracted[0] == 3;
            reflected[0]++;
            return "{\"changes\":[]}";
        });
        assert store.applied == 3 && extracted[0] == 3 && reflected[0] == 1 && store.personaApplied == 1;
    }

    private static void reflectionFailurePreservesReplyAndRetry() throws Exception {
        Store store = new Store(); store.personaJob = reflectionJob();
        Listener listener = new Listener(); ChatSession session = new ChatSession(store);
        session.reply("続けよう", true, (instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) return "{\"memories\":[]}";
            if (input.length() == 1) throw new IOException("reflection connection interrupted");
            return "続けよう！";
        }, listener);
        assert listener.replies == 1 && listener.warning != null;
        assert store.roles.size() == 2 && store.texts.get(1).equals("続けよう！");
        assert store.applied == 1 && store.personaApplied == 0 && store.personaJob.getLong("through_id") == 36;
        session.processMemories((instructions, input, delta) -> "{\"changes\":[]}");
        assert store.roles.size() == 2 && store.personaApplied == 1 && store.personaThrough == 36;
    }

    private static void cancelledReflectionDoesNotCommit() throws Exception {
        Store store = new Store(); store.pending = false; store.personaJob = reflectionJob();
        ChatSession session = new ChatSession(store);
        try {
            session.processMemories((instructions, input, delta) -> { session.cancel(); return "{\"changes\":[]}"; });
            throw new AssertionError("cancelled reflection committed");
        } catch (IOException expected) {}
        assert store.personaApplied == 0 && store.personaJob.getLong("through_id") == 36;
        session.begin();
        session.processMemories((instructions, input, delta) -> "{\"changes\":[]}");
        assert store.personaApplied == 1;
    }

    private static void rejectedReflectionStaysPending() throws Exception {
        boolean[] rejectReflection = {true};
        Store store = new Store() {
            @Override public void applyPersona(long throughId, String payload) throws Exception {
                if (rejectReflection[0]) throw new IOException("Invalid personality evidence");
                super.applyPersona(throughId, payload);
            }
        };
        store.pending = false; store.personaJob = reflectionJob();
        ChatSession session = new ChatSession(store);
        try {
            session.processMemories((instructions, input, delta) -> "{\"changes\":[]}");
            throw new AssertionError("rejected personality evidence was accepted");
        } catch (IOException expected) {}
        assert store.personaApplied == 0 && store.personaJob.getLong("through_id") == 36;
        rejectReflection[0] = false;
        session.processMemories((instructions, input, delta) -> "{\"changes\":[]}");
        assert store.personaApplied == 1;
    }
}
