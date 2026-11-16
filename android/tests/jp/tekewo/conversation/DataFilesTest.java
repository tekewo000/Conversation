package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class DataFilesTest {
    public static void main(String[] args) throws Exception {
        ImageVaultTest.install();
        File person = Files.createTempDirectory("data-files-").toFile();
        try {
            File file = new File(person,"person.md");
            byte[] body="private person".getBytes("UTF-8");
            DataFiles.write(person,file,body);
            assert DataVault.encrypted(Files.readAllBytes(file.toPath()));
            assert Arrays.equals(body,DataFiles.read(person,file));
            Files.copy(file.toPath(),new File(person,"other.md").toPath());
            DataVaultTest.rejects(() -> DataFiles.read(person,new File(person,"other.md")));
            DataVaultTest.rejects(() -> DataFiles.write(person,new File(person,"../outside"),body));
            PersonState.save(person,new JSONObject().put("draft","私の下書き").put("attachments","[private]"));
            assert PersonState.load(person).getString("draft").equals("私の下書き");
            for(File item:person.listFiles()) assert !new String(Files.readAllBytes(item.toPath()),"UTF-8").contains("私の下書き");
            assert new File(person,"state.json.tmp").mkdir();
            DataVaultTest.rejects(() -> PersonState.save(person,new JSONObject().put("draft","lost")));
            assert PersonState.load(person).getString("draft").equals("私の下書き");
            System.out.println("PASS: encrypted files/state, path authentication, traversal rejection and failed-write preservation");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
