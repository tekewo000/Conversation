package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class PersonDocumentTest {
    static String document() throws Exception {
        String template = new String(Files.readAllBytes(Paths.get("docs/formats/person-v1.md")), StandardCharsets.UTF_8);
        return template.replace("- 名前:", "- 名前：\n  小春")
            .replace("- 一人称:", "- 一人称: 私")
            .replace("- ユーザーについて知っていること:", "- ユーザーについて知っていること: ユーザーはテニスが好き")
            .replace("- 時期:", "- 時期: 2026-02-15")
            .replace("- 区分（現実／創作／会話内で共有した体験）:", "- 区分（現実／創作／会話内で共有した体験）: 創作")
            .replace("- 起きたこと:", "- 起きたこと: 二人で月へ行った")
            .replace("- 本人が実際に言ったこと・したこと:", "- 本人が実際に言ったこと・したこと: 「きれいだね」と言った")
            .replace("- 本人にとっての意味・受け止め方:", "- 本人にとっての意味・受け止め方: 【推測】安心できた")
            .replace("- まだ果たしていない約束:", "- まだ果たしていない約束: ユーザーが写真を送る")
            .replace("- 不明なこと:", "- 不明なこと: 正確な誕生日は不明");
    }
    public static void main(String[] args) throws Exception {
        String markdown = document();
        PersonDocument doc = PersonDocument.parse(markdown);
        assert doc.name.equals("小春");
        assert doc.rawMarkdown.equals(markdown);
        assert doc.sourceSha256.matches("[0-9a-f]{64}");
        assert doc.profile.contains("正確な誕生日は不明");
        assert !doc.profile.contains("月へ行った") && !doc.profile.contains("写真を送る");
        JSONObject payload = doc.importPayload("43c56650-1792-4b38-a661-3c810bbd4679");
        assert payload.getString("source_md").equals(markdown);
        JSONArray seeds = payload.getJSONArray("seeds");
        boolean event = false, inference = false, promise = false, userFact = false;
        for (int i = 0; i < seeds.length(); i++) {
            JSONObject seed = seeds.getJSONObject(i);
            assert seed.getString("section").contains("L");
            assert seed.getString("text").contains("引き継ぎ資料");
            assert seed.getString("text").getBytes(StandardCharsets.UTF_8).length <= 4096;
            if (seed.getString("kind").equals("event")) {
                event = true;
                assert seed.getString("scope").equals("fiction");
                assert seed.getString("certainty").equals("explicit");
                assert seed.getString("text").contains("きれいだね");
                assert seed.getString("occurred_at").equals("2026-02-15");
            }
            if (seed.getString("kind").equals("interpretation")) {
                inference = true; assert seed.getString("certainty").equals("inferred");
            }
            if (seed.getString("kind").equals("promise")) {
                promise = true;
                assert seed.getString("status").equals("active");
                assert seed.getString("actor").equals("user");
                assert seed.getString("scope").equals("shared");
                assert seed.getString("text").contains("区分未指定");
            }
            if (seed.getString("text").contains("テニス")) {
                userFact = true; assert seed.getString("subject").equals("user");
            }
        }
        assert event && inference && promise && userFact;
        String indented = markdown.replaceAll("(?m)^- ", "  - ").replace("  小春", "    小春");
        assert PersonDocument.parse(indented).name.equals("小春");
        assert PersonDocument.parse(indented).importPayload("person").getJSONArray("seeds").length() == seeds.length();
        String userHistory = markdown.replace("二人で月へ行った", "ユーザーが試合に出た")
            .replace("「きれいだね」と言った", "不明").replace("【推測】安心できた", "なし");
        JSONArray userSeeds = PersonDocument.parse(userHistory).importPayload("person").getJSONArray("seeds");
        for (int i = 0; i < userSeeds.length(); i++) {
            JSONObject seed = userSeeds.getJSONObject(i);
            if (seed.getString("kind").equals("event")) {
                assert seed.getString("actor").equals("user");
                assert seed.getString("subject").equals("user");
            }
        }
        expectInvalid(markdown.replace("conversation-person-v1", "conversation-person-v2"));
        expectInvalid(markdown.replace("## 考え方", "## 別の章"));
        expectInvalid(markdown.replace("  小春", "  不明"));
        expectInvalid(markdown + "\ud800");
        try { PersonDocument.parse(new byte[] { (byte) 0xc3, (byte) 0x28 }); throw new AssertionError("Accepted malformed UTF-8"); }
        catch (IllegalArgumentException expected) {}
        expectInvalid(markdown + repeat("あ", 180000));
        expectInvalid(markdown.replace("私", repeat("あ", 12000)));
        expectInvalid(markdown.replace("二人で月へ行った", repeat("あ", 1500)));
        String unknownScope = markdown.replace(": 創作", ": 不明");
        JSONArray unknownSeeds = PersonDocument.parse(unknownScope).importPayload("person").getJSONArray("seeds");
        for (int i = 0; i < unknownSeeds.length(); i++) {
            JSONObject seed = unknownSeeds.getJSONObject(i);
            if (seed.getString("kind").equals("event")) {
                assert seed.getString("scope").equals("shared");
                assert seed.getString("text").contains("区分未指定");
            }
        }
        String emptyEvents = markdown.replace("二人で月へ行った", "なし")
            .replace("「きれいだね」と言った", "不明").replace("【推測】安心できた", "なし")
            .replace("ユーザーが写真を送る", "なし");
        JSONArray empty = PersonDocument.parse(emptyEvents).importPayload("person").getJSONArray("seeds");
        for (int i = 0; i < empty.length(); i++) assert !empty.getJSONObject(i).getString("kind").equals("event");
        StringBuilder many = new StringBuilder(markdown);
        for (int i = 0; i < 513; i++) many.append("\n### 出来事").append(i).append("\n- 起きたこと: 出来事\n");
        expectInvalid(markdown.replace("## 続いていること", many.substring(markdown.length()) + "\n## 続いていること"));
        System.out.println("PASS: person Markdown validation, profile separation, sourced memory types and UTF-8 limits");
    }
    private static String repeat(String value, int count) {
        StringBuilder text = new StringBuilder(); for (int i = 0; i < count; i++) text.append(value); return text.toString();
    }
    private static void expectInvalid(String text) {
        try { PersonDocument.parse(text); throw new AssertionError("Accepted invalid person Markdown"); }
        catch (IllegalArgumentException expected) {}
    }
}
