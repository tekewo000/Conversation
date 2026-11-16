package jp.tekewo.conversation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Git runs only on the original mirror, a new encrypted repository, or explicit recovery output. */
public final class GitMigration {
    static final int CHUNK=8*1024*1024;
    static Process gitProcess(File directory,String... args) throws IOException {
        String nullPath=System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null";
        List<String> command=new ArrayList<>(Arrays.asList("git","--no-pager","-c","core.hooksPath="+nullPath,"-c","core.attributesFile="+nullPath,"-c","core.fsmonitor=false","-c","protocol.ext.allow=never"));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder=new ProcessBuilder(command).directory(directory).redirectError(ProcessBuilder.Redirect.DISCARD);
        Map<String,String> env=builder.environment();
        for(String name:new ArrayList<>(env.keySet())) if(name.startsWith("GIT_TRACE")||Arrays.asList("GIT_DIR","GIT_WORK_TREE","GIT_INDEX_FILE","GIT_OBJECT_DIRECTORY","GIT_ALTERNATE_OBJECT_DIRECTORIES").contains(name)) env.remove(name);
        env.put("GIT_TERMINAL_PROMPT","0"); env.put("GIT_LFS_SKIP_SMUDGE","1"); env.put("GIT_ATTR_NOSYSTEM","1");env.put("GIT_NO_LAZY_FETCH","1");
        return builder.start();
    }
    static byte[] git(File directory,String... args) throws IOException {
        Process process=gitProcess(directory,args); process.getOutputStream().close();
        try {
            byte[] output=bounded(process.getInputStream(),DataVault.MAX_BYTES);
            if(process.waitFor()!=0) throw new IOException("Git operation failed: "+args[0]); return output;
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted Git operation",error); }
        finally { process.destroy(); }
    }
    static String text(File directory,String... args) throws IOException { return new String(git(directory,args),StandardCharsets.UTF_8).trim(); }
    static byte[] bounded(InputStream input,int max) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] buffer=new byte[65536]; int n;
        while((n=input.read(buffer))!=-1) { if(output.size()>max-n) throw new IOException("Git metadata exceeds its bound"); output.write(buffer,0,n); }
        return output.toByteArray();
    }
    static JSONObject describe(File mirror) throws Exception {
        if(!new File(mirror,"HEAD").isFile()||!new File(mirror,"objects").isDirectory()||new File(mirror,"shallow").exists()||new File(mirror,"objects/info/alternates").exists()) throw new IOException("Use a complete standalone bare mirror");
        String partial="";
        try { partial=text(mirror,"config","--get-regexp","^(extensions\\.partialclone|remote\\..*\\.promisor)$"); } catch(IOException absent) { }
        if(!partial.isEmpty()) throw new IOException("Partial/promisor repositories are not accepted; use a complete mirror");
        JSONObject refs=new JSONObject();
        for(String line:text(mirror,"for-each-ref","--format=%(refname) %(objectname)").split("\n")) {
            String[] pair=line.trim().split(" ");
            if(pair.length!=2||!pair[0].startsWith("refs/")||!pair[1].matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Missing or invalid Git references");
            refs.put(pair[0],pair[1]);
        }
        String head=text(mirror,"rev-parse","HEAD");
        String symbolic=""; try { symbolic=text(mirror,"symbolic-ref","HEAD"); } catch(IOException detached) { }
        if(!head.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid Git HEAD");
        return new JSONObject().put("refs",refs).put("head",head).put("symbolic_head",symbolic);
    }
    static JSONArray parts(InputStream input,File output,byte[] key,String person,String prefix) throws Exception {
        JSONArray parts=new JSONArray(); byte[] buffer=new byte[CHUNK]; int n,index=0;
        while((n=input.readNBytes(buffer,0,buffer.length))>0) {
            byte[] plain=Arrays.copyOf(buffer,n); String name=String.format(Locale.ROOT,"history/%s-%08d.enc",prefix,index++);
            byte[] encoded=DataVault.seal(key,person,"history",name,plain);
            Files.write(new File(output,name).toPath(),encoded,StandardOpenOption.CREATE_NEW);
            parts.put(new JSONObject().put("file",name).put("size",n).put("sha256",GitArchiveFiles.sha256(plain)).put("encrypted_sha256",GitArchiveFiles.sha256(encoded)));
            Arrays.fill(plain,(byte)0);
        }
        Arrays.fill(buffer,(byte)0); return parts;
    }
    static Map<String,Long> lfsPointers(File mirror) throws Exception {
        Set<String> ids=new LinkedHashSet<>();
        for(String line:text(mirror,"rev-list","--objects","--all","HEAD").split("\n")) ids.add(line.split(" ",2)[0]);
        Map<String,Long> pointers=new LinkedHashMap<>();
        Process process=gitProcess(mirror,"cat-file","--batch");
        try(BufferedInputStream input=new BufferedInputStream(process.getInputStream()); OutputStream requests=process.getOutputStream()) {
            for(String id:ids) {
                if(!id.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid Git object ID");
                requests.write((id+"\n").getBytes(StandardCharsets.US_ASCII)); requests.flush();
                String header=line(input); String[] fields=header.split(" ");
                if(fields.length!=3) throw new IOException("Missing Git object");
                long size=Long.parseLong(fields[2]); if(size<0) throw new IOException("Invalid object size");
                if(fields[1].equals("blob")&&size<=1024) {
                    byte[] data=input.readNBytes((int)size); if(data.length!=size) throw new IOException("Incomplete Git object");
                    String value=new String(data,StandardCharsets.UTF_8);
                    if(value.startsWith("version https://git-lfs.github.com/spec/v1\n")||value.startsWith("version https://git-lfs.github.com/spec/v1\r\n")) {
                        Matcher oid=Pattern.compile("(?m)^oid sha256:([0-9a-f]{64})\\r?$").matcher(value);
                        Matcher length=Pattern.compile("(?m)^size ([0-9]+)\\r?$").matcher(value);
                        if(!oid.find()||!length.find()) throw new IOException("Invalid Git LFS pointer");
                        long count=Long.parseLong(length.group(1));
                        Long prior=pointers.put(oid.group(1),count);
                        if(count<0||(prior!=null&&prior!=count)) throw new IOException("Conflicting LFS pointer");
                    }
                } else input.skipNBytes(size);
                if(input.read()!='\n') throw new IOException("Incomplete Git object");
            }
            requests.close(); if(process.waitFor()!=0) throw new IOException("Git object enumeration failed");
        } finally { process.destroy(); }
        return pointers;
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream(); int value;
        while((value=input.read())!='\n') { if(value<0||output.size()>1024) throw new IOException("Invalid Git response"); output.write(value); }
        return output.toString(StandardCharsets.US_ASCII);
    }
    static void pack(File mirror,File output,byte[] key,String person,JSONObject metadata) throws Exception {
        JSONObject original=describe(mirror); git(mirror,"fsck","--full","--no-reflogs");
        Map<String,Long> lfs=lfsPointers(mirror);
        for(Map.Entry<String,Long> entry:lfs.entrySet()) {
            File file=lfsFile(mirror,entry.getKey());
            if(!file.isFile()||file.length()!=entry.getValue()||!sha(file).equals(entry.getKey())) throw new IOException("Missing or invalid Git LFS object; fetch all LFS objects before migration");
        }
        if(output.exists()||!output.mkdirs()) throw new IOException("Use a new encrypted output directory");
        Files.createDirectory(new File(output,"history").toPath());
        JSONObject index=new JSONObject().put("format","conversation-git-history-v1").put("person_id",person).put("original",original).put("source",metadata);
        Process bundle=gitProcess(mirror,"bundle","create","-","--all","HEAD"); bundle.getOutputStream().close();
        try {
            JSONArray chunks=parts(bundle.getInputStream(),output,key,person,"pack");
            if(bundle.waitFor()!=0||chunks.length()==0) throw new IOException("Could not collect the complete Git bundle");
            index.put("bundle",chunks);
        } finally { bundle.destroy(); }
        JSONArray assets=new JSONArray();
        for(Map.Entry<String,Long> entry:lfs.entrySet()) try(InputStream input=new FileInputStream(lfsFile(mirror,entry.getKey()))) {
            assets.put(new JSONObject().put("oid",entry.getKey()).put("size",entry.getValue()).put("chunks",parts(input,output,key,person,"lfs-"+DataVault.id(key,person,"history",entry.getKey()))));
        }
        index.put("lfs",assets);
        if(!GitArchiveFiles.canonical(original).equals(GitArchiveFiles.canonical(describe(mirror)))) throw new IOException("Source references changed during migration");
        Files.write(new File(output,"history/index.enc").toPath(),DataVault.seal(key,person,"history","index",index.toString().getBytes(StandardCharsets.UTF_8)),StandardOpenOption.CREATE_NEW);
        Files.write(new File(output,"history/format.json").toPath(),"{\"format\":\"conversation-git-history-v1\"}\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
    }
    static File lfsFile(File mirror,String oid) throws IOException {
        if(!oid.matches("[0-9a-f]{64}")) throw new IOException("Invalid LFS object ID");
        return new File(mirror,"lfs/objects/"+oid.substring(0,2)+"/"+oid.substring(2,4)+"/"+oid);
    }
    static String sha(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) { byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static JSONObject index(File source,byte[] key,String person) throws Exception {
        JSONObject index=new JSONObject(new String(DataVault.open(key,person,"history","index",BackupRestore.readFile(source,"history/index.enc")),StandardCharsets.UTF_8));
        if(!"conversation-git-history-v1".equals(index.getString("format"))||!person.equals(index.getString("person_id"))) throw new IOException("Wrong migration archive");
        return index;
    }
    static long recoverParts(File source,byte[] key,String person,JSONArray chunks,OutputStream output) throws Exception {
        long count=0; Set<String> seen=new HashSet<>();
        for(int i=0;i<chunks.length();i++) {
            JSONObject part=chunks.getJSONObject(i);String name=part.getString("file");
            if(!name.matches("history/(pack|lfs-[0-9a-f]{64})-[0-9]{8}\\.enc")||!seen.add(name)) throw new IOException("Invalid history chunk");
            byte[] encoded=BackupRestore.readFile(source,name);
            if(!shaBytes(encoded).equals(part.getString("encrypted_sha256"))) throw new IOException("Missing or changed history chunk");
            byte[] bytes=DataVault.open(key,person,"history",name,encoded);
            if(bytes.length!=GitArchiveFiles.integer(part,"size")||!shaBytes(bytes).equals(part.getString("sha256"))) throw new IOException("Invalid history chunk");
            output.write(bytes);count+=bytes.length;Arrays.fill(bytes,(byte)0);
        }
        return count;
    }
    static String shaBytes(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static void restore(File source,File target,byte[] key,String person) throws Exception {
        if(target.exists()||target.getCanonicalFile().toPath().startsWith(source.getCanonicalFile().toPath())) throw new IOException("Use a new recovery directory outside the source");
        JSONObject index=index(source,key,person);
        if(!target.mkdirs()) throw new IOException("Cannot create recovery directory");
        File bundle=new File(target,"history.bundle");
        try(OutputStream output=new FileOutputStream(bundle)) { recoverParts(source,key,person,index.getJSONArray("bundle"),output); }
        File repo=new File(target,"repository.git"); git(target,"init","--bare",repo.getPath());
        git(repo,"bundle","verify",bundle.getPath()); git(repo,"bundle","unbundle",bundle.getPath());
        JSONObject original=index.getJSONObject("original"),refs=original.getJSONObject("refs");
        for(Iterator<String> names=refs.keys();names.hasNext();) {
            String ref=names.next(),oid=refs.getString(ref);
            if(!ref.startsWith("refs/")||!oid.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid stored reference");
            git(repo,"check-ref-format",ref);git(repo,"update-ref",ref,oid);
        }
        String symbolic=original.getString("symbolic_head"),head=original.getString("head");
        if(!symbolic.isEmpty()) { git(repo,"check-ref-format",symbolic);Files.writeString(new File(repo,"HEAD").toPath(),"ref: "+symbolic+"\n"); }
        else { if(!head.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid stored HEAD");Files.writeString(new File(repo,"HEAD").toPath(),head+"\n"); }
        JSONArray assets=index.getJSONArray("lfs");
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);String oid=asset.getString("oid"); File file=lfsFile(repo,oid);
            Files.createDirectories(file.getParentFile().toPath());
            long size;try(OutputStream output=new FileOutputStream(file)) { size=recoverParts(source,key,person,asset.getJSONArray("chunks"),output); }
            if(size!=GitArchiveFiles.integer(asset,"size")||!sha(file).equals(oid)) throw new IOException("Invalid restored LFS object");
        }
        git(repo,"fsck","--full","--no-reflogs");
        if(!GitArchiveFiles.canonical(original).equals(GitArchiveFiles.canonical(describe(repo)))) throw new IOException("Restored Git references do not match");
        Files.delete(bundle.toPath());
    }
    static String credential(File trustedWork,String repository) throws Exception {
        Process process=gitProcess(trustedWork,"credential","fill");
        try {
            process.getOutputStream().write(("protocol=https\nhost=github.com\npath="+repository+"\n\n").getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            String response=new String(bounded(process.getInputStream(),16384),StandardCharsets.UTF_8);
            if(process.waitFor()!=0) throw new IOException("Git credential helper could not authorize GitHub");
            for(String line:response.split("\n")) if(line.startsWith("password=")) return line.substring(9).trim();
            throw new IOException("GitHub credential not found");
        } finally { process.destroy(); }
    }
    static void removeOwned(File root,File target) throws IOException {
        Path allowed=root.getCanonicalFile().toPath(),resolved=target.getCanonicalFile().toPath();
        if(resolved.equals(allowed)||!resolved.startsWith(allowed)||Files.isSymbolicLink(target.toPath())) throw new IOException("Refused unsafe work directory cleanup");
        try(java.util.stream.Stream<Path> paths=Files.walk(resolved)) {
            for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) {
                if(!path.toAbsolutePath().normalize().startsWith(allowed)) throw new IOException("Unsafe cleanup path");
                if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) path.toFile().setWritable(true);
                Files.delete(path);
            }
        }
    }
    static String repository(String value) { return PersonRegistry.normalizeRepository(value); }
    static void validateSource(File mirror,byte[] key,String person,long repositoryId) throws Exception {
        boolean encrypted;
        try { git(mirror,"cat-file","-e","HEAD:archive.enc");encrypted=true; } catch(IOException absent) { encrypted=false; }
        JSONObject marker;
        if(encrypted) marker=EncryptedArchive.manifest(key,person,git(mirror,"show","HEAD:archive.enc"));
        else {
            byte[] bytes=git(mirror,"show","HEAD:archive.json");
            if(bytes.length>1024*1024) throw new IOException("Invalid source archive marker");
            marker=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        }
        if(!GitArchiveFiles.FORMAT.equals(marker.getString("format"))||!person.equals(marker.getString("person_id"))||GitArchiveFiles.integer(marker,"repository_id")!=repositoryId)
            throw new IOException("Source archive does not match the person key and original repository");
        Set<String> seen=new HashSet<>();
        for(String line:text(mirror,"rev-list","--objects","--all","HEAD").split("\n")) {
            String[] object=line.split(" ",2);if(object.length!=2) continue;
            String name=new File(object[1]).getName();if(name.endsWith(".enc")) name=name.substring(0,name.length()-4);
            if(!ImageVault.image(name)||!seen.add(object[0]+"/"+name)) continue;
            byte[] bytes=git(mirror,"cat-file","blob",object[0]);
            if(ImageVault.encrypted(bytes)) ImageVault.open(key,person,name,bytes);
        }
    }
    static void checkDestination(GitHubBackup api,GitHubBackup.Repository expected) throws IOException {
        GitHubBackup.Repository current=api.verifyRepository(expected.repo);
        if(current.repoId!=expected.repoId||!current.branch.equals(expected.branch)) throw new IOException("Destination identity changed; nothing was overwritten");
    }
    static void materializeCipherTree(File repository) throws Exception {
        String listing=text(repository,"ls-tree","-r","HEAD");
        for(String line:listing.split("\n")) {
            String[] entry=line.split("\t",2);
            if(entry.length!=2||!entry[0].startsWith("100644 blob ")) throw new IOException("Destination has an unsafe tree entry");
            String path=entry[1];
            if(!path.matches("history/(format\\.json|index\\.enc|complete\\.enc|(pack|lfs-[0-9a-f]{64})-[0-9]{8}\\.enc)")
                    && !path.matches("(format\\.json|archive\\.enc|objects/[0-9a-f]{64}\\.enc)")) throw new IOException("Destination contains unrelated files");
        }
        for(String line:listing.split("\n")) {
            String path=line.split("\t",2)[1];File target=new File(repository,path);
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(),git(repository,"show","HEAD:"+path),StandardOpenOption.CREATE_NEW);
        }
        git(repository,"reset","--mixed","HEAD");
    }
    static void migrate(String source,String destination,File work,byte[] key,String person) throws Exception {
        String dest=repository(destination);
        if(!work.exists()&&!work.mkdirs()) throw new IOException("Cannot create migration work directory");
        if(Files.isSymbolicLink(work.toPath())) throw new IOException("Invalid migration work directory");
        File job=new File(work,"job.enc"),mirror=new File(work,"source.git"),encrypted=new File(work,"encrypted"),download=new File(work,"download"),verification=new File(work,"verification");
        JSONObject state;
        if(job.exists()) {
            if(job.length()>DataVault.MAX_BYTES+DataVault.OVERHEAD||Files.isSymbolicLink(job.toPath())) throw new IOException("Invalid migration state");
            state=new JSONObject(new String(DataVault.open(key,person,"history","job",Files.readAllBytes(job.toPath())),StandardCharsets.UTF_8));
            if(!dest.equals(state.getString("destination"))||!source.equals(state.getString("input"))) throw new IOException("Resume inputs do not match the original migration");
        } else {
            if(work.list().length!=0) throw new IOException("Use an empty migration work directory");
            state=new JSONObject().put("destination",dest).put("input",source).put("packed",false);
            Files.write(job.toPath(),DataVault.seal(key,person,"history","job",state.toString().getBytes(StandardCharsets.UTF_8)),StandardOpenOption.CREATE_NEW);
        }
        String token=credential(work,dest);
        try(GitHubBackup api=new GitHubBackup(new GitHubBackup.HttpTransport(token),key.clone())) {
            GitHubBackup.Repository remote=api.verifyRepository(dest);
            if(!state.optBoolean("packed")) {
                File supplied=new File(source);
                String origin;
                if(supplied.isDirectory()) { mirror=supplied.getCanonicalFile();origin=text(mirror,"remote","get-url","origin"); }
                else {
                    if(mirror.exists()) removeOwned(work,mirror);
                    origin="https://github.com/"+repository(source)+".git";git(work,"clone","--mirror","--",origin,mirror.getPath());
                }
                String sourceRepo=repository(origin);
                GitHubBackup.Repository original=api.verifyRepository(sourceRepo);
                if(original.repoId==remote.repoId) throw new IOException("Source and destination repositories are identical");
                validateSource(mirror,key,person,original.repoId);
                if(!lfsPointers(mirror).isEmpty()&&!supplied.isDirectory())
                    git(mirror,"-c","lfs.url=https://github.com/"+sourceRepo+".git/info/lfs","-c","remote.origin.lfsurl=https://github.com/"+sourceRepo+".git/info/lfs","-c","lfs.basictransfersonly=true","-c","lfs.standalonetransferagent=","lfs","fetch","--all","origin");
                if(encrypted.exists()) removeOwned(work,encrypted);
                pack(mirror,encrypted,key,person,new JSONObject().put("repository",sourceRepo).put("repository_id",original.repoId));
                state.put("packed",true).put("index_sha256",sha(new File(encrypted,"history/index.enc"))).put("repository_id",remote.repoId).put("branch",remote.branch);
                Files.write(job.toPath(),DataVault.seal(key,person,"history","job",state.toString().getBytes(StandardCharsets.UTF_8)));
            }
            if(state.getLong("repository_id")!=remote.repoId||!sha(new File(encrypted,"history/index.enc")).equals(state.getString("index_sha256"))) throw new IOException("Migration work data or destination changed");
            String url="https://github.com/"+dest+".git";
            String refs=text(work,"ls-remote","--refs",url);
            if(!refs.isEmpty()) for(String ref:refs.split("\n"))
                if(!ref.endsWith("\trefs/heads/"+remote.branch)) throw new IOException("Destination contains other references");
            if(refs.isEmpty()) {
                if(!new File(encrypted,".git").exists()) {
                    git(encrypted,"init","-b",remote.branch);git(encrypted,"add","--","history");
                    git(encrypted,"-c","user.name=Conversation archive","-c","user.email=archive@invalid","commit","-m","Store encrypted history");
                }
                checkDestination(api,remote);git(encrypted,"push",url,"HEAD:refs/heads/"+remote.branch);
            }
            if(download.exists()) removeOwned(work,download);
            git(work,"clone","--no-checkout","--single-branch","--branch",remote.branch,"--",url,download.getPath());
            materializeCipherTree(download);
            if(!sha(new File(download,"history/index.enc")).equals(state.getString("index_sha256"))) throw new IOException("Destination contains a different archive; nothing was overwritten");
            if(verification.exists()) removeOwned(work,verification);
            restore(download,verification,key,person);
            JSONObject original=index(download,key,person).getJSONObject("original");
            File recovered=new File(verification,"repository.git");
            if(!GitArchiveFiles.canonical(original).equals(GitArchiveFiles.canonical(describe(recovered)))) throw new IOException("Remote restoration differs from original references");
            if(!new File(download,"history/complete.enc").exists()) {
                JSONObject proof=new JSONObject().put("format","conversation-git-migration-v1").put("person_id",person).put("source_repository_id",index(download,key,person).getJSONObject("source").getLong("repository_id"))
                    .put("repository_id",remote.repoId).put("repository",dest).put("index_sha256",state.getString("index_sha256")).put("history_head",text(download,"rev-parse","HEAD")).put("verified",true);
                Files.write(new File(download,"history/complete.enc").toPath(),DataVault.seal(key,person,"history","complete",proof.toString().getBytes(StandardCharsets.UTF_8)),StandardOpenOption.CREATE_NEW);
                git(download,"add","--","history/complete.enc");git(download,"-c","user.name=Conversation archive","-c","user.email=archive@invalid","commit","-m","Verify encrypted history");
                checkDestination(api,remote);git(download,"push",url,"HEAD:refs/heads/"+remote.branch);
            }
            git(download,"fetch",url,remote.branch);
            byte[] proof=git(download,"show","FETCH_HEAD:history/complete.enc");
            JSONObject checked=new JSONObject(new String(DataVault.open(key,person,"history","complete",proof),StandardCharsets.UTF_8));
            if(!checked.getBoolean("verified")||checked.getLong("repository_id")!=remote.repoId||!state.getString("index_sha256").equals(checked.getString("index_sha256"))) throw new IOException("Remote verification proof does not match");
            checkDestination(api,remote);
            removeOwned(work,verification);removeOwned(work,download);
            if(new File(work,"source.git").exists()) removeOwned(work,new File(work,"source.git"));
            System.out.println("Verified encrypted migration: https://github.com/"+dest);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1) throw new IOException("Usage: migrate|verify|restore --key <file> --source <repo-or-directory> [--destination <repo> --work <directory>|--output <new-directory>]");
        Map<String,String> options=new HashMap<>();
        for(int i=1;i<args.length;i+=2) { if(i+1>=args.length||!args[i].startsWith("--")||options.put(args[i],args[i+1])!=null) throw new IOException("Invalid command arguments"); }
        String keyPath=options.get("--key"),source=options.get("--source"); if(keyPath==null||source==null) throw new IOException("A key file and source are required");
        File keyFile=new File(keyPath);if(keyFile.length()==0||keyFile.length()>4096) throw new IOException("Invalid recovery key file");
        Properties data=new Properties();try(InputStream input=new FileInputStream(keyFile)) { data.load(input); }
        byte[] key=ImageVault.recoveryKey(data);String person=data.getProperty("person_id");
        try {
            switch(args[0]) {
                case "migrate": if(!options.containsKey("--destination")||!options.containsKey("--work")) throw new IOException("Destination and work directory are required");migrate(source,options.get("--destination"),new File(options.get("--work")),key,person);break;
                case "restore": if(!options.containsKey("--output")) throw new IOException("A new output directory is required");restore(new File(source),new File(options.get("--output")),key,person);System.out.println("Complete Git history restored.");break;
                case "verify": File temp=Files.createTempDirectory("conversation-verify-").toFile();try { restore(new File(source),new File(temp,"restored"),key,person);System.out.println("Complete Git history verified."); } finally { removeOwned(temp,new File(temp,"restored"));Files.delete(temp.toPath()); }break;
                default: throw new IOException("Unknown migration command");
            }
        } finally { Arrays.fill(key,(byte)0); }
    }
}
