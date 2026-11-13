package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PersonRegistry {
    static final String LEGACY_ID = "legacy";
    private final File root;
    private final File peopleDirectory;
    private final File index;
    private final Map<String, Person> people = new LinkedHashMap<>();
    private final Map<String, Person> staged = new LinkedHashMap<>();
    private String activeId = LEGACY_ID;

    static final class Person {
        final String id;
        final String name;
        final String repository;
        final File directory;
        final String rawMarkdown;
        final String profile;
        long remoteId;
        private Person(String id, String name, String repository, File directory, String raw, String profile, long remoteId) {
            this.id = id; this.name = name; this.repository = repository; this.directory = directory;
            rawMarkdown = raw; this.profile = profile; this.remoteId = remoteId;
        }
        @Override public String toString() { return name; }
    }

    PersonRegistry(File directory) throws IOException {
        root = directory.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("人物の保存先を作成できません。");
        peopleDirectory = new File(root, "people"); index = new File(root, "people-registry.json");
        people.put(LEGACY_ID, new Person(LEGACY_ID, "これまでの会話", null, root, "", "", 0));
        if (!index.exists()) return;
        if (index.length() <= 0 || index.length() > 1024 * 1024 || Files.isSymbolicLink(index.toPath())) throw new IOException("人物一覧が不正です。");
        try {
            JSONObject json = new JSONObject(read(index));
            if (json.getInt("version") != 1) throw new IOException("人物一覧の形式に対応していません。");
            JSONArray records = json.getJSONArray("people");
            Set<String> repositories = new HashSet<>(); Set<Long> remoteIds = new HashSet<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                String id = record.getString("id");
                if (!validId(id) || people.containsKey(id)) throw new IOException("人物IDが不正です。");
                String repository = normalizeRepository(record.getString("repository"));
                if (!repositories.add(repository)) throw new IOException("同じリポジトリが複数の人物に設定されています。");
                long remoteId = record.optLong("remote_id", 0);
                if (remoteId < 0 || (remoteId != 0 && !remoteIds.add(remoteId))) throw new IOException("GitHubリポジトリIDが重複しています。");
                File personDirectory = personDirectory(id);
                File rawFile = new File(personDirectory, "person.md");
                if (rawFile.length() > PersonDocument.MAX_BYTES || Files.isSymbolicLink(rawFile.toPath())) throw new IOException("人物ファイルが不正です。");
                PersonDocument doc = PersonDocument.parse(Files.readAllBytes(rawFile.toPath()));
                if (!record.getString("name").equals(doc.name)) throw new IOException("人物名と保存した資料が一致しません。");
                people.put(id, new Person(id, doc.name, repository, personDirectory, doc.rawMarkdown, doc.profile, remoteId));
            }
            activeId = json.getString("active_id");
            if (!people.containsKey(activeId)) throw new IOException("選択中の人物が見つかりません。");
        } catch (JSONException | IllegalArgumentException error) { throw new IOException("人物一覧を読み込めません。", error); }
    }

    synchronized List<Person> list() { return new ArrayList<>(people.values()); }
    synchronized Person active() { return people.get(activeId); }

    synchronized Person stage(PersonDocument doc, String repositoryInput) throws IOException {
        if (doc == null) throw new IllegalArgumentException("人物ファイルが必要です。");
        String repository = normalizeRepository(repositoryInput);
        ensureAvailable(repository, 0, null);
        String id = UUID.randomUUID().toString();
        File directory = personDirectory(id);
        if (!directory.mkdirs()) throw new IOException("人物の保存先を作成できません。");
        Person person = new Person(id, doc.name, repository, directory, doc.rawMarkdown, doc.profile, 0);
        try {
            writeAtomic(new File(directory, "person.md"), doc.rawMarkdown);
            writeAtomic(new File(directory, "profile.md"), doc.profile);
            writeAtomic(new File(directory, "person.json"), doc.importPayload(id).toString());
            staged.put(id, person);
            return person;
        } catch (IOException | JSONException error) {
            try { deleteDirectory(directory); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw new IOException("人物資料を保存できません。", error);
        }
    }

    synchronized void commit(Person person) throws IOException {
        if (person == null || staged.get(person.id) != person) throw new IllegalArgumentException("保存待ちの人物ではありません。");
        ensureAvailable(person.repository, person.remoteId, person.id);
        Map<String, Person> updated = new LinkedHashMap<>(people); updated.put(person.id, person);
        persist(updated, person.id);
        people.put(person.id, person); staged.remove(person.id); activeId = person.id;
    }

    synchronized void discard(Person person) throws IOException {
        if (person == null || staged.get(person.id) != person) throw new IllegalArgumentException("保存待ちの人物ではありません。");
        deleteDirectory(personDirectory(person.id)); staged.remove(person.id);
    }

    synchronized void activate(String id) throws IOException {
        if (!people.containsKey(id)) throw new IllegalArgumentException("人物が見つかりません。");
        if (activeId.equals(id)) return;
        persist(people, id); activeId = id;
    }

    synchronized void bindRemoteId(String personId, long remoteId) throws IOException {
        if (remoteId <= 0) throw new IllegalArgumentException("GitHubリポジトリIDが不正です。");
        Person person = people.get(personId);
        if (person == null) person = staged.get(personId);
        if (person == null || person.repository == null) throw new IllegalArgumentException("リポジトリを持つ人物が見つかりません。");
        if (person.remoteId != 0 && person.remoteId != remoteId) throw new IllegalArgumentException("人物のリポジトリIDは変更できません。");
        ensureAvailable(person.repository, remoteId, personId);
        if (person.remoteId == remoteId) return;
        long previous = person.remoteId; person.remoteId = remoteId;
        if (people.containsKey(personId)) {
            try { persist(people, activeId); } catch (IOException error) { person.remoteId = previous; throw error; }
        }
    }

    static String normalizeRepository(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("人物ごとのGitHubリポジトリを指定してください。");
        String text = input.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("github.com/")) text = "https://" + text;
        if (text.contains("://")) {
            try {
                URI uri = new URI(text);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) ||
                        uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1)
                    throw new IllegalArgumentException("https://github.com/所有者/リポジトリ を指定してください。");
                text = uri.getRawPath();
                if (text == null || !text.startsWith("/")) throw new IllegalArgumentException("リポジトリのURLが不正です。");
                text = text.substring(1);
            } catch (URISyntaxException error) { throw new IllegalArgumentException("リポジトリのURLが不正です。", error); }
        }
        if (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.toLowerCase(Locale.ROOT).endsWith(".git")) text = text.substring(0, text.length() - 4);
        String[] parts = text.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?") ||
                !parts[1].matches("[A-Za-z0-9._-]{1,100}") || parts[1].equals(".") || parts[1].equals(".."))
            throw new IllegalArgumentException("GitHubの 所有者/リポジトリ を指定してください。");
        return (parts[0] + "/" + parts[1]).toLowerCase(Locale.ROOT);
    }

    private void ensureAvailable(String repository, long remoteId, String self) {
        List<Person> all = new ArrayList<>(people.values()); all.addAll(staged.values());
        for (Person person : all) {
            if (person.id.equals(self)) continue;
            if (repository.equals(person.repository)) throw new IllegalArgumentException("このGitHubリポジトリは既に別の人物が使用しています。");
            if (remoteId > 0 && person.remoteId == remoteId) throw new IllegalArgumentException("このGitHubリポジトリIDは既に別の人物が使用しています。");
        }
    }
    private void persist(Map<String, Person> records, String selectedId) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (Person person : records.values()) if (!person.id.equals(LEGACY_ID)) {
                array.put(new JSONObject().put("id", person.id).put("name", person.name)
                    .put("repository", person.repository).put("remote_id", person.remoteId));
            }
            writeAtomic(index, new JSONObject().put("version", 1).put("active_id", selectedId).put("people", array).toString());
        } catch (JSONException error) { throw new IOException("人物一覧を保存できません。", error); }
    }
    private File personDirectory(String id) throws IOException {
        if (!validId(id)) throw new IOException("人物IDが不正です。");
        if (Files.isSymbolicLink(peopleDirectory.toPath())) throw new IOException("人物の保存先が不正です。");
        File directory = new File(peopleDirectory, id);
        if (Files.isSymbolicLink(directory.toPath()) || !directory.getCanonicalPath().startsWith(root.getPath() + File.separator))
            throw new IOException("人物の保存先が不正です。");
        return directory;
    }
    private static boolean validId(String id) {
        try { return UUID.fromString(id).toString().equals(id); } catch (IllegalArgumentException error) { return false; }
    }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void writeAtomic(File file, String text) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(text.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static void deleteDirectory(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.delete(file.toPath()); return; }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("保存待ちの人物を削除できません。");
            for (File child : children) deleteDirectory(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
