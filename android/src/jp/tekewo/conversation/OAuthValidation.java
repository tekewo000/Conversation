package jp.tekewo.conversation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class OAuthValidation {
    private OAuthValidation() {}

    static Map<String,String> callback(String target, String state, String selectedClient) throws IOException {
        try {
            URI uri = new URI(target);
            if (!target.startsWith("/auth/callback?") || uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getFragment() != null || !"/auth/callback".equals(uri.getRawPath())) throw rejected();
            Map<String,String> values = new LinkedHashMap<>();
            for (String item : uri.getRawQuery().split("&", -1)) {
                String[] parts = item.split("=", 2);
                String name = URLDecoder.decode(parts[0], "UTF-8");
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
                if (values.put(name, value) != null) throw rejected();
            }
            if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                    values.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) throw rejected();
            if (values.containsKey("error")) throw new CallbackFailure("ChatGPT のサインインが許可されませんでした。");
            if (values.getOrDefault("code", "").isEmpty()) throw rejected();
            String client = values.getOrDefault("client_id", selectedClient);
            if (client.isEmpty() || "dynamic_agent_client".equals(client) || client.length() > 512
                    || (!selectedClient.isEmpty() && !selectedClient.equals(client))) throw rejected();
            values.put("client_id", client);
            return values;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw rejected(); }
    }

    static JSONObject verifyIdToken(String token, JSONObject jwks, String client, String nonce,
            String selectedSubject, long nowSeconds) throws GeneralSecurityException {
        try {
            if (token == null || token.length() > 65536 || client.isEmpty()) throw rejectedToken();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw rejectedToken();
            JSONObject header = new JSONObject(new String(decode(parts[0]), StandardCharsets.UTF_8));
            if (!"RS256".equals(header.optString("alg")) || header.has("crit") || header.has("b64")) throw rejectedToken();
            String kid = header.getString("kid");
            if (kid.isEmpty()) throw rejectedToken();
            JSONObject key = null;
            JSONArray keys = jwks.getJSONArray("keys");
            for (int i = 0; i < keys.length(); i++) {
                JSONObject candidate = keys.getJSONObject(i);
                if (kid.equals(candidate.optString("kid"))) {
                    if (key != null) throw rejectedToken();
                    key = candidate;
                }
            }
            if (key == null || !"RSA".equals(key.optString("kty"))
                    || !"RS256".equals(key.optString("alg", "RS256"))
                    || !"sig".equals(key.optString("use", "sig"))) throw rejectedToken();
            if (key.has("key_ops")) {
                boolean verify = false;
                JSONArray operations = key.getJSONArray("key_ops");
                for (int i = 0; i < operations.length(); i++) verify |= "verify".equals(operations.optString(i));
                if (!verify) throw rejectedToken();
            }
            BigInteger modulus = new BigInteger(1, decode(key.getString("n")));
            BigInteger exponent = new BigInteger(1, decode(key.getString("e")));
            if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || exponent.bitLength() > 32
                    || exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)) throw rejectedToken();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decode(parts[2]))) throw rejectedToken();
            JSONObject claims = new JSONObject(new String(decode(parts[1]), StandardCharsets.UTF_8));
            if (!"https://auth.openai.com".equals(claims.getString("iss"))) throw rejectedToken();
            Object audience = claims.get("aud");
            boolean matches = client.equals(audience);
            int audiences = 1;
            if (audience instanceof JSONArray) {
                JSONArray list = (JSONArray)audience;
                audiences = list.length();
                for (int i = 0; i < list.length(); i++) matches |= client.equals(list.get(i));
            }
            if (!matches || (audiences > 1 && !client.equals(claims.optString("azp")))
                    || (claims.has("azp") && !client.equals(claims.getString("azp")))) throw rejectedToken();
            long expiry = integerClaim(claims, "exp"), issued = integerClaim(claims, "iat");
            if (expiry <= nowSeconds - 5 || issued > nowSeconds + 5 || expiry <= issued
                    || (claims.has("nbf") && integerClaim(claims, "nbf") > nowSeconds + 5)) throw rejectedToken();
            if (!(claims.get("sub") instanceof String)) throw rejectedToken();
            String subject = claims.getString("sub");
            if (subject.trim().isEmpty() || (!selectedSubject.isEmpty() && !selectedSubject.equals(subject))) throw rejectedToken();
            if (nonce != null && !nonce.equals(claims.getString("nonce"))) throw rejectedToken();
            return claims;
        } catch (GeneralSecurityException e) { throw rejectedToken(); }
        catch (Exception e) { throw rejectedToken(); }
    }

    private static long integerClaim(JSONObject claims, String name) throws Exception {
        Object value = claims.get(name);
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) throw rejectedToken();
        return ((Number)value).longValue();
    }
    private static byte[] decode(String value) throws GeneralSecurityException {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw rejectedToken();
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw rejectedToken(); }
    }
    static final class CallbackFailure extends IOException {
        CallbackFailure(String message) { super(message); }
    }
    private static IOException rejected() { return new CallbackFailure("サインインの戻り先を確認できませんでした。もう一度お試しください。"); }
    private static GeneralSecurityException rejectedToken() { return new GeneralSecurityException("ChatGPT の本人確認に失敗しました。もう一度サインインしてください。"); }
}
