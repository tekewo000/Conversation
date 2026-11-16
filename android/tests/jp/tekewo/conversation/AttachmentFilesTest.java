package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

public final class AttachmentFilesTest {
    public static void main(String[] args) throws Exception {
        ImageVaultTest.install();
        File person = Files.createTempDirectory("conversation-attachments-").toFile();
        try {
            File deletedPerson = new File(person, "deleted-person");
            try { AttachmentFiles.directory(deletedPerson); throw new AssertionError(); } catch (IOException expected) { }
            assert !deletedPerson.exists();
            byte[] bytes = "保存するテキスト".getBytes(StandardCharsets.UTF_8);
            AttachmentFiles.Ref ref = AttachmentFiles.save(person, "資料[1].txt", "txt", new ByteArrayInputStream(bytes));
            assert DataVault.encrypted(Files.readAllBytes(AttachmentFiles.resolve(person,ref.fileName).toPath()));
            assert Arrays.equals(bytes, AttachmentFiles.read(person, ref));
            assert AttachmentFiles.references("本文\n" + ref.markdown()).get(0).fileName.equals(ref.fileName);
            assert AttachmentFiles.withoutReferences("本文\n" + ref.markdown()).equals("本文");
            AttachmentFiles.Ref again = AttachmentFiles.save(person, "別名", "txt", new ByteArrayInputStream(bytes));
            assert again.fileName.equals(ref.fileName);
            try { AttachmentFiles.resolve(person, "../secret.txt"); throw new AssertionError(); } catch (IOException expected) {}
            try { AttachmentFiles.save(person, "empty", "txt", new ByteArrayInputStream(new byte[0])); throw new AssertionError(); } catch (IOException expected) {}
            try {
                AttachmentFiles.save(person, "large", "mp4", new InputStream() {
                    int remaining = AttachmentFiles.MAX_BYTES + 1;
                    public int read() { if (remaining-- > 0) return 0; return -1; }
                    public int read(byte[] data, int start, int length) { int count = Math.min(remaining, length); remaining -= count; return count == 0 ? -1 : count; }
                });
                throw new AssertionError("Oversized attachment accepted");
            } catch (IOException expected) { }
            assert Arrays.stream(AttachmentFiles.directory(person).list()).noneMatch(name -> name.endsWith(".pending"));
            JSONObject delta = GitArchiveFilesTest.delta(0, 1);
            delta.getJSONArray("records").getJSONObject(0).getJSONArray("events").getJSONObject(0).getJSONObject("row").put("text", ref.markdown());
            AttachmentFiles.addToDelta(person, delta);
            GitArchiveFiles.Batch git = GitArchiveFiles.build(GitArchiveFilesTest.ID, 42, GitArchiveFilesTest.RAW, delta);
            assert Arrays.equals(bytes, git.files.get("media/" + ref.fileName));
            BackupArchive writer=new BackupArchive(person,new File(person,"export"));
            writer.writeChunk("messages-000000.jsonl",(new JSONObject().put("content",ref.markdown())+"\n").getBytes(StandardCharsets.UTF_8));
            writer.finish();
            ByteArrayOutputStream output=new ByteArrayOutputStream(); BackupArchive.write(writer.directory,output);
            File restored=new File(person,"recovered");
            BackupRestore.restore(ImageVault.keys.get(person,false),person.getName(),writer.directory,restored);
            assert Arrays.equals(bytes,Files.readAllBytes(new File(restored,"media/"+ref.fileName).toPath()));
            writer.close();
            Files.write(AttachmentFiles.resolve(person, ref.fileName).toPath(), new byte[]{1});
            try { AttachmentFiles.read(person, ref); throw new AssertionError(); } catch (IOException expected) {}
            System.out.println("PASS: attachment persistence, deduplication, traversal/integrity rejection, ZIP and Git files");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(person.toPath())) {
                for (Path path : (Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
