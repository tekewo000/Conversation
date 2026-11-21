package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

public final class InitiativePolicyTest {
    private static final long MINUTE = 60_000;
    private static final long NOW = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli();

    public static void main(String[] args) throws Exception {
        JSONObject state = new JSONObject();
        InitiativePolicy empty = new InitiativePolicy(new JSONArray());
        assert empty.eligible(state, InitiativePolicy.Trigger.OPEN, NOW);
        assert !empty.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);
        assert empty.eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);

        InitiativePolicy pending = new InitiativePolicy(new JSONArray().put(turn(1, "user", 241)));
        assert !pending.eligible(state, InitiativePolicy.Trigger.OPEN, NOW);
        assert !pending.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);
        assert !pending.eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);

        InitiativePolicy recent = exchange(4);
        assert !recent.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);
        InitiativePolicy idle = exchange(5);
        assert idle.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);
        assert !idle.eligible(state, InitiativePolicy.Trigger.OPEN, NOW);
        assert exchange(30).eligible(state, InitiativePolicy.Trigger.OPEN, NOW);
        assert !exchange(239).eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        assert exchange(240).eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        state.put("attachments", "attachment reference");
        assert !exchange(240).eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        state.remove("attachments");
        state.put("draft", "書きかけ");
        assert !exchange(240).eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        state.remove("draft");

        state.put(InitiativePolicy.ATTEMPT_AT, NOW - MINUTE);
        assert !idle.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);
        state.remove(InitiativePolicy.ATTEMPT_AT);
        state.put(InitiativePolicy.SENT_THROUGH, 0);
        assert !empty.eligible(state, InitiativePolicy.Trigger.OPEN, NOW);
        assert idle.eligible(state, InitiativePolicy.Trigger.IDLE, NOW);

        state.put(InitiativePolicy.SENT_THROUGH, 2);
        InitiativePolicy notesOnlySinceSend = new InitiativePolicy(new JSONArray()
                .put(turn(1, "user", 245)).put(turn(2, "assistant", 244))
                .put(turn(3, "user", 243).put("annotation", "meta")));
        assert notesOnlySinceSend.revision == 3;
        assert !notesOnlySinceSend.eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        InitiativePolicy newUser = new InitiativePolicy(new JSONArray()
                .put(turn(3, "user", 245)).put(turn(4, "assistant", 244)));
        assert newUser.eligible(state, InitiativePolicy.Trigger.BACKGROUND, NOW);
        System.out.println("Initiative policy tests passed");
    }

    private static InitiativePolicy exchange(int minutesAgo) throws Exception {
        return new InitiativePolicy(new JSONArray().put(turn(1, "user", minutesAgo + 1))
                .put(turn(2, "assistant", minutesAgo)));
    }

    private static JSONObject turn(int id, String role, int minutesAgo) {
        return new JSONObject().put("id", id).put("role", role)
                .put("created_at", Instant.ofEpochMilli(NOW - minutesAgo * MINUTE).toString());
    }
}
