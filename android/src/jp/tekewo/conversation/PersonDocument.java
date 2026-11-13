package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PersonDocument {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_PROFILE_BYTES = 128 * 1024;
    static final int MAX_SEEDS = 2048;
    static final int MAX_SEED_BYTES = 16 * 1024;
    private static final String[] SECTIONS = { "基本プロフィール", "しゃべり方", "考え方", "ユーザーとの関係", "これまでの出来事", "続いていること", "不明・推測・矛盾" };
    private static final Pattern FIELD = Pattern.compile("^\\s*(?:[-*+]\\s+)?([^:：]+)[:：]\\s*(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{2,3})\\s+(.+?)\\s*#*\\s*$");
    final String name;
    final String profile;
    final String rawMarkdown;
    final String sourceSha256;
    private final JSONArray seeds;

    private PersonDocument(String name, String profile, String raw, JSONArray seeds) {
        this.name = name; this.profile = profile; rawMarkdown = raw;
        sourceSha256 = sha256(utf8(raw)); this.seeds = seeds;
    }

    static PersonDocument named(String input) {
        if (input == null || !input.matches("[^\\p{Cntrl}\\u2028\\u2029]+"))
            throw invalid("名前を一行で入力してください。");
        StringBuilder markdown = new StringBuilder("形式: conversation-person-v1\n\n");
        for (String section : SECTIONS) {
            markdown.append("## ").append(section).append('\n');
            if (section.equals(SECTIONS[0])) markdown.append("- 名前: ").append(input.trim()).append('\n');
            markdown.append('\n');
        }
        return parse(markdown.toString());
    }

    static PersonDocument parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw invalid("人物ファイルは2 MiB以内にしてください。");
        try {
            return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException error) { throw invalid("人物ファイルをUTF-8で保存してください。"); }
    }

    static PersonDocument parse(String markdown) {
        if (markdown == null || markdown.length() > MAX_BYTES || utf8(markdown).length > MAX_BYTES || markdown.indexOf('\0') >= 0)
            throw invalid("人物ファイルはUTF-8の2 MiB以内のMarkdownにしてください。");
        String[] lines = markdown.split("\\r\\n|\\n|\\r", -1);
        Map<String, Section> sections = new LinkedHashMap<>();
        Section section = null; int markerCount = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.matches("形式\\s*[:：]\\s*conversation-person-v1")) markerCount++;
            Matcher header = HEADING.matcher(line);
            if (header.matches() && header.group(1).equals("##")) {
                String title = header.group(2).trim();
                if (!isSection(title)) throw invalid("未対応の章です: " + title);
                if (sections.containsKey(title)) throw invalid("章が重複しています: " + title);
                section = new Section(title);
                sections.put(title, section);
            } else if (section != null) section.lines.add(new Line(line, i + 1));
        }
        if (markerCount != 1) throw invalid("形式: conversation-person-v1 を一度だけ記載してください。");
        for (String required : SECTIONS) if (!sections.containsKey(required)) throw invalid("必要な章がありません: " + required);
        String name = null;
        for (Field field : fields(sections.get(SECTIONS[0]).lines)) {
            if (field.label.equals("名前")) {
                if (name != null) throw invalid("名前が重複しています。");
                name = field.value.trim();
            }
        }
        if (!meaningful(name) || name.indexOf('\n') >= 0 || utf8(name).length > 256) throw invalid("名前を一行、256バイト以内で記載してください。");
        StringBuilder profile = new StringBuilder();
        for (int n : new int[] { 0, 1, 2, 3, 6 }) {
            Section current = sections.get(SECTIONS[n]);
            profile.append("## ").append(current.title).append('\n');
            for (Line line : current.lines) profile.append(line.text).append('\n');
        }
        if (utf8(profile.toString()).length > MAX_PROFILE_BYTES) throw invalid("基本プロフィール・しゃべり方・考え方・関係・不明点の合計を128 KiB以内にしてください。");
        JSONArray seeds = new JSONArray();
        try {
            for (int n : new int[] { 0, 3 }) {
                Section current = sections.get(SECTIONS[n]);
                for (Field field : fields(current.lines)) {
                    if (!meaningful(field.value) || field.label.equals("名前")) continue;
                    String subject = field.label.equals("ユーザーについて知っていること") ? "user" : n == 3 ? "relationship" : "friend";
                    addSeed(seeds, inferred(field.value) ? "interpretation" : "fact", "friend", subject,
                        "profile/" + field.label, field.label + ": " + field.value, "shared", false,
                        current.title + " (L" + field.start + "-L" + field.end + ")", null);
                }
            }
            Section history = sections.get(SECTIONS[4]);
            List<Line> block = new ArrayList<>(); String title = "出来事";
            for (Line line : history.lines) {
                Matcher header = HEADING.matcher(line.text);
                if (header.matches() && header.group(1).equals("###")) {
                    addEvent(seeds, history.title, title, block);
                    block.clear(); title = header.group(2).trim();
                } else block.add(line);
            }
            addEvent(seeds, history.title, title, block);
            Section ongoing = sections.get(SECTIONS[5]);
            for (Field field : fields(ongoing.lines)) {
                if (!meaningful(field.value)) continue;
                String actor = field.value.startsWith("ユーザー") ? "user" : field.value.startsWith("本人") ? "friend" : "unknown";
                String kind = inferred(field.value) ? "interpretation" : field.label.contains("約束") ? "promise" : "event";
                addSeed(seeds, kind, actor, actor.equals("unknown") ? "relationship" : actor, "ongoing/" + field.label,
                    field.label + ": " + field.value, "shared", false,
                    ongoing.title + " (L" + field.start + "-L" + field.end + ")", null);
            }
        } catch (JSONException error) { throw new IllegalStateException(error); }
        return new PersonDocument(name, profile.toString(), markdown, seeds);
    }

    JSONObject importPayload(String personId) throws JSONException {
        return new JSONObject().put("person_id", personId).put("name", name).put("profile", profile)
            .put("source_md", rawMarkdown).put("source_sha256", sourceSha256)
            .put("seeds", new JSONArray(seeds.toString()));
    }

    private static void addEvent(JSONArray seeds, String section, String title, List<Line> lines) throws JSONException {
        List<Field> fields = fields(lines);
        String scope = "shared", date = null, time = null; boolean scopeKnown = false, userActor = false, friendActor = false;
        for (Field field : fields) {
            if (field.label.startsWith("区分")) {
                String value = field.value.trim();
                if (value.equals("現実") || value.equalsIgnoreCase("real")) { scope = "real"; scopeKnown = true; }
                else if (value.equals("創作") || value.equalsIgnoreCase("fiction")) { scope = "fiction"; scopeKnown = true; }
                else if (value.equals("会話内で共有した体験") || value.equalsIgnoreCase("shared")) scopeKnown = true;
            }
            if (field.label.equals("時期") && meaningful(field.value)) {
                time = field.value;
                if (time.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    try { LocalDate.parse(time); date = time; } catch (DateTimeParseException ignored) {}
                }
            }
        }
        StringBuilder event = new StringBuilder(); int first = 0, last = 0;
        for (Field field : fields) {
            if (field.label.equals("時期") || field.label.startsWith("区分") || !meaningful(field.value)) continue;
            String evidence = field.label + ": " + field.value;
            if (field.label.contains("意味") || field.label.contains("受け止め方") || inferred(field.value)) {
                addSeed(seeds, "interpretation", "friend", "friend", "history/" + title + "/" + field.label,
                    (time == null ? "" : "時期: " + time + "\n") + evidence, scope, scopeKnown,
                    section + " / " + title + " (L" + field.start + "-L" + field.end + ")", date);
            } else {
                if (field.value.startsWith("ユーザー")) userActor = true;
                if (field.value.startsWith("本人") || field.label.startsWith("本人が実際")) friendActor = true;
                if (first == 0) first = field.start;
                last = field.end;
                if (event.length() != 0) event.append('\n');
                event.append(evidence);
            }
        }
        String actor = userActor == friendActor ? "unknown" : userActor ? "user" : "friend";
        String subject = userActor && friendActor ? "relationship" : userActor ? "user" : "friend";
        if (event.length() > 0) addSeed(seeds, "event", actor, subject, "history/" + title,
            (time == null ? "" : "時期: " + time + "\n") + event, scope, scopeKnown,
            section + " / " + title + " (L" + first + "-L" + last + ")", date);
    }

    private static void addSeed(JSONArray seeds, String kind, String actor, String subject, String key, String value,
            String scope, boolean scopeKnown, String section, String date) throws JSONException {
        String text = "引き継ぎ資料に記載" + (scopeKnown ? "" : "（区分未指定）") + ": " + value;
        if (utf8(text).length > MAX_SEED_BYTES || utf8(key).length > 256 || utf8(section).length > 512)
            throw invalid("一つの出来事・項目が長すぎます。本文は16 KiB以内、見出しは短くしてください。");
        if (seeds.length() >= MAX_SEEDS) throw invalid("記憶にする項目は2048件以内にしてください。");
        JSONObject seed = new JSONObject().put("kind", kind).put("actor", actor).put("subject", subject)
            .put("key", key).put("text", text).put("scope", scope)
            .put("certainty", kind.equals("interpretation") ? "inferred" : "explicit").put("section", section);
        if (kind.equals("promise")) seed.put("status", "active");
        if (date != null) seed.put("occurred_at", date);
        seeds.put(seed);
    }

    private static List<Field> fields(List<Line> lines) {
        List<Field> fields = new ArrayList<>(); Field current = null;
        for (Line line : lines) {
            if (line.text.trim().startsWith("#")) { current = null; continue; }
            Matcher match = FIELD.matcher(line.text);
            int indent = 0;
            while (indent < line.text.length() && Character.isWhitespace(line.text.charAt(indent))) indent++;
            boolean nested = current != null && indent > current.indent;
            if (match.matches() && !nested) {
                current = new Field(match.group(1).trim(), match.group(2).trim(), line.number, indent);
                fields.add(current);
            } else if (current != null && !line.text.trim().isEmpty()) {
                String value = line.text.trim().replaceFirst("^[-*+]\\s+", "");
                current.value += (current.value.isEmpty() ? "" : "\n") + value;
                current.end = line.number;
            }
        }
        return fields;
    }

    private static boolean isSection(String title) {
        for (String section : SECTIONS) if (section.equals(title)) return true;
        return false;
    }
    private static boolean meaningful(String value) {
        if (value == null) return false;
        String text = value.trim();
        return !text.isEmpty() && !text.equals("不明") && !text.equals("なし") && !text.equals("無し") && !text.equals("未記入") && !text.equals("-");
    }
    private static boolean inferred(String value) { return value.contains("【推測】"); }
    private static byte[] utf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException error) { throw invalid("不正なUnicode文字が含まれています。"); }
    }
    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static final class Section {
        final String title; final List<Line> lines = new ArrayList<>();
        Section(String title) { this.title = title; }
    }
    private static final class Line {
        final String text; final int number;
        Line(String text, int number) { this.text = text; this.number = number; }
    }
    private static final class Field {
        final String label; String value; final int start; int end; final int indent;
        Field(String label, String value, int number, int indent) {
            this.label = label; this.value = value; start = number; end = number; this.indent = indent;
        }
    }
}
