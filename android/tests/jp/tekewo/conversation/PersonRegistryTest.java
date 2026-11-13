package jp.tekewo.conversation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class PersonRegistryTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("conversation-people-").toFile();
        try {
            File legacyDb = new File(root, "conversation.db");
            byte[] legacyData = "existing conversation stays here".getBytes(StandardCharsets.UTF_8);
            Files.write(legacyDb.toPath(), legacyData);
            PersonRegistry registry = new PersonRegistry(root);
            assert registry.active().id.equals(PersonRegistry.LEGACY_ID);
            assert registry.active().directory.equals(root.getCanonicalFile());
            assert registry.list().size() == 1;
            PersonDocument doc = PersonDocument.parse(PersonDocumentTest.document());
            PersonRegistry.Person first = registry.stage(doc, "https://GitHub.com/Owner/Friend.git/");
            assert first.repository.equals("owner/friend");
            assert registry.list().size() == 1;
            assert new String(Files.readAllBytes(new File(first.directory, "person.md").toPath()), StandardCharsets.UTF_8).equals(doc.rawMarkdown);
            registry.bindRemoteId(first.id, 123);
            registry.commit(first);
            assert registry.active().id.equals(first.id);
            assert registry.list().size() == 2;
            assert new String(Files.readAllBytes(legacyDb.toPath()), StandardCharsets.UTF_8).equals(new String(legacyData, StandardCharsets.UTF_8));
            expectFailure(() -> registry.stage(doc, "OWNER/FRIEND"));
            expectFailure(() -> registry.bindRemoteId(first.id, 999));
            PersonRegistry.Person second = registry.stage(doc, "another/repo");
            expectFailure(() -> registry.bindRemoteId(second.id, 123));
            registry.bindRemoteId(second.id, 456);
            registry.commit(second);
            assert !first.directory.equals(second.directory);
            registry.activate(first.id);
            PersonRegistry reloaded = new PersonRegistry(root);
            assert reloaded.list().size() == 3 && reloaded.active().id.equals(first.id);
            assert reloaded.active().remoteId == 123;
            reloaded.activate(PersonRegistry.LEGACY_ID);
            assert reloaded.active().directory.equals(root.getCanonicalFile());
            byte[] beforeFailedWrite = Files.readAllBytes(new File(root, "people-registry.json").toPath());
            assert new File(root, "people-registry.json.tmp").mkdir();
            expectFailure(() -> reloaded.activate(first.id));
            assert reloaded.active().id.equals(PersonRegistry.LEGACY_ID);
            assert java.util.Arrays.equals(beforeFailedWrite, Files.readAllBytes(new File(root, "people-registry.json").toPath()));
            PersonRegistry.Person draft = reloaded.stage(doc, "another/draft");
            reloaded.discard(draft);
            assert !draft.directory.exists() && reloaded.list().size() == 3;
            PersonDocument largeDoc = PersonDocument.parse(PersonDocumentTest.rawAtLimit(PersonDocumentTest.document()));
            PersonRegistry.Person large = reloaded.stage(largeDoc, "another/large");
            reloaded.commit(large);
            assert new PersonRegistry(root).active().rawMarkdown.equals(largeDoc.rawMarkdown);
            for (String invalid : new String[] { "https://evil.example/owner/repo", "https://user:pass@github.com/a/b", "https://github.com/a/b?q=x", "https://github.com/a/b#x", "https://github.com/a/../b", "https://github.com/a/%2e%2e", "a/b/c", "http://github.com/a/b", "" }) {
                expectFailure(() -> PersonRegistry.normalizeRepository(invalid));
            }
            assert PersonRegistry.normalizeRepository("github.com/A/B.git").equals("a/b");
            checkLocalPeopleAndDeletion();
            System.out.println("PASS: person registry legacy preservation, isolation, canonical repo and remote ID uniqueness");
        } finally { remove(root); }
    }
    private static void checkLocalPeopleAndDeletion() throws Exception {
        File root = Files.createTempDirectory("conversation-local-people-").toFile();
        try {
            File legacyDb = new File(root, "conversation.db");
            Files.write(legacyDb.toPath(), new byte[] { 1, 2, 3 });
            PersonRegistry registry = new PersonRegistry(root);
            PersonRegistry.Person first = registry.stage(PersonDocument.named("小春"), null);
            registry.commit(first);
            PersonRegistry.Person second = registry.stage(PersonDocument.named("夏"), null);
            registry.commit(second);
            Files.write(new File(first.directory, "conversation.db").toPath(), new byte[] { 4, 5 });
            File nested = new File(first.directory, "export/snapshot");
            assert nested.mkdirs();
            Files.write(new File(nested, "messages.jsonl").toPath(), new byte[] { 6 });
            PersonRegistry reloaded = new PersonRegistry(root);
            assert reloaded.list().size() == 3 && reloaded.active().id.equals(second.id);
            assert reloaded.active().repository == null && reloaded.active().remoteId == 0;
            expectFailure(() -> reloaded.bindRemoteId(second.id, 123));
            expectFailure(() -> reloaded.delete(PersonRegistry.LEGACY_ID));
            expectFailure(() -> reloaded.delete("../outside"));
            File blockedIndex = new File(root, "people-registry.json.tmp");
            assert blockedIndex.mkdir();
            byte[] before = Files.readAllBytes(new File(root, "people-registry.json").toPath());
            expectFailure(() -> reloaded.delete(second.id));
            assert reloaded.active().id.equals(second.id) && reloaded.list().size() == 3;
            assert second.directory.isDirectory();
            assert java.util.Arrays.equals(before, Files.readAllBytes(new File(root, "people-registry.json").toPath()));
            Files.deleteIfExists(blockedIndex.toPath());
            reloaded.delete(first.id);
            assert !first.directory.exists() && second.directory.isDirectory();
            assert reloaded.active().id.equals(second.id);
            PersonRegistry afterInactiveDelete = new PersonRegistry(root);
            assert afterInactiveDelete.list().size() == 2 && afterInactiveDelete.active().id.equals(second.id);
            reloaded.delete(second.id);
            assert !second.directory.exists();
            assert reloaded.list().size() == 1 && reloaded.active().id.equals(PersonRegistry.LEGACY_ID);
            assert new PersonRegistry(root).active().id.equals(PersonRegistry.LEGACY_ID);
            assert java.util.Arrays.equals(new byte[] { 1, 2, 3 }, Files.readAllBytes(legacyDb.toPath()));
            System.out.println("PASS: local-only people reload, isolated deletion, active fallback and failed-write preservation");
        } finally { remove(root); }
    }
    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation action) throws Exception {
        try { action.run(); throw new AssertionError("Accepted invalid registry change"); }
        catch (IOException | IllegalArgumentException expected) {}
    }
    private static void remove(File file) throws IOException {
        if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
        Files.delete(file.toPath());
    }
}
