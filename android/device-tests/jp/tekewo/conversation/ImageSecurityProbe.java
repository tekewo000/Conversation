package jp.tekewo.conversation;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Exercises production classes in the installed Release using only synthetic images and keys. */
public final class ImageSecurityProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); File person = null;
        ConversationApplication app = null;
        int code = Activity.RESULT_CANCELED;
        try {
            app = (ConversationApplication)getTargetContext().getApplicationContext();
            person = Files.createTempDirectory(getTargetContext().getFilesDir().toPath(), "release-images-").toFile();
            Bitmap bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.GREEN);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Synthetic PNG encoding"); }
            finally { bitmap.recycle(); }
            byte[] png = output.toByteArray();
            AttachmentFiles.Ref picked = AttachmentFiles.save(person, "Synthetic photo", "png", new ByteArrayInputStream(png));
            String answer = AttachmentMedia.response(person, "![Synthetic generated image](data:image/png;base64," + Base64.getEncoder().encodeToString(png) + ")");
            AttachmentFiles.Ref generated = AttachmentFiles.references(answer).get(0);
            for (AttachmentFiles.Ref ref : Arrays.asList(picked, generated)) {
                check(ImageVault.encrypted(Files.readAllBytes(AttachmentFiles.resolve(person, ref.fileName).toPath())), "Images are encrypted on disk");
                check(Arrays.equals(png, AttachmentFiles.read(person, ref)), "Images still decode exactly");
            }
            JSONArray prepared = AttachmentMedia.input(person, new JSONArray().put(new JSONObject().put("role", "user").put("content", picked.markdown())))
                    .getJSONObject(0).getJSONArray("content");
            boolean image = false;
            for (int i = 0; i < prepared.length(); i++) if (prepared.getJSONObject(i).optString("type").equals("input_image")) image = true;
            check(image, "Vision input contains the image");
            File file = AttachmentFiles.resolve(person, picked.fileName);
            try { ImageVault.backup(person, file); throw new AssertionError("Unrecoverable backup accepted"); } catch (IOException expected) { }
            ByteArrayOutputStream recovery = new ByteArrayOutputStream(); app.writeRecovery(person, recovery);
            byte[] sealed = ImageVault.backup(person, file);
            Properties properties = new Properties(); properties.load(new ByteArrayInputStream(recovery.toByteArray()));
            byte[] key = ImageVault.recoveryKey(properties);
            try {
                check(Arrays.equals(png, ImageVault.open(key, person.getName(), picked.fileName, sealed)), "Portable recovery matches");
                byte[] damaged = sealed.clone(); damaged[damaged.length - 1] ^= 1;
                try { ImageVault.open(key, person.getName(), picked.fileName, damaged); throw new AssertionError("Tamper accepted"); } catch (IOException expected) { }
                File wrapped = new File(getTargetContext().getNoBackupFilesDir(), "images-" + person.getName() + "-connection.v1");
                byte[] original = Files.readAllBytes(wrapped.toPath()), corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1;
                try {
                    Files.write(wrapped.toPath(), corrupt);
                    try { app.get(person, true); throw new AssertionError("Corrupt key replaced"); } catch (IOException expected) { }
                    check(Arrays.equals(corrupt, Files.readAllBytes(wrapped.toPath())), "Corrupt wrapped key preserved");
                } finally { Files.write(wrapped.toPath(), original); }
                app.deleteKeys(person.getName());
                try { app.get(person, true); throw new AssertionError("Lost key replaced"); } catch (IOException expected) { }
                check(Arrays.equals(png, ImageVault.open(key, person.getName(), picked.fileName, sealed)), "Recovery works without device key");
            } finally { Arrays.fill(key, (byte)0); }
            result.putString("stream", "IMAGE_SECURITY_RELEASE_PASS: installed Release encrypts picked/generated images, builds vision input, gates backups, detects tamper and recovers without its device key\n");
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", "IMAGE_SECURITY_RELEASE_FAIL\n" + trace);
        } finally {
            try { if (person != null) { if (app != null) app.deleteKeys(person.getName()); remove(person); } }
            catch (Exception error) { code = Activity.RESULT_CANCELED; result.putString("stream", "IMAGE_SECURITY_RELEASE_FAIL: synthetic fixture cleanup failed\n"); }
        }
        finish(code, result);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void remove(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }
}
