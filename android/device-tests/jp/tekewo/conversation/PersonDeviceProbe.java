package jp.tekewo.conversation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;

/** Framework instrumentation under the app UID; uses only synthetic people and tokens. */
public final class PersonDeviceProbe extends Instrumentation {
    private static final String PACKAGE = "jp.tekewo.conversation";
    private static Class<?> activity;
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String probeId = arguments.getString("probe_id");
            check(probeId != null && UUID.fromString(probeId).toString().equals(probeId), "Valid synthetic probe ID");
            if ("true".equals(arguments.getString("cleanup_only"))) cleanupOwned(getTargetContext(), probeId);
            else if ("snapshot-existing".equals(arguments.getString("mode"))) existingData(getTargetContext(), false);
            else if ("verify-existing".equals(arguments.getString("mode"))) existingData(getTargetContext(), true);
            else if ("inspect-memory".equals(arguments.getString("mode"))) inspectMemory(getTargetContext());
            else runProbe(getTargetContext(), probeId);
            result.putString("stream", "PERSON_DEVICE_PROBE_PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", "PERSON_DEVICE_PROBE_FAIL\n" + trace);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void existingData(Context app, boolean verify) throws Exception {
        AtomicFile marker = new AtomicFile(new File(app.getNoBackupFilesDir(), "release-verification.json"));
        JSONObject baseline = null;
        if (verify) {
            try (FileInputStream input = marker.openRead()) { baseline = new JSONObject(new String(readBounded(input, 1024 * 1024), StandardCharsets.UTF_8)); }
            check(baseline.getInt("format") == 1 && baseline.getInt("uid") == android.os.Process.myUid(), "Installed upgrade retained the app UID");
        }
        File root = app.getFilesDir().getCanonicalFile();
        File registry = new File(root, "people-registry.json");
        check(!Files.isSymbolicLink(registry.toPath()) && (!registry.exists() || registry.length() <= 1024 * 1024), "Valid existing registry path");
        byte[] registryBytes = registry.exists() ? Files.readAllBytes(registry.toPath()) : new byte[0];
        String registryHash = digest(registryBytes);
        JSONObject index = registryBytes.length == 0 ? new JSONObject().put("people", new JSONArray()) : new JSONObject(new String(registryBytes, StandardCharsets.UTF_8));
        Map<String, File> directories = new LinkedHashMap<>();
        if (new File(root, "conversation.sqlite3").exists()) directories.put(digest(bytes("legacy")), root);
        File people = new File(root, "people");
        check(!Files.isSymbolicLink(people.toPath()), "Existing people directory is not a symbolic link");
        JSONArray persons = index.getJSONArray("people");
        JSONObject github = new JSONObject();
        for (int i = 0; i < persons.length(); i++) {
            String id = persons.getJSONObject(i).getString("id");
            check(UUID.fromString(id).toString().equals(id), "Existing person ID is valid");
            File directory = new File(people, id);
            check(!Files.isSymbolicLink(directory.toPath()) && directory.getCanonicalFile().getParentFile().equals(people.getCanonicalFile()), "Existing person directory stays under people");
            String identity = digest(bytes(id));
            check(!directories.containsKey(identity), "Existing person identities are unique");
            directories.put(identity, directory);
            github.put(identity, !new GitHubTokenStore(app, id).get().isEmpty());
        }
        JSONObject connection = new ConnectionStore(app).load();
        boolean access = !connection.optString("access_token").isEmpty(), refresh = !connection.optString("refresh_token").isEmpty();
        JSONObject databases = new JSONObject();
        long messages = 0, memories = 0;
        for (Map.Entry<String, File> entry : directories.entrySet()) {
            String identity = entry.getKey();
            if (verify && !baseline.getJSONObject("databases").has(identity)) continue;
            File database = new File(entry.getValue(), "conversation.sqlite3");
            check(database.isFile() && !Files.isSymbolicLink(database.toPath())
                    && database.getCanonicalFile().getParentFile().equals(entry.getValue().getCanonicalFile()), "Existing database path is valid");
            JSONObject old = verify ? baseline.getJSONObject("databases").getJSONObject(identity) : null;
            JSONObject tables = databaseDigests(database, old);
            databases.put(identity, tables);
            messages += tables.has("messages") ? tables.getJSONObject("messages").getLong("count") : 0;
            memories += tables.has("memories") ? tables.getJSONObject("memories").getLong("count") : 0;
        }
        if (verify) {
            check(registryHash.equals(baseline.getString("registry_sha256")), "Existing registry bytes are preserved");
            check(databases.length() == baseline.getJSONObject("databases").length(), "All baseline histories remain present");
            check(!baseline.getBoolean("chatgpt_access_present") || access, "Existing ChatGPT access credential remains readable");
            check(!baseline.getBoolean("chatgpt_refresh_present") || refresh, "Existing ChatGPT refresh credential remains readable");
            JSONObject oldGithub = baseline.getJSONObject("github_present");
            java.util.Iterator<String> ids = oldGithub.keys();
            while (ids.hasNext()) { String id = ids.next(); check(!oldGithub.getBoolean(id) || github.optBoolean(id), "Existing GitHub credential remains readable"); }
            report("PASS: release upgrade preserved app UID, registry, old SQLite rows, and readable credential presence (" + databases.length() + " histories, " + messages + " messages, " + memories + " memories)");
        } else {
            JSONObject snapshot = new JSONObject().put("format", 1).put("uid", android.os.Process.myUid()).put("registry_sha256", registryHash)
                    .put("databases", databases).put("github_present", github).put("chatgpt_access_present", access).put("chatgpt_refresh_present", refresh);
            byte[] encoded = bytes(snapshot.toString());
            check(encoded.length <= 1024 * 1024, "Release baseline marker stays within its read bound");
            FileOutputStream output = null;
            try {
                output = marker.startWrite(); output.write(encoded); marker.finishWrite(output); output = null;
                try (FileInputStream input = marker.openRead()) { check(MessageDigest.isEqual(encoded, readBounded(input, 1024 * 1024)), "Baseline marker was saved atomically"); }
            } finally { if (output != null) marker.failWrite(output); }
            report("PASS: saved release baseline containing only row digests/counts, UID, registry digest, and credential-presence booleans (" + databases.length() + " histories, " + messages + " messages, " + memories + " memories)");
        }
    }

