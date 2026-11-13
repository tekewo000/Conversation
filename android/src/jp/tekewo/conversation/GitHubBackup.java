package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

/** A private repository archives one imported person at a time. */
final class GitHubBackup {
    interface Transport { Response request(String method, String apiPath, JSONObject body) throws IOException; }
    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
        Response(int status, JSONObject body) { this(status, body.toString()); }
        Response(int status, JSONArray body) { this(status, body.toString()); }
    }
    static final class Failure extends IOException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    static final class PersonIdentity {
        final String id;
        PersonIdentity(String id) {
            if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid person ID.");
            this.id = id;
        }
    }
    static final class Repository {
        final String repo, branch;
        final long repoId;
        Repository(String repo, long repoId, String branch) { this.repo = repo; this.repoId = repoId; this.branch = branch; }
    }
    static class SyncState {
        final String head, repo, branch;
        final long cursor, repoId;
        SyncState(String head, long cursor, long repoId, String repo, String branch) {
            this.head = head; this.cursor = cursor; this.repoId = repoId; this.repo = repo; this.branch = branch;
        }
        static SyncState empty() { return new SyncState("", 0, 0, "", ""); }
        static SyncState fromJson(JSONObject value) throws Exception {
            if (value.length() == 0) return empty();
            SyncState state = new SyncState(value.getString("head"), GitArchiveFiles.integer(value, "cursor"),
                    GitArchiveFiles.integer(value, "repository_id"), normalizeRepository(value.getString("repo")), value.getString("branch"));
            if (!state.head.matches("[0-9a-f]{40,64}") || state.cursor < 0 || state.repoId <= 0 || state.branch.isEmpty())
                throw new IOException("Invalid GitHub backup state.");
            return state;
        }
        JSONObject toJson() throws Exception {
            return new JSONObject().put("head", head).put("cursor", cursor).put("repository_id", repoId)
                    .put("repo", repo).put("branch", branch);
        }
    }
    static final class SyncResult extends SyncState {
        final String status;
        SyncResult(Repository repository, String head, long cursor, String status) {
            super(head, cursor, repository.repoId, repository.repo, repository.branch); this.status = status;
        }
    }
    private static final class Remote {
        final String head;
        // null only when archive.json is absent and the pinned tree was verified empty.
        final JSONObject manifest;
        Remote(String head, JSONObject manifest) { this.head = head; this.manifest = manifest; }
    }
    private final Transport transport;
    GitHubBackup(Transport transport) { this.transport = transport; }

    static String normalizeRepository(String input) throws IOException {
        try {
            String value = input.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("github.com/")) value = "https://" + value;
            if (value.contains("://")) {
                URI uri = new URI(value);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IOException();
                value = uri.getRawPath();
                if (!value.startsWith("/")) throw new IOException();
                value = value.substring(1);
            }
            if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            if (value.toLowerCase(Locale.ROOT).endsWith(".git")) value = value.substring(0, value.length() - 4);
            if (!value.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}")
                    || value.endsWith("/.") || value.endsWith("/..")) throw new IOException();
            return value.toLowerCase(Locale.ROOT);
        } catch (Exception e) { throw new IOException("GitHub の owner/repo または HTTPS リポジトリ URL を指定してください。"); }
    }

    /** Call before binding the numeric ID in the local people registry. Does not write. */
    Repository verifyRepository(String input) throws IOException {
        try {
            String requested = normalizeRepository(input);
            Response response = request("GET", "/repos/" + requested, null);
            if (response.status == 404) throw fail("repository_access",
                    "GitHub のリポジトリが見つからないか、このトークンでアクセスできません (HTTP 404)。\n"
                    + "リポジトリが作成済みで、URL が正しいか確認してください。\n"
                    + "トークンの Resource owner を保存先の所有者に合わせ、Repository access でこのリポジトリを選択してください。Contents は Read and write に設定します。\n"
                    + "組織の承認が必要な場合は、承認後に再試行してください。");
            JSONObject data = object(response, 200, "リポジトリ情報の確認");
            if (!data.getBoolean("private")) throw fail("public_repository", "公開リポジトリには人物データを保存できません。");
            long id = GitArchiveFiles.integer(data, "id");
            String canonical = normalizeRepository(data.getString("full_name"));
            String branch = data.getString("default_branch");
            if (!requested.equals(canonical) || id <= 0 || branch.isEmpty() || branch.length() > 255)
                throw fail("repository", "リポジトリの識別情報が一致しません。URL を確認してください。");
            return new Repository(canonical, id, branch);
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw fail("repository", "GitHub の非公開リポジトリを確認できませんでした。"); }
    }

    /** Verify remote ownership before registering a new local person, without changing GitHub. */
    Repository verifyIdentity(PersonIdentity identity, String input) throws IOException {
        try {
            Repository repository = verifyRepository(input);
            Remote remote = readRemote(repository);
            if (remote.head.isEmpty()) verifyEmpty(repository);
            else if (remote.manifest != null) validateOwner(remote, identity.id, repository.repoId);
            return repository;
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw fail("repository", "保存先の人物識別情報を確認できませんでした。"); }
    }

    SyncResult sync(PersonIdentity identity, String repo, String rawMd, JSONObject delta, SyncState prior) throws IOException {
        return sync(identity, repo, rawMd, delta, prior, 0);
    }

    SyncResult sync(PersonIdentity identity, String repo, String rawMd, JSONObject delta, SyncState prior, long expectedRepositoryId) throws IOException {
        try {
            Repository repository = verifyRepository(repo);
            if (expectedRepositoryId < 0 || (expectedRepositoryId > 0 && repository.repoId != expectedRepositoryId))
                throw conflict("人物に固定されたリポジトリ ID が一致しません。");
            if (prior.repoId != 0 && (prior.repoId != repository.repoId || !prior.repo.equals(repository.repo)
                    || !prior.branch.equals(repository.branch)))
                throw conflict("以前の保存先とリポジトリ ID またはブランチが異なります。");
            GitArchiveFiles.Batch batch = GitArchiveFiles.build(identity.id, repository.repoId, rawMd, delta);
            if (batch.from != prior.cursor) throw conflict("ローカルの保存カーソルが一致しません。");
            Remote remote = readRemote(repository);
            if (remote.head.isEmpty()) {
                if (!prior.head.isEmpty() || batch.from != 0) throw conflict("リモートの履歴がなくなっています。");
                verifyEmpty(repository);
                // The bootstrap contains only ownership metadata, so an interrupted initialization is recognizable.
                JSONObject marker = GitArchiveFiles.marker(identity.id, repository.repoId);
                JSONObject init = new JSONObject().put("message", "Initialize person archive")
                        .put("branch", repository.branch).put("content", encode(GitArchiveFiles.utf8(GitArchiveFiles.canonical(marker) + "\n")));
                checkWriteTarget(repository);
                try {
                    object(request("PUT", base(repository) + "/contents/archive.json", init), 201, "アーカイブの初期化");
                } catch (IOException uncertain) {
                    remote = readRemote(repository);
                    validateOwner(remote, identity.id, repository.repoId);
                    if (remote.manifest.getBoolean("complete")) {
                        if (matchesReplay(repository, remote, batch, delta, prior)) return result(repository, remote, "recovered");
                        throw conflict("初期化中にリモートの履歴が変わりました。");
                    }
                    if (remote.head.isEmpty()) throw uncertain;
                }
                remote = readRemote(repository);
            }
            if (remote.manifest == null) {
                if (!prior.head.isEmpty() || prior.cursor != 0)
                    throw conflict("保存先のアーカイブが削除されています。ローカルの会話は保持しています。");
                // An existing empty tree can receive the complete archive atomically at its verified HEAD.
                remote = new Remote(remote.head, GitArchiveFiles.marker(identity.id, repository.repoId));
            }
            validateOwner(remote, identity.id, repository.repoId);
            boolean complete = remote.manifest.getBoolean("complete");
            if (!remote.head.equals(prior.head)) {
                if (complete && matchesReplay(repository, remote, batch, delta, prior)) return result(repository, remote, "recovered");
                if (complete || !prior.head.isEmpty() || prior.cursor != 0)
                    throw conflict("リモートに別の更新があります。ローカルの会話は保持しています。");
            }
            long remoteCursor = GitArchiveFiles.integer(remote.manifest, "cursor");
            if (remoteCursor != prior.cursor) throw conflict("リモートの保存カーソルが一致しません。");
            if (!complete && prior.cursor != 0) throw conflict("リモートの初期化が完了していません。");
            if (complete && matchesContent(remote.manifest, batch.manifest) && batch.through == batch.from)
                return new SyncResult(repository, remote.head, batch.through, "unchanged");
            batch.manifest.put("previous_head", remote.head);
            batch.files.put("archive.json", GitArchiveFiles.utf8(GitArchiveFiles.canonical(batch.manifest) + "\n"));
            JSONArray additions = new JSONArray();
            for (Map.Entry<String, byte[]> file : batch.files.entrySet()) {
                if (complete && file.getKey().equals("person.md") && sameHash(remote.manifest, batch.manifest, "source_sha256")) continue;
                if (complete && file.getKey().equals("persona.json") && sameHash(remote.manifest, batch.manifest, "persona_sha256")) continue;
                additions.put(new JSONObject().put("path", file.getKey()).put("contents", encode(file.getValue())));
            }
            JSONObject input = new JSONObject().put("branch", new JSONObject().put("repositoryNameWithOwner", repository.repo)
                    .put("branchName", repository.branch)).put("expectedHeadOid", remote.head)
                    .put("message", new JSONObject().put("headline", "Save person archive through " + batch.through))
                    .put("fileChanges", new JSONObject().put("additions", additions));
            JSONObject body = new JSONObject().put("query", "mutation($input:CreateCommitOnBranchInput!){createCommitOnBranch(input:$input){commit{oid}}}")
                    .put("variables", new JSONObject().put("input", input));
            checkWriteTarget(repository);
            try {
                JSONObject response = object(request("POST", "/graphql", body), 200, "アーカイブの保存");
                if (response.has("errors")) throw fail("remote", "GitHub が保存を拒否しました。権限または競合を確認してください。");
                String head = response.getJSONObject("data").getJSONObject("createCommitOnBranch").getJSONObject("commit").getString("oid");
                if (!head.matches("[0-9a-f]{40,64}")) throw new IOException();
                return new SyncResult(repository, head, batch.through, "synced");
            } catch (Exception uncertain) {
                // A committed response can be lost. Read the exact manifest at HEAD before permitting any retry.
                Remote check = readRemote(repository);
                if (check.manifest != null) {
                    validateOwner(check, identity.id, repository.repoId);
                    if (matchesReplay(repository, check, batch, delta, new SyncState(remote.head, batch.from, repository.repoId,
                            repository.repo, repository.branch))) return result(repository, check, "recovered");
                }
                if (!check.head.equals(remote.head)) throw conflict("保存中にリモートが更新されました。上書きは行いません。");
                throw fail("remote", "GitHub への保存を確認できませんでした。次回は同じカーソルから再確認します。");
            }
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw fail("backup", "人物データを GitHub に保存できませんでした。ローカルの会話は保持しています。"); }
    }

    private Remote readRemote(Repository repository) throws Exception {
        Response response = request("GET", base(repository) + "/git/ref/heads/" + component(repository.branch), null);
        if (response.status == 404 || response.status == 409) return new Remote("", new JSONObject());
        JSONObject ref = object(response, 200, "ブランチの確認");
        JSONObject target = ref.getJSONObject("object");
        String head = target.getString("sha");
        if (!target.getString("type").equals("commit") || !head.matches("[0-9a-f]{40,64}")) throw new IOException();
        Response file = request("GET", base(repository) + "/contents/archive.json?ref=" + head, null);
        if (file.status == 404) {
            JSONObject commit = object(request("GET", base(repository) + "/git/commits/" + head, null), 200, "コミットの確認");
            String treeSha = commit.getJSONObject("tree").getString("sha");
            if (!treeSha.matches("[0-9a-f]{40,64}")) throw new IOException();
            // GitHub returns 404 for the canonical empty tree even when its commit exists.
            if (treeSha.equals("4b825dc642cb6eb9a060e54bf8d69288fbee4904")) return new Remote(head, null);
            JSONObject tree = object(request("GET", base(repository) + "/git/trees/" + treeSha, null), 200, "ファイルツリーの確認");
            if (tree.getBoolean("truncated") || tree.getJSONArray("tree").length() != 0)
                throw conflict("保存先にファイルが残っています。初回保存には既定ブランチのファイルをすべて削除してください。");
            return new Remote(head, null);
        }
        JSONObject data = object(file, 200, "アーカイブの確認");
        if (!data.getString("encoding").equals("base64") || data.optLong("size", Long.MAX_VALUE) > 65536) throw new IOException();
        byte[] decoded = Base64.getDecoder().decode(data.getString("content").replace("\n", "").replace("\r", ""));
        if (decoded.length > 65536) throw new IOException();
        return new Remote(head, new JSONObject(new String(decoded, StandardCharsets.UTF_8)));
    }

    private void verifyEmpty(Repository repository) throws Exception {
        Response refs = request("GET", base(repository) + "/git/matching-refs/", null);
        if (refs.status == 200 && new JSONArray(refs.body).length() == 0) return;
        if (refs.status == 409) {
            // Git APIs also return 409 for an unavailable repository; confirm actual emptiness rather than guessing.
            String[] parts = repository.repo.split("/");
            JSONObject query = new JSONObject().put("query", "query($owner:String!,$name:String!){repository(owner:$owner,name:$name){isEmpty}}")
                    .put("variables", new JSONObject().put("owner", parts[0]).put("name", parts[1]));
            JSONObject response = object(request("POST", "/graphql", query), 200, "空リポジトリの確認");
            if (!response.has("errors") && Boolean.TRUE.equals(response.getJSONObject("data").getJSONObject("repository").get("isEmpty"))) return;
        }
        throw conflict("初回の保存先には完全に空のリポジトリを指定してください。");
    }

    private static void validateOwner(Remote remote, String personId, long repoId) throws Exception {
        if (remote.head.isEmpty() || !GitArchiveFiles.FORMAT.equals(remote.manifest.optString("format"))
                || !personId.equals(remote.manifest.optString("person_id"))
                || GitArchiveFiles.integer(remote.manifest, "repository_id") != repoId
                || !(remote.manifest.get("complete") instanceof Boolean) || GitArchiveFiles.integer(remote.manifest, "cursor") < 0)
            throw conflict("このリポジトリは別の人物用、または識別情報が異なります。");
    }
    private static boolean sameHash(JSONObject a, JSONObject b, String key) {
        return !a.optString(key).isEmpty() && a.optString(key).equals(b.optString(key));
    }
    private static boolean matchesContent(JSONObject a, JSONObject b) {
        return sameHash(a, b, "source_sha256") && sameHash(a, b, "persona_sha256");
    }
    private static boolean matches(Remote remote, GitArchiveFiles.Batch batch) throws Exception {
        JSONObject latest = remote.manifest.optJSONObject("latest_batch");
        return remote.manifest.optBoolean("complete") && GitArchiveFiles.integer(remote.manifest, "cursor") == batch.through
                && matchesContent(remote.manifest, batch.manifest) && latest != null
                && sameHash(latest, batch.manifest.getJSONObject("latest_batch"), "sha256");
    }
    private boolean matchesReplay(Repository repository, Remote remote, GitArchiveFiles.Batch batch, JSONObject delta, SyncState prior) throws Exception {
        if (!remote.manifest.optBoolean("complete")) return false;
        if (!matches(remote, batch)) {
            long cursor = GitArchiveFiles.integer(remote.manifest, "cursor");
            JSONObject latest = remote.manifest.optJSONObject("latest_batch");
            if (latest == null || cursor <= batch.from || cursor >= batch.through
                    || GitArchiveFiles.integer(latest, "from_cursor") != batch.from
                    || GitArchiveFiles.integer(latest, "through_cursor") != cursor
                    || !sameHash(remote.manifest, batch.manifest, "source_sha256")) return false;
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            JSONArray records = delta.getJSONArray("records");
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                if (GitArchiveFiles.integer(record, "cursor") > cursor) break;
                prefix.write(GitArchiveFiles.utf8(GitArchiveFiles.canonical(record) + "\n"));
            }
            if (!GitArchiveFiles.sha256(prefix.toByteArray()).equals(latest.optString("records_sha256"))) return false;
        }
        String parent = remote.manifest.optString("previous_head");
        if (!parent.matches("[0-9a-f]{40,64}") || (!prior.head.isEmpty() && !prior.head.equals(parent))) return false;
        JSONObject commit = object(request("GET", base(repository) + "/git/commits/" + remote.head, null), 200, "保存済みコミットの確認");
        JSONArray parents = commit.getJSONArray("parents");
        return parents.length() == 1 && parent.equals(parents.getJSONObject(0).getString("sha"));
    }
    private void checkWriteTarget(Repository expected) throws IOException {
        Repository current = verifyRepository(expected.repo);
        if (current.repoId != expected.repoId || !current.branch.equals(expected.branch))
            throw conflict("保存直前にリポジトリの識別情報が変わりました。");
    }
    private static SyncResult result(Repository repo, Remote remote, String status) throws Exception {
        return new SyncResult(repo, remote.head, GitArchiveFiles.integer(remote.manifest, "cursor"), status);
    }
    private Response request(String method, String path, JSONObject body) throws IOException {
        try { return transport.request(method, path, body); }
        catch (Exception e) { throw fail("network", "GitHub との通信に失敗しました。"); }
    }
    private static JSONObject object(Response response, int expected, String operation) throws IOException {
        if (response.status != expected) throw fail("http", "GitHub の" + operation + "に失敗しました (HTTP " + response.status + ")。");
        try { return new JSONObject(response.body); }
        catch (Exception e) { throw fail("response", "GitHub からの応答を確認できませんでした。"); }
    }
    private static String base(Repository repository) { return "/repos/" + repository.repo; }
    private static String component(String value) throws Exception { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
    private static String encode(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static Failure fail(String code, String message) { return new Failure(code, message); }
    private static Failure conflict(String message) { return fail("conflict", message); }

    /** Fixed HTTPS origin, direct connection, no redirects, and no response body in errors. */
    static final class HttpTransport implements Transport {
        private final String token;
        HttpTransport(String token) {
            if (token == null || token.isEmpty() || token.length() > 4096 || !token.matches("[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("GitHub トークンの形式が正しくありません。");
            this.token = token;
        }
        @Override public Response request(String method, String apiPath, JSONObject body) throws IOException {
            HttpsURLConnection connection = null;
            try {
                if (!apiPath.startsWith("/repos/") && !apiPath.equals("/graphql")) throw new IOException();
                if (apiPath.contains("\\") || apiPath.contains("\r") || apiPath.contains("\n") || apiPath.contains("#")) throw new IOException();
                URL url = new URL("https://api.github.com" + apiPath);
                if (!url.getHost().equals("api.github.com") || url.getPort() != -1 || url.getUserInfo() != null) throw new IOException();
                if (!method.equals("GET") && !method.equals("POST") && !method.equals("PUT")) throw new IOException();
                connection = (HttpsURLConnection)url.openConnection(Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
                connection.setRequestProperty("User-Agent", "Conversation-Android");
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 64 * 1024 * 1024) throw new IOException();
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) throw new IOException();
                if (status < 200 || status >= 300) return new Response(status, "{}");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 4 * 1024 * 1024) throw new IOException();
                        output.write(buffer, 0, count);
                    }
                    return new Response(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) { throw new IOException("GitHub との HTTPS 通信に失敗しました。"); }
            finally { if (connection != null) connection.disconnect(); }
        }
    }
}
