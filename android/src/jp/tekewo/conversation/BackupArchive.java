package jp.tekewo.conversation;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BackupArchive {
    static void write(File directory, OutputStream destination) throws Exception {
        File root = directory.getCanonicalFile();
        File metadata = new File(root, "metadata.json");
        if (metadata.length() == 0 || metadata.length() > 1024 * 1024) throw new IOException("Invalid backup metadata");
        String manifest = new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8);
        File snapshot = new File(root, new JSONObject(manifest).getString("snapshot"));
        validate(root, snapshot);
        if (!snapshot.isDirectory()) throw new IOException("Backup snapshot missing");
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            add(root, metadata, zip, buffer);
            add(root, snapshot, zip, buffer);
        }
    }
    private static void validate(File root, File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Invalid backup path");
    }
    private static void add(File root, File file, ZipOutputStream zip, byte[] buffer) throws Exception {
        validate(root, file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Backup directory could not be read");
            for (File child : children) add(root, child, zip, buffer);
            return;
        }
        zip.putNextEntry(new ZipEntry(root.toPath().relativize(file.toPath()).toString().replace('\\', '/')));
        try (FileInputStream stream = new FileInputStream(file)) {
            int count; while ((count = stream.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
