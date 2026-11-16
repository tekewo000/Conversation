package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Content-addressed files stay with their person; message text contains portable relative references. */
final class AttachmentFiles {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    static final Pattern REFERENCES = Pattern.compile("!?\\[([^\\]\\r\\n]{0,160})\\]\\(attachment:([0-9a-f]{64}\\.(?:jpg|png|webp|gif|mp4|webm|mov|pdf|txt))\\)");
    static final class Ref {
        final String name, fileName;
        Ref(String name, String fileName) { this.name = name; this.fileName = fileName; }
        boolean image() { return fileName.matches(".*\\.(jpg|png|webp|gif)"); }
        boolean video() { return fileName.matches(".*\\.(mp4|webm|mov)"); }
        String markdown() { return (image() ? "!" : "") + "[" + name + "](attachment:" + fileName + ")"; }
    }
    static List<Ref> references(String text) {
        List<Ref> result = new ArrayList<>();
        Matcher match = REFERENCES.matcher(text);
        while (match.find()) result.add(new Ref(match.group(1), match.group(2)));
        return result;
    }
    static String withoutReferences(String text) {
        Matcher refs = REFERENCES.matcher(text);
        return refs.find() ? refs.replaceAll("").trim() : text;
    }
    static String label(String value) {
        String clean = value == null ? "添付ファイル" : value.replaceAll("[\\[\\]()`\\r\\n\\\\]", "_");
        return clean.isEmpty() ? "添付ファイル" : clean.substring(0, clean.offsetByCodePoints(0, Math.min(120, clean.codePointCount(0, clean.length()))));
    }
    static File directory(File person) throws IOException {
        if (!person.isDirectory() || Files.isSymbolicLink(person.toPath())) throw new IOException("人物の保存先が見つかりません。");
        File result = new File(person, "media");
        if (Files.isSymbolicLink(result.toPath()) || (!result.isDirectory() && !result.mkdir() && !result.isDirectory()))
            throw new IOException("添付ファイルの保存先を開けません。");
        return result.getCanonicalFile();
    }
    static File resolve(File person, String name) throws IOException {
        if (!name.matches("[0-9a-f]{64}\\.(jpg|png|webp|gif|mp4|webm|mov|pdf|txt)"))
            throw new IOException("添付ファイルの参照が不正です。");
        File root = directory(person), file = new File(root, name);
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().getParentFile().equals(root))
            throw new IOException("添付ファイルの参照が不正です。");
        return file;
    }
    static Ref save(File person, String name, String extension, InputStream input) throws Exception {
        if (input == null) throw new IOException("添付ファイルを開けません。");
        if (!extension.matches("jpg|png|webp|gif|mp4|webm|mov|pdf|txt")) throw new IOException("このファイル形式には対応していません。");
        File temporary = File.createTempFile("attachment-", ".pending", directory(person));
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            long total = 0;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("添付ファイルは1件25MiB以内にしてください。");
                    hash.update(buffer, 0, count);
                    if (image == null) output.write(buffer, 0, count); else image.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total == 0) throw new IOException("空のファイルは添付できません。");
            StringBuilder digest = new StringBuilder();
            for (byte value : hash.digest()) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
            String fileName = digest + "." + extension;
            File target = resolve(person, fileName);
            if (target.exists()) verify(target);
            else {
                if (image != null) try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(ImageVault.image(fileName) ? ImageVault.encode(person, fileName, image.toByteArray())
                            : encode(person,fileName,image.toByteArray())); output.getFD().sync();
                }
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            return new Ref(label(name), fileName);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static byte[] encode(File person,String fileName,byte[] bytes) throws IOException {
        byte[] key=ImageVault.keys.get(person,true);
        try { return DataVault.seal(key,person.getName(),"file","media/"+fileName,bytes); }
        finally { Arrays.fill(key,(byte)0); }
    }
    static byte[] read(File person, Ref ref) throws Exception {
        File file = resolve(person, ref.fileName);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES + DataVault.OVERHEAD)
            throw new IOException("添付ファイルが見つからないか、大きすぎます。");
        byte[] bytes = ref.image() ? ImageVault.read(person, file) : DataFiles.read(person,file);
        if (bytes.length > MAX_BYTES || !ref.fileName.startsWith(GitArchiveFiles.sha256(bytes) + "."))
            throw new IOException("添付ファイルの内容を確認できません。");
        if (ref.image()) ImageVault.protect(person, file, bytes);
        return bytes;
    }
    static void verify(File file) throws Exception {
        read(file.getParentFile().getParentFile(), new Ref("", file.getName()));
    }
    static void backup(File person, File export) throws Exception {
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(export, "metadata.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        File snapshot = new File(export, manifest.getString("snapshot")).getCanonicalFile();
        if (snapshot.equals(export.getCanonicalFile()) || !snapshot.toPath().startsWith(export.getCanonicalFile().toPath()) || !snapshot.isDirectory()) throw new IOException("バックアップの保存先が不正です。");
        File destination = directory(snapshot);
        File[] chunks = snapshot.listFiles((dir, name) -> name.matches("messages-[0-9]+\\.jsonl"));
        if (chunks == null) throw new IOException("会話のバックアップを読み込めません。");
        Set<String> copied = new HashSet<>();
        for (File chunk : chunks) {
            if (Files.isSymbolicLink(chunk.toPath()) || chunk.length() > 16 * 1024 * 1024) throw new IOException("バックアップの会話ファイルが不正です。");
            try (BufferedReader reader = Files.newBufferedReader(chunk.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) for (Ref ref : references(new JSONObject(line).getString("content"))) {
                    if (!copied.add(ref.fileName)) continue;
                    File checked = resolve(person, ref.fileName); verify(checked);
                    String name = checked.getName() + (ref.image() ? ".enc" : "");
                    if (Files.isSymbolicLink(new File(destination, name).toPath())) throw new IOException("バックアップの画像の保存先が不正です。");
                    if (ref.image()) {
                        Files.write(new File(destination, name).toPath(), ImageVault.backup(person, checked));
                        Files.deleteIfExists(new File(destination, checked.getName()).toPath());
                    } else Files.copy(checked.toPath(), new File(destination, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
    static void addToDelta(File person, JSONObject delta) throws Exception {
        // shortcut: one Git batch is bounded to 24 MiB of binary files; larger batches remain local and can be exported as ZIP.
        JSONObject files = new JSONObject(), images = new JSONObject(); long size = 0;
        for (Ref ref : references(delta.getJSONArray("records").toString())) {
            if (files.has(ref.fileName) || images.has(ref.fileName + ".enc")) continue;
            File file = resolve(person, ref.fileName);
            byte[] bytes = read(person, ref);
            size += file.length();
            if (size > 24 * 1024 * 1024) throw new IOException("この差分の添付はGit保存の上限24MiBを超えています。会話は端末に保持されています。ZIPバックアップをご利用ください。");
            if (ref.image()) images.put(ref.fileName + ".enc", Base64.getEncoder().encodeToString(ImageVault.backup(person, file)));
            else files.put(ref.fileName, Base64.getEncoder().encodeToString(bytes));
        }
        if (files.length() > 0) delta.put("attachment_files", files);
        if (images.length() > 0) delta.put("encrypted_image_files", images);
    }

    static void migrateImages(File person) throws Exception {
        File[] files = directory(person).listFiles();
        if (files == null) throw new IOException("画像の保存先を読み込めません。");
        Exception failure = null;
        for (File file : files) if (ImageVault.image(file.getName())) {
            try { read(person, new Ref("", file.getName())); }
            catch (Exception error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw failure;
    }
}
