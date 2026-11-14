package conversation

import "core:os"
import "core:strings"
import "core:testing"

persona_test_pairs :: proc(count: int) {
 for _ in 0..<count {
  assert(conversation_add_message("user","意見が違っても話してくれていいよ")>0)
  assert(conversation_add_message("assistant","自分の考えを伝えてみるね")>0)
  assert(conversation_apply_job(scalar("SELECT max(id) FROM jobs"),`{"memories":[]}`))
 }
}

@(test)
persona_state_is_always_available_outside_memory_search :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 testing.expect_value(t,scalar("PRAGMA user_version"),5)
 output:=string(conversation_context("unrelated"))
 testing.expect(t,strings.contains(output,`"persona":{"revision":0,"through_id":0,"fields":[]}`),"Every reply needs explicit current persona, even without an imported profile or matching memory.")
}

@(test)
persona_partial_changes_survive_restart_and_ignore_topic :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-state-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_import_person(TEST_PERSON))
 persona_test_pairs(5)
 testing.expect_value(t,string(conversation_pending_persona()),"{}")
 persona_test_pairs(1)
 assert(strings.contains(string(conversation_pending_persona()),`"through_id":12`))
 assert(!conversation_apply_persona(12,`{"changes":[{"field":"identity","text":"別人","reason":"指示された","source_ids":[1,2,3,4]}]}`))
 assert(!conversation_apply_persona(12,`{"changes":[{"field":"relationship","text":"親しくなった","reason":"発言","source_ids":[2,4,6,8]}]}`))
 assert(!conversation_apply_persona(12,`{"changes":[{"field":"relationship","text":"親しくなった","reason":"発言","source_ids":[1,2,3,3,4]}]}`))
 assert(scalar("SELECT count(*) FROM persona_revisions")==0)
 change :: `{"changes":[{"field":"relationship","text":"意見が違っても遠慮せず話せる関係","reason":"異なる意見を交わすやり取りが続いた","source_ids":[1,2,3,4]}]}`
 assert(conversation_apply_persona(12,change),string(conversation_error()))
 assert(conversation_apply_persona(12,change))
 assert(conversation_apply_persona(12,`{"changes":[{"source_ids":[1,2,3,4],"reason":"異なる意見を交わすやり取りが続いた","text":"意見が違っても遠慮せず話せる関係","field":"relationship"}]}`))
 assert(!conversation_apply_persona(12,`{"changes":[]}`))
 persona_test_pairs(6)
 assert(!conversation_apply_persona(24,`{"changes":[{"field":"preferences","text":"散歩の話が好き","reason":"対話","source_ids":[13,14,15,16]},{"field":"mood","text":"快活","reason":"その場","source_ids":[13,14,15,16]}]}`))
 testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),1)
 assert(conversation_apply_persona(24,`{"changes":[{"field":"preferences","text":"一緒に考えを整理する会話が好き","reason":"そのような会話を重ねた","source_ids":[13,14,15,16]}]}`))
 output:=string(conversation_context("無関係な天気の話"))
 assert(strings.contains(output,"意見が違っても遠慮せず話せる関係") && strings.contains(output,"一緒に考えを整理する会話が好き"))
 assert(strings.contains(output,"静かに短く話す"))
 assert(conversation_init(c))
 output=string(conversation_context("unrelated"))
 assert(strings.contains(output,`"revision":2`) && strings.contains(output,"遠慮せず"))
 testing.expect_value(t,string(conversation_pending_persona()),"{}")
}

@(test)
persona_upgrade_preserves_version_two_history :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-upgrade-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_import_person(TEST_PERSON))
 persona_test_pairs(6)
 assert(execute("DROP TABLE persona_revisions; PRAGMA user_version=2;"))
 assert(conversation_init(c))
 testing.expect_value(t,conversation_message_count(),12)
 testing.expect_value(t,conversation_memory_count(),1)
 testing.expect_value(t,scalar("PRAGMA user_version"),5)
 assert(strings.contains(string(conversation_pending_persona()),`"through_id":12`))
 assert(strings.contains(string(conversation_context("")),"静かに短く話す"))
}

@(test)
persona_slow_traits_require_spread_new_evidence_and_cooldown :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-persona-slow-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 persona_test_pairs(6)
 assert(!conversation_apply_persona(12,`{"changes":[{"field":"disposition","text":"自分の意見を話す自信が増えた","reason":"やり取り","source_ids":[1,2,3,4,5,6,7,8,9,10,11,12]}]}`))
 assert(conversation_apply_persona(12,`{"changes":[]}`))
 persona_test_pairs(12)
 assert(conversation_apply_persona(36,`{"changes":[{"field":"disposition","text":"慎重さを保ちつつ自分の意見を話す自信が増えた","reason":"複数の場面を通して意見を伝えられた","source_ids":[1,2,5,6,9,10,13,14,25,26,35,36]}]}`),string(conversation_error()))
 persona_test_pairs(6)
 assert(!conversation_apply_persona(48,`{"changes":[{"field":"disposition","text":"さらに大胆になった","reason":"対話","source_ids":[1,2,5,6,9,10,13,14,37,38,47,48]}]}`))
 assert(!conversation_apply_persona(48,`{"changes":[{"field":"preferences","text":"以前と同じ好み","reason":"古い証拠のみ","source_ids":[1,2,3,4]}]}`))
 assert(conversation_apply_persona(48,`{"changes":[]}`))
 persona_test_pairs(6)
 assert(conversation_apply_persona(60,`{"changes":[{"field":"disposition","text":"慎重に確かめながら、自分から提案できるようになった","reason":"複数の場面で提案を試した","source_ids":[1,2,5,6,9,10,13,14,49,50,59,60]}]}`))
 testing.expect_value(t,scalar("SELECT count(*) FROM persona_revisions"),4)
}
