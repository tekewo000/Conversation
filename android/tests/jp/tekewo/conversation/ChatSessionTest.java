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
        JSONObject person, continuity = new JSONObject(), persona = new JSONObject().put("revision", 0).put("through_id", 0).put("fields", new JSONArray());
        JSONObject personaJob = new JSONObject();
        int applied, personaApplied;
        long personaThrough;
        String personaPayload;
        String replyState;
        public long add(String role, String text) { roles.add(role); texts.add(text); return roles.size(); }
        public long addReply(String text, String state) { replyState = state; return add("assistant", text); }
        public JSONObject context(String query) throws Exception {
            JSONArray turns = new JSONArray();
            for (int i = 0; i < roles.size(); ++i) turns.put(new JSONObject().put("id", i + 1)
                    .put("role", roles.get(i)).put("content", texts.get(i)).put("created_at", "2026-11-13T14:00:00Z"));
            return new JSONObject().put("messages", turns).put("memories", new JSONArray()).put("person", person).put("persona", persona).put("continuity", continuity);
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
    static class Listener implements ChatSession.Listener {
        int users, replies;
        String stream, warning;
        public void userSaved() { users++; }
        public void streaming(String text) { stream = text; }
        public void replySaved() { replies++; }
        public void memoryStatus(String error) { warning = error; }
    }
    public static void main(String[] arguments) throws Exception {
        memoryPayloadAcceptsThirtyDetailedScenesAndRejectsOversizedResults();
        cancelledRetrievalDoesNotStartTransport();
        replySavesFeelingWithoutAnotherRequestOrVisibleMetadata();
        incompleteReplyStateCannotCommitAReply();
        missingReplyStateKeepsTheAnswerAndPreviousFeeling();
        replyRetrievalReceivesFullTextWithoutChangingExtraction();
        assistantFeelingAndOldEvidenceReachReplyAndExtraction();
        relationshipAndOldEvidenceReachUnrelatedReplyAndExtraction();
        reflectionReceivesRelationshipAndItsOriginalEvidence();
        replyDoesNotWaitForMemory();
        internalLabelsNeverReachReplyText();
        Store continuing = new Store() {
            @Override public JSONObject pending() throws Exception {
                if (!pending) return new JSONObject();
                JSONArray lastPair = new JSONArray();
                for (int i = roles.size() - 2; i < roles.size(); i++) lastPair.put(new JSONObject().put("id", i + 1).put("role", roles.get(i)).put("content", texts.get(i)));
                return new JSONObject().put("id", 1).put("messages", lastPair);
            }
        };
        continuing.continuity = new JSONObject("{\"through_id\":2,\"threads\":[{\"key\":\"trip\",\"status\":\"active\",\"text\":\"候補は京都、金沢、松本。未決定。\",\"source_ids\":[1,2]}],\"episodes\":[]}");
        continuing.add("user", "候補は京都、金沢、松本");
        continuing.add("assistant", "まだ決めていないね");
        for (int i = 0; i < 10; i++) { continuing.add("user", "別の話題"); continuing.add("assistant", "雑談"); }
        replyAndOrganize(new ChatSession(continuing),"二つ目にしよう", true, (instructions, input, delta) -> {
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content").replaceFirst("^[^\\n]*\\n", ""));
            assert data.getJSONObject("continuity").getJSONArray("threads").getJSONObject(0).getString("text").contains("金沢");
            if (instructions.equals(ChatSession.EXTRACT)) {
                assert data.getJSONArray("evidence").length() == 16;
                assert data.getJSONArray("evidence").getJSONObject(0).getLong("id") == 1;
                return "{\"memories\":[]}";
            }
            assert data.getJSONArray("history").getJSONObject(0).getLong("id") == 1;
            return "金沢にしよう";
        }, new Listener());
        Store backlog = new Store() {
            @Override public JSONObject pending() throws Exception {
                if (!pending) return new JSONObject();
                JSONArray old = new JSONArray();
                for (int i = 0; i < 2; i++) old.put(new JSONObject().put("id", i + 1).put("role", roles.get(i)).put("content", texts.get(i)));
                return new JSONObject().put("id", 1).put("messages", old);
            }
            @Override public JSONObject context(String query) throws Exception {
                JSONObject result = super.context(query);
                if (result.getJSONArray("messages").length() > 0)
                    result.getJSONArray("messages").getJSONObject(0).put("truncated", true);
                return result;
            }
        };
        backlog.add("user", "昔の発言の抜粋"); backlog.add("assistant", "昔の返答");
        backlog.add("user", "後からの訂正"); backlog.add("assistant", "新しい返答");
        replyAndOrganize(new ChatSession(backlog),"今の質問", true, (instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) {
                JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
                JSONArray evidence = data.getJSONArray("evidence");
                assert evidence.length() == 2;
                assert evidence.getJSONObject(1).getLong("id") == 2;
                return "{\"memories\":[]}";
            }
            assert new JSONObject(input.getJSONObject(0).getString("content").split("\\n", 2)[1]).getJSONArray("history").getJSONObject(0).getBoolean("truncated");
            assert !input.getJSONObject(input.length() - 1).getString("content").contains("[原文の一部");
            return "返答";
        }, new Listener());
        String emoji = new String(new int[]{0x1f3be}, 0, 1);
        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 100; ++i) longQuery.append(emoji);
        String bounded = ChatSession.query(longQuery.toString());
        assert bounded.codePointCount(0, bounded.length()) == 64;
        assert bounded.getBytes("UTF-8").length == 256;
        assert ChatSession.jsonPayload("```json\n{\"memories\":[]}\n```").equals("{\"memories\":[]}");
        try { ChatSession.jsonPayload("{} trailing"); throw new AssertionError(); } catch (IOException expected) {}

        Store ok = new Store(); Listener events = new Listener(); ChatSession session = new ChatSession(ok);
        replyAndOrganize(session,"またゲームしよう", true, (instructions, input, delta) -> {
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
            replyAndOrganize(new ChatSession(failed),"こんにちは", true, (instructions, input, delta) -> {
                delta.accept("途中"); throw new IOException("network interrupted");
            }, failure);
            throw new AssertionError();
        } catch (IOException expected) {}
        assert failed.roles.size() == 1 && failure.replies == 0 && failed.applied == 0;

        Store cancelled = new Store(); ChatSession cancel = new ChatSession(cancelled);
        try {
            replyAndOrganize(cancel,"中断", true, (instructions, input, delta) -> {
                delta.accept("途中"); cancel.cancel(); return "確定させない";
            }, new Listener());
            throw new AssertionError();
        } catch (IOException expected) {}
        assert cancelled.roles.size() == 1;

        Store invalidMemory = new Store(); invalidMemory.reject = true; Listener warning = new Listener();
        replyAndOrganize(new ChatSession(invalidMemory),"保存", true, (instructions, input, delta) ->
                instructions.equals(ChatSession.EXTRACT) ? "{\"memories\":[]}" : "返答", warning);
        assert invalidMemory.roles.size() == 2 && invalidMemory.pending && warning.warning != null;

        Store retry = new Store(); retry.add("user", "再試行");
        replyAndOrganize(new ChatSession(retry),"再試行", false, (instructions, input, delta) ->
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
        replyAndOrganize(new ChatSession(truncatedContext),longMessage.toString(), true, (instructions, input, delta) -> {
            if (instructions.equals(ChatSession.EXTRACT)) return "{\"memories\":[]}";
            assert input.getJSONObject(input.length() - 1).getString("content").endsWith("最後の質問");
            assert input.getJSONObject(input.length() - 1).getString("content").equals(longMessage.toString());
            return "全文に返答";
        }, new Listener());
        String[] profiles = {"一人称は私。静かに短く話す。", "一人称は俺。快活に率直に話す。"};
        for (int person = 0; person < profiles.length; ++person) {
            final String profile = profiles[person], otherProfile = profiles[1 - person];
            Store selected = new Store();
            selected.person = new JSONObject().put("id", "person-" + person).put("name", "人物" + person).put("profile", profile);
            replyAndOrganize(new ChatSession(selected),"無関係な新しい話題", true, (instructions, input, delta) -> {
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

    static void memoryPayloadAcceptsThirtyDetailedScenesAndRejectsOversizedResults() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4096; i++) text.append('a');
        JSONArray episodes = new JSONArray();
        for (int i = 0; i < 30; i++) episodes.put(new JSONObject().put("key", "scene-" + i)
                .put("text", text.substring(0, 1536)).put("emotion", text.substring(0, 256))
                .put("scene", text.substring(0, 256)).put("scope", "shared").put("source_ids", new JSONArray().put(1)));
        String payload = new JSONObject().put("memories", new JSONArray().put(new JSONObject()
                .put("kind", "fact").put("subject", "user").put("key", "detail").put("text", text.toString())
                .put("scope", "real").put("certainty", "explicit").put("source_ids", new JSONArray().put(1))))
                .put("continuity", new JSONObject().put("threads", new JSONArray()).put("episodes", episodes)).toString();
        assert payload.length() > 65536;
        assert ChatSession.jsonPayload(payload).equals(payload) : "Thirty detailed scenes must reach native validation";
        StringBuilder oversized = new StringBuilder("{");
        for (int i = 0; i < 33; i++) oversized.append(text);
        oversized.append('}');
        try {
            ChatSession.jsonPayload(oversized.toString());
            throw new AssertionError("Unbounded memory output was accepted");
        } catch (IOException expected) { }
    }

    static void cancelledRetrievalDoesNotStartTransport() throws Exception {
        ChatSession[] running = new ChatSession[1];
        Store store = new Store() {
            @Override public JSONObject replyContext(String text) throws Exception {
                running[0].cancel();
                return super.context(text);
            }
        };
        running[0] = new ChatSession(store);
        try {
            running[0].reply("生成前に停止", true, (instructions, input, delta) -> {
                throw new AssertionError("Cancelled context preparation started a network request");
            }, new Listener());
            throw new AssertionError("Cancelled reply succeeded");
        } catch (IOException expected) { assert running[0].isCancelled(); }
        assert store.roles.size() == 1 && store.roles.get(0).equals("user");
    }

    private static void replySavesFeelingWithoutAnotherRequestOrVisibleMetadata() throws Exception {
        Store store = new Store();
        Listener listener = new Listener() {
            public void streaming(String text) {
                assert "お疲れさま！\n".startsWith(text) : "Feeling metadata leaked into the reply";
                super.streaming(text);
            }
            public void replySaved() {
                assert store.replyState != null : "The feeling must be stored before the reply is announced";
                super.replySaved();
            }
        };
        int[] requests = {0};
        String state = "{\"emotion\":\"嬉しい\",\"reason\":\"試合終了を聞いた\",\"event\":\"一緒に喜んだ会話\",\"scope\":\"shared\"}";
        String generated = "お疲れさま！\n<assistant_state>\n" + state;
        new ChatSession(store).reply("試合が終わったよ", true, (instructions, input, delta) -> {
            assert instructions.contains("<assistant_state>") && instructions.contains("\"scope\"");
            requests[0]++;
            for (int i = 0; i < generated.length(); i++) delta.accept(generated.substring(i, i + 1));
            return generated;
        }, listener);
        assert requests[0] == 1 && store.applied == 0;
        assert store.texts.get(1).equals("お疲れさま！") && listener.stream.equals("お疲れさま！");
        assert new JSONObject(store.replyState).getString("reason").equals("試合終了を聞いた");
    }

    private static void incompleteReplyStateCannotCommitAReply() throws Exception {
        for (String answer : new String[]{"返答\n<assistant_state>\n{", "\n<assistant_state>\nnull"}) {
            Store store = new Store(); Listener listener = new Listener();
            try {
                new ChatSession(store).reply("質問", true, (instructions, input, delta) -> answer, listener);
                throw new AssertionError("An incomplete reply or state was committed");
            } catch (IOException expected) {}
            assert store.roles.size() == 1 && store.replyState == null && listener.replies == 0;
        }
    }

    private static void missingReplyStateKeepsTheAnswerAndPreviousFeeling() throws Exception {
        for (String answer : new String[]{"返答", "返答\n<assistant_sta", "![AIの画像](data:image/png;base64,aGVsbG8=)"}) {
            Store store = new Store(); Listener listener = new Listener();
            store.replyState = "previous";
            new ChatSession(store).reply("質問", true, (instructions, input, delta) -> answer, listener);
            assert store.roles.size() == 2 && store.replyState.equals("previous") && listener.replies == 1;
            assert store.texts.get(1).equals(answer.startsWith("返答") ? "返答" : answer);
        }
    }

    private static void replyRetrievalReceivesFullTextWithoutChangingExtraction() throws Exception {
        final boolean[] retrieved = {false};
        String text = "長い質問です。" + new String(new char[100]).replace('\0', 'あ') + "最後の意味も検索する";
        Store store = new Store() {
            @Override public JSONObject replyContext(String fullText) throws Exception {
                assert fullText.equals(text);
                retrieved[0] = true;
                return context(ChatSession.query(fullText));
            }
        };
        new ChatSession(store).reply(text, true, (instructions, input, delta) -> reply("返答"), new Listener());
        assert retrieved[0] : "Replies must use the semantic retrieval hook with full input";
    }

    private static void replyAndOrganize(ChatSession session, String text, boolean persistUser,
                                         ChatSession.Transport backend, Listener listener) throws Exception {
        session.reply(text, persistUser, (instructions, input, delta) -> reply(backend.request(instructions, input, delta)), listener);
        try { session.processMemories(backend); listener.memoryStatus(null); }
        catch (Exception error) { listener.memoryStatus("記憶の整理は再試行待ちです。"); }
    }

    private static void replyDoesNotWaitForMemory() throws Exception {
        Store store = new Store(); Listener listener = new Listener();
        new ChatSession(store).reply("次をすぐ送りたい", true, (instructions, input, delta) -> {
            assert !instructions.equals(ChatSession.EXTRACT) && !instructions.equals(ChatSession.REFLECT)
                    : "Saving a reply must not run background memory work inline";
            return reply("続けて話せるよ");
        }, listener);
        assert listener.replies == 1 && store.texts.get(1).equals("続けて話せるよ");
        assert store.pending && store.applied == 0;
    }

    private static void assistantFeelingAndOldEvidenceReachReplyAndExtraction() throws Exception {
        Store store = new Store() {
            @Override public JSONObject pending() throws Exception {
                if (!pending) return new JSONObject();
                JSONArray pair = new JSONArray();
                for (int i = roles.size() - 2; i < roles.size(); i++) pair.put(new JSONObject()
                        .put("id", i + 1).put("role", roles.get(i)).put("content", texts.get(i)));
                return new JSONObject().put("id", 1).put("messages", pair);
            }
        };
        store.continuity = new JSONObject("{\"through_id\":2,\"threads\":[],\"episodes\":[],\"assistant_state\":{\"emotion\":\"ほっとして嬉しい\",\"reason\":\"試合終了を聞いた\",\"event\":\"一緒に喜んだ会話\",\"scope\":\"shared\",\"source_ids\":[1,2]}}");
        store.add("user", "試合が終わったよ"); store.add("assistant", "ほっとした。嬉しいね");
        for (int i = 0; i < 10; i++) { store.add("user", "別の話"); store.add("assistant", "雑談"); }
        ChatSession session = new ChatSession(store);
        session.reply("まだ嬉しい？", true, (instructions, input, delta) -> {
            JSONObject reference = new JSONObject(input.getJSONObject(0).getString("content").split("\\n", 2)[1]);
            assert reference.getJSONObject("continuity").getJSONObject("assistant_state").getString("emotion").equals("ほっとして嬉しい");
            assert instructions.contains("assistant_state") && instructions.contains("恒久的");
            return reply("うん、一緒に喜べたことが嬉しいよ");
        }, new Listener());
        final boolean[] extracted = {false};
        session.processMemories((instructions, input, delta) -> {
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
            assert data.getJSONObject("continuity").getJSONObject("assistant_state").getString("reason").equals("試合終了を聞いた");
            JSONArray evidence = data.getJSONArray("evidence");
            assert evidence.getJSONObject(0).getLong("id") == 1 : "Old feeling sources must reach extraction even without threads or episodes";
            assert evidence.getJSONObject(1).getLong("id") == 2;
            extracted[0] = true;
            return "{\"memories\":[]}";
        });
        assert extracted[0];
    }

    private static void relationshipAndOldEvidenceReachUnrelatedReplyAndExtraction() throws Exception {
        Store store = new Store() {
            @Override public JSONObject pending() throws Exception {
                if (!pending) return new JSONObject();
                JSONArray pair = new JSONArray();
                for (int i = roles.size() - 2; i < roles.size(); i++) pair.put(new JSONObject()
                        .put("id", i + 1).put("role", roles.get(i)).put("content", texts.get(i)));
                return new JSONObject().put("id", 1).put("messages", pair);
            }
        };
        store.person = new JSONObject().put("id", "rin").put("name", "りん").put("profile", "慎重で率直に短く話す。");
        store.continuity = new JSONObject("{\"through_id\":2,\"threads\":[],\"episodes\":[],\"relationship\":{\"trust\":\"謝罪は受け止めたが信頼はまだ慎重\",\"openness\":\"雑談はできるが秘密はまだ話さない\",\"basis\":\"約束を破られて傷ついた。謝罪はあったが再発しない行動はまだ確認していない\",\"source_ids\":[1,2]}}");
        store.add("user", "約束を破ってごめん"); store.add("assistant", "謝罪は受け止めた。まだ秘密は話せない");
        store.add("user", "根拠にしていない古い雑談"); store.add("assistant", "古い雑談の返事");
        for (int i = 0; i < 10; i++) { store.add("user", "別の話"); store.add("assistant", "雑談"); }
        ChatSession session = new ChatSession(store);
        int[] requests = {0, 0};
        session.reply("今日の夕飯は何がいい？", true, (instructions, input, delta) -> {
            JSONObject reference = new JSONObject(input.getJSONObject(0).getString("content").split("\\n", 2)[1]);
            JSONObject relationship = reference.getJSONObject("continuity").getJSONObject("relationship");
            assert relationship.getString("trust").equals("謝罪は受け止めたが信頼はまだ慎重");
            assert relationship.getString("openness").equals("雑談はできるが秘密はまだ話さない");
            assert instructions.contains("慎重で率直に短く話す。");
            assert input.getJSONObject(input.length() - 1).getString("content").equals("今日の夕飯は何がいい？");
            requests[0]++;
            return reply("温かいスープはどう？");
        }, new Listener());
        session.processMemories((instructions, input, delta) -> {
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
            assert data.getJSONObject("continuity").getJSONObject("relationship").getString("basis")
                    .equals("約束を破られて傷ついた。謝罪はあったが再発しない行動はまだ確認していない");
            JSONArray evidence = data.getJSONArray("evidence");
            assert evidence.length() == 16 : "Retain relationship sources in addition to the recent 12 and the job pair";
            assert evidence.getJSONObject(0).getLong("id") == 1 : "Old relationship evidence must survive unrelated conversations";
            assert evidence.getJSONObject(0).getString("content").equals("約束を破ってごめん");
            assert evidence.getJSONObject(1).getLong("id") == 2;
            assert evidence.getJSONObject(1).getString("content").equals("謝罪は受け止めた。まだ秘密は話せない");
            assert evidence.getJSONObject(2).getLong("id") == 13 : "Unreferenced old messages should stay outside extraction evidence";
            assert data.getJSONObject("job").getJSONArray("messages").getJSONObject(0).getLong("id") == 25;
            requests[1]++;
            return "{\"memories\":[]}";
        });
        assert requests[0] == 1 && requests[1] == 1 : "Relationship continuity must use the existing reply and extraction calls";
    }

    private static void reflectionReceivesRelationshipAndItsOriginalEvidence() throws Exception {
        Store store = new Store(); store.pending = false; store.personaJob = reflectionJob();
        store.personaJob.put("relationship", new JSONObject().put("trust", "行動を見て少し信頼が戻った")
                .put("openness", "相談はするが話さないこともある").put("basis", "以前の傷つきは残るが約束を守る行動が続いた")
                .put("source_ids", new JSONArray().put(1).put(2).put(25).put(26)));
        store.personaJob.getJSONArray("messages").getJSONObject(0).put("content", "傷つけたことはごめん。約束を守る");
        store.personaJob.getJSONArray("messages").getJSONObject(1).put("content", "謝罪は受け止めたが、まだ少し距離を置きたい");
        int[] requests = {0};
        new ChatSession(store).processMemories((instructions, input, delta) -> {
            assert input.length() == 1;
            JSONObject data = new JSONObject(input.getJSONObject(0).getString("content"));
            assert data.getJSONObject("relationship").getString("trust").equals("行動を見て少し信頼が戻った");
            assert data.getJSONArray("messages").getJSONObject(0).getString("content").equals("傷つけたことはごめん。約束を守る");
            assert data.getJSONArray("messages").getJSONObject(1).getString("content").equals("謝罪は受け止めたが、まだ少し距離を置きたい");
            assert data.getJSONObject("policy").getJSONArray("slow_fields").getString(2).equals("disposition");
            assert data.getJSONObject("policy").getInt("slow_min_each_role") == 6;
            assert instructions.contains("relationship") && instructions.contains("disposition");
            assert instructions.contains("ユーザーへの信頼") && instructions.contains("一般的な優しさ")
                    : "Reflection must distinguish relationship repair from an enduring disposition change";
            requests[0]++;
            return "{\"changes\":[]}";
        });
        assert requests[0] == 1 && store.personaApplied == 1 && store.personaThrough == 36;
    }

    private static void internalLabelsNeverReachReplyText() throws Exception {
        Store store = new Store();
        store.add("user", "前の相談");
        store.add("assistant", "[発言ID: 1385]\n[発言の記録時刻: 2026-11-14T03:51:11.093Z]\n前の返答");
        Listener listener = new Listener() {
            public void streaming(String text) {
                assert !text.contains("[発言") && !text.contains("L'd:") : "Internal labels leaked during streaming";
                super.streaming(text);
            }
        };
        String generated = "うん。\n[発言ID: 1385]\nL'd: 2026-11-14T03:51:11.093Z]\n続けよう。";
        new ChatSession(store).reply("2026-11-14の予定を話そう", true, (instructions, input, delta) -> {
            assert input.getJSONObject(2).getString("content").equals("前の返答");
            assert input.getJSONObject(3).getString("content").equals("2026-11-14の予定を話そう");
            JSONObject reference = new JSONObject(input.getJSONObject(0).getString("content").split("\\n", 2)[1]);
            JSONObject metadata = reference.getJSONArray("history").getJSONObject(1);
            assert metadata.getLong("id") == 2 && metadata.getInt("input_index") == 2;
            delta.accept("うん。\n[発言");
            delta.accept("ID: 1385]\nL'd: 2026-11-14T03:51:11.093Z]\n続けよう。");
            return reply(generated);
        }, listener);
        assert listener.stream.equals("うん。\n続けよう。");
        assert store.texts.get(store.texts.size() - 1).equals("うん。\n続けよう。");
    }

    private static String reply(String text) { return text == null ? null : text + "\n<assistant_state>\nnull"; }

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
        replyAndOrganize(new ChatSession(selected),"人物像とは無関係な話題", true, (instructions, input, delta) -> {
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
        replyAndOrganize(session,"続けよう", true, (instructions, input, delta) -> {
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
