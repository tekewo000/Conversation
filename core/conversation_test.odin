package conversation
import "core:os"
import "core:strings"
import "core:testing"
import "core:fmt"
import "core:time"
import "core:encoding/json"

@(test)
short_term_context_keeps_thirty_exchanges_plus_current_input_with_escaped_long_text :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-thirty-exchanges-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 body,e2:=strings.repeat("\x01",3000)
 assert(e2==nil)
 defer delete(body)
 assert(execute("BEGIN IMMEDIATE"))
 for _ in 0..<35 {
  assert(insert_message("user",body)>0)
  assert(insert_message("assistant",body)>0)
 }
 assert(enqueue_uncovered())
 assert(execute("COMMIT"))
 assert(conversation_add_message("user","今回の入力は全文")>0)
 conversation_close()
 assert(conversation_init(c))
 output:=string(conversation_context("unmatched"))
 root,ok:=parse_json(transmute([]byte)output,8)
 assert(ok)
 defer json.destroy_value(root)
 rows:=root.(json.Object)["messages"].(json.Array)
 testing.expect_value(t,len(rows),61)
 testing.expect_value(t,i64(rows[0].(json.Object)["id"].(json.Integer)),11)
 testing.expect_value(t,i64(rows[len(rows)-1].(json.Object)["id"].(json.Integer)),71)
 testing.expect(t,bool(rows[0].(json.Object)["truncated"].(json.Boolean)))
 testing.expect_value(t,string(rows[len(rows)-1].(json.Object)["content"].(json.String)),"今回の入力は全文")
 testing.expect(t,len(output)<800000,"Escaped long history must remain bounded while retaining every selected turn.")
}

@(test)
sqlite_durable_jobs_validation_and_paging :: proc(t: ^testing.T) {
 dir, e := os.make_directory_temp("", "conversation-sqlite-*", context.allocator)
 assert(e == nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c := strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(!conversation_send(" \n"))
 assert(conversation_add_message("system", "bad") == 0)
 assert(conversation_add_message("user", "私は東京に住む") == 1)
 assert(conversation_add_message("assistant", "東京ですね") == 2)
 assert(strings.contains(string(conversation_pending_job()), "東京"))
 good :: `{"memories":[{"kind":"fact","subject":"user","key":"home","text":"東京","scope":"real","certainty":"explicit","source_ids":[1]}]}`
 bad :: `{"memories":[{"kind":"fact","subject":"user","key":"home","text":"future","scope":"real","certainty":"explicit","source_ids":[3]}]}`
 assert(!conversation_apply_job(1, bad))
 assert(conversation_memory_count() == 0)
 assert(conversation_apply_job(1, good))
 assert(conversation_apply_job(1, good))
 assert(conversation_memory_count() == 1)
 assert(conversation_add_message("user", "京都へ引っ越した") == 3)
 assert(conversation_add_message("assistant", "了解") == 4)
 cross :: `{"memories":[{"kind":"interpretation","subject":"user","key":"home","text":"京都かも","scope":"real","certainty":"inferred","source_ids":[3],"supersedes_id":1}]}`
 assert(!conversation_apply_job(2, cross))
 inferred_fact :: `{"memories":[{"kind":"fact","subject":"user","key":"home","text":"京都かも","scope":"real","certainty":"inferred","source_ids":[3],"supersedes_id":1}]}`
 assert(!conversation_apply_job(2,inferred_fact))
 assert(conversation_memory_count() == 1)
 updated :: `{"memories":[{"kind":"fact","subject":"user","key":"home","text":"京都","scope":"real","certainty":"explicit","source_ids":[3],"supersedes_id":1,"valid_from":"2026-11-13"}]}`
 assert(conversation_apply_job(2, updated))
 testing.expect_value(t, conversation_memory_count(), 2)
 result := string(conversation_memories("home"))
 assert(strings.contains(result, "京都") && strings.contains(result, "source_ids"))
 past := string(conversation_context("以前の東京"))
 assert(strings.contains(past,`"current":false`) && strings.contains(past,"東京"))
 conversation_close()
 assert(conversation_init(c))
 assert(conversation_message_count() == 4)
 assert(conversation_memory_count() == 2)
 assert(conversation_load_window(1, 2))
 assert(conversation_message_text(0) == nil)
 assert(conversation_message_id(1) == 2)
 assert(conversation_message_text(3) == nil)
 assert(!conversation_load_window(0, 81))
 assert(conversation_export(c))
 manifest:=strings.concatenate({dir,"/metadata.json"})
 defer delete(manifest)
 bytes,export_e:=os.read_entire_file(manifest,context.allocator)
 defer delete(bytes)
 assert(export_e==nil && strings.contains(string(bytes),`"snapshot":"snapshot-`))
}

@(test)
legacy_migration_preserves_file_and_enqueues_once :: proc(t: ^testing.T) {
 dir, e := os.make_directory_temp("", "conversation-migrate-*", context.allocator)
 assert(e == nil)
 defer delete(dir)
 defer os.remove_all(dir)
 path := strings.concatenate({dir, "/notebook.json"})
 defer delete(path)
 old :: `{"version":1,"messages":["昔の記憶","次の文"],"memories":[0]}`
 assert(os.write_entire_file(path, old) == nil)
 c := strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_message_count() == 2)
 assert(conversation_memory_count() == 1)
 assert(strings.contains(string(conversation_pending_job()), "昔の記憶"))
 conversation_close()
 assert(conversation_init(c))
 assert(conversation_message_count() == 2)
 data, read_e := os.read_entire_file(path, context.allocator)
 defer delete(data)
 assert(read_e == nil && string(data) == old)
 testing.expect(t, true)
}

