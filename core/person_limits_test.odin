package conversation

import "core:encoding/json"
import "core:fmt"
import "core:os"
import "core:strings"
import "core:testing"

Limit_Seed :: struct { kind,actor,subject,key,text,scope,certainty,section: string }
Limit_Person :: struct { person_id,name,profile,source_md,source_sha256: string, seeds: []Limit_Seed }
limit_payload :: proc(profile, source, sha, text: string, count: int) -> cstring {
 seeds:=make([]Limit_Seed,count)
 defer delete(seeds)
 for &seed in seeds do seed=Limit_Seed{"fact","user","user","limit",text,"real","explicit","section"}
 bytes,error:=json.marshal(Limit_Person{"11111111-1111-4111-8111-111111111111","人物",profile,source,sha,seeds})
 assert(error==nil)
 defer delete(bytes)
 result,allocation_error:=strings.clone_to_cstring(string(bytes))
 assert(allocation_error==nil)
 return result
}
limit_bytes :: proc(count: int, value: byte) -> []byte {
 result:=make([]byte,count)
 for &character in result do character=value
 return result
}
limit_extraction :: proc(text: string) -> cstring {
 bytes,error:=json.marshal(struct {memories: []struct {kind,subject,key,text,scope,certainty: string,source_ids: []i64}}{
  { {"fact","user","actual",text,"real","explicit",{1}} },
 })
 assert(error==nil)
 defer delete(bytes)
 result:=strings.clone_to_cstring(string(bytes))
 return result
}
limit_source_matches :: proc(expected: string) -> bool {
 stmt:=prepare("SELECT raw_markdown FROM source_documents WHERE id=1")
 if stmt==nil do return false
 defer sqlite3_finalize(stmt)
 return sqlite3_step(stmt)==100 && string(sqlite3_column_text(stmt,0))==expected
}

