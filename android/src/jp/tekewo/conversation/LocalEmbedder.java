package jp.tekewo.conversation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.Closeable;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class LocalEmbedder implements Closeable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession session;
    long tokenizer;

    LocalEmbedder(RagModels.Assets files) throws Exception {
        tokenizer = NativeRag.openTokenizer(Files.readAllBytes(files.tokenizer.toPath()));
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            session = environment.createSession(files.model.getAbsolutePath(), options);
        } catch (Exception error) { NativeRag.closeTokenizer(tokenizer); tokenizer = 0; throw error; }
    }
    synchronized float[] embed(String text) throws Exception {
        if (session == null) throw new IOException("意味検索モデルを開き直してください。");
        long[] ids = NativeRag.tokenize(tokenizer, text.getBytes(StandardCharsets.UTF_8));
        long[] mask = new long[ids.length]; Arrays.fill(mask, 1);
        long[] shape = {1, ids.length};
        try (OnnxTensor input = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape);
             OnnxTensor attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>(); inputs.put("input_ids", input); inputs.put("attention_mask", attention);
            try (OrtSession.Result result = session.run(inputs)) {
                FloatBuffer values = ((OnnxTensor)result.get(0)).getFloatBuffer();
                if (values == null || values.remaining() != ids.length * 256) throw new IOException("意味検索モデルの出力が不正です。");
                float[] vector = new float[256];
                for (int token = 0; token < ids.length; token++) for (int i = 0; i < 256; i++) vector[i] += values.get() / ids.length;
                double norm = 0;
                for (float value : vector) { if (!Float.isFinite(value)) throw new IOException("意味検索の数値が不正です。"); norm += (double)value * value; }
                if (norm < 1e-12) throw new IOException("意味検索のベクトルが空です。");
                float scale = (float)(1 / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) vector[i] *= scale;
                return vector;
            }
        }
    }
    @Override public synchronized void close() {
        if (session != null) { try { session.close(); } catch (Exception ignored) {} session = null; }
        if (tokenizer != 0) { NativeRag.closeTokenizer(tokenizer); tokenizer = 0; }
    }
}
