package conversation

import "core:encoding/json"
import "core:fmt"
import "core:os"
import "core:strings"
import "core:testing"

REPLY_STATE_FIXTURE :: `{"emotion":"嬉しい","reason":"勝ったと聞いた","event":"試合結果を一緒に喜んだ","scope":"shared"}`

@(test)
reply_state_is_immediate_and_survives_delayed_extraction_restart_and_archive :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-reply-state-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","試合が終わった")>0)
 assert(conversation_add_message("assistant","お疲れさま")>0)
 assert(conversation_add_message("user","しかも勝ったよ！")>0)
 assert(conversation_add_reply("やったね！",REPLY_STATE_FIXTURE)==4,string(conversation_error()))
 testing.expect_value(t,string(conversation_assistant_emotion()),"嬉しい")
 testing.expect_value(t,scalar("SELECT count(*) FROM jobs WHERE processed=1"),0)
 // An older extraction can finish after the next reply has already saved its feeling.
 assert(conversation_apply_job(1,ASSISTANT_STATE_FIXTURE),string(conversation_error()))
 assert(conversation_apply_job(2,`{"memories":[],"continuity":{"threads":[],"episodes":[]}}`),string(conversation_error()))
 testing.expect_value(t,string(conversation_assistant_emotion()),"嬉しい")
 conversation_close()
 assert(conversation_init(c))
 testing.expect_value(t,string(conversation_assistant_emotion()),"嬉しい")
 testing.expect(t,strings.contains(string(conversation_context("無関係")),"勝ったと聞いた"))
 testing.expect(t,strings.contains(string(conversation_context("無関係")),`"source_ids":[3,4]`))
 assert(conversation_load_window(0,80))
 testing.expect_value(t,string(conversation_message_text(3)),"やったね！")
 assert(conversation_export(c))
 metadata_path:=strings.concatenate({dir,"/metadata.json"})
 defer delete(metadata_path)
 metadata,read_error:=os.read_entire_file(metadata_path,context.allocator)
 assert(read_error==nil)
 defer delete(metadata)
 {
  root,parsed:=parse_json(metadata)
  assert(parsed)
  defer json.destroy_value(root)
  metadata_obj,object_ok:=root.(json.Object)
  count,count_ok:=metadata_obj["assistant_states"].(json.Integer)
  if !testing.expect(t,object_ok && count_ok,"Export must include the assistant state count.") do return
  testing.expect_value(t,i64(count),1)
 }
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 conversation_close()
 target,e2:=os.make_directory_temp("","conversation-reply-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 target_c:=strings.clone_to_cstring(target)
 defer delete(target_c)
 assert(conversation_init(target_c))
 poisoned,replaced:=strings.replace_all(string(delta),`\"source_ids\":[3,4]`,`\"source_ids\":[999,4]`)
 assert(replaced)
 defer delete(poisoned)
 poison_c:=strings.clone_to_cstring(poisoned)
 defer delete(poison_c)
 testing.expect(t,!conversation_apply_archive(poison_c))
 testing.expect_value(t,conversation_message_count(),0)
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,string(conversation_assistant_emotion()),"嬉しい")
 assert(conversation_add_message("user","落ち着いたね")>0)
 assert(conversation_add_reply("うん", "null")==6)
 assert(conversation_apply_job(3,`{"memories":[],"continuity":{"threads":[],"episodes":[],"assistant_state":null}}`))
 testing.expect_value(t,string(conversation_assistant_emotion()),"")
 conversation_close()
 assert(conversation_init(target_c))
 testing.expect_value(t,string(conversation_assistant_emotion()),"")
}

@(test)
invalid_reply_state_rolls_back_the_reply_jobs_and_journal :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-reply-invalid-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","試合に勝ったよ")==1)
 cursor:=scalar("SELECT max(id) FROM archive_transactions")
 for payload in ([7]cstring{
  `{}`,`[]`,`{"emotion":123,"reason":"理由","event":"会話","scope":"shared"}`,
  `{"emotion":"嬉しい","reason":"理由","event":"会話","scope":"real"}`,
  `{"emotion":"嬉しい","reason":"理由","event":"会話","scope":"shared","source_ids":[999]}`,
  `{"emotion":"嬉しい","reason":"理由","event":"会話","scope":"shared"} trailing`,nil,
 }) {
  testing.expect_value(t,conversation_add_reply("やったね",payload),0)
  testing.expect_value(t,conversation_message_count(),1)
  testing.expect_value(t,scalar("SELECT count(*) FROM jobs"),0)
  testing.expect_value(t,scalar("SELECT count(*) FROM assistant_states"),0)
  testing.expect_value(t,scalar("SELECT max(id) FROM archive_transactions"),cursor)
 }
 escaped,e2:=strings.repeat("\\u0061",768)
 assert(e2==nil)
 defer delete(escaped)
 event,e3:=strings.repeat("\\u0061",500)
 assert(e3==nil)
 defer delete(event)
 oversized:=fmt.aprintf(`{{"emotion":"%s","reason":"%s","event":"%s","scope":"shared"}}`,escaped,escaped,event)
 defer delete(oversized)
 oversized_c:=strings.clone_to_cstring(oversized)
 defer delete(oversized_c)
 if !testing.expect(t,conversation_add_reply("やったね",oversized_c)==0,"Reserve space for source IDs so every saved state can be restored.") do return
 testing.expect_value(t,conversation_message_count(),1)
 assert(conversation_add_reply("やったね",REPLY_STATE_FIXTURE)==2)
}

@(test)
schema_four_upgrade_retains_legacy_feeling_until_a_new_reply :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-reply-upgrade-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","試合が終わった")>0)
 assert(conversation_add_message("assistant","ほっとした")>0)
 assert(conversation_apply_job(1,ASSISTANT_STATE_FIXTURE))
 assert(execute("DROP TABLE assistant_states; PRAGMA user_version=4"))
 conversation_close()
 assert(conversation_init(c))
 testing.expect_value(t,scalar("PRAGMA user_version"),5)
 testing.expect_value(t,string(conversation_assistant_emotion()),"ほっとして嬉しい")
 assert(conversation_add_message("user","勝ったよ")>0)
 assert(conversation_add_reply("やったね",REPLY_STATE_FIXTURE)>0)
 testing.expect_value(t,string(conversation_assistant_emotion()),"嬉しい")
}
