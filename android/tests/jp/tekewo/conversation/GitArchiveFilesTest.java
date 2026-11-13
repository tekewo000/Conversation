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
        JSONObject oversized = delta(0, 1);
        char[] chars = new char[GitArchiveFiles.MAX_DELTA_BYTES]; Arrays.fill(chars, 'x');
        oversized.getJSONArray("records").getJSONObject(0).put("extra", new String(chars));
        reject(() -> GitArchiveFiles.build(ID, 42, RAW, oversized));
        System.out.println("GitArchiveFilesTest passed");
    }
    interface Checked { void run() throws Exception; }
    static void reject(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid batch accepted"); }
        catch (java.io.IOException expected) { }
    }
}
