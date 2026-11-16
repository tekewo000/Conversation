package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

final class DataFiles {
    static String path(File person, File file) throws IOException {
        File root=person.getCanonicalFile(), current=file.getAbsoluteFile();
        if(!person.isDirectory() || Files.isSymbolicLink(person.toPath()) || !current.getCanonicalPath().startsWith(root.getPath()+File.separator))
            throw new IOException("データの保存先が不正です。");
        while(true) {
            if(Files.isSymbolicLink(current.toPath())) throw new IOException("データの保存先が不正です。");
            if(current.getCanonicalFile().equals(root)) break;
            current=current.getParentFile();
            if(current==null) throw new IOException("データの保存先が不正です。");
        }
        return root.toPath().relativize(file.getCanonicalFile().toPath()).toString().replace(File.separatorChar,'/');
    }
    static byte[] read(File person,File file) throws IOException {
        String name=path(person,file);
        if(!file.isFile() || file.length()>DataVault.MAX_BYTES+DataVault.OVERHEAD) throw new IOException("暗号化ファイルを読み込めません。");
        byte[] key=ImageVault.keys.get(person,false);
        try { return DataVault.open(key,person.getName(),"file",name,Files.readAllBytes(file.toPath())); }
        finally { Arrays.fill(key,(byte)0); }
    }
    static synchronized void write(File person,File file,byte[] data) throws IOException {
        String name=path(person,file);
        File temporary=new File(file.getPath()+".tmp"); path(person,temporary);
        if(temporary.exists() && !temporary.isFile()) throw new IOException("暗号化の一時保存先が不正です。");
        byte[] key=ImageVault.keys.get(person,true);
        try {
            byte[] encoded=DataVault.seal(key,person.getName(),"file",name,data);
            try(FileOutputStream output=new FileOutputStream(temporary)) { output.write(encoded); output.getFD().sync(); }
            if(!MessageDigest.isEqual(data,DataVault.open(key,person.getName(),"file",name,Files.readAllBytes(temporary.toPath()))))
                throw new IOException("暗号化データを照合できません。");
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Arrays.fill(key,(byte)0); Files.deleteIfExists(temporary.toPath()); }
    }
    static void migrate(File person,File file) throws IOException {
        path(person,file);
        if(!file.isFile() || file.length()>DataVault.MAX_BYTES+DataVault.OVERHEAD) throw new IOException("移行するデータが不正です。");
        byte[] data=Files.readAllBytes(file.toPath());
        if(DataVault.encrypted(data)) { read(person,file); return; }
        write(person,file,data);
    }
}
