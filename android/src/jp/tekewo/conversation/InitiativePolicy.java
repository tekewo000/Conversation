package jp.tekewo.conversation;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class InitiativePolicy {
    static final String ATTEMPT_AT = "initiative_attempt_at";
    static final String SENT_THROUGH = "initiative_sent_through";
    enum Trigger {
        OPEN("initiative_open", 30), IDLE("initiative_idle", 5), BACKGROUND("initiative_background", 240);

        final String preference;
        final long quietMillis;

        Trigger(String preference, long minutes) {
            this.preference = preference;
            quietMillis = TimeUnit.MINUTES.toMillis(minutes);
        }
    }

    final long revision;
    private final long lastUserId;
    private final String lastSpeaker;
    private final String lastActivity;

    InitiativePolicy(JSONArray messages) throws JSONException {
        JSONObject latest = messages.optJSONObject(messages.length() - 1);
        revision = latest == null ? 0 : latest.getLong("id");
        lastActivity = latest == null ? "" : latest.getString("created_at");
        String speaker = "";
        long userId = 0;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject turn = messages.getJSONObject(i);
            if (!turn.optString("annotation").isEmpty()) continue;
            String role = turn.getString("role");
            if (speaker.isEmpty()) speaker = role;
            if (role.equals("user")) { userId = turn.getLong("id"); break; }
        }
        lastUserId = userId;
        lastSpeaker = speaker;
    }

    boolean eligible(JSONObject state, Trigger trigger, long now) {
        if (lastUserId <= state.optLong(SENT_THROUGH, -1) || lastSpeaker.equals("user")) return false;
        long attemptedAt = state.optLong(ATTEMPT_AT, 0);
        if (attemptedAt > 0 && now - attemptedAt < Trigger.IDLE.quietMillis) return false;
        if (trigger == Trigger.IDLE && lastUserId == 0) return false;
        if (trigger == Trigger.BACKGROUND && (!state.optString("draft", "").trim().isEmpty()
                || !state.optString("attachments", "").trim().isEmpty())) return false;
        if (revision == 0) return true;
        try { return now - Instant.parse(lastActivity).toEpochMilli() >= trigger.quietMillis; }
        catch (DateTimeParseException error) { return false; }
    }
}
