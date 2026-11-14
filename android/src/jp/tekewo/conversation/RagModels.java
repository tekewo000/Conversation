package jp.tekewo.conversation;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.Consumer;

/** Public, pinned model assets; conversation text never enters the download request. */
final class RagModels {
    static final String VERSION = "ruri-v3-30m-q8-256-v1";
    static final class Assets {
        final File model, tokenizer;
        Assets(File root) { model = new File(root, "model.onnx"); tokenizer = new File(root, "tokenizer.model"); }
    }
    static synchronized Assets ensure(Context context, Consumer<String> status) throws Exception {
        File root = new File(context.getFilesDir(), "models/" + VERSION);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("意味検索モデルの保存先を作れません。");
        Assets files = new Assets(root);
        download(files.tokenizer,
            "https://huggingface.co/cl-nagoya/ruri-v3-30m/resolve/24899e5de370b56d179604a007c0d727bf144504/tokenizer.model",
            1831879, "008293028e1a9d9a1038d9b63d989a2319797dfeaa03f171093a57b33a3a8277", status);
        download(files.model,
            "https://huggingface.co/onnx-community/ruri-v3-30m-ONNX/resolve/1b41a27f31213fc55a9c4653c067770e39406408/onnx/model_quantized.onnx",
            37048575, "bd07545fa4eb8e7765939536944da1138a7e61441d81633adfa1faf218df6bb1", status);
        return files;
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static void download(File file, String url, long size, String expectedHash, Consumer<String> status) throws Exception {
        if (file.length() == size && hash(file).equals(expectedHash)) return;
        File temporary = new File(file.getParentFile(), file.getName() + ".pending");
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("意味検索モデルをダウンロードできませんでした。");
            try (InputStream stream = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int read, previous = -1; long total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > size) throw new IOException("意味検索モデルのサイズが不正です。");
                    output.write(buffer, 0, read);
                    int progress = (int)(100 * total / size);
                    if (progress != previous) { previous = progress; status.accept("意味検索モデルを準備しています（" + progress + "%）"); }
                }
                output.getFD().sync();
            }
            if (temporary.length() != size || !hash(temporary).equals(expectedHash)) throw new IOException("意味検索モデルの検証に失敗しました。");
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { connection.disconnect(); Files.deleteIfExists(temporary.toPath()); }
    }
}
