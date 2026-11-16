package jp.tekewo.conversation;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Run against the actual installed classes, including the old Release for the upgrade baseline. */
public final class DataSecurityProbe extends Instrumentation {
    private ClassLoader loader;
    private String mode;
    private static final String CANARY="upgrade-private-canary-20261116";
    @Override public void onCreate(Bundle args) { super.onCreate(args);mode=args==null?"Verify":args.getString("mode","Verify");start(); }
    private Class<?> type(String name) throws Exception { return loader.loadClass("jp.tekewo.conversation."+name); }
    private Object call(Object target,String name,Class<?>[] parameters,Object... args) throws Exception {
        Class<?> owner=target instanceof Class?(Class<?>)target:target.getClass();
        Method method=owner.getDeclaredMethod(name,parameters);method.setAccessible(true);
        return method.invoke(target instanceof Class?null:target,args);
    }
    private Object field(Object object,String name) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
    private Object people(Context context) throws Exception { Constructor<?> ctor=type("PersonRegistry").getDeclaredConstructor(File.class);ctor.setAccessible(true);return ctor.newInstance(context.getFilesDir()); }
    private String hash(byte[] data) throws Exception {
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(data)) result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();int code=Activity.RESULT_OK;
        try {
            Context context=getTargetContext();loader=context.getClassLoader();Object app=context.getApplicationContext();
            File record=new File(context.getNoBackupFilesDir(),"encryption-upgrade-fixture.json");
            if("Models".equals(mode)) {
                File models=new File(context.getFilesDir(),"models/ruri-v3-30m-q8-256-v1");if(!models.isDirectory()&&!models.mkdirs()) throw new IOException("Cannot prepare models");
                String[] names={"model.onnx","tokenizer.model"};
                String[] hashes={"bd07545fa4eb8e7765939536944da1138a7e61441d81633adfa1faf218df6bb1","008293028e1a9d9a1038d9b63d989a2319797dfeaa03f171093a57b33a3a8277"};
                long[] lengths={37048575,1831879};
                for(int i=0;i<names.length;i++) {
                    File pending=new File(models,names[i]+".probe-pending");
                    try(android.os.ParcelFileDescriptor pipe=getUiAutomation().executeShellCommand("cat /data/local/tmp/conversation-encryption-models/"+names[i]);
                        InputStream input=new FileInputStream(pipe.getFileDescriptor());OutputStream output=new FileOutputStream(pending)) {
                        byte[] buffer=new byte[65536];int n;long size=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
                        while((n=input.read(buffer))!=-1) { size+=n;if(size>lengths[i]) throw new IOException("Oversized pinned model");output.write(buffer,0,n);digest.update(buffer,0,n); }
                        StringBuilder actual=new StringBuilder();for(byte value:digest.digest()) actual.append(String.format(Locale.ROOT,"%02x",value&255));
                        if(size!=lengths[i]||!hashes[i].equals(actual.toString())) throw new IOException("Pinned model checksum mismatch");
                    }
                    Files.move(pending.toPath(),new File(models,names[i]).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                }
                result.putString("stream","DATA_SECURITY_MODELS_PASS: pinned public models provisioned for offline inference\n");
            } else if("Baseline".equals(mode)) {
                if(record.exists()) throw new IOException("An upgrade fixture already exists; preserve it for verification.");
                Intent intent=new Intent().setClassName(context.getPackageName(),context.getPackageName()+".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Activity activity=startActivitySync(intent);waitForIdleSync();runOnMainSync(activity::finish);waitForIdleSync();
                Object registry=people(context);
                Object previous=call(registry,"active",new Class<?>[]{});
                Object doc=call(type("PersonDocument"),"named",new Class<?>[]{String.class},"Encryption upgrade fixture");
                Object person=call(registry,"stage",new Class<?>[]{type("PersonDocument"),String.class},doc,null);
                String id=(String)field(person,"id");File directory=(File)field(person,"directory");
                JSONObject payload=(JSONObject)call(doc,"importPayload",new Class<?>[]{String.class},id);
                Object switched=call(type("MainActivity"),"nativeSwitchPerson",new Class<?>[]{byte[].class,byte[].class},directory.getPath().getBytes("UTF-8"),payload.toString().getBytes("UTF-8"));
                if(!"".equals(switched)) throw new AssertionError("Baseline core initialization failed: "+switched);
                call(registry,"commit",new Class<?>[]{type("PersonRegistry$Person")},person);
                call(type("MainActivity"),"nativeAddMessage",new Class<?>[]{byte[].class,byte[].class},"user".getBytes("UTF-8"),CANARY.getBytes("UTF-8"));
                call(type("MainActivity"),"nativeAddMessage",new Class<?>[]{byte[].class,byte[].class},"assistant".getBytes("UTF-8"),"upgrade reply".getBytes("UTF-8"));
                JSONObject snapshot=new JSONObject((String)call(type("MainActivity"),"nativeMessages",new Class<?>[]{int.class},-1));
                if(snapshot.getInt("total")!=2) throw new AssertionError("Baseline messages were not saved");
                byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
                call(type("AttachmentFiles"),"save",new Class<?>[]{File.class,String.class,String.class,InputStream.class},directory,"fixture.png","png",new ByteArrayInputStream(png));
                call(type("AttachmentFiles"),"save",new Class<?>[]{File.class,String.class,String.class,InputStream.class},directory,"fixture.txt","txt",new ByteArrayInputStream(CANARY.getBytes("UTF-8")));
                call(type("AttachmentFiles"),"save",new Class<?>[]{File.class,String.class,String.class,InputStream.class},directory,"fixture.pdf","pdf",new ByteArrayInputStream(("%PDF-1.4\n"+CANARY).getBytes("UTF-8")));
                byte[] root=(byte[])call(app,"get",new Class<?>[]{File.class,boolean.class},directory,true);
                call(app,"writeRecovery",new Class<?>[]{File.class,OutputStream.class},directory,new ByteArrayOutputStream());
                context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().putString("draft_"+id,CANARY+"-draft").commit();
                JSONObject state=new JSONObject().put("id",id).put("key_sha256",hash(root)).put("previous",previous==null?"":field(previous,"id"));Arrays.fill(root,(byte)0);
                Files.write(record.toPath(),state.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
                result.putString("stream","DATA_SECURITY_BASELINE_PASS: legacy Release conversation, image key, PDF/text and draft seeded\n");
            } else {
                if(!record.isFile()) throw new IOException("Run the Baseline probe against the old signed Release first.");
                JSONObject baseline=new JSONObject(new String(Files.readAllBytes(record.toPath()),StandardCharsets.UTF_8));String id=baseline.getString("id");
                File directory=new File(context.getFilesDir(),"people/"+id);
                call(type("DataMigration"),"ensure",new Class<?>[]{Context.class},context);
                Object labelRegistry=people(context),originalPerson=call(labelRegistry,"active",new Class<?>[]{});
                String originalName=(String)field(originalPerson,"name");
                call(labelRegistry,"setDisplayName",new Class<?>[]{String.class,String.class},id,"Release display-name fixture");
                Object renamed=call(people(context),"active",new Class<?>[]{});
                if(!"Release display-name fixture".equals(field(renamed,"displayName"))||!originalName.equals(field(renamed,"name"))||!id.equals(field(renamed,"id"))) throw new AssertionError("Display-name update changed the original person");
                byte[] root=(byte[])call(app,"get",new Class<?>[]{File.class,boolean.class},directory,false);
                if(!hash(root).equals(baseline.getString("key_sha256"))) throw new AssertionError("Existing image key changed during upgrade");Arrays.fill(root,(byte)0);
                JSONObject state=(JSONObject)call(type("PersonState"),"load",new Class<?>[]{File.class},directory);
                if(!state.getString("draft").equals(CANARY+"-draft")) throw new AssertionError("Draft changed during upgrade");
                if(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).contains("draft_"+id)) throw new AssertionError("Plain draft remains");
                Object db=call(type("EncryptedDatabase"),"open",new Class<?>[]{File.class,File.class,boolean.class},directory,new File(directory,"conversation.sqlite3"),true);
                try {
                    Cursor rows=(Cursor)call(db,"rawQuery",new Class<?>[]{String.class,String[].class},"SELECT content FROM messages ORDER BY id",new String[0]);
                    try { if(!rows.moveToFirst()||!CANARY.equals(rows.getString(0))||rows.getCount()!=2) throw new AssertionError("Original messages changed"); } finally { rows.close(); }
                } finally { ((Closeable)db).close(); }
                Intent intent=new Intent().setClassName(context.getPackageName(),context.getPackageName()+".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Activity activity=startActivitySync(intent);waitForIdleSync();
                String snapshot=(String)call(type("MainActivity"),"nativeMessages",new Class<?>[]{int.class},-1);
                if(!snapshot.contains(CANARY)) throw new AssertionError("Core cannot read migrated ciphertext");
                Constructor<?> ctor=type("BackupArchive").getDeclaredConstructor(File.class,File.class);ctor.setAccessible(true);
                Object writer=ctor.newInstance(directory,new File(directory,"export"));
                try {
                    String error=(String)call(type("MainActivity"),"nativeExport",new Class<?>[]{type("BackupArchive")},writer);
                    if(!error.isEmpty()) throw new AssertionError("Encrypted native export failed: "+error);
                    call(writer,"finish",new Class<?>[]{});
                    ByteArrayOutputStream zip=new ByteArrayOutputStream();call(type("BackupArchive"),"write",new Class<?>[]{File.class,OutputStream.class},field(writer,"directory"),zip);
                    if(new String(zip.toByteArray(),StandardCharsets.ISO_8859_1).contains(CANARY)) throw new AssertionError("ZIP contains plaintext");
                } finally { ((AutoCloseable)writer).close(); }
                try(java.util.stream.Stream<Path> paths=Files.walk(directory.toPath())) {
                    for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator)
                        if(new String(Files.readAllBytes(path),StandardCharsets.ISO_8859_1).contains(CANARY)) throw new AssertionError("Plain canary remains in app files");
                }
                runOnMainSync(activity::finish);waitForIdleSync();
                call(type("MainActivity"),"nativeSwitchPerson",new Class<?>[]{byte[].class,byte[].class},new byte[0],new byte[0]);
                Object registry=people(context);call(registry,"delete",new Class<?>[]{String.class},id);call(app,"deleteKeys",new Class<?>[]{String.class},id);
                if(!baseline.getString("previous").isEmpty()) call(registry,"activate",new Class<?>[]{String.class},baseline.getString("previous"));
                Files.delete(record.toPath());
                result.putString("stream","DATA_SECURITY_RELEASE_PASS: signed upgrade preserved key/messages/draft, display name and encrypted native ZIP verified\n");
            }
        } catch(Throwable error) {
            StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));result.putString("stream","DATA_SECURITY_FAIL\n"+trace);code=Activity.RESULT_CANCELED;
        }
        finish(code,result);
    }
}
