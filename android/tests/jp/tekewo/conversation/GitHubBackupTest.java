package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GitHubBackupTest {
    static final String ID = GitArchiveFilesTest.ID, RAW = GitArchiveFilesTest.RAW;
    static final GitHubBackup.PersonIdentity PERSON = new GitHubBackup.PersonIdentity(ID);
    static final String REPO = "owner/person";

    static final class Fake implements GitHubBackup.Transport {
        boolean privateRepo = true, unrelatedRefs, unavailableRefs, loseBootstrap, loseCommit, failCommit, raceCommit, makePublicBeforeWrite;
        int writes, commits, repoReads, archiveReads;
        long repoId = 42;
        String head = "";
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<String, String> parents = new HashMap<>();
        final ArrayList<String> requests = new ArrayList<>();
        JSONArray additions = new JSONArray();
        private String commit() { String next = String.format("%040x", ++commits); parents.put(next, head); head = next; return head; }
        void manifest(JSONObject value) throws Exception { files.put("archive.json", GitArchiveFiles.utf8(value.toString())); }
        JSONObject manifest() throws Exception { return new JSONObject(new String(files.get("archive.json"), StandardCharsets.UTF_8)); }
        @Override public GitHubBackup.Response request(String method, String path, JSONObject body) throws IOException {
            requests.add(method + " " + path);
            try {
                if (method.equals("GET") && path.equals("/repos/" + REPO)) {
                    repoReads++;
                    return response(200, new JSONObject().put("id", repoId).put("private", privateRepo)
                            .put("full_name", "Owner/Person").put("default_branch", "main"));
                }
                if (method.equals("GET") && path.endsWith("/git/ref/heads/main")) {
                    if (head.isEmpty()) return response(409, new JSONObject());
                    return response(200, new JSONObject().put("object", new JSONObject().put("type", "commit").put("sha", head)));
                }
                if (method.equals("GET") && path.endsWith("/git/matching-refs/")) {
                    if (unavailableRefs) return response(409, new JSONObject());
                    return new GitHubBackup.Response(200, unrelatedRefs ? new JSONArray().put(new JSONObject().put("ref", "refs/tags/old")) : new JSONArray());
                }
                if (method.equals("GET") && path.contains("/contents/archive.json?ref=")) {
                    archiveReads++;
                    if (makePublicBeforeWrite) { makePublicBeforeWrite = false; privateRepo = false; }
                    byte[] bytes = files.get("archive.json");
                    if (bytes == null) return response(404, new JSONObject());
                    return response(200, new JSONObject().put("encoding", "base64").put("size", bytes.length)
                            .put("content", Base64.getEncoder().encodeToString(bytes)));
                }
                if (method.equals("GET") && path.contains("/git/commits/")) {
                    String sha = path.substring(path.lastIndexOf('/') + 1);
                    String parent = parents.get(sha);
                    return response(200, new JSONObject().put("parents", parent == null || parent.isEmpty() ? new JSONArray()
                            : new JSONArray().put(new JSONObject().put("sha", parent))));
                }
                if (method.equals("PUT") && path.endsWith("/contents/archive.json")) {
                    writes++;
                    assert head.isEmpty();
                    files.put("archive.json", Base64.getDecoder().decode(body.getString("content")));
                    commit();
                    if (loseBootstrap) { loseBootstrap = false; throw new IOException("lost bootstrap response"); }
                    return response(201, new JSONObject().put("commit", new JSONObject().put("sha", head)));
                }
                if (method.equals("POST") && path.equals("/graphql")) {
                    if (!body.getJSONObject("variables").has("input"))
                        return response(200, new JSONObject().put("data", new JSONObject().put("repository",
                                new JSONObject().put("isEmpty", !unrelatedRefs))));
                    writes++;
                    JSONObject input = body.getJSONObject("variables").getJSONObject("input");
                    assert input.getJSONObject("branch").getString("repositoryNameWithOwner").equals(REPO);
                    assert input.getJSONObject("branch").getString("branchName").equals("main");
                    if (raceCommit) { raceCommit = false; commit(); return response(200, new JSONObject().put("errors", new JSONArray().put("conflict"))); }
                    assert input.getString("expectedHeadOid").equals(head);
                    if (failCommit) throw new IOException("github_pat_SECRET_in_transport_error");
                    additions = input.getJSONObject("fileChanges").getJSONArray("additions");
                    for (int i = 0; i < additions.length(); i++) {
                        JSONObject addition = additions.getJSONObject(i);
                        String name = addition.getString("path");
                        byte[] bytes = Base64.getDecoder().decode(addition.getString("contents"));
                        if (name.startsWith("events/") && files.containsKey(name)) assert Arrays.equals(files.get(name), bytes);
                        files.put(name, bytes);
                    }
                    commit();
                    if (loseCommit) { loseCommit = false; throw new IOException("lost commit response github_pat_SECRET"); }
                    return response(200, new JSONObject().put("data", new JSONObject().put("createCommitOnBranch",
                            new JSONObject().put("commit", new JSONObject().put("oid", head)))));
                }
                throw new AssertionError("Unexpected request " + method + " " + path);
            } catch (IOException e) { throw e; }
            catch (Exception e) { throw new IOException(e); }
        }
        private GitHubBackup.Response response(int status, JSONObject value) { return new GitHubBackup.Response(status, value); }
    }

    static GitHubBackup.SyncResult sync(Fake fake, JSONObject delta, GitHubBackup.SyncState prior) throws Exception {
        return new GitHubBackup(fake).sync(PERSON, REPO, RAW, delta, prior);
    }
    static JSONObject delta(long from, long through) throws Exception { return GitArchiveFilesTest.delta(from, through); }
    interface Checked { void run() throws Exception; }
    static void rejected(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Rejected operation succeeded"); }
        catch (GitHubBackup.Failure expected) {
            assert expected.code.equals(code) : expected.code + ": " + expected.getMessage();
            assert !expected.toString().contains("github_pat_SECRET");
            assert expected.getCause() == null;
        }
    }

    public static void main(String[] args) throws Exception {
        assert GitHubBackup.normalizeRepository("https://github.com/Owner/Person.git/").equals(REPO);
        assert GitHubBackup.normalizeRepository("GITHUB.COM/Owner/Person.GIT/").equals(REPO);
        for (String bad : Arrays.asList("http://github.com/owner/person", "https://user:pass@github.com/owner/person", "https://evil.com/owner/person",
                "https://github.com/owner/person?token=secret", "https://github.com/owner/%70erson", "owner/../person", "owner/person#token")) {
            try { GitHubBackup.normalizeRepository(bad); throw new AssertionError("Bad URL accepted"); }
            catch (IOException expected) { assert !expected.getMessage().contains("secret"); }
        }
        Fake publicRepo = new Fake(); publicRepo.privateRepo = false;
        rejected("public_repository", () -> sync(publicRepo, delta(0, 1), GitHubBackup.SyncState.empty()));
        assert publicRepo.writes == 0 && publicRepo.requests.size() == 1;
        Fake replacedBeforeFirstSync = new Fake(); replacedBeforeFirstSync.repoId = 43;
        rejected("conflict", () -> new GitHubBackup(replacedBeforeFirstSync).sync(PERSON, REPO, RAW, delta(0, 1), GitHubBackup.SyncState.empty(), 42));
        assert replacedBeforeFirstSync.writes == 0 && replacedBeforeFirstSync.requests.size() == 1;
        Fake tagsOnly = new Fake(); tagsOnly.unrelatedRefs = true;
        rejected("conflict", () -> new GitHubBackup(tagsOnly).verifyIdentity(PERSON, REPO));
        assert tagsOnly.writes == 0;
        Fake unavailable = new Fake(); unavailable.unavailableRefs = true; unavailable.unrelatedRefs = true;
        rejected("conflict", () -> sync(unavailable, delta(0, 1), GitHubBackup.SyncState.empty()));
        assert unavailable.writes == 0;
        Fake reallyEmpty = new Fake(); reallyEmpty.unavailableRefs = true;
        assert sync(reallyEmpty, delta(0, 1), GitHubBackup.SyncState.empty()).cursor == 1;
        Fake unrelated = new Fake(); unrelated.commit();
        rejected("conflict", () -> sync(unrelated, delta(0, 1), GitHubBackup.SyncState.empty()));
        assert unrelated.writes == 0;
        Fake foreign = new Fake(); foreign.commit();
        foreign.manifest(GitArchiveFiles.marker("e24785e0-f679-4e67-bd8c-7323760c9956", 42));
        rejected("conflict", () -> new GitHubBackup(foreign).verifyIdentity(PERSON, REPO));
        rejected("conflict", () -> sync(foreign, delta(0, 1), GitHubBackup.SyncState.empty()));
        assert foreign.writes == 0;

        Fake fake = new Fake();
        assert new GitHubBackup(fake).verifyIdentity(PERSON, REPO).repoId == 42 && fake.writes == 0;
        GitHubBackup.SyncResult first = sync(fake, delta(0, 2), GitHubBackup.SyncState.empty());
        assert first.status.equals("synced") && first.cursor == 2 && first.repoId == 42;
        assert fake.commits == 2 && fake.writes == 2;
        assert Arrays.equals(fake.files.get("person.md"), RAW.getBytes(StandardCharsets.UTF_8));
        assert fake.manifest().getBoolean("complete");
        assert GitHubBackup.SyncState.fromJson(first.toJson()).head.equals(first.head);
        assert fake.additions.length() == 4;
        int writes = fake.writes;
        assert sync(fake, delta(2, 2), first).status.equals("unchanged");
        assert fake.writes == writes;
        GitHubBackup.SyncResult second = sync(fake, delta(2, 3), first);
        assert second.cursor == 3 && fake.additions.length() == 2;
        assert fake.files.keySet().stream().filter(name -> name.startsWith("events/")).count() == 2;
        for (String request : fake.requests) assert !request.contains("/git/trees") && !request.contains("/contents/events/");
        writes = fake.writes;
        fake.repoId = 43;
        rejected("conflict", () -> sync(fake, delta(3, 4), second));
        assert fake.writes == writes;
        fake.repoId = 42;
        fake.commit();
        rejected("conflict", () -> sync(fake, delta(3, 4), second));
        assert fake.writes == writes;

        Fake uncertain = new Fake(); uncertain.loseBootstrap = true; uncertain.loseCommit = true;
        GitHubBackup.SyncResult recovered = sync(uncertain, delta(0, 2), GitHubBackup.SyncState.empty());
        assert recovered.status.equals("recovered") && recovered.cursor == 2 && uncertain.commits == 2;
        int savedWrites = uncertain.writes;
        assert sync(uncertain, delta(0, 2), GitHubBackup.SyncState.empty()).status.equals("recovered");
        assert uncertain.writes == savedWrites && uncertain.commits == 2;
        GitHubBackup.SyncResult prefixRecovered = sync(uncertain, delta(0, 3), GitHubBackup.SyncState.empty());
        assert prefixRecovered.status.equals("recovered") && prefixRecovered.cursor == 2 && uncertain.writes == savedWrites;
        assert sync(uncertain, delta(2, 3), prefixRecovered).cursor == 3;
        savedWrites = uncertain.writes;
        uncertain.commit();
        rejected("conflict", () -> sync(uncertain, delta(2, 3), prefixRecovered));
        assert uncertain.writes == savedWrites;

        Fake resume = new Fake(); resume.failCommit = true;
        rejected("remote", () -> sync(resume, delta(0, 1), GitHubBackup.SyncState.empty()));
        assert !resume.manifest().getBoolean("complete") && resume.commits == 1;
        resume.failCommit = false;
        assert sync(resume, delta(0, 1), GitHubBackup.SyncState.empty()).cursor == 1;
        assert resume.commits == 2;

        Fake race = new Fake(); GitHubBackup.SyncResult beforeRace = sync(race, delta(0, 1), GitHubBackup.SyncState.empty());
        race.raceCommit = true;
        rejected("conflict", () -> sync(race, delta(1, 2), beforeRace));
        assert race.manifest().getLong("cursor") == 1;

        Fake privacyChange = new Fake();
        GitHubBackup.SyncResult beforePublic = sync(privacyChange, delta(0, 1), GitHubBackup.SyncState.empty());
        int beforeWrites = privacyChange.writes;
        privacyChange.makePublicBeforeWrite = true;
        rejected("public_repository", () -> sync(privacyChange, delta(1, 2), beforePublic));
        assert privacyChange.writes == beforeWrites;

        GitHubBackup broken = new GitHubBackup((method, path, body) -> { throw new IOException("github_pat_SECRET"); });
        rejected("network", () -> broken.verifyRepository(REPO));
        new GitHubBackup.HttpTransport("github_pat_not_a_live_token");
        try { new GitHubBackup.HttpTransport("secret\r\nInjected: yes"); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { assert !expected.getMessage().contains("secret"); }
        if (args.length == 1 && args[0].equals("--live-public-read")) {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection)new java.net.URL(
                    "https://api.github.com/repos/octocat/Hello-World").openConnection(java.net.Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Conversation-Backup-Public-Rejection-Test");
            String metadata;
            try (java.io.InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 128 * 1024) throw new IOException("Public test response too large");
                    output.write(buffer, 0, count);
                }
                metadata = new String(output.toByteArray(), StandardCharsets.UTF_8);
            } finally { connection.disconnect(); }
            GitHubBackup publicRead = new GitHubBackup((method, path, body) -> {
                assert method.equals("GET") && path.equals("/repos/octocat/hello-world") && body == null;
                return new GitHubBackup.Response(200, metadata);
            });
            rejected("public_repository", () -> publicRead.verifyRepository("octocat/hello-world"));
            System.out.println("Live public repository read rejected; no credentials or writes used");
        }
        System.out.println("GitHubBackupTest passed");
    }
}
