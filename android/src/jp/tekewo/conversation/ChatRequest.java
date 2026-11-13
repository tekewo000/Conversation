package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

final class ChatRequest {
    static final class Model {
        final String slug, displayName;
        final List<String> efforts;
        final boolean supportsFast;

        Model(JSONObject value) throws JSONException {
            slug = value.getString("slug");
            displayName = value.getString("display_name");
            List<String> supported = new ArrayList<>();
            JSONArray levels = value.optJSONArray("supported_reasoning_levels");
            if (levels != null) for (int i = 0; i < levels.length(); i++) {
                String effort = levels.getJSONObject(i).getString("effort");
                if (!effort.isEmpty() && !supported.contains(effort)) supported.add(effort);
            }
            efforts = Collections.unmodifiableList(supported);
            boolean priority = false;
            JSONArray tiers = value.optJSONArray("service_tiers");
            if (tiers != null) for (int i = 0; i < tiers.length(); i++)
                priority |= "priority".equals(tiers.getJSONObject(i).optString("id"));
            else {
                JSONArray legacy = value.optJSONArray("additional_speed_tiers");
                if (legacy != null) for (int i = 0; i < legacy.length(); i++)
                    priority |= "fast".equals(legacy.optString(i));
            }
            supportsFast = priority;
        }
    }

    final Model model;
    final String effort;
    final boolean fast;

    ChatRequest(Model model, String effort, boolean fast) {
        this.model = model;
        this.effort = effort;
        this.fast = fast;
    }

    JSONObject body(String instructions, JSONArray input) throws IOException, JSONException {
        if (model == null || model.slug.isEmpty() || input == null || input.length() == 0)
            throw new IOException("モデルとメッセージを指定してください。");
        if (effort == null || (!effort.isEmpty() && !model.efforts.contains(effort)))
            throw new IOException("このモデルでは選択したEffortを利用できません。設定を選び直してください。");
        if (fast && !model.supportsFast) throw new IOException("このモデルではFastを利用できません。");
        JSONObject body = new JSONObject().put("model", model.slug).put("instructions", instructions == null ? "" : instructions)
                .put("input", input).put("stream", true).put("store", false)
                .put("service_tier", fast ? "priority" : "default");
        if (!effort.isEmpty()) body.put("reasoning", new JSONObject().put("effort", effort));
        return body;
    }
}
