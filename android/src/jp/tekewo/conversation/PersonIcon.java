package jp.tekewo.conversation;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Normalize picked images to a small square before they enter person storage. */
final class PersonIcon {
    static Bitmap decode(byte[] bytes) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float scale = Math.min(1f, 512f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    static byte[] read(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("画像を開けませんでした。");
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; int count;
        while ((count = stream.read(chunk)) != -1) {
            if (source.size() + count > 16 * 1024 * 1024) throw new IOException("アイコンに使う画像は16MiB以内にしてください。");
            source.write(chunk, 0, count);
        }
        Bitmap decoded = decode(source.toByteArray());
        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        Bitmap square = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2, (decoded.getHeight() - side) / 2, side, side);
        Bitmap icon = Bitmap.createScaledBitmap(square, 256, 256, true);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!icon.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("アイコンを保存できませんでした。");
            return output.toByteArray();
        } finally {
            icon.recycle();
            if (square != icon) square.recycle();
            if (decoded != square && decoded != icon) decoded.recycle();
        }
    }
}