@(test)
person_import_accepts_four_raised_exact_limits_and_replays_sources :: proc(t: ^testing.T) {
 root,error:=os.make_directory_temp("","conversation-raised-limits-*",context.allocator)
 assert(error==nil)
 defer delete(root)
 defer os.remove_all(root)
 defer conversation_close()
 source:=limit_bytes(2*1048576,1)
 defer delete(source)
 source[0]='a'
 profile:=limit_bytes(128*1024,'p')
 defer delete(profile)
 text:=limit_bytes(16*1024,'t')
 defer delete(text)
 cases:=[]struct {source,sha,profile,text: string,count: int}{
  {string(source),"6d89bfaba913dd2d02e868074007e9eb901da726eb188fbb84ff363fc6e50d2b","profile","seed",1},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",string(profile),"seed",1},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","profile","seed",2048},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","profile",string(text),1},
 }
 for scenario,i in cases {
  path:=fmt.aprintf("%s/source-%d",root,i)
  defer delete(path)
  path_c:=strings.clone_to_cstring(path)
  defer delete(path_c)
  assert(conversation_init(path_c))
  payload:=limit_payload(scenario.profile,scenario.source,scenario.sha,scenario.text,scenario.count)
  defer delete(payload)
  assert(conversation_import_person(payload),string(conversation_error()))
  testing.expect_value(t,conversation_message_count(),0)
  testing.expect_value(t,conversation_memory_count(),i32(scenario.count))
  assert(string(conversation_pending_job())=="{}" && limit_source_matches(scenario.source))
  context_value,context_ok:=parse_json(transmute([]byte)string(conversation_context("unrelated")),8)
  assert(context_ok)
  testing.expect(t,string(context_value.(json.Object)["person"].(json.Object)["profile"].(json.String))==scenario.profile)
  json.destroy_value(context_value)
  if i==0 {
   assert(conversation_export(path_c),string(conversation_error()))
   manifest_path:=strings.concatenate({path,"/metadata.json"})
   defer delete(manifest_path)
   manifest_bytes,read_error:=os.read_entire_file(manifest_path,context.allocator)
   assert(read_error==nil)
   defer delete(manifest_bytes)
   manifest,manifest_ok:=parse_json(manifest_bytes,6)
   assert(manifest_ok)
   defer json.destroy_value(manifest)
   metadata:=manifest.(json.Object)
   testing.expect_value(t,i64(metadata["chunk_bytes_max"].(json.Integer)),16*1048576)
   document_path:=fmt.aprintf("%s/%s/source_documents-000000.jsonl",path,string(metadata["snapshot"].(json.String)))
   defer delete(document_path)
   document_bytes,document_error:=os.read_entire_file(document_path,context.allocator)
   assert(document_error==nil && len(document_bytes)>2*1048576 && len(document_bytes)<=16*1048576)
   defer delete(document_bytes)
   document,document_ok:=parse_json(document_bytes,6)
   assert(document_ok)
   defer json.destroy_value(document)
   testing.expect(t,string(document.(json.Object)["raw_markdown"].(json.String))==scenario.source)
  }
  delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
  defer delete(delta)
  assert(len(string(delta))<=20*1048576)
  if i==0 do assert(len(string(delta))>2*1048576)
  target:=fmt.aprintf("%s/replay-%d",root,i)
  defer delete(target)
  target_c:=strings.clone_to_cstring(target)
  defer delete(target_c)
  assert(conversation_init(target_c))
  assert(conversation_apply_archive(delta),string(conversation_error()))
  testing.expect_value(t,conversation_memory_count(),i32(scenario.count))
  assert(limit_source_matches(scenario.source))
  testing.expect(t,string(conversation_memory_text(0))==scenario.text)
  testing.expect(t,string(conversation_archive_delta(0))==string(delta))
  if i==3 {
   archived,parsed:=parse_json(transmute([]byte)string(delta),10)
   assert(parsed)
   for record in archived.(json.Object)["records"].(json.Array) {
    for event in record.(json.Object)["events"].(json.Array) {
     object:=event.(json.Object)
     if string(object["table"].(json.String))!="memories" do continue
     row:=object["row"].(json.Object)
     original_job:=row["job_id"]
     row["job_id"]=json.Integer(1)
     testing.expect(t,!archive_row_valid(archive_table("memories"),row))
     row["job_id"]=original_job
    }
   }
   json.destroy_value(archived)
   assert(conversation_add_message("user","actual message")==1 && conversation_add_message("assistant","reply")==2)
   oversized_extraction:=limit_extraction(scenario.text)
   defer delete(oversized_extraction)
   testing.expect(t,!conversation_apply_job(1,oversized_extraction))
   testing.expect_value(t,conversation_memory_count(),1)
   assert(scalar("SELECT processed FROM jobs WHERE id=1")==0)
   ordinary_extraction:=limit_extraction(scenario.text[:4096])
   defer delete(ordinary_extraction)
   assert(conversation_apply_job(1,ordinary_extraction))
   testing.expect_value(t,conversation_memory_count(),2)
  }
 }
}

@(test)
person_import_rejects_each_raised_limit_plus_one_atomically :: proc(t: ^testing.T) {
 root,error:=os.make_directory_temp("","conversation-over-limits-*",context.allocator)
 assert(error==nil)
 defer delete(root)
 defer os.remove_all(root)
 root_c:=strings.clone_to_cstring(root)
 defer delete(root_c)
 defer conversation_close()
 assert(conversation_init(root_c))
 source:=limit_bytes(2*1048576+1,'a')
 defer delete(source)
 profile:=limit_bytes(128*1024+1,'p')
 defer delete(profile)
 text:=limit_bytes(16*1024+1,'t')
 defer delete(text)
 cases:=[]struct {source,sha,profile,text: string,count: int}{
  {string(source),"16a429a1e5162fcf59e3079724763e9afafcef1e2c7f862571e9267b3d959113","profile","seed",1},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",string(profile),"seed",1},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","profile","seed",2049},
  {"hello","2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","profile",string(text),1},
 }
 for scenario in cases {
  payload:=limit_payload(scenario.profile,scenario.source,scenario.sha,scenario.text,scenario.count)
  defer delete(payload)
  testing.expect(t,!conversation_import_person(payload))
  testing.expect_value(t,conversation_memory_count(),0)
  testing.expect_value(t,scalar("SELECT (SELECT count(*) FROM person_profiles)+(SELECT count(*) FROM source_documents)+(SELECT count(*) FROM archive_transactions)+(SELECT count(*) FROM memory_document_sources)"),0)
 }
 assert(conversation_import_person(TEST_PERSON))
 testing.expect_value(t,conversation_memory_count(),1)
}
