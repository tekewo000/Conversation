package conversation

import "core:os"
import "core:strings"
import "core:testing"
import "core:encoding/json"
import "core:fmt"

TEST_PERSON :: `{"person_id":"11111111-1111-4111-8111-111111111111","name":"葵","profile":"一人称は私。静かに短く話す。","source_md":"hello","source_sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","seeds":[{"kind":"fact","actor":"user","subject":"user","key":"home","text":"東京に住むとの引き継ぎ","scope":"real","certainty":"explicit","section":"ユーザーとの関係"}]}`

@(test)
source_sha256_matches_vectors_at_padding_and_utf8_boundaries :: proc(t: ^testing.T) {
 vectors:=[][2]string{
  {"","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
  {"abc","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
  {"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq","248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"},
  {"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"},
  {"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0"},
  {"人物\nこんにちは\n","404330bfe2c77d6e7a9596436ef7ed278fecd9ded6d02514302c87264a529acc"},
 }
 for vector in vectors do testing.expect(t,source_hash_valid(vector[0],vector[1]))
 testing.expect(t,!source_hash_valid("abc","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ac"))
}

@(test)
person_import_preserves_provenance_and_replays_incrementally :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-person-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_import_person(TEST_PERSON))
 assert(conversation_import_person(TEST_PERSON))
 testing.expect_value(t,conversation_message_count(),0)
 testing.expect_value(t,conversation_memory_count(),1)
 testing.expect_value(t,string(conversation_pending_job()),"{}")
 output:=string(conversation_context("unrelated"))
 assert(strings.contains(output,"静かに短く話す") && strings.contains(output,`"person":{`))
 assert(strings.contains(output,"reported_handover"))
 source:=string(conversation_memories("home"))
 assert(strings.contains(source,`"source_ids":[]`) && strings.contains(source,"imported_sources") && strings.contains(source,"ユーザーとの関係"))
 first:=strings.clone(string(conversation_archive_delta(0)))
 defer delete(first)
 assert(first==string(conversation_archive_delta(0)))
 assert(conversation_add_message("user","今は京都に住んでいる")==1)
 assert(conversation_add_message("assistant","京都ですね")==2)
 update :: `{"memories":[{"kind":"fact","subject":"user","key":"home","text":"京都","scope":"real","certainty":"explicit","supersedes_id":1,"source_ids":[1]}]}`
 assert(conversation_apply_job(1,update))
 assert(scalar("SELECT processed FROM jobs WHERE id=1")==1)
 full:=strings.clone(string(conversation_archive_delta(0)))
 defer delete(full)
 assert(strings.contains(full,`"table":"jobs","op":"update"`))
 assert(strings.contains(full,`"supersedes_id":1`))
 assert(strings.contains(full,"source_documents") && strings.contains(full,"memory_document_sources"))
 assert(conversation_export(c))
 manifest_path:=strings.concatenate({dir,"/metadata.json"})
 defer delete(manifest_path)
 manifest,manifest_error:=os.read_entire_file(manifest_path,context.allocator)
 defer delete(manifest)
 assert(manifest_error==nil && strings.contains(string(manifest),`"documents":1`) && strings.contains(string(manifest),`"document_sources":1`) && strings.contains(string(manifest),"静かに短く話す"))
 parsed_manifest,manifest_ok:=parse_json(manifest,6)
 assert(manifest_ok)
 json.destroy_value(parsed_manifest)
 other,e2:=os.make_directory_temp("","conversation-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(other)
 defer os.remove_all(other)
 other_c:=strings.clone_to_cstring(other)
 defer delete(other_c)
 assert(conversation_init(other_c))
 assert(!strings.contains(string(conversation_context("")),"静かに短く話す"))
 archived:=strings.clone_to_cstring(full)
 defer delete(archived)
 assert(conversation_apply_archive(archived),string(conversation_error()))
 assert(conversation_apply_archive(archived))
 testing.expect_value(t,conversation_message_count(),2)
 testing.expect_value(t,conversation_memory_count(),2)
 testing.expect_value(t,scalar("SELECT processed FROM jobs WHERE id=1"),1)
 testing.expect_value(t,scalar("SELECT count(*) FROM source_documents WHERE raw_markdown='hello'"),1)
 assert(strings.contains(string(conversation_context("home")),"静かに短く話す"))
 assert(string(conversation_archive_delta(0))==full)
}

@(test)
person_import_rejects_invalid_hash_and_rolls_back_partial_seeds :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-person-bad-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 wrong,_:=strings.replace_all(TEST_PERSON,"2cf24dba", "3cf24dba")
 defer delete(wrong)
 wrong_c:=strings.clone_to_cstring(wrong)
 defer delete(wrong_c)
 assert(!conversation_import_person(wrong_c))
 invalid,_:=strings.replace_all(TEST_PERSON,`"certainty":"explicit"`, `"certainty":"inferred"`)
 defer delete(invalid)
 invalid_c:=strings.clone_to_cstring(invalid)
 defer delete(invalid_c)
 assert(!conversation_import_person(invalid_c))
 testing.expect_value(t,scalar("SELECT count(*) FROM person_profiles"),0)
 testing.expect_value(t,scalar("SELECT count(*) FROM source_documents"),0)
 testing.expect_value(t,conversation_memory_count(),0)
 assert(conversation_add_message("user","existing chat")==1)
 testing.expect(t,!conversation_import_person(TEST_PERSON))
 testing.expect_value(t,conversation_message_count(),1)
}

