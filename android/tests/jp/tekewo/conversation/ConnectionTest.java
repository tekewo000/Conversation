package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

public final class ConnectionTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ChatRequest.Model model = new ChatRequest.Model(new JSONObject("{\"slug\":\"test-model\",\"display_name\":\"Test model\","
                + "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"high\"},{\"effort\":\"ultra\"}],"
                + "\"service_tiers\":[{\"id\":\"default\"},{\"id\":\"priority\"}]}"));
        check(model.efforts.size() == 3 && model.efforts.contains("ultra"), "catalog efforts retained without a hardcoded model list");
        check(model.supportsFast, "priority tier enables Fast");
        JSONArray turns = new JSONArray().put(new JSONObject().put("role", "user").put("content", "こんにちは"));
        JSONObject request = new ChatRequest(model, "high", true).body("instructions", turns);
        check("test-model".equals(request.getString("model")), "selected model reaches request");
        check("high".equals(request.getJSONObject("reasoning").getString("effort")), "selected effort reaches request");
        check("priority".equals(request.getString("service_tier")), "Fast uses priority tier");
        check(request.getBoolean("stream") && !request.getBoolean("store")
                && "こんにちは".equals(request.getJSONArray("input").getJSONObject(0).getString("content"))
                && "instructions".equals(request.getString("instructions")), "streaming, privacy and conversation preserved");
        JSONObject defaults = new ChatRequest(model, "", false).body(null, turns);
        check(!defaults.has("tools"), "ordinary replies do not require image generation support");
        check(!defaults.has("reasoning") && "default".equals(defaults.getString("service_tier")), "default effort omitted and Fast off explicitly standard");
        fails(() -> new ChatRequest(model, "unsupported", false).body("", turns));
        fails(() -> new ChatRequest(model, "low", false).body("", new JSONArray()));
        ChatRequest.Model basic = new ChatRequest.Model(new JSONObject("{\"slug\":\"basic\",\"display_name\":\"Basic\"}"));
        check(basic.efforts.isEmpty() && !basic.supportsFast, "missing capabilities do not enable unsupported settings");
        check(!new ChatRequest(basic, "", false).body("", turns).has("reasoning"), "models without reasoning still work");
        fails(() -> new ChatRequest(basic, "", true).body("", turns));
        ChatRequest.Model legacy = new ChatRequest.Model(new JSONObject("{\"slug\":\"legacy\",\"display_name\":\"Legacy\",\"additional_speed_tiers\":[\"fast\"]}"));
        check(legacy.supportsFast, "legacy catalog Fast capability recognized");
        ChatRequest.Model standardOnly = new ChatRequest.Model(new JSONObject("{\"slug\":\"standard\",\"display_name\":\"Standard\","
                + "\"service_tiers\":[],\"additional_speed_tiers\":[\"fast\"]}"));
        check(!standardOnly.supportsFast, "explicit current tier catalog takes precedence over legacy hint");

        Map<String,String> callback = OAuthValidation.callback("/auth/callback?state=ok&code=a%2Bb&client_id=oaiapp_test", "ok", "");
        check("a+b".equals(callback.get("code")), "query decoding");
        fails(() -> OAuthValidation.callback("/auth/callback?state=wrong&code=secret&client_id=oaiapp_test", "ok", ""));
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&state=ok&code=a&client_id=oaiapp_test", "ok", ""));
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&code=a", "ok", ""));
        fails(() -> OAuthValidation.callback("/callback?state=ok&code=a&client_id=oaiapp_test", "ok", ""));
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&code=a&client_id=oaiapp_other", "ok", "oaiapp_saved"));
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&error=access_denied", "ok", ""));
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&code=a&client_id=dynamic_agent_client", "ok", ""));
        check("oaiapp_saved".equals(OAuthValidation.callback("/auth/callback?state=ok&code=a", "ok", "oaiapp_saved").get("client_id")), "returning client retained");
        fails(() -> OAuthValidation.callback("/auth/callback?state=ok&code=a&client_id=oaiapp_test#fragment", "ok", ""));
        fails(() -> OAuthValidation.callback("http://evil/auth/callback?state=ok&code=a&client_id=oaiapp_test", "ok", ""));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey)pair.getPublic();
        JSONObject key = new JSONObject().put("kid", "key1").put("kty", "RSA").put("alg", "RS256").put("use", "sig")
                .put("n", b64(unsigned(publicKey.getModulus().toByteArray())))
                .put("e", b64(unsigned(publicKey.getPublicExponent().toByteArray())));
        JSONObject jwks = new JSONObject().put("keys", new JSONArray().put(key));
        JSONObject claims = new JSONObject().put("iss", "https://auth.openai.com").put("aud", new JSONArray().put("oaiapp_test"))
                .put("sub", "subject").put("exp", 2000).put("iat", 900).put("nonce", "nonce");
        JSONObject verified = OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000);
        check("subject".equals(verified.getString("sub")), "verified subject");
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "none"), jwks, "oaiapp_test", "nonce", "", 1000));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), jwks, "oaiapp_other", "nonce", "", 1000));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), jwks, "oaiapp_test", "wrong", "", 1000));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), jwks, "oaiapp_test", "nonce", "another", 1000));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), jwks, "oaiapp_test", "nonce", "", 2006));
        JSONObject badIssuer = new JSONObject(claims.toString()).put("iss", "https://example.com");
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, badIssuer, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject multi = new JSONObject(claims.toString()).put("aud", new JSONArray().put("oaiapp_test").put("other"));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, multi, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject missingExp = new JSONObject(claims.toString()); missingExp.remove("exp");
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, missingExp, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        KeyPair other = generator.generateKeyPair();
        fails(() -> OAuthValidation.verifyIdToken(jwt(other, claims, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject numericSubject = new JSONObject(claims.toString()).put("sub", 42);
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, numericSubject, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject fractionalExpiry = new JSONObject(claims.toString()).put("exp", 2000.5);
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, fractionalExpiry, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject futureIssued = new JSONObject(claims.toString()).put("iat", 1006);
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, futureIssued, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        JSONObject wrongPurpose = new JSONObject().put("keys", new JSONArray().put(new JSONObject(key.toString()).put("use", "enc")));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), wrongPurpose, "oaiapp_test", "nonce", "", 1000));
        JSONObject duplicateKeys = new JSONObject().put("keys", new JSONArray().put(key).put(key));
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, claims, "RS256"), duplicateKeys, "oaiapp_test", "nonce", "", 1000));
        JSONObject missingNonce = new JSONObject(claims.toString()); missingNonce.remove("nonce");
        fails(() -> OAuthValidation.verifyIdToken(jwt(pair, missingNonce, "RS256"), jwks, "oaiapp_test", "nonce", "", 1000));
        check("subject".equals(OAuthValidation.verifyIdToken(jwt(pair, missingNonce, "RS256"), jwks,
                "oaiapp_test", null, "subject", 1000).getString("sub")), "refresh identity without nonce");

        StringBuilder deltas = new StringBuilder();
        String text = stream(": heartbeat\r\n\r\nevent: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"こん\"}\r\n\r\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"にちは\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n", deltas);
        check("こんにちは".equals(text) && text.equals(deltas.toString()), "UTF-8 streamed deltas");
        String hiddenAndFinal = "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"hidden\",\"type\":\"message\",\"role\":\"assistant\",\"channel\":\"analysis\"}}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"hidden\",\"delta\":\"内部の文章\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"channel\":\"analysis\",\"delta\":\"別の内部文章\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"日本語の返答\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"role\":\"assistant\",\"channel\":\"analysis\",\"content\":[{\"type\":\"output_text\",\"text\":\"内部の文章\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"channel\":\"final\",\"content\":[{\"type\":\"output_text\",\"text\":\"日本語の返答\"}]}]}}\n\n";
        StringBuilder visibleDeltas = new StringBuilder();
        check(stream(hiddenAndFinal, visibleDeltas).equals("日本語の返答") && visibleDeltas.toString().equals("日本語の返答"), "internal channels must never become visible response text");
        check("final".equals(stream("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}]}}\n\n", new StringBuilder())), "final output");
        fails(() -> stream("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n", new StringBuilder()));
        fails(() -> stream("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n", new StringBuilder()));
        fails(() -> stream("data: {\"type\":\"response.incomplete\"}\n\n", new StringBuilder()));
        fails(() -> stream("data: [DONE]\n\n", new StringBuilder()));
        fails(() -> stream("data: {bad}\n\n", new StringBuilder()));
        check("split".equals(stream("event: ignored\n: comment\ndata: {\"type\":\"response.completed\",\n"
                + "data: \"response\":{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"split\"}]}]}}\n\n", new StringBuilder())), "multiline SSE data");
        fails(() -> SseReader.read(new ByteArrayInputStream(new byte[] {(byte)0xc0, (byte)0xaf}), ignored -> {}));
        StringBuilder oversized = new StringBuilder("data: ");
        for (int i = 0; i < 2 * 1024 * 1024; i++) oversized.append('x');
        fails(() -> stream(oversized.toString(), new StringBuilder()));
        String completeJson = "{\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"完了\"}]}]}";
        check("完了".equals(SseReader.readJson(new ByteArrayInputStream(completeJson.getBytes(StandardCharsets.UTF_8)), ignored -> {})), "completed JSON response");
        fails(() -> SseReader.readJson(new ByteArrayInputStream(completeJson.replace("completed", "in_progress").getBytes(StandardCharsets.UTF_8)), ignored -> {}));
        fails(() -> SseReader.readJson(new ByteArrayInputStream("{\"message\":\"not a response\"}".getBytes(StandardCharsets.UTF_8)), ignored -> {}));
        check("完了".equals(SseReader.readAuto(new ByteArrayInputStream(completeJson.getBytes(StandardCharsets.UTF_8)), ignored -> {})), "payload detection without content type");
        check("完了".equals(SseReader.readAuto(new ByteArrayInputStream(("data: {\"type\":\"response.completed\",\"response\":" + completeJson + "}\n\n").getBytes(StandardCharsets.UTF_8)), ignored -> {})), "SSE without content type");
        fails(() -> SseReader.readAuto(new ByteArrayInputStream("<html>not an API reply</html>".getBytes(StandardCharsets.UTF_8)), ignored -> {}));
        System.out.println("Connection tests passed: " + checks);
    }
    private static String stream(String text, StringBuilder deltas) throws Exception {
        return SseReader.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), deltas::append);
    }
    private static String jwt(KeyPair pair, JSONObject claims, String alg) throws Exception {
        String head = b64(new JSONObject().put("alg", alg).put("kid", "key1").toString().getBytes(StandardCharsets.UTF_8));
        String body = b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(pair.getPrivate());
        signature.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
        return head + "." + body + "." + b64(signature.sign());
    }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] unsigned(byte[] bytes) { return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void fails(Checked action) throws Exception {
        try { action.run(); } catch (IOException | java.security.GeneralSecurityException expected) { checks++; return; }
        throw new AssertionError("Expected safe rejection");
    }
    private interface Checked { void run() throws Exception; }
}
