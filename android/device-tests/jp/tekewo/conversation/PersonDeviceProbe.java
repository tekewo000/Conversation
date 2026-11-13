package jp.tekewo.conversation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipFile;
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
            else runProbe(getTargetContext(), probeId);
            result.putString("stream", "PERSON_DEVICE_PROBE_PASS\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", "PERSON_DEVICE_PROBE_FAIL\n" + trace);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runProbe(Context app, String probeId) throws Exception {
        File files = app.getFilesDir().getCanonicalFile();
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
            try (ZipFile apk = new ZipFile(app.getApplicationInfo().sourceDir)) {
                success(call("nativeInit", new Class<?>[]{byte[].class, byte[].class, byte[].class}, bytes(directoryA.getPath()),
                        asset(apk, "NotoSansJP-Regular.ttf"), asset(apk, "NotoEmoji-Regular.ttf")));
            }
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
        } finally {
            cleanupOwned(app, probeId);
            check(!root.exists() && !fileA.exists() && !fileB.exists(), "Probe files removed");
            keys.load(null);
            check(!keys.containsAlias(alias(idA)) && !keys.containsAlias(alias(idB)), "Probe keys removed");
            report("PASS: cleaned only synthetic UUID directories and token aliases");
        }
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
    private static byte[] asset(ZipFile apk, String name) throws Exception {
        try (InputStream input = apk.getInputStream(apk.getEntry("assets/" + name)); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 32 * 1024 * 1024) throw new IOException("Font asset is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
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
