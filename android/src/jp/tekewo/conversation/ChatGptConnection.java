package jp.tekewo.conversation;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** A local, public OAuth client. Blocking methods belong on the app's worker queue. */
public final class ChatGptConnection {
    private static final String ISSUER = "https://auth.openai.com";
    private static final String AUTHORIZE = ISSUER + "/api/accounts/authorize";
    private static final String TOKEN = ISSUER + "/api/accounts/oauth/token";
    private static final String JWKS = ISSUER + "/.well-known/jwks.json";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, work -> {
        Thread thread = new Thread(work, "ChatGPT timeouts"); thread.setDaemon(true); return thread;
    });
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }

    public interface LoginCallback { void complete(String error); }

    private final Activity activity;
    private final ConnectionStore store;
    private final Object stateLock = new Object(), operationLock = new Object();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean signingIn = new AtomicBoolean();
    private JSONObject state;
    private String storageError;
    private volatile String lastSignOutMessage = "";
    private volatile HttpURLConnection activeHttp;
    private volatile ServerSocket activeListener;
    private volatile Socket activeCallback;
    private volatile String loginPhase = "idle";
    private volatile boolean foreground;
    private final Object foregroundLock = new Object();
    private void phase(String name) { loginPhase = name; android.util.Log.i("ConversationAuth", name); }

    public void setForeground(boolean value) {
        synchronized (foregroundLock) { foreground = value; foregroundLock.notifyAll(); }
    }

    private void awaitForeground(int attempt) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        synchronized (foregroundLock) {
            while (!foreground) {
                checked(attempt);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SafeFailure("Conversationに戻って、もう一度接続してください。");
                TimeUnit.NANOSECONDS.timedWait(foregroundLock, Math.min(remaining, TimeUnit.SECONDS.toNanos(1)));
            }
        }
        checked(attempt);
    }

    public ChatGptConnection(Activity activity) {
        this.activity = activity;
        store = new ConnectionStore(activity.getApplicationContext());
        try {
            state = store.load();
            if (state.optString("host_id").isEmpty()) {
                state.put("host_id", "urn:uuid:" + UUID.randomUUID().toString());
                store.save(state);
            }
        } catch (Exception e) {
            state = new JSONObject();
            storageError = "接続情報を読み込めません。サインアウトしてから再接続してください。";
        }
    }

    public boolean hasSession() {
        synchronized (stateLock) { return storageError == null && !state.optString("access_token").isEmpty() && !state.optString("refresh_token").isEmpty()
                && !state.optString("subject").isEmpty() && !state.optString("client_id").isEmpty(); }
    }

    public String accountLabel() {
        synchronized (stateLock) {
            String email = state.optString("email");
            return state.optString("subject").isEmpty() ? "未接続" : email.isEmpty() ? "ChatGPT アカウント" : email;
        }
    }

    public void signIn(LoginCallback callback) {
        if (!signingIn.compareAndSet(false, true)) {
            activity.runOnUiThread(() -> callback.complete("サインインを処理中です。"));
            return;
        }
        cancel();
        final int attempt = generation.get();
        Thread worker = new Thread(() -> {
            String error = null;
            synchronized (operationLock) {
                try { login(attempt); }
                catch (IOException e) { android.util.Log.w("ConversationAuth", loginPhase + ":" + e.getClass().getSimpleName()); error = safeMessage(e); }
                catch (Exception e) { android.util.Log.w("ConversationAuth", loginPhase + ":" + e.getClass().getSimpleName()); error = "サインインを完了できませんでした。もう一度お試しください。"; }
                finally { closeListener(); signingIn.set(false); }
            }
            final String result = error;
            activity.runOnUiThread(() -> callback.complete(result));
        }, "ChatGPT sign-in");
        worker.setDaemon(true);
        worker.start();
    }

    private void login(int attempt) throws Exception {
        JSONObject selected = snapshot();
        checked(attempt);
        phase("discovery");
        JSONObject discovery = discovery(attempt);
        String selectedClient = selected.optString("client_id", selected.optString("pending_client"));
        String verifier = random(64), nonce = random(32), oauthState = random(32);
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(false);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        listener.setSoTimeout(1000);
        activeListener = listener;
        checked(attempt);
        String redirect = "http://127.0.0.1:" + listener.getLocalPort() + "/auth/callback";
        Map<String,String> params = form("client_id", selectedClient.isEmpty() ? "dynamic_agent_client" : selectedClient,
                "ext_agent_host_id", selected.getString("host_id"), "response_type", "code", "redirect_uri", redirect,
                "scope", SCOPES, "resource", RESOURCE, "state", oauthState, "nonce", nonce,
                "code_challenge_method", "S256", "code_challenge", Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        if (selectedClient.isEmpty()) params.put("agent_name_hint", "Conversation");
        else {
            if (!selected.optString("id_token").isEmpty()) params.put("id_token_hint", selected.getString("id_token"));
            if (!selected.optString("email").isEmpty()) params.put("login_hint", selected.getString("email"));
        }
        String authorization = discovery.getString("authorization_endpoint") + "?" + encode(params);
        activity.runOnUiThread(() -> {
            if (attempt != generation.get()) return;
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authorization))); }
            catch (Exception e) { cancel(); }
        });
        phase("browser_callback");
        Map<String,String> callback = awaitCallback(listener, oauthState, selectedClient, attempt);
        phase("callback_validated");
        closeListener();
        String client = callback.get("client_id");
        if (selectedClient.isEmpty()) {
            JSONObject pending = snapshot(); pending.put("pending_client", client);
            persist(pending, attempt);
        }
        phase("return_to_app");
        awaitForeground(attempt);
        phase("token_exchange");
        JSONObject tokens = json(TOKEN, "POST", encode(form("grant_type", "authorization_code", "client_id", client,
                "code", callback.get("code"), "code_verifier", verifier, "redirect_uri", redirect, "resource", RESOURCE)), "", attempt);
        phase("identity_validation");
        JSONObject claims = OAuthValidation.verifyIdToken(tokens.getString("id_token"), json(JWKS, "GET", null, "", attempt),
                client, nonce, selected.optString("subject"), System.currentTimeMillis() / 1000);
        JSONObject replacement = new JSONObject(selected.toString());
        replacement.put("client_id", client).put("subject", claims.getString("sub"))
                .put("email", claims.optString("email")).put("id_token", tokens.getString("id_token"));
        replacement.remove("pending_client");
        applyTokens(replacement, tokens, true);
        persist(replacement, attempt);
        phase("connected");
        if (!hasScope(replacement.optString("scope"), "chatgpt.tokens.use.direct"))
            throw new SafeFailure("サインインは完了しました。ChatGPT プランの利用許可がありません。再接続して利用を許可してください。");
    }

    private Map<String,String> awaitCallback(ServerSocket listener, String state, String client, int attempt) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        int connections = 0;
        while (System.nanoTime() < deadline && connections < 32) {
            checked(attempt);
            try (Socket socket = listener.accept()) {
                connections++;
                activeCallback = socket;
                socket.setSoTimeout(3000);
                long requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                String request = httpLine(socket.getInputStream(), 8192, requestDeadline);
                String[] start = request.split(" ");
                String host = "";
                int total = request.length();
                for (int i = 0; i < 64; i++) {
                    String header = httpLine(socket.getInputStream(), 8192 - total, requestDeadline);
                    total += header.length() + 2;
                    if (total > 8192) throw new IOException();
                    if (header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon > 0 && "host".equalsIgnoreCase(header.substring(0, colon))) {
                        if (!host.isEmpty()) throw new IOException();
                        host = header.substring(colon + 1).trim();
                    }
                    if (i == 63) throw new IOException();
                }
                if (start.length != 3 || !"GET".equals(start[0]) || !"HTTP/1.1".equals(start[2])
                        || !("127.0.0.1:" + listener.getLocalPort()).equals(host)
                        || !start[1].startsWith("/auth/callback?")) {
                    callbackPage(socket, false); continue;
                }
                try {
                    Map<String,String> values = OAuthValidation.callback(start[1], state, client);
                    callbackPage(socket, true);
                    return values;
                } catch (IOException e) { callbackPage(socket, false); throw e; }
            } catch (SocketTimeoutException e) { checked(attempt); }
            finally { activeCallback = null; }
        }
        throw new SafeFailure("サインインの待機時間が終了しました。もう一度お試しください。");
    }

    public List<ChatRequest.Model> listModels() throws IOException {
        synchronized (operationLock) {
            int attempt = generation.get();
            String token = accessToken(attempt);
            try {
                JSONObject catalog = json(RESOURCE + "/models", "GET", null, token, attempt);
                JSONArray models = catalog.getJSONArray("models");
                List<ChatRequest.Model> visible = new ArrayList<>();
                for (int i = 0; i < models.length(); i++) {
                    JSONObject model = models.getJSONObject(i);
                    if ("list".equals(model.optString("visibility"))) {
                        ChatRequest.Model parsed = new ChatRequest.Model(model);
                        if (!parsed.slug.isEmpty() && !parsed.displayName.isEmpty()) visible.add(parsed);
                    }
                }
                if (visible.isEmpty()) throw new SafeFailure("このアカウントで利用できるモデルがありません。");
                return visible;
            } catch (HttpFailure e) { handleUnauthorized(e, attempt); throw e; }
            catch (IOException e) { throw e; }
            catch (Exception e) { throw new SafeFailure("モデル一覧を読み取れませんでした。"); }
        }
    }

    public String request(ChatRequest request, String instructions, JSONArray input, Consumer<String> onDelta) throws IOException {
        synchronized (operationLock) {
            int attempt = generation.get();
            String token = accessToken(attempt);
            HttpURLConnection connection = null;
            ScheduledFuture<?> timeout = null;
            try {
                JSONObject body = request.body(instructions, input);
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                if (encoded.length > 4 * 1024 * 1024) throw new SafeFailure("会話が長すぎます。新しい会話を開始してください。");
                connection = open(RESOURCE + "/responses", "POST", token, attempt);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(encoded.length);
                final HttpURLConnection bound = connection;
                timeout = TIMEOUTS.schedule(bound::disconnect, 120, TimeUnit.SECONDS);
                try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
                int status = connection.getResponseCode();
                if (status != 200) throw httpFailure(connection, status);
                String contentType = connection.getContentType();
                android.util.Log.i("ConversationApi", "Response status=" + status + " contentType=" + contentType);
                try (InputStream stream = connection.getInputStream()) {
                    Consumer<String> delta = value -> { if (attempt == generation.get()) onDelta.accept(value); };
                    // Subscription responses can omit Content-Type; the parser still requires verified completion.
                    String result = SseReader.readAuto(stream, delta);
                    checked(attempt);
                    return result;
                }
            } catch (HttpFailure e) { handleUnauthorized(e, attempt); throw e; }
            catch (IOException e) { throw sanitized(e); }
            catch (Exception e) { throw new SafeFailure("ChatGPT に接続できませんでした。接続状態を確認してください。"); }
            finally {
                if (timeout != null) timeout.cancel(false);
                if (connection != null) connection.disconnect();
                activeHttp = null;
            }
        }
    }

    private String accessToken(int attempt) throws IOException {
        try {
            checked(attempt);
            JSONObject selected = snapshot();
            if (!hasSession()) throw new SafeFailure("ChatGPT にサインインしてください。");
            if (!hasScope(selected.optString("scope"), "chatgpt.tokens.use.direct"))
                throw new SafeFailure("ChatGPT プランの利用許可がありません。再接続して許可してください。");
            if (selected.optLong("expires_at") > System.currentTimeMillis() + 60000) return selected.getString("access_token");
            JSONObject signingKeys = json(JWKS, "GET", null, "", attempt);
            JSONObject refreshed;
            try {
                refreshed = new JSONObject(raw(TOKEN, "POST", encode(form("grant_type", "refresh_token", "client_id", selected.getString("client_id"),
                        "refresh_token", selected.getString("refresh_token"), "resource", RESOURCE)), "", attempt, 2 * 1024 * 1024, true));
            } catch (HttpFailure e) {
                if (e.invalidGrant || e.status == 401) clearTokens(attempt);
                throw e;
            }
            if (refreshed.has("id_token")) {
                JSONObject claims = OAuthValidation.verifyIdToken(refreshed.getString("id_token"), signingKeys,
                        selected.getString("client_id"), null, selected.getString("subject"), System.currentTimeMillis() / 1000);
                selected.put("id_token", refreshed.getString("id_token"));
                selected.put("email", claims.optString("email", selected.optString("email")));
            }
            applyTokens(selected, refreshed, false);
            // Commit a verified rotation before honoring cancellation; sign-out is serialized behind this operation.
            synchronized (stateLock) { saveState(selected); }
            if (!hasScope(selected.optString("scope"), "chatgpt.tokens.use.direct"))
                throw new SafeFailure("ChatGPT プランの利用許可が解除されています。再接続してください。");
            return selected.getString("access_token");
        } catch (IOException e) { throw sanitized(e); }
        catch (Exception e) { throw new SafeFailure("ChatGPT の接続を更新できませんでした。再接続してください。"); }
    }

    private void applyTokens(JSONObject selected, JSONObject response, boolean initial) throws Exception {
        if (!(response.get("access_token") instanceof String) || !(response.get("refresh_token") instanceof String)
                || !(response.get("expires_in") instanceof Number)
                || (response.has("scope") && !(response.get("scope") instanceof String)))
            throw new SafeFailure("ChatGPT の接続情報を確認できませんでした。");
        String access = response.getString("access_token"), refresh = response.getString("refresh_token");
        long expires = response.getLong("expires_in");
        if (access.isEmpty() || refresh.isEmpty() || access.length() > 65536 || refresh.length() > 65536
                || !"Bearer".equalsIgnoreCase(response.getString("token_type")) || expires <= 0 || expires > 86400)
            throw new SafeFailure("ChatGPT の接続情報を確認できませんでした。");
        if (initial && !response.has("scope")) throw new SafeFailure("ChatGPT の利用許可を確認できませんでした。");
        selected.put("access_token", access).put("refresh_token", refresh)
                .put("scope", response.optString("scope", selected.optString("scope")))
                .put("expires_at", System.currentTimeMillis() + expires * 1000);
        // expires_in is documented; earliest_refresh_at has no documented scheduling semantics.
        selected.remove("earliest_refresh_at");
    }

    public void signOut() {
        cancel();
        synchronized (operationLock) {
            int attempt = generation.get();
            String refresh = "", client = "";
            boolean cleared = false, unknownRemote = false;
            try {
                JSONObject selected = snapshot();
                refresh = selected.optString("refresh_token"); client = selected.optString("client_id");
                clearTokens(attempt); cleared = true;
            } catch (Exception e) {
                synchronized (stateLock) {
                    unknownRemote = refresh.isEmpty();
                    try {
                        JSONObject retained = new JSONObject();
                        for (String key : Arrays.asList("host_id", "client_id", "subject", "email", "pending_client"))
                            if (state.has(key)) retained.put(key, state.get(key));
                        if (retained.optString("host_id").isEmpty()) retained.put("host_id", "urn:uuid:" + UUID.randomUUID());
                        store.delete(); state = retained; storageError = null;
                        store.save(state); cleared = true;
                    }
                    catch (Exception failure) { storageError = "接続情報を消去できませんでした。アプリのストレージ設定を確認してください。"; }
                }
            }
            lastSignOutMessage = cleared ? "サインアウトしました。" : "接続情報を消去できませんでした。";
            if (refresh.isEmpty()) {
                if (unknownRemote && cleared) lastSignOutMessage = "端末の接続情報を消去しました。サーバー側の解除は未確認です。ChatGPT の設定から接続を解除できます。";
                return;
            }
            boolean revoked = false;
            for (int retry = 0; retry < 2 && !revoked; retry++) {
                try {
                    JSONObject config = discovery(attempt);
                    raw(config.getString("revocation_endpoint"), "POST", encode(form("token", refresh,
                            "token_type_hint", "refresh_token", "client_id", client)), "", attempt, 65536);
                    revoked = true;
                } catch (Exception e) {
                    if (retry == 0) try { Thread.sleep(400); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            if (!revoked) lastSignOutMessage = "端末からサインアウトしました。サーバー側の解除は未確認です。ChatGPT の設定から接続を解除できます。";
        }
    }

    public String signOutStatus() { return lastSignOutMessage; }

    public void cancel() {
        generation.incrementAndGet();
        HttpURLConnection http = activeHttp;
        if (http != null) http.disconnect();
        closeListener();
    }

    private void closeListener() {
        Socket callback = activeCallback; activeCallback = null;
        if (callback != null) try { callback.close(); } catch (IOException ignored) {}
        ServerSocket listener = activeListener; activeListener = null;
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
    }

    private JSONObject discovery(int attempt) throws IOException {
        JSONObject config = json(ISSUER + "/.well-known/openid-configuration", "GET", null, "", attempt);
        if (!ISSUER.equals(config.optString("issuer")) || !AUTHORIZE.equals(config.optString("authorization_endpoint"))
                || !TOKEN.equals(config.optString("token_endpoint")) || !JWKS.equals(config.optString("jwks_uri"))
                || !(ISSUER + "/api/accounts/oauth/revoke").equals(config.optString("revocation_endpoint")))
            throw new SafeFailure("ChatGPT の認証先を確認できませんでした。");
        return config;
    }

    private JSONObject json(String url, String method, String body, String bearer, int attempt) throws IOException {
        try { return new JSONObject(raw(url, method, body, bearer, attempt, 2 * 1024 * 1024)); }
        catch (IOException e) { throw e; }
        catch (Exception e) { throw new SafeFailure("ChatGPT の接続情報を読み取れませんでした。"); }
    }

    private String raw(String url, String method, String body, String bearer, int attempt, int limit) throws IOException {
        return raw(url, method, body, bearer, attempt, limit, false);
    }

    private String raw(String url, String method, String body, String bearer, int attempt, int limit, boolean rotation) throws IOException {
        HttpURLConnection connection = null;
        ScheduledFuture<?> timeout = null;
        try {
            connection = open(url, method, bearer, attempt);
            final HttpURLConnection bound = connection;
            timeout = TIMEOUTS.schedule(bound::disconnect, 30, TimeUnit.SECONDS);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw httpFailure(connection, status);
            String response;
            try (InputStream input = connection.getInputStream()) { response = readBounded(input, limit); }
            if (!rotation) checked(attempt);
            return response;
        } catch (HttpFailure e) { throw e; }
        catch (IOException e) {
            android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(process);
            android.util.Log.w("ConversationAuth", loginPhase + ":network:" + e.getClass().getSimpleName() + ":importance=" + process.importance);
            throw sanitized(e);
        }
        finally {
            if (timeout != null) timeout.cancel(false);
            if (connection != null) connection.disconnect();
            activeHttp = null;
        }
    }

    private HttpURLConnection open(String endpoint, String method, String bearer, int attempt) throws IOException {
        checked(attempt);
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1
                || !("auth.openai.com".equals(url.getHost()) || "api.openai.com".equals(url.getHost())))
            throw new SafeFailure("接続先を確認できませんでした。");
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        activeHttp = connection;
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Conversation-Android/0.1");
        if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        try { checked(attempt); }
        catch (IOException e) { connection.disconnect(); throw e; }
        return connection;
    }

    private HttpFailure httpFailure(HttpURLConnection connection, int status) {
        String code = "";
        try (InputStream error = connection.getErrorStream()) {
            if (error != null) {
                JSONObject parsed = new JSONObject(readBounded(error, 65536));
                JSONObject object = parsed.optJSONObject("error");
                code = object == null ? parsed.optString("error") : object.optString("code");
            }
        } catch (Exception ignored) {}
        String message;
        if ("invalid_grant".equals(code) || status == 401) message = "ChatGPT の接続の有効期限が切れました。もう一度サインインしてください。";
        else if (status == 429 || code.startsWith("subscription_sharing_usage_")) message = "ChatGPT の利用上限に達したか、現在利用できません。ChatGPT の使用状況を確認してください。";
        else if (status == 403) message = "このアカウントまたはワークスペースでは利用が許可されていません。ChatGPT の設定を確認してください。";
        else if (status >= 500) message = "ChatGPT が一時的に利用できません。時間をおいてお試しください。";
        else message = "ChatGPT への要求が受け付けられませんでした。再接続または別のモデルでお試しください。";
        return new HttpFailure(message, status, "invalid_grant".equals(code));
    }

    private void handleUnauthorized(HttpFailure failure, int attempt) throws IOException {
        if (failure.status == 401 || failure.invalidGrant) clearTokens(attempt);
    }

    private void clearTokens(int attempt) throws IOException {
        try {
            JSONObject clean = snapshot();
            for (String key : Arrays.asList("access_token", "refresh_token", "id_token", "scope", "expires_at", "earliest_refresh_at")) clean.remove(key);
            persist(clean, attempt);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new SafeFailure("接続情報を消去できませんでした。"); }
    }

    private JSONObject snapshot() throws IOException {
        synchronized (stateLock) {
            if (storageError != null) throw new IOException(storageError);
            try { return new JSONObject(state.toString()); }
            catch (Exception e) { throw new SafeFailure("接続情報を読み取れませんでした。"); }
        }
    }

    private void persist(JSONObject replacement, int attempt) throws IOException {
        synchronized (stateLock) {
            checked(attempt);
            saveState(replacement);
        }
    }

    private void saveState(JSONObject replacement) throws IOException {
        try { store.save(replacement); }
        catch (IOException e) {
            storageError = "接続情報を安全に保存できませんでした。サインアウトしてから再接続してください。";
            throw new SafeFailure(storageError);
        }
        state = replacement;
    }

    private void checked(int attempt) throws IOException {
        if (generation.get() != attempt || Thread.currentThread().isInterrupted()) throw new SafeFailure("処理をキャンセルしました。");
    }

    private static Map<String,String> form(String... pairs) {
        Map<String,String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    private static String encode(Map<String,String> values) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String,String> value : values.entrySet()) {
            if (encoded.length() > 0) encoded.append('&');
            encoded.append(URLEncoder.encode(value.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(value.getValue(), "UTF-8"));
        }
        return encoded.toString();
    }

    private static String random(int size) {
        byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean hasScope(String scopes, String expected) { return Arrays.asList(scopes.split("\\s+")).contains(expected); }

    private static String readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096]; int count;
        while ((count = input.read(bytes)) != -1) {
            if (output.size() + count > limit) throw new SafeFailure("ChatGPT の応答が大きすぎます。");
            output.write(bytes, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String httpLine(InputStream input, int limit, long deadline) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while (System.nanoTime() < deadline && (value = input.read()) != -1) {
            if (value == '\n') return line.length() > 0 && line.charAt(line.length() - 1) == '\r' ? line.substring(0, line.length() - 1) : line.toString();
            if (value < 32 && value != '\r' && value != '\t' || value > 126 || line.length() >= limit) throw new IOException();
            line.append((char)value);
        }
        throw new SafeFailure("サインインの応答を読み取れませんでした。");
    }

    private static void callbackPage(Socket socket, boolean accepted) throws IOException {
        byte[] body = ("<!doctype html><meta charset=utf-8><meta name=referrer content=no-referrer><title>Conversation</title>"
                + "<p>" + (accepted ? "サインインの応答を受け取りました。Conversation に戻って接続を完了してください。" : "サインインの応答を確認できませんでした。Conversation に戻って再試行してください。")
                + "</p><a href=\"intent://auth/return#Intent;scheme=conversation;package=jp.tekewo.conversation;end\">Conversationに戻る</a>").getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(((accepted ? "HTTP/1.1 200 OK" : "HTTP/1.1 400 Bad Request")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush();
    }

    private static IOException sanitized(IOException error) {
        return error instanceof HttpFailure || error instanceof SafeFailure || error instanceof OAuthValidation.CallbackFailure
                || error instanceof SseReader.StreamFailure ? error
                : new SafeFailure("接続が切れたか、待機時間が終了しました。接続状態を確認してもう一度お試しください。");
    }

    private static String safeMessage(IOException error) {
        String message = error.getMessage();
        // Platform/network exception messages can contain credentials or URLs.
        if (error instanceof SafeFailure || error instanceof HttpFailure || error instanceof OAuthValidation.CallbackFailure
                || error instanceof SseReader.StreamFailure) return message;
        return "接続が切れたか、待機時間が終了しました。接続状態を確認してもう一度お試しください。";
    }

    private static final class HttpFailure extends IOException {
        final int status; final boolean invalidGrant;
        HttpFailure(String message, int status, boolean invalidGrant) { super(message); this.status = status; this.invalidGrant = invalidGrant; }
    }
    private static final class SafeFailure extends IOException {
        SafeFailure(String message) { super(message); }
    }
}
