package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Offline recovery with the original image recovery key. */
public final class BackupRestore {
    static byte[] readFile(File root,String name) throws IOException {
        EncryptedArchive.validatePath(name);
        File file=new File(root,name);
        if(Files.isSymbolicLink(root.toPath()) || !root.isDirectory() || !file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) throw new IOException("Invalid backup root");
        for(File current=file;;current=current.getParentFile()) {
            if(current==null||Files.isSymbolicLink(current.toPath())) throw new IOException("Symbolic links are not accepted");
            if(current.getCanonicalFile().equals(root.getCanonicalFile())) break;
        }
        if(!file.isFile() || file.length()>DataVault.MAX_BYTES+DataVault.OVERHEAD) throw new IOException("Missing or oversized backup file");
        return Files.readAllBytes(file.toPath());
    }
    static JSONObject verify(byte[] key,String person,File source) throws Exception {
        if(!Arrays.equals(readFile(source,"format.json"),EncryptedArchive.FORMAT)) throw new IOException("Unsupported backup format");
        JSONObject manifest=EncryptedArchive.manifest(key,person,readFile(source,"archive.enc"));
        if(!manifest.optBoolean("complete")) throw new IOException("Backup is incomplete");
        JSONObject entries=manifest.getJSONObject("files");
        for(Iterator<String> names=entries.keys();names.hasNext();) {
            String name=names.next(); EncryptedArchive.read(key,person,name,entries.getJSONObject(name),p->readFile(source,p));
        }
        return manifest;
    }
    static void restore(byte[] key,String person,File source,File target) throws Exception {
        if(target.exists() || target.getCanonicalFile().toPath().startsWith(source.getCanonicalFile().toPath())) throw new IOException("Use a new output directory outside the source");
        JSONObject entries=verify(key,person,source).getJSONObject("files");
        if(!target.mkdirs()) throw new IOException("Cannot create recovery directory");
        for(Iterator<String> names=entries.keys();names.hasNext();) {
            String name=names.next();
            byte[] bytes=EncryptedArchive.read(key,person,name,entries.getJSONObject(name),p->readFile(source,p));
            if(name.endsWith(".enc") && ImageVault.encrypted(bytes)) {
                name=name.substring(0,name.length()-4);
                bytes=ImageVault.open(key,person,new File(name).getName(),bytes);
            }
            File output=new File(target,name);
            if(!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) throw new IOException("Cannot create recovery directory");
            Files.write(output.toPath(),bytes,StandardOpenOption.CREATE_NEW);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3 && args.length!=4) throw new IOException("Usage: BackupRestore verify|restore recovery.key source-directory [new-output-directory]");
        if(!args[0].equals("verify")&&!args[0].equals("restore")) throw new IOException("Unknown recovery command");
        File keyFile=new File(args[1]); if(keyFile.length()==0||keyFile.length()>4096) throw new IOException("Invalid key file");
        Properties properties=new Properties(); try(InputStream input=new FileInputStream(keyFile)) { properties.load(input); }
        byte[] key=ImageVault.recoveryKey(properties);
        try {
            if(args[0].equals("verify")&&args.length==3) verify(key,properties.getProperty("person_id"),new File(args[2]));
            else if(args[0].equals("restore")&&args.length==4) restore(key,properties.getProperty("person_id"),new File(args[2]),new File(args[3]));
            else throw new IOException("Unexpected recovery arguments");
            System.out.println("Backup recovery verification passed.");
        } finally { Arrays.fill(key,(byte)0); }
    }
}
