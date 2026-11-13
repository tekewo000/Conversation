package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class GitArchiveFilesTest {
    static final String ID = "41a0c8d0-c54e-4355-8d20-1648828ba149";
    static final String RAW = "# 人物\r\n好きな色: 青🪻\r\n";

    static JSONObject delta(long from, long through) throws Exception {
        JSONArray records = new JSONArray();
        for (long i = from + 1; i <= through; i++) records.put(new JSONObject().put("cursor", i)
                .put("events", new JSONArray().put(new JSONObject().put("table", "messages").put("op", "insert")
                        .put("row", new JSONObject().put("id", i).put("text", "記録 " + i)))));
        return new JSONObject().put("format", 1).put("person", new JSONObject().put("id", ID).put("name", "青")
                .put("profile", "落ち着いた会話").put("source_sha256", GitArchiveFiles.sha256(GitArchiveFiles.utf8(RAW))))
                .put("from_cursor", from).put("through_cursor", through).put("has_more", false).put("records", records);
    }

    public static void main(String[] args) throws Exception {
        GitArchiveFiles.Batch first = GitArchiveFiles.build(ID, 42, RAW, delta(0, 2));
        assert Arrays.equals(first.files.get("person.md"), RAW.getBytes(StandardCharsets.UTF_8));
        assert first.files.size() == 4;
        String path = first.manifest.getJSONObject("latest_batch").getString("path");
        assert path.equals("events/00000000/00000000000000000001-00000000000000000002.jsonl");
        String[] lines = new String(first.files.get(path), StandardCharsets.UTF_8).split("\n");
        assert lines.length == 2;
        assert new JSONObject(lines[0]).getLong("cursor") == 1;
        assert new JSONObject(lines[1]).getLong("cursor") == 2;
        JSONObject reordered = new JSONObject("{\"through_cursor\":2,\"format\":1}");
        JSONObject original = delta(0, 2);
        for (String key : Arrays.asList("person", "from_cursor", "has_more", "records")) reordered.put(key, original.get(key));
        GitArchiveFiles.Batch again = GitArchiveFiles.build(ID, 42, RAW, reordered);
        assert Arrays.equals(first.files.get(path), again.files.get(path));
        assert first.manifest.getJSONObject("latest_batch").getString("sha256")
                .equals(again.manifest.getJSONObject("latest_batch").getString("sha256"));
        GitArchiveFiles.Batch next = GitArchiveFiles.build(ID, 42, RAW, delta(2, 3));
        assert !next.files.containsKey(path);
        assert next.files.size() == 4;
        assert GitArchiveFiles.build(ID, 42, RAW, delta(3, 3)).files.size() == 3;
        reject(() -> GitArchiveFiles.build(ID, 42, RAW + "changed", delta(0, 1)));
        JSONObject skipped = delta(0, 2); skipped.getJSONArray("records").remove(0);
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, skipped));
        JSONObject partial = delta(0, 2); partial.put("through_cursor", 1);
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, partial));
        JSONObject fractional = delta(0, 1); fractional.put("through_cursor", 1.5);
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, fractional));
        expandedPersonBounds();
        sqliteUnicodeExpansionFitsEncodedBudget();
        expandedDeltaBound();
        JSONObject oversized = delta(0, 1);
        char[] chars = new char[GitArchiveFiles.MAX_DELTA_BYTES]; Arrays.fill(chars, 'x');
        oversized.getJSONArray("records").getJSONObject(0).put("extra", new String(chars));
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, oversized));
        System.out.println("GitArchiveFilesTest passed");
    }

    private static void expandedPersonBounds() throws Exception {
        String source = repeated('s', 2 * 1024 * 1024 - 4) + "🎾";
        String profile = repeated('p', 128 * 1024 - 3) + "葵";
        JSONObject large = delta(0, 1);
        large.getJSONObject("person").put("source_sha256", GitArchiveFiles.sha256(GitArchiveFiles.utf8(source))).put("profile", profile);
        GitArchiveFiles.Batch atBound = GitArchiveFiles.build(ID, 42, source, large);
        assert atBound.files.get("person.md").length == 2 * 1024 * 1024;
        assert new JSONObject(new String(atBound.files.get("persona.json"), StandardCharsets.UTF_8)).getString("profile").equals(profile);
        String overSource = source + "x";
        JSONObject sourceOver = new JSONObject(large.toString());
        sourceOver.getJSONObject("person").put("source_sha256", GitArchiveFiles.sha256(GitArchiveFiles.utf8(overSource)));
        reject(() -> GitArchiveFiles.build(ID, 42, overSource, sourceOver));
        JSONObject profileOver = new JSONObject(large.toString()); profileOver.getJSONObject("person").put("profile", profile + "x");
        reject(() -> GitArchiveFiles.build(ID, 42, source, profileOver));
    }

    private static void expandedDeltaBound() throws Exception {
        JSONObject large = delta(0, 1);
        JSONObject record = large.getJSONArray("records").getJSONObject(0);
        record.put("padding", "");
        int limit = GitArchiveFiles.MAX_DELTA_BYTES;
        int padding = limit - large.toString().getBytes(StandardCharsets.UTF_8).length;
        record.put("padding", repeated('d', padding));
        assert large.toString().getBytes(StandardCharsets.UTF_8).length == limit;
        GitArchiveFiles.Batch atBound = GitArchiveFiles.build(ID, 42, RAW, large);
        String path = atBound.manifest.getJSONObject("latest_batch").getString("path");
        assert atBound.files.get(path).length > 2 * 1024 * 1024;
        assert atBound.manifest.getLong("cursor") == 1;
        record.put("padding", repeated('d', padding + 1));
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, large));
    }

    private static void sqliteUnicodeExpansionFitsEncodedBudget() throws Exception {
        JSONObject expanded = delta(1, 2);
        JSONArray events = new JSONArray();
        String text = repeated('\u2014', 5461) + "x";
        assert text.getBytes(StandardCharsets.UTF_8).length == 16 * 1024;
        for (int i = 1; i <= 900; i++) {
            JSONObject row = new JSONObject().put("id", i).put("job_id", JSONObject.NULL).put("ordinal", JSONObject.NULL)
                    .put("kind", "fact").put("actor", "friend").put("subject", "friend").put("key", "unicode/" + i)
                    .put("text", text).put("scope", "shared").put("certainty", "explicit").put("status", "active")
                    .put("supersedes_id", JSONObject.NULL).put("valid_from", "").put("created_at", "2026-11-14T00:00:00Z");
            events.put(new JSONObject().put("table", "memories").put("op", "insert").put("row", row));
        }
        expanded.getJSONArray("records").getJSONObject(0).put("events", events);
        String javaJson = expanded.toString();
        int javaBytes = javaJson.getBytes(StandardCharsets.UTF_8).length;
        int sqliteBytes = javaJson.replace("\\u2014", "\u2014").getBytes(StandardCharsets.UTF_8).length;
        assert sqliteBytes < 16 * 1024 * 1024;
        assert javaBytes > 20 * 1024 * 1024;
        GitArchiveFiles.Batch batch = GitArchiveFiles.build(ID, 42, RAW, expanded);
        String path = batch.manifest.getJSONObject("latest_batch").getString("path");
        String line = new String(batch.files.get(path), StandardCharsets.UTF_8);
        JSONArray restored = new JSONObject(line).getJSONArray("events");
        assert restored.length() == 900;
        assert restored.getJSONObject(0).getJSONObject("row").getString("text").equals(text);
    }

    private static String repeated(char value, int size) {
        char[] chars = new char[size]; Arrays.fill(chars, value); return new String(chars);
    }
    interface Checked { void run() throws Exception; }
    static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid batch accepted"); }
        catch (java.io.IOException expected) { }
    }
}
