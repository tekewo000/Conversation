package jp.tekewo.conversation;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

final class ImageDownload {
    static Uri save(Context context, File person, AttachmentFiles.Ref ref) throws Exception {
        if (!ref.image()) throw new IOException("保存できるのは画像だけです。");
        byte[] original = AttachmentFiles.read(person, ref);
        String extension = ref.fileName.substring(ref.fileName.lastIndexOf('.') + 1);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Conversation-" + ref.fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, extension.equals("jpg") ? "image/jpeg" : "image/" + extension);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Conversation");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        android.content.ContentResolver resolver = context.getContentResolver();
        Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) throw new IOException("画像の保存先を作成できませんでした。");
        try {
            try (OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("画像の保存先を開けませんでした。");
                output.write(original);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(destination, ready, null, null) != 1) throw new IOException("画像の保存を確定できませんでした。");
            return destination;
        } catch (Exception error) {
            try { resolver.delete(destination, null, null); }
            catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}
