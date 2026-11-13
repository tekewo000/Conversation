package conversation

import "core:os"
import "core:encoding/json"
import "core:fmt"
import "core:strings"
import "core:testing"

TEST_PERSONA_CHANGES :: `[{"field":"preferences","text":"短く具体的に話す会話が好き","reason":"短い説明を希望する会話が続いた","source_ids":[1,2,3,4]}]`

test_persona_archive_history :: proc() {
 assert(conversation_import_person(TEST_PERSON))
 for pair:i64=1;pair<=6;pair+=1 {
  assert(conversation_add_message("user","短く具体的に話してほしい")==pair*2-1)
  assert(conversation_add_message("assistant","短く具体的に話します")==pair*2)
  assert(conversation_apply_job(pair,`{"memories":[]}`))
 }
}

test_persona_archive_append :: proc(delta: string, changes: string = TEST_PERSONA_CHANGES) -> cstring {
 stmt:=prepare(`SELECT json_set(?1,'$.through_cursor',json_extract(?1,'$.through_cursor')+1,'$.records[#]',json_object('cursor',json_extract(?1,'$.through_cursor')+1,'events',json_array(json_object('table','persona_revisions','op','insert','row',json_object('id',1,'through_id',12,'changes',?2,'created_at','2026-11-14T00:00:00Z')))))`)
 assert(stmt!=nil)
 defer sqlite3_finalize(stmt)
 assert(bind_text(stmt,1,delta) && bind_text(stmt,2,changes) && sqlite3_step(stmt)==100)
 return strings.clone_to_cstring(string(sqlite3_column_text(stmt,0)))
}

test_persona_archive_patch :: proc(delta, suffix, value: string) -> cstring {
 stmt:=prepare(`SELECT json_set(?1,'$.records['||(json_array_length(?1,'$.records')-1)||'].events[0]'||?2,json(?3))`)
 assert(stmt!=nil)
 defer sqlite3_finalize(stmt)
 assert(bind_text(stmt,1,delta) && bind_text(stmt,2,suffix) && bind_text(stmt,3,value) && sqlite3_step(stmt)==100)
 return strings.clone_to_cstring(string(sqlite3_column_text(stmt,0)))
}

@(test)
persona_archive_replays_revision_after_its_processed_evidence :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-archive-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 test_persona_archive_history()
 payload:=strings.concatenate({`{"changes":`,TEST_PERSONA_CHANGES,`}`})
 defer delete(payload)
 payload_c:=strings.clone_to_cstring(payload)
 defer delete(payload_c)
 assert(conversation_apply_persona(12,payload_c),string(conversation_error()))
 persona_test_pairs(6)
 assert(conversation_apply_persona(24,`{"changes":[]}`),string(conversation_error()))
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 target,e2:=os.make_directory_temp("","conversation-persona-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,conversation_message_count(),24)
 testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),2)
 testing.expect_value(t,scalar("SELECT through_id FROM persona_revisions WHERE id=1"),12)
 testing.expect_value(t,scalar("SELECT through_id FROM persona_revisions WHERE id=2"),24)
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),2)
 assert(strings.contains(string(conversation_context("")),"短く具体的に話す"))
 testing.expect_value(t,string(conversation_pending_persona()),"{}")
 testing.expect_value(t,string(conversation_archive_delta(0)),string(delta))
}

@(test)
persona_archive_rejects_poisoned_revisions_and_rolls_back_history :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-poison-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 test_persona_archive_history()
 original:=strings.clone(string(conversation_archive_delta(0)))
 defer delete(original)
 valid:=test_persona_archive_append(original)
 defer delete(valid)
 target,e2:=os.make_directory_temp("","conversation-persona-poison-target-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 invalid_changes:=[]string{
  `[{"field":"name","text":"別の名前","reason":"記憶から推定","source_ids":[1,2,3,4]}]`,
  `[{"field":"preferences","text":"短く話す","reason":"未発言の出典","source_ids":[1,2,13,14]}]`,
  `[{"field":"preferences","text":"短く話す","reason":"片側だけの会話","source_ids":[1,3]}]`,
  `[{"field":"preferences","text":"短く話す","reason":"重複した出典","source_ids":[1,2,2,3,4]}]`,
  `[{"field":"preferences","text":"短く話す","reason":"未知の変更欄","source_ids":[1,2,3,4],"identity":"創作した人物"}]`,
  `{}`,
 }
 for changes in invalid_changes {
  poisoned:=test_persona_archive_append(original,changes)
  testing.expect(t,!conversation_apply_archive(poisoned))
  delete(poisoned)
  testing.expect_value(t,conversation_message_count(),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM messages"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM archive_transactions"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM person_profiles"),0)
 }
 for mutation in ([][2]string{{".op",`"update"`},{".row.id","2"},{".row.through_id","11"},{".row.through_id","14"},{".row.through_id","12.5"}}) {
  poisoned:=test_persona_archive_patch(string(valid),mutation[0],mutation[1])
  testing.expect(t,!conversation_apply_archive(poisoned))
  delete(poisoned)
  testing.expect_value(t,scalar("SELECT count(*) FROM messages"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM archive_transactions"),0)
 }
 padding:=make([]byte,32769)
 defer delete(padding)
 for &character in padding do character=' '
 oversized:=strings.concatenate({string(padding),TEST_PERSONA_CHANGES})
 defer delete(oversized)
 poisoned:=test_persona_archive_append(original,oversized)
 defer delete(poisoned)
 testing.expect(t,!conversation_apply_archive(poisoned))
 testing.expect_value(t,scalar("SELECT count(*) FROM messages"),0)
 assert(conversation_apply_archive(valid),string(conversation_error()))
 testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),1)
}

@(test)
persona_export_retains_revision_jsonl_and_format_two_counts :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-export-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 test_persona_archive_history()
 delta:=test_persona_archive_append(string(conversation_archive_delta(0)))
 defer delete(delta)
 // Replay into the same history also checks that old archive records remain accepted.
 assert(conversation_apply_archive(delta),string(conversation_error()))
 assert(conversation_export(c),string(conversation_error()))
 path:=strings.concatenate({dir,"/metadata.json"})
 defer delete(path)
 metadata,e2:=os.read_entire_file(path,context.allocator)
 defer delete(metadata)
 assert(e2==nil)
 root,ok:=parse_json(metadata,6)
 assert(ok)
 defer json.destroy_value(root)
 manifest:=root.(json.Object)
 testing.expect_value(t,i64(manifest["format"].(json.Integer)),2)
 count,count_ok:=manifest["persona_revisions"].(json.Integer)
 assert(count_ok && count==1)
 testing.expect_value(t,i64(manifest["chunks"].(json.Object)["persona_revisions"].(json.Integer)),1)
 revision_path:=fmt.aprintf("%s/%s/persona_revisions-000000.jsonl",dir,string(manifest["snapshot"].(json.String)))
 defer delete(revision_path)
 revision_bytes,e3:=os.read_entire_file(revision_path,context.allocator)
 defer delete(revision_bytes)
 assert(e3==nil)
 revision,revision_ok:=parse_json(revision_bytes,2)
 assert(revision_ok)
 defer json.destroy_value(revision)
 row:=revision.(json.Object)
 testing.expect_value(t,i64(row["id"].(json.Integer)),1)
 testing.expect_value(t,i64(row["through_id"].(json.Integer)),12)
 testing.expect_value(t,string(row["changes"].(json.String)),TEST_PERSONA_CHANGES)
}
