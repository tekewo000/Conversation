package jp.tekewo.conversation;

import org.json.*;
import java.io.IOException;
import java.util.Arrays;

public final class ImageApiTest {
    public static void main(String[] args) throws Exception {
        ImageApi.validateKey("sk-synthetic_key_for_local_test_only");
        try { ImageApi.validateKey("sk-bad\r\nInjected:header"); throw new AssertionError(); } catch (IOException expected) {}
        JSONObject body = ImageApi.body("青い丸を描いて", ImageApi.DEFAULT_MODEL);
        assert body.getString("prompt").equals("青い丸を描いて");
        assert body.getInt("n") == 1 && body.getString("quality").equals("medium");
        assert body.getString("size").equals("auto") && body.getString("output_format").equals("png");
        assert !body.has("api_key") && !body.has("input") && !body.has("instructions");
        try { ImageApi.body(" ", ImageApi.DEFAULT_MODEL); throw new AssertionError(); } catch (IOException expected) {}
        try { ImageApi.body("prompt", "bad\nmodel"); throw new AssertionError(); } catch (IOException expected) {}
        char[] encoded = new char[2 * 1024 * 1024 + 4]; Arrays.fill(encoded, 'A');
        JSONObject response = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("b64_json", new String(encoded))));
        assert ImageApi.image(response.toString()).startsWith("![AIの画像](data:image/png;base64,");
        try { ImageApi.image("{\"data\":[]}"); throw new AssertionError(); } catch (IOException expected) {}
        try { ImageApi.image("{\"data\":[{\"b64_json\":\"bad characters!\"}]}"); throw new AssertionError(); } catch (IOException expected) {}
        assert ImageApi.failure(401).getMessage().contains("APIキー");
        assert ImageApi.failure(429).getMessage().contains("課金");
        ImageApi api = new ImageApi(); int attempt = api.generation(); api.cancel();
        try {
            api.generate("sk-synthetic_key_for_local_test_only", ImageApi.DEFAULT_MODEL, "停止した生成", attempt);
            throw new AssertionError("Cancelled paid attempt was accepted");
        } catch (IOException expected) { assert expected.getMessage().contains("キャンセル"); }
        System.out.println("PASS: paid image request contains only the prompt, bounded image parsing, key/header validation and safe API errors");
    }
}
