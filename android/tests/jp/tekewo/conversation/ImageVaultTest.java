package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class ImageVaultTest {
    static void install() {
        Map<String, byte[]> keys = new HashMap<>();
        ImageVault.keys = new ImageVault.Keys() {
            public byte[] get(File person, boolean create) throws IOException {
                String id = person.getName();
                if (!keys.containsKey(id) && create) keys.put(id, ImageVault.newKey());
                if (!keys.containsKey(id)) throw new IOException("Missing key");
                return keys.get(id).clone();
            }
            public void requireRecovery(File person) { }
        };
    }
    public static void main(String[] args) throws Exception {
        install();
        byte[] key = ImageVault.newKey(), original = "private face image".getBytes("UTF-8");
        byte[] sealed = ImageVault.seal(key, "person", "icon.png", original);
        assert ImageVault.encrypted(sealed);
        assert !Arrays.equals(sealed, ImageVault.seal(key, "person", "icon.png", original));
        assert Arrays.equals(original, ImageVault.open(key, "person", "icon.png", sealed));
        reject(() -> ImageVault.open(key, "other", "icon.png", sealed));
        reject(() -> ImageVault.open(key, "person", "other.png", sealed));
        reject(() -> ImageVault.open(ImageVault.newKey(), "person", "icon.png", sealed));
        byte[] damaged = sealed.clone(); damaged[damaged.length - 1] ^= 1;
        reject(() -> ImageVault.open(key, "person", "icon.png", damaged));
        reject(() -> ImageVault.open(key, "person", "icon.png", original));
        File person = Files.createTempDirectory("vault-").toFile();
        try {
            byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            AttachmentFiles.Ref ref = AttachmentFiles.save(person, "顔", "png", new ByteArrayInputStream(png));
            File image = AttachmentFiles.resolve(person, ref.fileName);
            assert ImageVault.encrypted(Files.readAllBytes(image.toPath())) : "Image persisted as plaintext";
            assert Arrays.equals(png, AttachmentFiles.read(person, ref));
            Files.write(image.toPath(), png);
            assert Arrays.equals(png, AttachmentFiles.read(person, ref));
            assert ImageVault.encrypted(Files.readAllBytes(image.toPath())) : "Legacy image not migrated";
            Properties recovery = ImageVault.recovery(person.getName(), ImageVault.keys.get(person, false));
            File recoveryFile = new File(person, "recovery.key");
            try (OutputStream output = new FileOutputStream(recoveryFile)) { recovery.store(output, "Test recovery"); }
            File restored = new File(person, "restored");
            File backup = new File(person, "backup"); assert backup.mkdir();
            Files.write(new File(backup, ref.fileName + ".enc").toPath(), ImageVault.backup(person, image));
            JSONObject delta = GitArchiveFilesTest.delta(0, 1);
            delta.getJSONArray("records").getJSONObject(0).getJSONArray("events").getJSONObject(0).getJSONObject("row").put("text", ref.markdown());
            AttachmentFiles.addToDelta(person, delta);
            assert !delta.has("attachment_files");
            GitArchiveFiles.Batch git = GitArchiveFiles.build(GitArchiveFilesTest.ID, 42, GitArchiveFilesTest.RAW, delta);
            assert !git.files.containsKey("media/" + ref.fileName);
            byte[] remote = git.files.get("media/" + ref.fileName + ".enc");
            assert ImageVault.encrypted(remote);
            assert Arrays.equals(png, ImageVault.open(ImageVault.keys.get(person, false), person.getName(), ref.fileName, remote));
            File export = new File(person, "export"), snapshot = new File(export, "snapshot-current"); assert snapshot.mkdirs();
            Files.write(new File(export, "metadata.json").toPath(), "{\"snapshot\":\"snapshot-current\"}".getBytes("UTF-8"));
            Files.write(new File(snapshot, "messages-000000.jsonl").toPath(), (new JSONObject().put("content", ref.markdown()) + "\n").getBytes("UTF-8"));
            AttachmentFiles.backup(person, export);
            BackupArchive writer=new BackupArchive(person,new File(person,"export-new"));
            writer.writeChunk("messages-000000.jsonl",(new JSONObject().put("content",ref.markdown())+"\n").getBytes("UTF-8"));
            writer.finish();
            ByteArrayOutputStream zipped=new ByteArrayOutputStream(); BackupArchive.write(writer.directory,zipped);
            assert zipped.size()>0; writer.close();

            File oldSnapshot = new File(export, "old-snapshot"); assert oldSnapshot.mkdir();
            Files.write(new File(oldSnapshot, "icon.png").toPath(), png);
            ImageVault.migrateExports(person);
            assert !new File(oldSnapshot, "icon.png").exists();
            assert ImageVault.encrypted(Files.readAllBytes(new File(oldSnapshot, "icon.png.enc").toPath()));
            Files.write(new File(oldSnapshot, "icon.png").toPath(), png);
            ImageVault.migrateExports(person);
            assert !new File(oldSnapshot, "icon.png").exists() : "Interrupted export migration was not resumed";
            Files.write(new File(oldSnapshot, "icon.png").toPath(), png);
            Files.write(new File(oldSnapshot, ref.fileName).toPath(), png);
            File[] exports = oldSnapshot.listFiles((dir, name) -> ImageVault.image(name));
            Files.write(exports[0].toPath(), new byte[]{'C', 'I', 'M', 'G', 1});
            reject(() -> ImageVault.migrateExports(person));
            assert !exports[1].exists() && new File(exports[1].getPath() + ".enc").isFile() : "Broken export stranded another photo";
            ImageVault.main(new String[] {recoveryFile.getPath(), backup.getPath(), restored.getPath()});
            assert Arrays.equals(png, Files.readAllBytes(new File(restored, ref.fileName).toPath()));
            File standalone = new File(person, "standalone-recovery");
            Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                    "android/src/jp/tekewo/conversation/ImageVault.java", recoveryFile.getPath(), backup.getPath(), standalone.getPath()).inheritIO().start();
            assert process.waitFor() == 0 : "Standalone recovery tool failed";
            assert Arrays.equals(png, Files.readAllBytes(new File(standalone, ref.fileName).toPath()));
            reject(() -> ImageVault.main(new String[] {recoveryFile.getPath(), backup.getPath(), restored.getPath()}));
            ImageVault.Keys working = ImageVault.keys;
            ImageVault.keys = new ImageVault.Keys() {
                public byte[] get(File directory, boolean create) throws IOException { throw new IOException("Locked"); }
                public void requireRecovery(File directory) throws IOException { throw new IOException("No recovery"); }
            };
            reject(() -> AttachmentFiles.read(person, ref));
            assert ImageVault.encrypted(Files.readAllBytes(image.toPath()));
            ImageVault.keys = working;
            assert Arrays.equals(png, AttachmentFiles.read(person, ref));
            byte[] otherPng = Arrays.copyOf(png, png.length + 1);
            AttachmentFiles.Ref other = AttachmentFiles.save(person, "別の写真", "png", new ByteArrayInputStream(otherPng));
            File[] ordered = AttachmentFiles.directory(person).listFiles((dir, name) -> ImageVault.image(name));
            assert ordered.length == 2;
            File broken = ordered[0], healthy = ordered[1];
            byte[] healthyBytes = AttachmentFiles.read(person, new AttachmentFiles.Ref("", healthy.getName()));
            Files.write(broken.toPath(), new byte[]{1});
            Files.write(healthy.toPath(), healthyBytes);
            reject(() -> AttachmentFiles.migrateImages(person));
            assert ImageVault.encrypted(Files.readAllBytes(healthy.toPath())) : "One corrupt image stranded a healthy plaintext image";
            System.out.println("PASS: encrypted images, nonce uniqueness, tamper/wrong-key rejection, migration and offline recovery");
        } finally { remove(person); }
    }
    static void reject(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Unsafe operation accepted"); } catch (IOException expected) { }
    }
    interface Action { void run() throws Exception; }
    static void remove(File file) throws IOException {
        if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
        Files.delete(file.toPath());
    }
}
