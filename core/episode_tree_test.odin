package conversation

import "core:encoding/json"
import "core:fmt"
import "core:os"
import "core:strings"
import "core:testing"

TREE_FIXTURE :: `{"memories":[],"continuity":{"threads":[],"episodes":[{"key":"kyoto-2026","text":"京都への二日間の旅行。","emotion":"","scene":"京都旅行","scope":"real","source_ids":[1],"children":[{"key":"day1","text":"初日は観光した。","emotion":"","scene":"1日目","scope":"real","source_ids":[1],"children":[{"key":"morning","text":"朝は喫茶店へ行った。","emotion":"","scene":"朝","scope":"real","source_ids":[1],"children":[{"key":"cafe","text":"青葉珈琲で仕事の悩みを打ち明けた。","emotion":"本人は安心したと話した。","scene":"青葉珈琲","scope":"real","source_ids":[1]}]}]},{"key":"day2","text":"翌日は寺を訪れた。","emotion":"","scene":"2日目","scope":"real","source_ids":[1]}]}]}}`

@(test)
archive_rejects_reparenting_a_later_revision_to_an_existing_sibling :: proc(t:^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-tree-parent-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","京都旅行の話。")>0)
 assert(conversation_add_message("assistant","聞いているよ。")>0)
 assert(conversation_apply_job(1,TREE_FIXTURE))
 assert(conversation_add_message("user","悩みを話して安心した、という訂正。")>0)
 assert(conversation_add_message("assistant","覚えておく。")>0)
 changed,_:=strings.replace_all(TREE_FIXTURE,"仕事の悩みを打ち明けた。","仕事の悩みを相談して安心した。")
 defer delete(changed)
 changed_c:=strings.clone_to_cstring(changed)
 defer delete(changed_c)
 assert(conversation_apply_job(2,changed_c))
 current:=scalar("SELECT max(memory_id) FROM episode_nodes WHERE node_key='cafe'")
 delta:=string(conversation_archive_delta(0))
 parsed,parse_error:=json.parse(transmute([]byte)delta,.JSON,true)
 assert(parse_error==nil)
 defer json.destroy_value(parsed)
 modified:=false
 for record in parsed.(json.Object)["records"].(json.Array) {
  for event in record.(json.Object)["events"].(json.Array) {
   obj:=event.(json.Object)
   if string(obj["table"].(json.String))!="episode_nodes" do continue
   row:=obj["row"].(json.Object)
   if i64(row["memory_id"].(json.Integer))!=current do continue
   json.destroy_value(row["parent_key"])
   row["parent_key"]=json.String(strings.clone("day2"))
   modified=true
  }
 }
 assert(modified)
 poisoned,marshal_error:=json.marshal(parsed)
 assert(marshal_error==nil)
 defer delete(poisoned)
 poisoned_c:=strings.clone_to_cstring(string(poisoned))
 defer delete(poisoned_c)
 target,e2:=os.make_directory_temp("","conversation-tree-parent-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 destination:=strings.clone_to_cstring(target)
 defer delete(destination)
 conversation_close()
 assert(conversation_init(destination))
 testing.expect(t,!conversation_apply_archive(poisoned_c),"A valid sibling is still an invalid replacement for a node's immutable parent.")
 testing.expect_value(t,conversation_message_count(),0)
}

@(test)
generic_memory_revision_cannot_disconnect_an_episode_branch :: proc(t:^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-tree-generic-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","京都旅行の話。")>0)
 assert(conversation_add_message("assistant","聞いているよ。")>0)
 assert(conversation_apply_job(1,TREE_FIXTURE))
 assert(conversation_add_message("user","後から訂正する。")>0)
 assert(conversation_add_message("assistant","うん。")>0)
 id:=scalar("SELECT n.memory_id FROM episode_nodes n WHERE n.node_key='cafe'")
 body:=fmt.aprintf(`{{"memories":[{{"kind":"event","actor":"user","subject":"episode","key":"episode-node:10:kyoto-2026:cafe","text":"別の内容","scope":"real","certainty":"explicit","source_ids":[3],"supersedes_id":%d}}]}}`,id)
 defer delete(body)
 payload:=strings.clone_to_cstring(body)
 defer delete(payload)
 testing.expect(t,!conversation_apply_job(2,payload),"Generic memory output must not supersede hierarchy-managed events.")
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes n JOIN memory_current c ON c.id=n.memory_id"),5)
}

@(test)
hierarchical_experience_keeps_children_across_updates_eviction_and_archive :: proc(t:^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-tree-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","京都旅行の初日の朝、青葉珈琲で仕事の悩みを打ち明けて安心した。翌日は寺へ行った。")>0)
 assert(conversation_add_message("assistant","二日間の旅だったんだね。")>0)
 testing.expect(t,conversation_apply_job(1,TREE_FIXTURE),string(conversation_error()))
 if string(conversation_error())!="" do return
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes"),5)
 snapshot:=string(conversation_context("青葉珈琲"))
 testing.expect(t,strings.contains(snapshot,"仕事の悩みを打ち明けた"))
 testing.expect(t,strings.contains(snapshot,`"hierarchy"`),"A direct leaf hit must carry its ancestor context.")
 testing.expect(t,strings.contains(snapshot,"京都旅行"))
 embedding:=string(conversation_embedding_batch(0))
 testing.expect(t,strings.contains(embedding,`"text":"青葉珈琲で仕事の悩みを打ち明けた。`),"Leaf embeddings must begin with the specific event, before long ancestor labels consume the model's token window.")
 snapshot=string(conversation_context("青葉珈琲"))
 root,parse_error:=json.parse(transmute([]byte)snapshot,.JSON,true)
 assert(parse_error==nil)
 defer json.destroy_value(root)
 episodes:=root.(json.Object)["continuity"].(json.Object)["episodes"].(json.Array)
 testing.expect_value(t,len(episodes),1)
 _,has_children:=episodes[0].(json.Object)["children"]
 testing.expect(t,!has_children,"Reply continuity must carry compact roots, not resend every descendant.")
 assert(conversation_add_message("user","京都旅行の話の続き。")>0)
 assert(conversation_add_message("assistant","聞かせて。")>0)
 update :: `{"memories":[],"continuity":{"threads":[],"episodes":[{"key":"kyoto-2026","text":"京都の二日間の旅で気持ちを整理した。","emotion":"","scene":"京都旅行","scope":"real","source_ids":[1,3]}]}}`
 assert(conversation_apply_job(2,update),string(conversation_error()))
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes n JOIN memory_current c ON c.id=n.memory_id"),5)
 assert(conversation_add_message("user","別の話。")>0)
 assert(conversation_add_message("assistant","うん。")>0)
 assert(conversation_apply_job(3,`{"memories":[],"continuity":{"threads":[],"episodes":[]}}`))
 delta:=strings.clone_to_cstring(string(conversation_archive_delta(0)))
 defer delete(delta)
 conversation_close()
 assert(conversation_init(c))
 testing.expect(t,strings.contains(string(conversation_context("青葉珈琲")),"仕事の悩みを打ち明けた"))
 target,e2:=os.make_directory_temp("","conversation-tree-replay-*",context.allocator)
 assert(e2==nil)
 defer delete(target)
 defer os.remove_all(target)
 destination:=strings.clone_to_cstring(target)
 defer delete(destination)
 conversation_close()
 assert(conversation_init(destination))
 poisoned,_:=strings.replace_all(string(delta),`"parent_key":"morning"`,`"parent_key":"cafe"`)
 defer delete(poisoned)
 poisoned_c:=strings.clone_to_cstring(poisoned)
 defer delete(poisoned_c)
 testing.expect(t,!conversation_apply_archive(poisoned_c),"A forged cyclic parent link must roll back the whole restore.")
 testing.expect_value(t,conversation_message_count(),0)
 assert(conversation_apply_archive(delta),string(conversation_error()))
 assert(conversation_apply_archive(delta),string(conversation_error()))
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes n JOIN memory_current c ON c.id=n.memory_id"),5)
 testing.expect(t,strings.contains(string(conversation_context("青葉珈琲")),"京都旅行"))
 assert(conversation_export(destination))
 testing.expect_value(t,scalar("PRAGMA user_version"),7)
}

@(test)
thirty_root_experiences_keep_more_than_thirty_children_and_page_without_loss :: proc(t:^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-tree-pages-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","三十回の旅行、それぞれの朝と昼の話。")>0)
 assert(conversation_add_message("assistant","別々の旅行だね。")>0)
 Child :: struct {key,text,emotion,scene,scope:string,source_ids:[]i64}
 Root :: struct {key,text,emotion,scene,scope:string,source_ids:[]i64,children:[]Child}
 roots:[30]Root
 keys:[30]string
 defer { for key in keys do delete(key) }
 children:=[2]Child{{"morning","朝の出来事","","朝","real",[]i64{1}},{"noon","昼の出来事","","昼","real",[]i64{1}}}
 for &root,i in roots { keys[i]=fmt.aprintf("trip-%d",i); root={keys[i],"別々の旅行","","旅行","real",[]i64{1},children[:]} }
 bytes,marshal_error:=json.marshal(struct {memories:[0]int,continuity:struct {threads:[0]int,episodes:[]Root}}{{},{{},roots[:]}})
 assert(marshal_error==nil)
 defer delete(bytes)
 payload:=strings.clone_to_cstring(string(bytes))
 defer delete(payload)
 assert(conversation_apply_job(1,payload),string(conversation_error()))
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes"),90)
 assert(conversation_add_message("user","最初の旅行の細かな出来事の続き。")>0)
 assert(conversation_add_message("assistant","続けて聞くよ。")>0)
 more:[65]Child
 more_keys:[65]string
 defer { for key in more_keys do delete(key) }
 for &child,i in more { more_keys[i]=fmt.aprintf("event-%d",i); child={more_keys[i],"旅先での出来事","","場面","real",[]i64{3}} }
 roots[0].children=more[:]
 next_bytes,next_error:=json.marshal(struct {memories:[0]int,continuity:struct {threads:[0]int,episodes:[]Root}}{{},{{},roots[:1]}})
 assert(next_error==nil)
 defer delete(next_bytes)
 next:=strings.clone_to_cstring(string(next_bytes))
 defer delete(next)
 assert(conversation_apply_job(2,next),string(conversation_error()))
 page:=conversation_episode_children(`{"root_key":"trip-0","parent_key":"trip-0","scope":"real","after_id":0}`)
 assert(page!=nil,string(conversation_error()))
 parsed,page_error:=json.parse(transmute([]byte)string(page),.JSON,true)
 assert(page_error==nil)
 defer json.destroy_value(parsed)
 obj:=parsed.(json.Object)
 testing.expect_value(t,len(obj["episodes"].(json.Array)),64)
 testing.expect(t,bool(obj["has_more"].(json.Boolean)))
 request:=fmt.aprintf(`{{"root_key":"trip-0","parent_key":"trip-0","scope":"real","after_id":%d}}`,obj["after_id"].(json.Integer))
 defer delete(request)
 request_c:=strings.clone_to_cstring(request)
 defer delete(request_c)
 last:=conversation_episode_children(request_c)
 parsed_last,last_error:=json.parse(transmute([]byte)string(last),.JSON,true)
 assert(last_error==nil)
 defer json.destroy_value(parsed_last)
 testing.expect_value(t,len(parsed_last.(json.Object)["episodes"].(json.Array)),3)
 testing.expect(t,!bool(parsed_last.(json.Object)["has_more"].(json.Boolean)))
}

@(test)
hierarchical_experience_rejects_duplicate_scope_and_depth_atomically :: proc(t:^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-bad-tree-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","京都旅行の話。")>0)
 assert(conversation_add_message("assistant","聞いているよ。")>0)
 for replacement in ([3][2]string{
  {`"key":"day2"`,`"key":"day1"`},
  {`"scene":"青葉珈琲","scope":"real"`,`"scene":"青葉珈琲","scope":"fiction"`},
  {`"scene":"青葉珈琲","scope":"real","source_ids":[1]`,`"scene":"青葉珈琲","scope":"real","source_ids":[999]`},
 }) {
  bad,_:=strings.replace_all(TREE_FIXTURE,replacement[0],replacement[1])
  defer delete(bad)
  payload:=strings.clone_to_cstring(bad)
  defer delete(payload)
  testing.expect(t,!conversation_apply_job(1,payload))
  testing.expect_value(t,conversation_memory_count(),0)
 }
 testing.expect(t,conversation_apply_job(1,TREE_FIXTURE),string(conversation_error()))
 assert(conversation_add_message("user","翌日の夜の話。")>0)
 assert(conversation_add_message("assistant","聞いているよ。")>0)
 fresh,_:=strings.replace_all(TREE_FIXTURE,`"source_ids":[1]`,`"source_ids":[3]`)
 defer delete(fresh)
 fresh_c:=strings.clone_to_cstring(fresh)
 defer delete(fresh_c)
 assert(conversation_apply_job(2,fresh_c),string(conversation_error()))
 testing.expect_value(t,conversation_memory_count(),5)
 testing.expect_value(t,scalar("SELECT count(*) FROM memory_sources s JOIN episode_nodes n ON n.memory_id=s.memory_id WHERE n.node_key='cafe' AND s.message_id=3"),1)
 assert(conversation_add_message("user","続きを話す。")>0)
 assert(conversation_add_message("assistant","うん。")>0)
 // Moving a known child under a different parent must not rewrite the original experience.
 moved,_:=strings.replace_all(TREE_FIXTURE,`"key":"morning"`,`"key":"evening"`)
 defer delete(moved)
 moved_c:=strings.clone_to_cstring(moved)
 defer delete(moved_c)
 testing.expect(t,!conversation_apply_job(3,moved_c))
 testing.expect_value(t,scalar("SELECT count(*) FROM episode_nodes"),5)
}
