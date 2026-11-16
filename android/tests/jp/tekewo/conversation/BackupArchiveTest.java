package jp.tekewo.conversation;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
public final class BackupArchiveTest {
 public static void main(String[] args) throws Exception {
  ImageVaultTest.install(); File person=Files.createTempDirectory("encrypted-zip-").toFile();
  try {
   ImageVault.keys.get(person,true);
   BackupArchive writer=new BackupArchive(person,new File(person,"export"));
   String original="{\"content\":\"こんにちは private archive canary\"}\n";
   writer.writeChunk("messages-000001.jsonl",original.getBytes("UTF-8")); writer.finish();
   ByteArrayOutputStream output=new ByteArrayOutputStream(); BackupArchive.write(writer.directory,output);
   Set<String> names=new HashSet<>();
   try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
    ZipEntry entry; byte[] buffer=new byte[4096];
    while((entry=zip.getNextEntry())!=null) {
     names.add(entry.getName()); ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int n;
     while((n=zip.read(buffer))!=-1) bytes.write(buffer,0,n);
     assert !new String(bytes.toByteArray(),"UTF-8").contains("private archive canary");
    }
   }
   assert names.contains("archive.enc") && names.contains("format.json") && !names.contains("messages-000001.jsonl");
   File restored=new File(person,"restored");
   byte[] root=ImageVault.keys.get(person,false);
   BackupRestore.restore(root,person.getName(),writer.directory,restored);
   assert new String(Files.readAllBytes(new File(restored,"messages-000001.jsonl").toPath()),"UTF-8").equals(original);
   DataVaultTest.rejects(()->BackupRestore.restore(root,person.getName(),writer.directory,restored));
   byte[] before=Files.readAllBytes(new File(writer.directory,"archive.enc").toPath()); before[before.length-1]^=1;
   Files.write(new File(writer.directory,"archive.enc").toPath(),before);
   DataVaultTest.rejects(()->BackupArchive.write(writer.directory,new ByteArrayOutputStream()));
   writer.close();
   System.out.println("PASS: ZIP ciphertext only, Unicode recovery, overwrite and tamper rejection");
  } finally { try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) { for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path); } }
 }
}
