package jp.tekewo.conversation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;

public final class BackupArchiveTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("conversation-backup-").toFile();
        try {
            File snapshot = new File(root, "snapshot-current"); assert snapshot.mkdir();
            File old = new File(root, "snapshot-old"); assert old.mkdir();
            Files.write(new File(old, "stale.jsonl").toPath(), "old".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(root, "metadata.json").toPath(), "{\"snapshot\":\"snapshot-current\"}".getBytes(StandardCharsets.UTF_8));
            String original = "{\"content\":\"こんにちは🎾\"}\n";
            Files.write(new File(snapshot, "messages-0001.jsonl").toPath(), original.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream data = new ByteArrayOutputStream(); BackupArchive.write(root, data);
            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data.toByteArray()))) {
                ZipEntry entry; byte[] buffer = new byte[4096];
                while ((entry = zip.getNextEntry()) != null) {
                    names.add(entry.getName()); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int count; while ((count = zip.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    if (entry.getName().endsWith(".jsonl")) assert original.equals(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                }
            }
            assert names.size() == 2 && names.contains("metadata.json") && names.contains("snapshot-current/messages-0001.jsonl");
            char[] profile = new char[128 * 1024]; java.util.Arrays.fill(profile, '\u0001');
            String metadata = new org.json.JSONObject().put("snapshot", "snapshot-current")
                    .put("person", new org.json.JSONObject().put("profile", new String(profile))).toString();
            assert metadata.getBytes(StandardCharsets.UTF_8).length > 65536;
            Files.write(new File(root, "metadata.json").toPath(), metadata.getBytes(StandardCharsets.UTF_8));
            BackupArchive.write(root, new ByteArrayOutputStream());
            Files.write(new File(root, "metadata.json").toPath(), new byte[1024 * 1024 + 1]);
            try { BackupArchive.write(root, new ByteArrayOutputStream()); throw new AssertionError("Oversized metadata accepted"); }
            catch (IOException expected) {}
            Files.write(new File(root, "metadata.json").toPath(), "{\"snapshot\":\"../outside\"}".getBytes(StandardCharsets.UTF_8));
            try { BackupArchive.write(root, new ByteArrayOutputStream()); throw new AssertionError(); } catch (IOException expected) {}
            System.out.println("PASS: backup current snapshot only, exact Unicode data and traversal rejection");
        } finally { remove(root); }
    }
    private static void remove(File file) throws IOException {
        if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
        Files.delete(file.toPath());
    }
}
