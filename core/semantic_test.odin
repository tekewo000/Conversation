package conversation

import "core:encoding/json"
import "core:os"
import "core:strings"
import "core:testing"

@(test)
semantic_candidates_join_sourced_context_and_reject_invalid_ids :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-semantic-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 path:=strings.clone_to_cstring(dir)
 defer delete(path)
 defer conversation_close()
 assert(conversation_init(path))
 assert(conversation_add_message("user","激しい雨なら家で過ごす。")>0)
 assert(conversation_add_message("assistant","外出を控えるんだね。")>0)
 assert(execute("BEGIN IMMEDIATE"))
 for i in 0..<32 {
  assert(execute("INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,valid_from) VALUES('fact','user','user','topic',CASE WHEN (SELECT count(*) FROM memories)=0 THEN '激しい雨なら家で過ごす' ELSE 'ケーキを焼く' END,'real','explicit','2026-11-14')"))
  assert(insert_source(i64(i+1),1))
 }
 assert(execute("COMMIT"))
 base:=string(conversation_context("downpour"))
 testing.expect(t,!strings.contains(base,"激しい雨なら家で過ごす\",\"scope"))
 result:=string(conversation_context_semantic("downpour","[1]"))
 testing.expect(t,strings.contains(result,"激しい雨なら家で過ごす\",\"scope"))
 testing.expect(t,strings.contains(result,`"source_ids":[1]`))
 for invalid in ([]cstring{"null","{}","[0]","[-1]","[1.5]","[true]","[1,1]","[\"1\"]"}) {
  testing.expect(t,conversation_context_semantic("downpour",invalid)==nil)
 }
 testing.expect_value(t,conversation_memory_count(),32)
 testing.expect(t,!strings.contains(string(conversation_context("downpour")),"激しい雨なら家で過ごす\",\"scope"),"Semantic candidates must not leak into the following ordinary context query.")
 batch_text:=string(conversation_embedding_batch(0))
 batch,ok:=parse_json(transmute([]byte)batch_text,8)
 assert(ok)
 obj:=batch.(json.Object)
 testing.expect_value(t,i64(obj["through_id"].(json.Integer)),16)
 testing.expect_value(t,len(obj["items"].(json.Array)),16)
 testing.expect(t,bool(obj["has_more"].(json.Boolean)))
 // A later C ABI parse resets the shared checked allocator to its own context.
 json.destroy_value(batch)
 testing.expect(t,strings.contains(string(conversation_embedding_batch(16)),`"through_id":32`))
 testing.expect(t,strings.contains(string(conversation_embedding_batch(32)),`"items":[]`))
 assert(conversation_add_message("user","好みが変わって晴れた日は外出する。")>0)
 assert(conversation_add_message("assistant","今の好みを更新するね。")>0)
 assert(execute("INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,valid_from,supersedes_id) VALUES('fact','user','user','topic','晴れた日は外出する','real','explicit','2026-11-14',1)"))
 assert(insert_source(33,3))
 testing.expect(t,!strings.contains(string(conversation_context_semantic("downpour","[1]")),"激しい雨なら家で過ごす\",\"scope"),"A stale vector must not bring a superseded memory back into current reply context.")
 testing.expect(t,strings.contains(string(conversation_embedding_batch(32)),`"supersedes_id":1`))
}