@(test)
person_schema_migrates_v1_without_replacing_history :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-person-v1-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 path:=strings.concatenate({dir,"/conversation.sqlite3"})
 defer delete(path)
 cpath:=strings.clone_to_cstring(path)
 defer delete(cpath)
 assert(sqlite3_open_v2(cpath,&db,6,nil)==0)
 assert(execute(SCHEMA))
 assert(insert_message("user","preserved v1 history")==1)
 assert(insert_job(1,1))
 assert(execute(`INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,valid_from) VALUES('event','user','user','old','v1 sourced memory','shared','explicit','2026-11-13'); INSERT INTO memory_sources VALUES(1,1); UPDATE jobs SET processed=1; INSERT INTO meta VALUES('initialized','1'); PRAGMA user_version=1;`))
 sqlite3_close(db)
 db=nil
 assert(conversation_init(c))
 testing.expect_value(t,scalar("PRAGMA user_version"),4)
 testing.expect_value(t,conversation_message_count(),1)
 assert(conversation_load_window(0,1))
 testing.expect_value(t,string(conversation_message_text(0)),"preserved v1 history")
 testing.expect_value(t,conversation_memory_count(),1)
 testing.expect_value(t,scalar("SELECT processed FROM jobs WHERE id=1"),1)
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 target,e2:=os.make_directory_temp("","conversation-v1-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,conversation_message_count(),1)
 testing.expect_value(t,conversation_memory_count(),1)
 testing.expect_value(t,scalar("SELECT processed FROM jobs WHERE id=1"),1)
 assert(strings.contains(string(conversation_memories("old")),"preserved v1 history"))
}

@(test)
archive_seek_is_bounded_after_twenty_thousand_messages :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-archive-large-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 // Fast fixture construction followed by the same one-time v1 backfill used in production.
 assert(execute("BEGIN IMMEDIATE"))
 for _ in 0..<20000 { assert(insert_message("user","昔の実際の会話")>0) }
 assert(execute("DELETE FROM meta WHERE key='archive_version'"))
 assert(archive_backfill())
 assert(execute("COMMIT"))
 cursor:=scalar("SELECT max(id) FROM archive_transactions")
 assert(cursor>1)
 conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("assistant","new message")==20001)
 delta:=strings.clone(string(conversation_archive_delta(cursor)))
 defer delete(delta)
 assert(len(delta)<262144 && strings.contains(delta,"new message") && !strings.contains(delta,"昔の実際の会話"))
 assert(delta==string(conversation_archive_delta(cursor)))
 assert(conversation_archive_delta(-1)==nil && conversation_archive_delta(cursor+1000000)==nil)
 seek:=prepare("SELECT id,bytes FROM archive_transactions WHERE id>?1 ORDER BY id LIMIT 256")
 assert(seek!=nil)
 defer sqlite3_finalize(seek)
 assert(bind_int(seek,1,cursor))
 for sqlite3_step(seek)==100 {}
 testing.expect(t,sqlite3_stmt_status(seek,1,0)<10)
 after:i64
 total:=0
 for {
  output:=string(conversation_archive_delta(after))
  assert(len(output)<=ARCHIVE_DELTA_MAX)
  value,ok:=parse_json(transmute([]byte)output,10)
  assert(ok)
  object:=value.(json.Object)
  batch:=object["records"].(json.Array)
  for record in batch { total+=len(record.(json.Object)["events"].(json.Array)) }
  after=i64(object["through_cursor"].(json.Integer))
  more:=bool(object["has_more"].(json.Boolean))
  json.destroy_value(value)
  if !more do break
 }
 // 20k old messages, one new assistant message and 1251 extraction jobs.
 testing.expect_value(t,total,20001+1251)
 fmt.println("Archive 20k fixture:",cursor,"backfill transactions; latest delta bytes:",len(delta))
}

