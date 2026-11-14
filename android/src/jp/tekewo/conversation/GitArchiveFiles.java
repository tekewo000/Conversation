package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds only one bounded journal batch; previous event files are never read or rewritten. */
final class GitArchiveFiles {
    // org.json can double SQLite's 20 MiB UTF-8 output by escaping U+2000..U+20FF.
    static final int MAX_DELTA_BYTES = 40 * 1024 * 1024;
    static final String FORMAT = "conversation-person-git-v1";

    static final class Batch {
        final long from, through;
        final JSONObject manifest;
        final Map<String, byte[]> files;
        Batch(long from, long through, JSONObject manifest, Map<String, byte[]> files) {
            this.from = from; this.through = through; this.manifest = manifest; this.files = files;
        }
    }

    static Batch build(String personId, long repoId, String rawMd, JSONObject delta) throws Exception {
        if (utf8(delta.toString()).length > MAX_DELTA_BYTES) throw new IOException("Archive batch is too large.");
        if (integer(delta, "format") != 1 || !delta.getJSONObject("person").getString("id").equals(personId))
            throw new IOException("Archive identity does not match.");
        long from = integer(delta, "from_cursor"), through = integer(delta, "through_cursor");
        if (from < 0 || through < from) throw new IOException("Invalid archive cursor.");
        JSONObject person = delta.getJSONObject("person");
        byte[] source = utf8(rawMd);
        if (source.length > PersonDocument.MAX_BYTES || !sha256(source).equals(person.getString("source_sha256")))
            throw new IOException("Original person document does not match its fingerprint.");
        String profile = person.getString("profile");
        if (utf8(profile).length > PersonDocument.MAX_PROFILE_BYTES) throw new IOException("Person profile is too large.");
        String name = person.getString("name");
        if (name.isEmpty() || utf8(name).length > 256) throw new IOException("Invalid person name.");
        JSONObject persona = new JSONObject().put("format", 1).put("id", personId)
                .put("name", name).put("profile", profile).put("source_sha256", sha256(source));
        if (delta.has("icon_png_base64")) {
            String encoded = delta.getString("icon_png_base64");
            if (encoded.length() > 1400000) throw new IOException("Person icon is too large.");
            try { PersonRegistry.validateIcon(java.util.Base64.getDecoder().decode(encoded)); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid person icon encoding.", error); }
            persona.put("icon_png_base64", encoded);
        }
        byte[] settings = utf8(canonical(persona) + "\n");
        JSONArray records = delta.getJSONArray("records");
        ByteArrayOutputStream lines = new ByteArrayOutputStream();
        long cursor = from;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            long next = integer(record, "cursor");
            if (next != cursor + 1 || next > through || record.getJSONArray("events").length() == 0)
                throw new IOException("Archive contains a partial or unordered transaction.");
            lines.write(utf8(canonical(record) + "\n"));
            if (lines.size() > MAX_DELTA_BYTES) throw new IOException("Archive batch is too large.");
            cursor = next;
        }
        if (cursor != through) throw new IOException("Archive cursor does not end at a complete transaction.");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("person.md", source);
        files.put("persona.json", settings);
        String segment = "";
        String eventHash = sha256(lines.toByteArray());
        if (through > from) {
            segment = String.format(java.util.Locale.ROOT, "events/%08d/%020d-%020d.jsonl", from / 10000, from + 1, through);
            files.put(segment, lines.toByteArray());
        }
        String batchHash = sha256(utf8(personId + "\n" + repoId + "\n" + from + "\n" + through + "\n"
                + sha256(source) + "\n" + sha256(settings) + "\n" + eventHash));
        JSONObject manifest = marker(personId, repoId).put("complete", true).put("cursor", through)
                .put("source_sha256", sha256(source)).put("persona_sha256", sha256(settings))
                .put("events", new JSONObject().put("namespace", "events/").put("encoding", "utf8-jsonl")
                        .put("record", "one-transaction").put("cursor_key", "cursor"))
                .put("latest_batch", new JSONObject().put("from_cursor", from).put("through_cursor", through)
                        .put("path", segment).put("records_sha256", eventHash).put("sha256", batchHash));
        files.put("archive.json", utf8(canonical(manifest) + "\n"));
        return new Batch(from, through, manifest, files);
    }

    static JSONObject marker(String personId, long repoId) throws Exception {
        return new JSONObject().put("format", FORMAT).put("person_id", personId).put("repository_id", repoId)
                .put("complete", false).put("cursor", 0);
    }

    static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Long) && !(value instanceof Integer) && !(value instanceof Short) && !(value instanceof Byte))
            throw new IOException("Invalid archive integer.");
        return ((Number)value).longValue();
    }

    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            ArrayList<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String)value);
        return String.valueOf(value);
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
