package jp.tekewo.conversation;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

public final class GitMigrationTest {
    static String git(File dir,String... args) throws Exception {
        List<String> command=new ArrayList<>(Arrays.asList("git","-c","user.name=Fixture","-c","user.email=fixture@invalid")); command.addAll(Arrays.asList(args));
        Process p=new ProcessBuilder(command).directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output=new String(p.getInputStream().readAllBytes(),"UTF-8");
        if(p.waitFor()!=0) throw new AssertionError("fixture git failed: "+Arrays.toString(args)); return output.trim();
    }
    static void rejected(DataVaultTest.Action action) throws Exception { DataVaultTest.rejects(action); }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("git-encrypted-migration-").toFile(); byte[] key=new byte[32];
        String person="aa000000-0000-0000-0000-000000000001";
        try {
            File source=new File(root,"source"); assert source.mkdir(); git(source,"init","-b","main");
            Files.writeString(new File(source,"secret.txt").toPath(),"private-history-canary-first"); git(source,"add","."); git(source,"commit","-m","private-history-message"); git(source,"tag","-a","v1","-m","private tag");
            git(source,"checkout","-b","dev"); Files.writeString(new File(source,"feature.txt").toPath(),"private-history-canary-feature"); git(source,"add","."); git(source,"commit","-m","feature");
            git(source,"checkout","main"); git(source,"mv","secret.txt","renamed.txt"); git(source,"commit","-m","renamed"); git(source,"merge","--no-ff","dev","-m","merge"); git(source,"tag","light");
            File mirror=new File(root,"mirror.git"); git(root,"clone","--mirror",source.getPath(),mirror.getPath());
            rejected(()->GitMigration.validateSource(mirror,key,person,11));
            Files.writeString(new File(source,"archive.json").toPath(),new JSONObject().put("format",GitArchiveFiles.FORMAT).put("person_id",person).put("repository_id",11).toString());
            git(source,"add","archive.json");git(source,"commit","-m","archive identity");git(mirror,"fetch",source.getPath(),"+refs/heads/*:refs/heads/*");
            GitMigration.validateSource(mirror,key,person,11);
            rejected(()->GitMigration.validateSource(mirror,key,person,99));
            rejected(()->GitMigration.validateSource(mirror,key,"wrong-person",11));
            File encrypted=new File(root,"encrypted");
            GitMigration.pack(mirror,encrypted,key,person,new JSONObject());
            String packaged=System.getProperty("packaged.classpath");
            if(packaged!=null) {
                File recovery=new File(root,"fixture.key");
                try(OutputStream output=new FileOutputStream(recovery)) { ImageVault.recovery(person,key).store(output,"Synthetic fixture"); }
                Process cli=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java.exe").getPath(),"-cp",packaged,
                    "jp.tekewo.conversation.GitMigration","verify","--key",recovery.getPath(),"--source",encrypted.getPath()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                String output=new String(cli.getInputStream().readAllBytes(),"UTF-8");
                assert cli.waitFor()==0&&output.contains("Complete Git history verified.");
            }
            git(mirror,"config","remote.origin.promisor","true");
            rejected(()->GitMigration.pack(mirror,new File(root,"partial"),key,person,new JSONObject()));
            git(mirror,"config","--unset","remote.origin.promisor");
            try(java.util.stream.Stream<Path> paths=Files.walk(encrypted.toPath())) {
                for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator)
                    assert !new String(Files.readAllBytes(path),"ISO-8859-1").contains("private-history-canary");
            }
            File restored=new File(root,"restored"); GitMigration.restore(encrypted,restored,key,person);
            File repo=new File(restored,"repository.git");
            assert git(repo,"for-each-ref","--format=%(refname) %(objectname)").equals(git(mirror,"for-each-ref","--format=%(refname) %(objectname)"));
            assert git(repo,"show","v1:secret.txt").equals("private-history-canary-first");
            assert git(repo,"show","main:feature.txt").equals("private-history-canary-feature");
            git(repo,"fsck","--full");
            rejected(()->GitMigration.removeOwned(root,root));
            byte[] lfsBody="private-lfs-canary".getBytes("UTF-8");
            String lfsOid=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(lfsBody));
            Files.writeString(new File(source,"asset.bin").toPath(),"version https://git-lfs.github.com/spec/v1\noid sha256:"+lfsOid+"\nsize "+lfsBody.length+"\n");
            git(source,"add","asset.bin");git(source,"commit","-m","lfs");git(mirror,"fetch",source.getPath(),"+refs/heads/*:refs/heads/*");
            rejected(()->GitMigration.pack(mirror,new File(root,"missing-lfs"),key,person,new JSONObject()));
            File lfsFile=new File(mirror,"lfs/objects/"+lfsOid.substring(0,2)+"/"+lfsOid.substring(2,4)+"/"+lfsOid);
            Files.createDirectories(lfsFile.getParentFile().toPath());Files.write(lfsFile.toPath(),lfsBody);
            File withLfs=new File(root,"with-lfs");GitMigration.pack(mirror,withLfs,key,person,new JSONObject());
            File restoredLfs=new File(root,"restored-lfs");GitMigration.restore(withLfs,restoredLfs,key,person);
            assert Arrays.equals(lfsBody,Files.readAllBytes(new File(restoredLfs,"repository.git/lfs/objects/"+lfsOid.substring(0,2)+"/"+lfsOid.substring(2,4)+"/"+lfsOid).toPath()));
            git(withLfs,"init","-b","main");git(withLfs,"add","history");git(withLfs,"commit","-m","encrypted");
            File download=new File(root,"no-checkout");git(root,"clone","--no-checkout",withLfs.getPath(),download.getPath());
            GitMigration.materializeCipherTree(download);
            assert Files.isRegularFile(new File(download,"history/index.enc").toPath());
            Files.writeString(new File(withLfs,".gitattributes").toPath(),"* filter=unsafe");git(withLfs,"add",".gitattributes");git(withLfs,"commit","-m","unsafe");
            File unsafe=new File(root,"unsafe");git(root,"clone","--no-checkout",withLfs.getPath(),unsafe.getPath());
            rejected(()->GitMigration.materializeCipherTree(unsafe));
            assert !new File(unsafe,"history").exists();
            rejected(()->GitMigration.restore(encrypted,restored,key,person));
            byte[] wrong=key.clone();wrong[0]=1; rejected(()->GitMigration.restore(encrypted,new File(root,"wrong"),wrong,person));
            File index=new File(encrypted,"history/index.enc");byte[] indexBytes=Files.readAllBytes(index.toPath());
            indexBytes[indexBytes.length-1]^=1;Files.write(index.toPath(),indexBytes);
            rejected(()->GitMigration.restore(encrypted,new File(root,"altered"),key,person));
            indexBytes[indexBytes.length-1]^=1;Files.write(index.toPath(),indexBytes);
            File part=Arrays.stream(new File(encrypted,"history").listFiles()).filter(f->f.getName().startsWith("pack-")).findFirst().get(); Files.delete(part.toPath());
            rejected(()->GitMigration.restore(encrypted,new File(root,"missing"),key,person));
            System.out.println("PASS: branches/tags/merge/renames, LFS completeness and restore, ciphertext-only tree, traversal/overwrite/key/tamper/missing rejection");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())) { for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) { if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) path.toFile().setWritable(true); Files.delete(path); } }
        }
    }
}