@(test)
large_history_restart_is_bounded_and_uses_incremental_storage :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-large-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 // Fixture loading is one transaction; the public append path is separately exercised below.
 assert(execute("BEGIN IMMEDIATE"))
 for _ in 0..<20000 { assert(insert_message("user","古い会話。東京。")>0) }
 assert(enqueue_uncovered())
 assert(execute("COMMIT"))
 conversation_close()
 assert(conversation_init(c))
 testing.expect_value(t,conversation_message_count(),20000)
 testing.expect_value(t,len(cache),80)
 assert(conversation_message_text(0)==nil)
 assert(conversation_load_window(0,1))
 assert(string(conversation_message_text(0))=="古い会話。東京。")
 assert(conversation_add_message("assistant","増分保存") == 20001)
 conversation_close()
 assert(conversation_init(c))
 assert(conversation_message_count()==20001)
 assert(len(cache)<=80)
 assert(len(string(conversation_pending_job()))<65536)
 assert(len(string(conversation_context("昔の東京")))<=65536)
 assert(conversation_export(c))
 assert(execute("PRAGMA query_only=ON"))
 testing.expect_value(t,conversation_add_message("user","write must fail"),0)
 testing.expect_value(t,conversation_message_count(),20001)
 assert(execute("PRAGMA query_only=OFF"))
}

@(test)
escaped_source_snippets_keep_job_and_context_byte_budgets :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-budget-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 bytes:=make([]byte,65536)
 defer delete(bytes)
 for &ch in bytes do ch=1
 bytes[0]='a'
 text:=strings.clone_to_cstring(string(bytes))
 defer delete(text)
 for _ in 0..<20 { assert(conversation_add_message("user",text)>0) }
 assert(conversation_add_message("assistant","ready")>0)
 for job:i64=1;job<=21;job+=1 {
  output:=string(conversation_pending_job())
  assert(len(output)<=65536)
  if output=="{}" do break
  assert(strings.contains(output,`"truncated":true`))
  assert(conversation_apply_job(job,`{"memories":[]}`))
 }
 testing.expect(t,len(string(conversation_context("a")))<=800000)
}

@(test)
promise_versions_and_partial_job_rollback :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-promise-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","明日、電話をかける")>0)
 assert(conversation_add_message("assistant","約束を覚えた")>0)
 partial :: `{"memories":[{"kind":"promise","subject":"user","key":"call","text":"電話する","scope":"real","certainty":"explicit","source_ids":[1]},{"kind":"fact","subject":"user","key":"bad","text":"fail","scope":"real","certainty":"explicit","source_ids":[999]}]}`
 assert(!conversation_apply_job(1,partial))
 assert(conversation_memory_count()==0)
 assert(strings.contains(string(conversation_pending_job()),`"id":1`))
 good :: `{"memories":[{"kind":"promise","subject":"user","key":"call","text":"電話する","scope":"real","certainty":"explicit","source_ids":[1]}]}`
 assert(conversation_apply_job(1,good))
 assert(conversation_add_message("user","電話は済んだ")>0)
 assert(conversation_add_message("assistant","完了ですね")>0)
 completed :: `{"memories":[{"kind":"promise","subject":"user","key":"call","text":"電話済み","scope":"real","certainty":"explicit","status":"fulfilled","supersedes_id":1,"source_ids":[3]}]}`
 assert(conversation_apply_job(2,completed))
 result:=string(conversation_memories("電話"))
 assert(strings.contains(result,"fulfilled") && strings.contains(result,`"supersedes_id":1`))
 testing.expect_value(t,conversation_memory_count(),2)
 // Malformed/untrusted values cannot mark a later job processed.
 assert(conversation_add_message("assistant","another")>0)
 invalid:=[]cstring{`{"memories":[{"kind":"fact","subject":"u","key":"k","text":"v","scope":"fiction","certainty":"explicit","source_ids":[]}]}`,`{"memories":[{"kind":"interpretation","subject":"u","key":"k","text":"v","scope":"real","certainty":"explicit","source_ids":[1]}]}`,`{"memories":[{"kind":"event","subject":"u","key":"k","text":"v","scope":"real","certainty":"explicit","source_ids":[1],"valid_from":"2026-02-31"}]}`,`{"memories":[`, `{"memories":[],"unknown":true}`}
 for value in invalid { assert(!conversation_apply_job(3,value)) }
 assert(conversation_apply_job(3,`{"memories":[]}`))
}