@(test)
archive_malformed_transaction_rolls_back_rows_and_cursor :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-archive-invalid-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_import_person(TEST_PERSON))
 original:=strings.clone(string(conversation_archive_delta(0)))
 defer delete(original)
 malformed,_:=strings.replace_all(original,`"section":"ユーザーとの関係"`,`"section":""`)
 defer delete(malformed)
 malicious:=strings.clone_to_cstring(malformed)
 defer delete(malicious)
 target,e2:=os.make_directory_temp("","conversation-archive-target-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 assert(!conversation_apply_archive(malicious))
 testing.expect_value(t,scalar("SELECT count(*) FROM archive_transactions"),0)
 testing.expect_value(t,scalar("SELECT count(*) FROM person_profiles"),0)
 testing.expect_value(t,conversation_memory_count(),0)
 valid:=strings.clone_to_cstring(original)
 defer delete(valid)
 assert(conversation_apply_archive(valid))
 assert(!conversation_apply_archive(malicious))
 testing.expect_value(t,conversation_memory_count(),1)
}

@(test)
archive_rejects_message_id_gaps_before_exposing_paging_state :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-archive-gap-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 gap :: `{"format":1,"person":null,"from_cursor":0,"through_cursor":1,"has_more":false,"records":[{"cursor":1,"events":[{"table":"messages","op":"insert","row":{"id":2,"role":"user","content":"invalid gap","created_at":"2026-11-13T14:00:00Z"}}]}]}`
 testing.expect(t,!conversation_apply_archive(gap))
 testing.expect_value(t,conversation_message_count(),0)
 testing.expect_value(t,scalar("SELECT count(*) FROM messages"),0)
}

@(test)
legacy_pinned_full_length_message_survives_archive_replay :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-long-legacy-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 text:=make([]byte,MAX_MESSAGE_BYTES)
 defer delete(text)
 for &character in text do character='a'
 path:=strings.concatenate({dir,"/notebook.json"})
 defer delete(path)
 notebook:=fmt.aprintf(`{{"version":1,"messages":["%s"],"memories":[0]}}`,string(text))
 defer delete(notebook)
 assert(os.write_entire_file(path,notebook)==nil)
 assert(conversation_init(c),string(conversation_error()))
 assert(conversation_memory_count()==1)
 testing.expect_value(t,string(conversation_memory_text(0)),string(text))
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 target,e2:=os.make_directory_temp("","conversation-long-legacy-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,conversation_message_count(),1)
 testing.expect_value(t,conversation_memory_count(),1)
 testing.expect_value(t,string(conversation_memory_text(0)),string(text))
 value,ok:=parse_json(transmute([]byte)string(delta),10)
 assert(ok)
 defer json.destroy_value(value)
 found:=false
 for record in value.(json.Object)["records"].(json.Array) {
  for event in record.(json.Object)["events"].(json.Array) {
   object:=event.(json.Object)
   if string(object["table"].(json.String))!="memories" do continue
   row:=object["row"].(json.Object)
   assert(archive_row_valid(archive_table("memories"),row))
   original_key:=row["key"]
   original_job:=row["job_id"]
   row["key"]=json.String("normal")
   testing.expect(t,!archive_row_valid(archive_table("memories"),row))
   row["key"]=original_key
   row["job_id"]=json.Integer(1)
   testing.expect(t,!archive_row_valid(archive_table("memories"),row))
   row["job_id"]=original_job
   found=true
  }
 }
 testing.expect(t,found)
}

@(test)
archive_replays_large_transaction_in_original_event_order :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-archive-multi-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(execute("BEGIN IMMEDIATE") && archive_begin())
 for _ in 0..<4096 { assert(insert_message("user","順番を保つ会話")>0) }
 assert(archive_finish() && execute("COMMIT"))
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 target,e2:=os.make_directory_temp("","conversation-archive-multi-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,conversation_message_count(),4096)
 assert(conversation_load_window(4095,1))
 testing.expect_value(t,conversation_message_id(4095),4096)
 testing.expect_value(t,string(conversation_message_text(4095)),"順番を保つ会話")
 testing.expect_value(t,string(conversation_archive_delta(0)),string(delta))
 assert(conversation_apply_archive(delta))
 testing.expect_value(t,conversation_message_count(),4096)
}
