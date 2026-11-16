package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.*;

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
    static synchronized void recoverInterrupted(File person) throws IOException {
        List<File> pending=new ArrayList<>();
        try(java.util.stream.Stream<Path> paths=Files.walk(person.toPath())) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                if(Files.isSymbolicLink(path)) throw new IOException("保存先にリンクがあります。原本を保持して停止しました。");
                String name=path.getFileName().toString();
                if(Files.isRegularFile(path) && (name.endsWith(".tmp")||name.matches("image-[A-Za-z0-9-]+\\.pending"))) pending.add(path.toFile());
            }
        }
        for(File file:pending) {
            if(!file.exists()) continue;
            String relative=path(person,file);
            if(file.length()>DataVault.MAX_BYTES) throw new IOException("中断した保存が大きすぎます。原本を保持しています。");
            byte[] bytes=Files.readAllBytes(file.toPath()),key=ImageVault.keys.get(person,true);
            try {
                if(DataVault.encrypted(bytes)) {
                    try { DataVault.open(key,person.getName(),"file",relative,bytes);continue; }
                    catch(IOException notAnArchivedTemporary) { }
                }
                File target=null;
                if(file.getName().endsWith(".tmp")) {
                    File candidate=new File(file.getPath().substring(0,file.getPath().length()-4));String destination=path(person,candidate);
                    try {
                        if(DataVault.encrypted(bytes)) { DataVault.open(key,person.getName(),"file",destination,bytes);target=candidate; }
                        else if(ImageVault.encrypted(bytes)&&ImageVault.image(candidate.getName())) { ImageVault.open(key,person.getName(),candidate.getName(),bytes);target=candidate; }
                    } catch(IOException incomplete) { }
                } else if(ImageVault.encrypted(bytes)) {
                    synchronized(ImageVault.class) {
                        File[] siblings=file.getParentFile().listFiles();if(siblings==null) throw new IOException("中断した画像を確認できません。");
                        for(File candidate:siblings) if(candidate.isFile()&&ImageVault.image(candidate.getName())) {
                            path(person,candidate);
                            try { ImageVault.open(key,person.getName(),candidate.getName(),bytes);target=candidate;break; }
                            catch(IOException notTheDestination) { }
                        }
                    }
                }
                if(target!=null) {
                    if(target.exists()&&!target.isFile()) throw new IOException("中断した保存の移行先が不正です。");
                    try(RandomAccessFile output=new RandomAccessFile(file,"rw")) { output.getFD().sync(); }
                    Files.move(file.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } else {
                    File retained=new File(person,".interrupted-writes");path(person,retained);
                    if(!retained.isDirectory()&&!retained.mkdir()) throw new IOException("中断した保存を保持できません。");
                    ByteArrayOutputStream original=new ByteArrayOutputStream();original.write(DataVault.fields(relative));original.write(bytes);
                    write(person,new File(retained,UUID.randomUUID()+".enc"),original.toByteArray());
                    Files.delete(file.toPath());
                }
            } finally { Arrays.fill(key,(byte)0); }
        }
    }
}