@(test)
fts_search_uses_incremental_virtual_indexes :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-index-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 // This historical source has no extracted memory and is far outside recent context.
 assert(conversation_add_message("user","山梨県の図書館で会った")>0)
 assert(conversation_add_message("assistant","覚えた")>0)
 assert(execute("BEGIN IMMEDIATE"))
 for _ in 0..<20000 { assert(insert_message("user","unrelated source")>0) }
 assert(execute(`WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<20000) INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,valid_from) SELECT 'fact','user','fixture','topic-'||i,'unrelated note','real','explicit','2026-11-13' FROM n; INSERT INTO memory_sources SELECT id,1 FROM memories;`))
 assert(execute("COMMIT"))
 output:=string(conversation_context("図書館"))
 testing.expect(t,strings.contains(output,"山梨県"))
 plan:=prepare("EXPLAIN QUERY PLAN SELECT rowid FROM memories_fts WHERE memories_fts MATCH ?1 ORDER BY rank LIMIT 30")
 assert(plan!=nil)
 defer sqlite3_finalize(plan)
 assert(bind_text(plan,1,`"absent"`))
 virtual_index:=false
 for sqlite3_step(plan)==100 {
  if strings.contains(string(sqlite3_column_text(plan,3)),"VIRTUAL TABLE INDEX") do virtual_index=true
 }
 testing.expect(t,virtual_index)
 started:=time.now()
 for _ in 0..<100 {
  assert(string(conversation_memories("qzxvjjk"))==`{"memories":[]}`)
  assert(conversation_context("qzxvjjk")!=nil)
 }
 elapsed:=time.since(started)
 fmt.println("FTS absent-term 100 memory+context queries / 20000 memories + 20002 messages:",elapsed)
 testing.expect(t,elapsed<5*time.Second)
 full_sql:=fmt.aprintf("EXPLAIN QUERY PLAN %s",string(CONTEXT_JSON))
 csql:=strings.clone_to_cstring(full_sql)
 defer delete(csql)
 delete(full_sql)
 full:=prepare(csql)
 assert(full!=nil)
 defer sqlite3_finalize(full)
 assert(bind_text(full,1,"absent") && bind_text(full,2,`"abs" OR "bse" OR "sen" OR "ent"`))
 message_index:=false
 memory_index:=false
 for sqlite3_step(full)==100 {
  detail:=string(sqlite3_column_text(full,3))
  if strings.contains(detail,"messages_fts VIRTUAL TABLE INDEX") do message_index=true
  if strings.contains(detail,"memories_fts VIRTUAL TABLE INDEX") do memory_index=true
  testing.expect(t,!strings.contains(detail,"SCAN m ") && detail!="SCAN m")
 }
 testing.expect(t,message_index && memory_index)
 budget_stmt:=prepare(CONTEXT_JSON)
 assert(budget_stmt!=nil)
 defer sqlite3_finalize(budget_stmt)
 assert(bind_text(budget_stmt,1,"qzxvjjk") && bind_text(budget_stmt,2,`"qzx" OR "zxv" OR "xvj" OR "vjj" OR "jjk"`))
 assert(sqlite3_step(budget_stmt)==100)
 // SQLITE_STMTSTATUS_FULLSCAN_STEP: a hidden 20k-row scan cannot pass this bound.
 testing.expect(t,sqlite3_stmt_status(budget_stmt,1,0)<5000)
 // MATCH operators and quotes are literal user text, never a raw FTS expression.
 assert(conversation_context(`" OR * NOT (query)`)!=nil)
}

@(test)
database_version_and_missing_marker_fail_closed :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","conversation-schema-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 c:=strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 assert(conversation_init(c))
 assert(conversation_add_message("user","existing durable row")>0)
 assert(execute("PRAGMA user_version=999"))
 conversation_close()
 testing.expect(t,!conversation_init(c))
 path:=strings.concatenate({dir,"/conversation.sqlite3"})
 defer delete(path)
 cpath:=strings.clone_to_cstring(path)
 defer delete(cpath)
 assert(sqlite3_open_v2(cpath,&db,6,nil)==0)
 assert(execute("PRAGMA user_version=1; DELETE FROM meta WHERE key='initialized'"))
 sqlite3_close(db)
 db=nil
 legacy:=strings.concatenate({dir,"/notebook.json"})
 defer delete(legacy)
 assert(os.write_entire_file(legacy,`{"version":1,"messages":["must not import"],"memories":[0]}`)==nil)
 testing.expect(t,!conversation_init(c))
 assert(sqlite3_open_v2(cpath,&db,6,nil)==0)
 testing.expect_value(t,scalar("SELECT count(*) FROM messages"),1)
 sqlite3_close(db)
 db=nil
}
