package jp.tekewo.conversation;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** Grants seekable, read-only access to decrypted bytes without a plaintext disk cache. */
public final class AttachmentProvider extends ContentProvider {
    private static final HandlerThread reads=new HandlerThread("Conversation attachment reads");
    static { reads.start(); }
    static Uri uri(Context context,File person,AttachmentFiles.Ref ref) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".attachments")
                .appendPath(person.getName()).appendPath(ref.fileName).build();
    }
    @Override public boolean onCreate() { return true; }
    private File person(Uri uri) throws IOException {
        List<String> paths=uri.getPathSegments();
        if(paths.size()!=2) throw new IOException("Invalid attachment URI");
        String id=paths.get(0);
        try { if(!UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid person"); }
        catch(IllegalArgumentException error) { throw new IOException("Invalid person"); }
        File person=new File(getContext().getFilesDir(),"people/"+id);
        AttachmentFiles.resolve(person,paths.get(1)); return person;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            File person=person(uri);
            byte[] data=AttachmentFiles.read(person,new AttachmentFiles.Ref("",uri.getLastPathSegment()));
            try {
                return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() { return data.length; }
                        @Override public int onRead(long offset,int size,byte[] output) {
                            if(offset<0||offset>=data.length) return 0;
                            int count=(int)Math.min(Math.min((long)size,output.length),data.length-offset);
                            System.arraycopy(data,(int)offset,output,0,count); return count;
                        }
                        @Override public void onRelease() { Arrays.fill(data,(byte)0); }
                    },new Handler(reads.getLooper()));
            } catch(Exception error) { Arrays.fill(data,(byte)0); throw error; }
        } catch(Exception error) { throw new FileNotFoundException("添付ファイルを復号して開けません。"); }
    }
    @Override public String getType(Uri uri) {
        String name=uri.getLastPathSegment();
        if(name==null) return "application/octet-stream";
        if(name.endsWith(".pdf")) return "application/pdf";
        if(name.endsWith(".txt")) return "text/plain";
        if(name.endsWith(".mp4")) return "video/mp4";
        if(name.endsWith(".webm")) return "video/webm";
        if(name.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if(projection==null) projection=new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor result=new MatrixCursor(projection);
        try {
            File file=AttachmentFiles.resolve(person(uri),uri.getLastPathSegment());
            Object[] values=new Object[projection.length];
            for(int i=0;i<projection.length;i++) {
                if(OpenableColumns.DISPLAY_NAME.equals(projection[i])) values[i]=file.getName();
                else if(OpenableColumns.SIZE.equals(projection[i])) values[i]=Math.max(0,file.length()-DataVault.OVERHEAD);
            }
            result.addRow(values);
        } catch(Exception ignored) { }
        return result;
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String where,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String where,String[] args) { throw new UnsupportedOperationException(); }
}
