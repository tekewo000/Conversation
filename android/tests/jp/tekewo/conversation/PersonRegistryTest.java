package jp.tekewo.conversation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class PersonRegistryTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("conversation-people-").toFile();
        try {
            PersonRegistry registry = new PersonRegistry(root);
            assert registry.active() == null;
            assert registry.list().isEmpty();
            PersonDocument doc = PersonDocument.parse(PersonDocumentTest.document());
            PersonRegistry.Person first = registry.stage(doc, "https://GitHub.com/Owner/Friend.git/");
            assert first.repository.equals("owner/friend");
            assert registry.list().isEmpty();
            assert new String(Files.readAllBytes(new File(first.directory, "person.md").toPath()), StandardCharsets.UTF_8).equals(doc.rawMarkdown);
            registry.bindRemoteId(first.id, 123);
            registry.commit(first);
            assert registry.active().id.equals(first.id);
            assert registry.list().size() == 1;
            expectFailure(() -> registry.stage(doc, "OWNER/FRIEND"));
            expectFailure(() -> registry.bindRemoteId(first.id, 999));
            PersonRegistry.Person second = registry.stage(doc, "another/repo");
            expectFailure(() -> registry.bindRemoteId(second.id, 123));
            registry.bindRemoteId(second.id, 456);
            registry.commit(second);
            assert !first.directory.equals(second.directory);
            registry.activate(first.id);
            PersonRegistry reloaded = new PersonRegistry(root);
            assert reloaded.list().size() == 2 && reloaded.active().id.equals(first.id);
            assert reloaded.active().remoteId == 123;
            reloaded.activate("");
            assert reloaded.active() == null && new PersonRegistry(root).active() == null;
            byte[] beforeFailedWrite = Files.readAllBytes(new File(root, "people-registry.json").toPath());
            assert new File(root, "people-registry.json.tmp").mkdir();
            expectFailure(() -> reloaded.activate(first.id));
            assert reloaded.active() == null;
            assert java.util.Arrays.equals(beforeFailedWrite, Files.readAllBytes(new File(root, "people-registry.json").toPath()));
            PersonRegistry.Person draft = reloaded.stage(doc, "another/draft");
            reloaded.discard(draft);
            assert !draft.directory.exists() && reloaded.list().size() == 2;
            PersonDocument largeDoc = PersonDocument.parse(PersonDocumentTest.rawAtLimit(PersonDocumentTest.document()));
            PersonRegistry.Person large = reloaded.stage(largeDoc, "another/large");
            reloaded.commit(large);
            assert new PersonRegistry(root).active().rawMarkdown.equals(largeDoc.rawMarkdown);
            for (String invalid : new String[] { "https://evil.example/owner/repo", "https://user:pass@github.com/a/b", "https://github.com/a/b?q=x", "https://github.com/a/b#x", "https://github.com/a/../b", "https://github.com/a/%2e%2e", "a/b/c", "http://github.com/a/b", "" }) {
                expectFailure(() -> PersonRegistry.normalizeRepository(invalid));
            }
            assert PersonRegistry.normalizeRepository("github.com/A/B.git").equals("a/b");
            checkLocalPeopleAndDeletion();
            checkLegacyCleanup();
            checkCleanupFailure();
            checkCleanupSymlink();
            checkPersonIcons();
            System.out.println("PASS: person registry no selection, exact legacy cleanup, isolation, canonical repo and remote ID uniqueness");
        } finally { remove(root); }
    }
    private static void checkPersonIcons() throws Exception {
        File root = Files.createTempDirectory("conversation-icons-").toFile();
        try {
            PersonRegistry people = new PersonRegistry(root);
            PersonRegistry.Person first = people.stage(PersonDocument.named("小春"), null); people.commit(first);
            PersonRegistry.Person second = people.stage(PersonDocument.named("夏"), null); people.commit(second);
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
            assert people.icon(first.id).length == 0;
            people.setIcon(first.id, png);
            assert java.util.Arrays.equals(new PersonRegistry(root).icon(first.id), png);
            assert people.icon(second.id).length == 0;
            expectFailure(() -> people.setIcon(first.id, new byte[] {1, 2, 3}));
            expectFailure(() -> people.setIcon(first.id, new byte[1024 * 1024 + 1]));
            expectFailure(() -> people.setIcon("../outside", png));
            File blocked = new File(first.directory, "icon.png.tmp"); assert blocked.mkdir();
            Files.write(new File(blocked, "keep").toPath(), new byte[] {1});
            expectFailure(() -> people.setIcon(first.id, png));
            assert java.util.Arrays.equals(people.icon(first.id), png);
            remove(blocked);
            people.setIcon(first.id, null);
            assert new PersonRegistry(root).icon(first.id).length == 0;
            people.setIcon(second.id, png); people.delete(second.id);
            assert !second.directory.exists();
        } finally { remove(root); }
    }
    private static void checkLocalPeopleAndDeletion() throws Exception {
        File root = Files.createTempDirectory("conversation-local-people-").toFile();
        try {
            PersonRegistry registry = new PersonRegistry(root);
            PersonRegistry.Person first = registry.stage(PersonDocument.named("小春"), null);
            registry.commit(first);
            PersonRegistry.Person second = registry.stage(PersonDocument.named("夏"), null);
            registry.commit(second);
            Files.write(new File(first.directory, "conversation.sqlite3").toPath(), new byte[] { 4, 5 });
            File nested = new File(first.directory, "export/snapshot");
            assert nested.mkdirs();
            Files.write(new File(nested, "messages.jsonl").toPath(), new byte[] { 6 });
            PersonRegistry reloaded = new PersonRegistry(root);
            assert reloaded.list().size() == 2 && reloaded.active().id.equals(second.id);
            assert reloaded.active().repository == null && reloaded.active().remoteId == 0;
            expectFailure(() -> reloaded.bindRemoteId(second.id, 123));
            expectFailure(() -> reloaded.delete("legacy"));
            expectFailure(() -> reloaded.delete("../outside"));
            File blockedIndex = new File(root, "people-registry.json.tmp");
            assert blockedIndex.mkdir();
            byte[] before = Files.readAllBytes(new File(root, "people-registry.json").toPath());
            expectFailure(() -> reloaded.delete(second.id));
            assert reloaded.active().id.equals(second.id) && reloaded.list().size() == 2;
            assert second.directory.isDirectory();
            assert java.util.Arrays.equals(before, Files.readAllBytes(new File(root, "people-registry.json").toPath()));
            Files.deleteIfExists(blockedIndex.toPath());
            PersonRegistry.Person third = reloaded.stage(PersonDocument.named("秋"), null);
            reloaded.commit(third);
            reloaded.delete(third.id);
            assert reloaded.active() == null && reloaded.list().size() == 2;
            assert new PersonRegistry(root).active() == null;
            reloaded.activate(second.id);
            reloaded.delete(first.id);
            assert !first.directory.exists() && second.directory.isDirectory();
            assert reloaded.active().id.equals(second.id);
            PersonRegistry afterInactiveDelete = new PersonRegistry(root);
            assert afterInactiveDelete.list().size() == 1 && afterInactiveDelete.active().id.equals(second.id);
            reloaded.delete(second.id);
            assert !second.directory.exists();
            assert reloaded.list().isEmpty() && reloaded.active() == null;
            assert new PersonRegistry(root).active() == null;
            System.out.println("PASS: local-only people reload, isolated deletion, no-selection fallback and failed-write preservation");
        } finally { remove(root); }
    }

    private static void checkLegacyCleanup() throws Exception {
        File app = Files.createTempDirectory("conversation-legacy-cleanup-").toFile();
        File root = new File(app, "files");
        try {
            PersonRegistry registry = new PersonRegistry(root);
            PersonRegistry.Person person = registry.stage(PersonDocument.named("小春"), null);
            registry.commit(person);
            byte[] retained = { 1, 2, 3 };
            File personDb = new File(person.directory, "conversation.sqlite3");
            Files.write(personDb.toPath(), retained);
            File personExport = new File(person.directory, "export/snapshot/messages-000000.jsonl");
            assert personExport.getParentFile().mkdirs();
            Files.write(personExport.toPath(), retained);
            File legacyExport = new File(root, "export/snapshot/messages-000000.jsonl");
            assert legacyExport.getParentFile().mkdirs();
            Files.write(legacyExport.toPath(), new byte[] { 4, 5 });
            File noBackup = new File(app, "no_backup");
            assert noBackup.mkdir();
            File auth = new File(noBackup, "chatgpt-connection.v1");
            Files.write(auth.toPath(), retained);
            File githubAuth = new File(noBackup, "github-token." + person.id + ".v1");
            Files.write(githubAuth.toPath(), retained);
            File unrelated = new File(root, "conversation.db");
            Files.write(unrelated.toPath(), retained);
            String[] oldFiles = { "conversation.sqlite3", "conversation.sqlite3-wal", "conversation.sqlite3-shm", "conversation.sqlite3-journal", "notebook.json" };
            for (String name : oldFiles) Files.write(new File(root, name).toPath(), new byte[] { 4, 5 });
            File index = new File(root, "people-registry.json");
            org.json.JSONObject oldIndex = new org.json.JSONObject(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
            oldIndex.put("active_id", "legacy");
            Files.write(index.toPath(), oldIndex.toString().getBytes(StandardCharsets.UTF_8));

            PersonRegistry migrated = new PersonRegistry(root);
            assert migrated.active() == null && migrated.list().size() == 1;
            for (String name : oldFiles) assert !new File(root, name).exists() : name;
            assert !new File(root, "export").exists();
            for (File file : new File[] { personDb, personExport, auth, githubAuth, unrelated })
                assert java.util.Arrays.equals(retained, Files.readAllBytes(file.toPath())) : file;
            assert new PersonRegistry(root).active() == null;
            migrated.activate(person.id);
            assert new PersonRegistry(root).active().id.equals(person.id);
        } finally { remove(app); }
    }

    private static void checkCleanupFailure() throws Exception {
        File root = Files.createTempDirectory("conversation-blocked-cleanup-").toFile();
        try {
            File blocked = new File(root, "notebook.json");
            assert blocked.mkdir();
            Files.write(new File(blocked, "unrelated.txt").toPath(), new byte[] { 1 });
            expectFailure(() -> new PersonRegistry(root));
            assert new File(blocked, "unrelated.txt").isFile();
        } finally { remove(root); }
    }

    private static void checkCleanupSymlink() throws Exception {
        File root = Files.createTempDirectory("conversation-linked-cleanup-").toFile();
        File outside = Files.createTempFile("conversation-retained-", ".txt").toFile();
        try {
            byte[] retained = { 1, 2, 3 };
            Files.write(outside.toPath(), retained);
            File link = new File(root, "conversation.sqlite3");
            try { Files.createSymbolicLink(link.toPath(), outside.toPath()); }
            catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
                System.out.println("SKIP: symlink creation is unavailable: " + unsupported.getClass().getSimpleName());
                return;
            }
            assert new PersonRegistry(root).active() == null;
            assert !Files.isSymbolicLink(link.toPath());
            assert java.util.Arrays.equals(retained, Files.readAllBytes(outside.toPath()));
        } finally { remove(root); Files.deleteIfExists(outside.toPath()); }
    }
    private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation action) throws Exception {
        try { action.run(); throw new AssertionError("Accepted invalid registry change"); }
        catch (IOException | IllegalArgumentException expected) {}
    }
    private static void remove(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) for (File child : file.listFiles()) remove(child);
        Files.delete(file.toPath());
    }
}