    private void inspectMemory(Context app) throws Exception {
        android.content.SharedPreferences preferences = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        String model = preferences.getString("model_slug", "");
        report("MEMORY_CONFIG " + new JSONObject().put("model", model).put("effort", preferences.getString("reasoning_effort:" + model, ""))
                .put("paused", preferences.getBoolean("memory_paused", false)).put("status", preferences.getString("memory_status", "")));
        File root = app.getFilesDir().getCanonicalFile();
        JSONObject registry = new JSONObject(new String(Files.readAllBytes(new File(root, "people-registry.json").toPath()), StandardCharsets.UTF_8));
        String id = registry.getString("active_id");
        check(UUID.fromString(id).toString().equals(id), "Valid active person ID");
        File people = new File(root, "people").getCanonicalFile();
        File directory = new File(people, id).getCanonicalFile();
        check(directory.getParentFile().equals(people), "Memory inspection stays under people");
        File file = new File(directory, "conversation.sqlite3");
        check(file.isFile() && !Files.isSymbolicLink(file.toPath()), "Memory database is an ordinary file");
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            try (Cursor counts = database.rawQuery("SELECT (SELECT count(*) FROM messages),(SELECT coalesce(max(id),0) FROM messages),(SELECT count(*) FROM jobs WHERE processed=0),(SELECT coalesce(min(first_id),0) FROM jobs WHERE processed=0),(SELECT coalesce(max(end_id),0) FROM jobs WHERE processed=1),(SELECT count(*) FROM memories WHERE subject='episode' AND actor='conversation')", null)) {
                check(counts.moveToFirst(), "Memory counts are readable");
                report("MEMORY_STATUS " + new JSONObject().put("messages", counts.getLong(0)).put("latest_message_id", counts.getLong(1))
                        .put("pending_jobs", counts.getLong(2)).put("first_pending_message", counts.getLong(3))
                        .put("processed_through", counts.getLong(4)).put("indexed_scene_revisions", counts.getLong(5)));
            }
            try (Cursor rows = database.rawQuery("SELECT id,through_id,created_at,state FROM continuity_revisions ORDER BY id DESC LIMIT 10", null)) {
                while (rows.moveToNext()) {
                    JSONObject state = new JSONObject(rows.getString(3));
                    report("MEMORY_REVISION " + new JSONObject().put("id", rows.getLong(0)).put("through_id", rows.getLong(1))
                            .put("saved_at", rows.getString(2)).put("episodes", state.getJSONArray("episodes").length())
                            .put("threads", state.getJSONArray("threads").length()).put("bytes", rows.getString(3).getBytes(StandardCharsets.UTF_8).length));
                }
            }
        }
    }

    private static JSONObject databaseDigests(File file, JSONObject baseline) throws Exception {
        String[] tables = {"meta", "messages", "jobs", "memories", "memory_sources", "person_profiles", "source_documents", "memory_document_sources", "archive_transactions", "archive_events"};
        JSONObject result = new JSONObject();
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            for (String table : tables) {
                boolean exists;
                try (Cursor check = database.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) { exists = check.moveToFirst(); }
                if (baseline != null && !baseline.has(table)) continue;
                check(exists || baseline == null, "Baseline SQLite table still exists: " + table);
                if (!exists) continue;
                long limit;
                if (baseline != null) limit = baseline.getJSONObject(table).getLong("max_rowid");
                else try (Cursor maximum = database.rawQuery("SELECT coalesce(max(rowid),0) FROM " + table, null)) { check(maximum.moveToFirst(), "Read SQLite row boundary"); limit = maximum.getLong(0); }
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (Cursor rows = database.rawQuery("SELECT rowid,* FROM " + table + " WHERE rowid<=? ORDER BY rowid", new String[]{Long.toString(limit)})) {
                    // A journal event can approach 16 MiB of JSON, with UTF-16 storage requiring twice its bytes.
                    ((SQLiteCursor)rows).setWindow(new CursorWindow("release-verification", 64 * 1024 * 1024L));
                    for (String column : rows.getColumnNames()) hashValue(sha, Cursor.FIELD_TYPE_STRING, bytes(column));
                    while (rows.moveToNext()) {
                        count++;
                        for (int column = 0; column < rows.getColumnCount(); column++) {
                            int type = rows.getType(column); byte[] value;
                            if (type == Cursor.FIELD_TYPE_NULL) value = new byte[0];
                            else if (type == Cursor.FIELD_TYPE_BLOB) value = rows.getBlob(column);
                            else if (type == Cursor.FIELD_TYPE_INTEGER) value = bytes(Long.toString(rows.getLong(column)));
                            else if (type == Cursor.FIELD_TYPE_FLOAT) value = bytes(Long.toString(Double.doubleToLongBits(rows.getDouble(column))));
                            else value = bytes(rows.getString(column));
                            hashValue(sha, type, value);
                        }
                    }
                }
                String fingerprint = hex(sha.digest());
                if (baseline != null) check(count == baseline.getJSONObject(table).getLong("count")
                        && fingerprint.equals(baseline.getJSONObject(table).getString("sha256")), "Existing SQLite rows changed: " + table);
                result.put(table, new JSONObject().put("max_rowid", limit).put("count", count).put("sha256", fingerprint));
            }
        }
        return result;
    }

    private static void hashValue(MessageDigest digest, int type, byte[] value) {
        digest.update((byte)type);
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte)(value.length >>> shift));
        digest.update(value);
    }
    private static String digest(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return result.toString(); }
    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > limit) throw new IOException("Verification marker is too large"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }

    private void runProbe(Context app, String probeId) throws Exception {
        File files = app.getFilesDir().getCanonicalFile();
        for (String name : new String[] { "conversation.sqlite3", "conversation.sqlite3-wal", "conversation.sqlite3-shm", "conversation.sqlite3-journal", "notebook.json", "export" })
            check(!new File(files, name).exists(), "Old shared conversation data removed: " + name);
        check(!app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).contains("draft"), "Old shared draft removed");
        report("PASS: old shared conversation, memory, export staging and draft removed");
        File root = new File(files, "person-device-probe." + probeId).getCanonicalFile();
        check(root.getParentFile().equals(files) && !root.exists() && root.mkdir(), "Fresh probe directory");
        String idA = UUID.randomUUID().toString(), idB = UUID.randomUUID().toString();
        GitHubTokenStore tokenA = new GitHubTokenStore(app, idA), tokenB = new GitHubTokenStore(app, idB);
        File fileA = new File(app.getNoBackupFilesDir(), "github-token." + idA + ".v1");
        File fileB = new File(app.getNoBackupFilesDir(), "github-token." + idB + ".v1");
        check(!fileA.exists() && !fileB.exists(), "Fresh token files");
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        check(!keys.containsAlias(alias(idA)) && !keys.containsAlias(alias(idB)), "Fresh token aliases");
        Files.write(new File(root, ".probe-owner.json").toPath(), bytes(new JSONObject().put("format", "conversation-synthetic-device-probe-v1")
                .put("probe_id", probeId).put("person_a", idA).put("person_b", idB).toString()));
        try {
            String fakeA = "github_pat_DEVICE_PROBE_SYNTHETIC_A_" + idA.replace("-", "");
            String fakeB = "github_pat_DEVICE_PROBE_SYNTHETIC_B_" + idB.replace("-", "");
            check(tokenA.get().isEmpty() && tokenB.get().isEmpty(), "Missing credentials return empty");
            tokenA.set(fakeA); tokenB.set(fakeB);
            check(tokenA.get().equals(fakeA) && tokenB.get().equals(fakeB), "Per-person token round trip");
            byte[] encryptedA = Files.readAllBytes(fileA.toPath()), encryptedB = Files.readAllBytes(fileB.toPath());
            check(!new String(encryptedA, StandardCharsets.ISO_8859_1).contains(fakeA), "Token is encrypted on disk");
            Files.write(fileB.toPath(), encryptedA);
            try { tokenB.get(); throw new AssertionError("Cross-person ciphertext accepted"); }
            catch (IOException expected) { check(expected.getCause() == null && !expected.toString().contains("github_pat_"), "Safe credential error"); }
            finally { Files.write(fileB.toPath(), encryptedB); }
            tokenA.clear();
            check(tokenA.get().isEmpty() && tokenB.get().equals(fakeB), "Clearing one token preserves the other");
            report("PASS: Android Keystore encryption, per-person isolation, AAD rejection, isolated clear");

            System.load(new File(app.getApplicationInfo().nativeLibraryDir, "libconversation.so").getPath());
            activity = Class.forName(PACKAGE + ".MainActivity");
            File directoryA = new File(root, idA), directoryB = new File(root, idB), bad = new File(root, UUID.randomUUID().toString());
            check(!directoryA.exists() && !directoryB.exists() && !bad.exists(), "Native operations receive fresh absent person directories");
            success(call("nativeInit", new Class<?>[]{byte[].class, byte[].class, byte[].class}, bytes(directoryA.getPath()),
                    new byte[0], new byte[0]));
            JSONObject payloadA = person(idA, "Probe A", "私は静かに話す。DEVICE_PROFILE_A");
            JSONObject payloadB = person(idB, "Probe B", "俺は快活に話す。DEVICE_PROFILE_B");
            switchPerson(directoryA, payloadA.toString());
            JSONObject contextA = context();
            check(contextA.getJSONObject("person").getString("id").equals(idA), "Person A is always in context");
            check(contextA.getJSONArray("messages").length() == 0, "Imported source does not invent conversation");
            check(contextA.toString().contains("reported_handover") && contextA.toString().contains("imported_sources"), "Source provenance is retained");
            String unicodeMessage = "DEVICE_MESSAGE_A 日本語🎾";
            check(add(unicodeMessage) == 1, "Person A append");
            JSONObject deltaA = archive();
            check(deltaA.getJSONObject("person").getString("id").equals(idA), "Person A archive identity");
            boolean originalSource = false, documentLink = false;
            JSONArray records = deltaA.getJSONArray("records");
            for (int i = 0; i < records.length(); i++) {
                JSONArray events = records.getJSONObject(i).getJSONArray("events");
                for (int j = 0; j < events.length(); j++) {
                    JSONObject event = events.getJSONObject(j);
                    if (event.getString("table").equals("source_documents"))
                        originalSource = event.getJSONObject("row").getString("raw_markdown").equals(payloadA.getString("source_md"));
                    if (event.getString("table").equals("memory_document_sources")) documentLink = true;
                }
            }
            check(originalSource && documentLink, "Original source and source links are archived");

            switchPerson(directoryB, payloadB.toString());
            JSONObject contextB = context();
            check(contextB.getJSONObject("person").getString("id").equals(idB) && contextB.getJSONArray("messages").length() == 0
                    && !contextB.toString().contains("DEVICE_PROFILE_A"), "Person B is isolated");
            check(add("DEVICE_MESSAGE_B") == 1, "Person B independent IDs");
            JSONObject invalid = new JSONObject(payloadA.toString()).put("source_sha256", new String(new char[64]).replace('\0', '0'));
            String failure = (String)call("nativeSwitchPerson", new Class<?>[]{byte[].class, byte[].class}, bytes(bad.getPath()), bytes(invalid.toString()));
            check(!failure.isEmpty() && context().getJSONObject("person").getString("id").equals(idB), "Failed switch restores previous person");
            check(add("DEVICE_MESSAGE_B_AFTER_FAILED_SWITCH") == 2, "Previous history stays writable");
            switchPerson(directoryA, "");
            JSONObject restoredA = context();
            check(restoredA.getJSONObject("person").getString("id").equals(idA)
                    && restoredA.getJSONArray("messages").length() == 1 && restoredA.toString().contains("DEVICE_MESSAGE_A")
                    && !restoredA.toString().contains("DEVICE_MESSAGE_B"), "Switching back preserves isolated history");
            check(archive().getLong("through_cursor") == deltaA.getLong("through_cursor"), "Switching does not duplicate journal records");
            report("PASS: Android JNI person import, source retention, incremental archive, two-person switching, failed-switch rollback");
            success(call("nativeExport", new Class<?>[]{}));
            File export = new File(directoryA, "export");
            JSONObject metadata = new JSONObject(new String(Files.readAllBytes(new File(export, "metadata.json").toPath()), StandardCharsets.UTF_8));
            check(metadata.getJSONObject("person").getString("id").equals(idA) && metadata.getInt("messages") == 1
                    && metadata.getInt("documents") == 1 && metadata.getInt("document_sources") == 1, "Native export metadata parses and counts retained data");
            ByteArrayOutputStream zipped = new ByteArrayOutputStream(); BackupArchive.write(export, zipped);
            boolean sawMessage = false, sawSource = false, sawMetadata = false;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()))) {
                ZipEntry entry; byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    ByteArrayOutputStream content = new ByteArrayOutputStream(); int count;
                    while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
                    String text = new String(content.toByteArray(), StandardCharsets.UTF_8);
                    if (entry.getName().equals("metadata.json")) { new JSONObject(text); sawMetadata = true; }
                    else {
                        check(entry.getName().startsWith(metadata.getString("snapshot") + "/"), "ZIP contains only the selected snapshot");
                        for (String line : text.split("\n")) {
                            if (line.isEmpty()) continue;
                            JSONObject row = new JSONObject(line);
                            if (entry.getName().contains("/messages-")) { check(row.getString("content").equals(unicodeMessage), "ZIP preserves exact Unicode"); sawMessage = true; }
                            if (entry.getName().contains("/source_documents-")) { check(row.getString("raw_markdown").equals(payloadA.getString("source_md")), "ZIP preserves original source"); sawSource = true; }
                        }
                    }
                }
            }
            check(sawMetadata && sawMessage && sawSource, "ZIP includes metadata, conversation, and original source");
            report("PASS: native export metadata JSON and BackupArchive ZIP preserve Unicode and original source");
            probeExpandedPerson(root);
            probeDigestRules(root);
        } finally {
            cleanupOwned(app, probeId);
            check(!root.exists() && !fileA.exists() && !fileB.exists(), "Probe files removed");
            keys.load(null);
            check(!keys.containsAlias(alias(idA)) && !keys.containsAlias(alias(idB)), "Probe keys removed");
            report("PASS: cleaned only synthetic UUID directories and token aliases");
        }
    }

    private void probeDigestRules(File root) throws Exception {
        File file = new File(root, "release-digest-selfcheck.sqlite3");
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            database.execSQL("CREATE TABLE jobs(id INTEGER PRIMARY KEY,processed INTEGER NOT NULL)");
            database.execSQL("INSERT INTO jobs VALUES(1,0)");
        }
        JSONObject baseline = databaseDigests(file, null);
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) { database.execSQL("INSERT INTO jobs VALUES(2,0)"); }
        check(databaseDigests(file, baseline).getJSONObject("jobs").getLong("count") == 1, "Row-prefix verification permits appended rows");
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, null)) { database.execSQL("UPDATE jobs SET processed=1 WHERE id=1"); }
        try { databaseDigests(file, baseline); throw new IOException("Changed old rows were not detected"); }
        catch (AssertionError expected) { check(expected.getMessage().equals("Existing SQLite rows changed: jobs"), "Changed-row failure identifies only its table"); }
        report("PASS: release row-prefix digests permit appended rows and detect changed old rows");
    }

    private void probeExpandedPerson(File root) throws Exception {
        PersonDocument document = expandedDocument();
        String id = UUID.randomUUID().toString();
        JSONObject payload = document.importPayload(id);
        JSONArray seeds = payload.getJSONArray("seeds");
        check(bytes(document.rawMarkdown).length == PersonDocument.MAX_BYTES
                && bytes(document.profile).length == PersonDocument.MAX_PROFILE_BYTES
                && seeds.length() == PersonDocument.MAX_SEEDS
                && bytes(seeds.getJSONObject(0).getString("text")).length == PersonDocument.MAX_SEED_BYTES, "One parsed fixture reaches all four expanded limits exactly");
        File directory = new File(root, id);
        switchPerson(directory, payload.toString());
        JSONObject context = context();
        check(context.getJSONObject("person").getString("profile").equals(document.profile)
                && context.getJSONArray("messages").length() == 0, "Full 128 KiB person profile is always present without invented messages");
        String encoded = (String)call("nativeArchiveDelta", new Class<?>[]{long.class}, 0L);
        check(bytes(encoded).length > 2 * 1024 * 1024, "Expanded native delta exceeds the former 2 MiB limit");
        JSONObject delta = new JSONObject(encoded);
        int memories = 0, links = 0; boolean source = false, fullSeed = false;
        JSONArray records = delta.getJSONArray("records");
        for (int i = 0; i < records.length(); i++) {
            JSONArray events = records.getJSONObject(i).getJSONArray("events");
            for (int j = 0; j < events.length(); j++) {
                JSONObject event = events.getJSONObject(j), row = event.getJSONObject("row");
                String table = event.getString("table");
                if (table.equals("source_documents")) source = row.getString("raw_markdown").equals(document.rawMarkdown);
                if (table.equals("memory_document_sources")) links++;
                if (table.equals("memories")) { memories++; fullSeed |= row.getString("text").equals(seeds.getJSONObject(0).getString("text")); }
            }
        }
        check(source && fullSeed && memories == PersonDocument.MAX_SEEDS && links == PersonDocument.MAX_SEEDS,
                "Expanded journal retains original source, 16 KiB seed, and every seed source link");
        GitArchiveFiles.Batch git = GitArchiveFiles.build(id, 424242, document.rawMarkdown, delta);
        check(git.manifest.getLong("cursor") == delta.getLong("through_cursor")
                && java.util.Arrays.equals(git.files.get("person.md"), bytes(document.rawMarkdown)), "Installed Git archive writer accepts the expanded native delta");
        success(call("nativeExport", new Class<?>[]{}));
        File export = new File(directory, "export");
        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(new File(export, "metadata.json").toPath()), StandardCharsets.UTF_8));
        check(metadata.getJSONObject("person").getString("profile").equals(document.profile)
                && metadata.getInt("memories") == PersonDocument.MAX_SEEDS && metadata.getInt("document_sources") == PersonDocument.MAX_SEEDS,
                "Expanded native export metadata retains the full profile and seed counts");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); BackupArchive.write(export, output);
        int zipMemories = 0; boolean zipSource = false, zipSeed = false, zipMetadata = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream(); int count;
                while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
                String text = new String(content.toByteArray(), StandardCharsets.UTF_8);
                if (entry.getName().equals("metadata.json")) {
                    zipMetadata = new JSONObject(text).getJSONObject("person").getString("profile").equals(document.profile);
                    continue;
                }
                check(entry.getName().startsWith(metadata.getString("snapshot") + "/"), "Expanded ZIP contains only its selected snapshot");
                for (String line : text.split("\n")) {
                    if (line.isEmpty()) continue;
                    JSONObject row = new JSONObject(line);
                    if (entry.getName().contains("/source_documents-")) zipSource = row.getString("raw_markdown").equals(document.rawMarkdown);
                    if (entry.getName().contains("/memories-")) { zipMemories++; zipSeed |= row.getString("text").equals(seeds.getJSONObject(0).getString("text")); }
                }
            }
        }
        check(zipMetadata && zipSource && zipSeed && zipMemories == PersonDocument.MAX_SEEDS, "Expanded ZIP preserves all four limits without truncation");
        report("PASS: real parser/JNI/Git writer/export ZIP preserve exact 2 MiB source, 128 KiB profile, 2048 seeds, and 16 KiB seed");
    }

    private static PersonDocument expandedDocument() {
        String profileStart = "## 基本プロフィール\n- 名前: Probe C\n## しゃべり方\n";
        String profileEnd = "\n## 考え方\n## ユーザーとの関係\n## 不明・推測・矛盾\n";
        String profilePadding = utf8Padding(PersonDocument.MAX_PROFILE_BYTES - bytes(profileStart + profileEnd).length);
        String seedPrefix = "引き継ぎ資料に記載（区分未指定）: 項目0: 本人";
        StringBuilder ongoing = new StringBuilder("- 項目0: 本人").append(utf8Padding(PersonDocument.MAX_SEED_BYTES - bytes(seedPrefix).length)).append('\n');
        for (int i = 1; i < PersonDocument.MAX_SEEDS; i++) ongoing.append("- 項目").append(i).append(": 本人の合成試験記録").append(i).append('\n');
        String before = "形式: conversation-person-v1\n<!--";
        String after = "-->\n" + profileStart + profilePadding + "\n## 考え方\n## ユーザーとの関係\n## これまでの出来事\n## 続いていること\n"
                + ongoing + "## 不明・推測・矛盾";
        return PersonDocument.parse(bytes(before + utf8Padding(PersonDocument.MAX_BYTES - bytes(before + after).length) + after));
    }

    private static String utf8Padding(int byteCount) {
        check(byteCount >= 0, "Fixture padding remains within its limit");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < byteCount / 3; i++) result.append('あ');
        for (int i = 0; i < byteCount % 3; i++) result.append('x');
        return result.toString();
    }

    private void report(String line) {
        Bundle status = new Bundle(); status.putString("stream", line + "\n"); sendStatus(0, status);
    }

    private static void cleanupOwned(Context app, String probeId) throws Exception {
        File files = app.getFilesDir().getCanonicalFile();
        File root = new File(files, "person-device-probe." + probeId).getCanonicalFile();
        check(UUID.fromString(probeId).toString().equals(probeId) && root.getParentFile().equals(files), "Owned cleanup root");
        if (!root.exists()) return;
        File marker = new File(root, ".probe-owner.json");
        check(marker.isFile() && marker.length() < 4096 && !Files.isSymbolicLink(marker.toPath()), "Owned cleanup marker");
        JSONObject owner = new JSONObject(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
        check(owner.getString("format").equals("conversation-synthetic-device-probe-v1") && owner.getString("probe_id").equals(probeId), "Matching cleanup identity");
        String a = owner.getString("person_a"), b = owner.getString("person_b");
        check(!a.equals(b) && UUID.fromString(a).toString().equals(a) && UUID.fromString(b).toString().equals(b), "Owned synthetic person IDs");
        try { new GitHubTokenStore(app, a).clear(); } finally { new GitHubTokenStore(app, b).clear(); }
        deleteOwned(root, root);
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        check(!root.exists() && !keys.containsAlias(alias(a)) && !keys.containsAlias(alias(b)), "Owned probe data and keys removed");
    }

    private static String alias(String id) { return "conversation.github.credentials.v1." + id; }
    private static JSONObject person(String id, String name, String profile) throws Exception {
        String source = "# " + name + "\nこれは実機試験用の合成引き継ぎ資料です。\n";
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes(source))) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return new JSONObject().put("person_id", id).put("name", name).put("profile", profile).put("source_md", source)
                .put("source_sha256", sha.toString()).put("seeds", new JSONArray().put(new JSONObject().put("kind", "fact")
                        .put("actor", "user").put("subject", "user").put("key", "device-probe").put("text", "合成資料からの試験用記憶")
                        .put("scope", "real").put("certainty", "explicit").put("section", "試験資料")));
    }
    private static JSONObject context() throws Exception { return new JSONObject((String)call("nativeContext", new Class<?>[]{byte[].class}, bytes("unrelated"))); }
    private static JSONObject archive() throws Exception { return new JSONObject((String)call("nativeArchiveDelta", new Class<?>[]{long.class}, 0L)); }
    private static long add(String text) throws Exception { return (Long)call("nativeAddMessage", new Class<?>[]{byte[].class, byte[].class}, bytes("user"), bytes(text)); }
    private static void switchPerson(File path, String payload) throws Exception {
        success(call("nativeSwitchPerson", new Class<?>[]{byte[].class, byte[].class}, bytes(path.getPath()), bytes(payload)));
    }
    private static Object call(String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method = activity.getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(null, values);
    }
    private static void success(Object result) { check(result instanceof String && ((String)result).isEmpty(), "Native operation succeeded: " + result); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String description) { if (!value) throw new AssertionError(description); }
    private static void deleteOwned(File root, File file) throws Exception {
        String canonicalRoot = root.getCanonicalPath(), canonical = file.getCanonicalPath();
        if ((!canonical.equals(canonicalRoot) && !canonical.startsWith(canonicalRoot + File.separator)) || Files.isSymbolicLink(file.toPath()))
            throw new IOException("Probe cleanup path escaped its own directory");
        if (file.isDirectory()) {
            File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot list probe directory");
            for (File child : children) deleteOwned(root, child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove synthetic probe file");
    }
}
