package conversation

import "base:runtime"
import "core:encoding/json"
import "core:strings"

REBUILD_SCHEMA :: `
CREATE TABLE IF NOT EXISTS message_annotations(message_id INTEGER PRIMARY KEY REFERENCES messages(id),kind TEXT NOT NULL CHECK(kind IN ('scene','meta')));
CREATE TRIGGER IF NOT EXISTS annotations_no_update BEFORE UPDATE ON message_annotations BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS annotations_no_delete BEFORE DELETE ON message_annotations BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TABLE IF NOT EXISTS episode_rebuilds(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL,request_id TEXT NOT NULL UNIQUE,roots TEXT NOT NULL CHECK(json_valid(roots) AND json_type(roots)='array'),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS rebuilds_no_update BEFORE UPDATE ON episode_rebuilds BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS rebuilds_no_delete BEFORE DELETE ON episode_rebuilds BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

insert_annotation :: proc(id:i64,kind:string) -> bool {
 stmt:=prepare("INSERT INTO message_annotations(message_id,kind) VALUES(?1,?2)")
 if stmt==nil do return false
 defer sqlite3_finalize(stmt)
 return bind_int(stmt,1,id) && bind_text(stmt,2,kind) && sqlite3_step(stmt)==101 && archive_capture("message_annotations",id)
}
message_is_annotation :: proc(id:i64) -> bool {
 stmt:=prepare("SELECT EXISTS(SELECT 1 FROM message_annotations WHERE message_id=?1)")
 if stmt==nil do return true
 defer sqlite3_finalize(stmt)
 return !bind_int(stmt,1,id) || sqlite3_step(stmt)!=100 || sqlite3_column_int64(stmt,0)==1
}
@(export)
conversation_add_note :: proc "c" (kind,text:cstring) -> i64 {
 context=runtime.default_context()
 if kind==nil || (string(kind)!="scene" && string(kind)!="meta") { fail("A scene or meta note kind is required."); return 0 }
 return add_message("user",text,nil,kind)
}
@(export)
conversation_message_annotation :: proc "c" (index:i32) -> cstring {
 if index<cache_first || int(index-cache_first)>=len(cache) do return nil
 return cache[index-cache_first].annotation
}

REBUILD_MESSAGE_JSON :: `json_patch(json_object('id',id,'role',role,'content',substr(content,1,4000),'created_at',created_at,'truncated',json(CASE WHEN length(content)>4000 THEN 'true' ELSE 'false' END)),coalesce((SELECT json_object('annotation',kind) FROM message_annotations WHERE message_id=messages.id),'{}'))`
@(export)
conversation_rebuild_batch :: proc "c" (after_id,through_id:i64) -> cstring {
 context=runtime.default_context()
 if !ready || after_id<0 || through_id<after_id || through_id>i64(message_count) { fail("Invalid historical rebuild boundary."); return nil }
 sql:=strings.clone_to_cstring(`WITH candidates AS MATERIALIZED (SELECT id,`+REBUILD_MESSAGE_JSON+` AS row FROM messages WHERE id>?1 AND id<=?2 ORDER BY id LIMIT 4096), budget AS (SELECT id,row,sum(length(CAST(row AS BLOB))+1) OVER(ORDER BY id) AS bytes FROM candidates), page AS (SELECT * FROM budget WHERE bytes<=262000) SELECT json_object('messages',json((SELECT json_group_array(json(row)) FROM page)),'through_id',coalesce((SELECT max(id) FROM page),?1),'boundary_id',?2)`)
 defer delete(sql)
 stmt:=prepare(sql)
 if stmt==nil do return nil
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,after_id) || !bind_int(stmt,2,through_id) || sqlite3_step(stmt)!=100 { fail("Could not read historical messages."); return nil }
 last_error=""
 return result_text(sqlite3_column_text(stmt,0))
}

rebuild_node_valid :: proc(node:json.Object,boundary:i64,key_limit:int) -> bool {
 if len(node)!=6 do return fail("Rebuild nodes must contain six fields and no children.")
 if _,ok:=string_field(node,"key",key_limit); !ok do return false
 if _,ok:=string_field(node,"text",1536); !ok do return false
 scope,scope_ok:=string_field(node,"scope",16)
 if !scope_ok || (scope!="real" && scope!="fiction" && scope!="shared") do return false
 for field in ([2]string{"emotion","scene"}) {
  text,ok:=node[field].(json.String)
  maximum:=768
  if !ok || (string(text)!="" && !valid_text(string(text),maximum)) do return false
 }
 sources,ok:=node["source_ids"].(json.Array)
 if !ok || len(sources)<1 || len(sources)>8 do return false
 for source,i in sources {
  id,id_ok:=source.(json.Integer)
  if !id_ok || id<1 || i64(id)>boundary do return false
  for old in sources[:i] { if old.(json.Integer)==id do return false }
  stmt:=prepare("SELECT 1 FROM messages WHERE id=?1")
  if stmt==nil do return false
  saved:=bind_int(stmt,1,i64(id)) && sqlite3_step(stmt)==100
  sqlite3_finalize(stmt)
  if !saved do return false
 }
 return true
}

rebuild_roots_valid :: proc(boundary:i64,encoded:string,require_links:bool) -> bool {
 if boundary<0 || boundary>scalar("SELECT coalesce(max(id),0) FROM messages") || len(encoded)>CONTINUITY_MAX_BYTES do return false
 value,ok:=parse_json(transmute([]byte)encoded)
 if !ok do return false
 defer json.destroy_value(value)
 roots,roots_ok:=value.(json.Array)
 if !roots_ok || len(roots)>30 do return false
 for root,i in roots {
  node,node_ok:=root.(json.Object)
  if !node_ok || !rebuild_node_valid(node,boundary,128) do return false
  for previous in roots[:i] { if previous.(json.Object)["key"].(json.String)==node["key"].(json.String) do return false }
  if require_links {
   text,e:=json.marshal(node)
   defer delete(text)
   if e!=nil do return false
   check:=prepare(`SELECT 1 FROM episode_nodes n JOIN memory_current c ON c.id=n.memory_id WHERE n.root_key=json_extract(?1,'$.key') AND n.parent_key='' AND n.node_key=n.root_key AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(n.node) EXCEPT SELECT fullkey,type,atom FROM json_tree(?1)) AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?1) EXCEPT SELECT fullkey,type,atom FROM json_tree(n.node))`)
   if check==nil do return false
   found:=bind_text(check,1,string(text)) && sqlite3_step(check)==100
   sqlite3_finalize(check)
   if !found do return false
  }
 }
 return true
}

rebuild_prepare :: proc(stage:SQLite,sql:cstring) -> Statement {
 stmt:Statement
 if sqlite3_prepare_v2(stage,sql,-1,&stmt,nil)!=0 { fail("Rebuild staging database is missing or corrupt; original history was preserved."); return nil }
 return stmt
}

@(export)
conversation_publish_profile_rebuild :: proc "c" (boundary:i64,payload:cstring) -> bool {
 context=runtime.default_context()
 if !ready do return fail("History is not open.")
 if boundary<1 || payload==nil || len(string(payload))>8192 do return fail("Invalid historical profile data.")
 value,parsed:=parse_json(transmute([]byte)string(payload))
 if !parsed do return false
 defer json.destroy_value(value)
 fields,object_ok:=value.(json.Object)
 if !object_ok || len(fields)>5 do return fail("Expected at most five historical profile fields.")
 if !execute("BEGIN IMMEDIATE") do return false
 committed:=false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 if boundary!=scalar("SELECT coalesce(max(id),0) FROM messages") do return fail("New messages arrived during rebuild; extend staging to the latest boundary and retry.")
 for label,candidate in fields {
  key:=strings.concatenate({"profile/",label})
  defer delete(key)
  field,field_ok:=candidate.(json.Object)
  if !profile_fact_key(key) || !field_ok || len(field)!=2 do return fail("Invalid historical profile field.")
  value,value_ok:=field["value"].(json.String)
  source,source_ok:=field["source_id"].(json.Integer)
  if !value_ok || !profile_fact_value_valid(key,string(value)) || !source_ok || source<1 || i64(source)>boundary || !profile_fact_source_valid(i64(source)) do return fail("Profile evidence must be an assistant reply in the rebuilt history.")
  previous,previous_source,_,ok:=current_profile_fact(key,string(value))
  if !ok do return false
  if previous_source>=i64(source) do continue
  if archive_group==0 && !archive_begin() do return false
  stmt:=prepare(`INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,supersedes_id,valid_from)
    VALUES('fact','assistant','friend',?1,?2,'shared','explicit',nullif(?3,0),(SELECT created_at FROM messages WHERE id=?4))`)
  if stmt==nil do return false
  saved:=bind_text(stmt,1,key) && bind_text(stmt,2,string(value)) && bind_int(stmt,3,previous) && bind_int(stmt,4,i64(source)) && sqlite3_step(stmt)==101
  sqlite3_finalize(stmt)
  if !saved do return fail("Could not save the historical profile field.")
  id:=sqlite3_last_insert_rowid(db)
  if !archive_capture("memories",id) || !insert_source(id,i64(source)) do return false
 }
 if archive_group!=0 && !archive_finish() do return false
 committed=execute("COMMIT")
 if committed do last_error=""
 return committed
}

@(export)
conversation_publish_rebuild :: proc "c" () -> bool {
 context=runtime.default_context()
 if !ready do return fail("History is not open.")
 stage_path:=strings.concatenate({directory,"/.memory-rebuild.sqlite3"})
 defer delete(stage_path)
 path:=strings.clone_to_cstring(stage_path)
 defer delete(path)
 stage:SQLite
 if sqlite3_open_v2(path,&stage,1,nil)!=0 { if stage!=nil do sqlite3_close(stage); return fail("Rebuild staging database could not be opened; original history was preserved.") }
 defer sqlite3_close(stage)
 if sqlite3_exec(stage,"BEGIN",nil,nil,nil)!=0 do return fail("Could not read rebuild staging database.")
 meta:=rebuild_prepare(stage,`SELECT (SELECT value FROM meta WHERE key='boundary'),(SELECT value FROM meta WHERE key='cursor'),(SELECT value FROM meta WHERE key='roots'),(SELECT value FROM meta WHERE key='request_id')`)
 if meta==nil do return false
 if sqlite3_step(meta)!=100 { sqlite3_finalize(meta); return fail("Invalid rebuild metadata.") }
 boundary_text:=strings.clone(string(sqlite3_column_text(meta,0)))
 cursor_text:=strings.clone(string(sqlite3_column_text(meta,1)))
 roots:=strings.clone(string(sqlite3_column_text(meta,2)))
 request:=strings.clone(string(sqlite3_column_text(meta,3)))
 sqlite3_finalize(meta)
 defer { delete(boundary_text); delete(cursor_text); delete(roots); delete(request) }
 boundary_value,boundary_ok:=parse_json(transmute([]byte)boundary_text)
 defer json.destroy_value(boundary_value)
 cursor_value,cursor_ok:=parse_json(transmute([]byte)cursor_text)
 defer json.destroy_value(cursor_value)
 boundary,b_ok:=boundary_value.(json.Integer)
 cursor,c_ok:=cursor_value.(json.Integer)
 if !boundary_ok || !cursor_ok || !b_ok || !c_ok || boundary<0 || cursor!=boundary || !person_uuid_valid(request) do return fail("Historical rebuild is incomplete or invalid.")
 if !execute("BEGIN IMMEDIATE") do return false
 committed:=false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 prefix,_:=strings.replace_all(request,"-","")
 defer delete(prefix)
 // Normalize only root identities, preserving stable descendant keys.
 prefix_c:=strings.clone_to_cstring(prefix)
 defer delete(prefix_c)
 normalized:=json_query(`SELECT json_group_array(json_set(value,'$.key',?2||json_extract(value,'$.key'))) FROM json_each(?1)`,roots,prefix_c)
 if normalized==nil do return false
 normalized_roots:=strings.clone(string(normalized))
 defer delete(normalized_roots)
 if !rebuild_roots_valid(i64(boundary),roots,false) || !rebuild_roots_valid(i64(boundary),normalized_roots,false) do return fail("Invalid rebuilt root summaries.")
 prior:=prepare("SELECT through_id,roots FROM episode_rebuilds WHERE request_id=?1")
 if prior==nil do return false
 found:=bind_text(prior,1,request) && sqlite3_step(prior)==100
 same:=found && sqlite3_column_int64(prior,0)==i64(boundary) && string(sqlite3_column_text(prior,1))==normalized_roots
 sqlite3_finalize(prior)
 if found {
  if !same do return fail("This rebuild request already published different results.")
 }
 if !found && i64(boundary)!=scalar("SELECT coalesce(max(id),0) FROM messages") do return fail("New messages arrived during rebuild; extend staging to the latest boundary and retry.")
 published_transaction:i64
 if found {
  check:=prepare("SELECT transaction_id FROM archive_events WHERE json_extract(event_json,'$.table')='episode_rebuilds' AND json_extract(event_json,'$.row.request_id')=?1 LIMIT 1")
  if check==nil do return false
  saved:=bind_text(check,1,request) && sqlite3_step(check)==100
  if saved do published_transaction=sqlite3_column_int64(check,0)
  sqlite3_finalize(check)
  if !saved do return fail("Published rebuild journal is missing.")
 } else if !archive_begin() do return false
 rows:=rebuild_prepare(stage,"SELECT root_key,key,parent_key,depth,node,typeof(root_key)='text' AND typeof(key)='text' AND typeof(parent_key)='text' AND typeof(depth)='integer' AND typeof(node)='text' FROM nodes ORDER BY depth,root_key,key")
 if rows==nil do return false
 defer sqlite3_finalize(rows)
 paths:=make(map[string]string)
 scopes:=make(map[string]string)
 defer { for key,value in paths { delete(key); delete(value) }; delete(paths); for key,value in scopes { delete(key); delete(value) }; delete(scopes) }
 roots_value,parsed:=parse_json(transmute([]byte)roots)
 if !parsed do return false
 defer json.destroy_value(roots_value)
 root_count,node_count:=0,0
 for {
  code:=sqlite3_step(rows)
  if code==101 do break
  if code!=100 do return fail("Could not read staged episode nodes.")
  if sqlite3_column_int64(rows,5)!=1 do return fail("Invalid staged hierarchy column types.")
  root,key,parent:=string(sqlite3_column_text(rows,0)),string(sqlite3_column_text(rows,1)),string(sqlite3_column_text(rows,2))
  depth:=sqlite3_column_int64(rows,3)
  encoded:=string(sqlite3_column_text(rows,4))
  if !valid_text(root,96) || !valid_text(key,96) || depth<0 || depth>3 || len(encoded)>32768 do return fail("Invalid staged hierarchy identity, depth or size.")
  value,ok:=parse_json(transmute([]byte)encoded)
  if !ok do return false
  node,node_ok:=value.(json.Object)
  if !node_ok || !rebuild_node_valid(node,i64(boundary),96) || string(node["key"].(json.String))!=key { json.destroy_value(value); return fail("Invalid staged hierarchy node.") }
  scene:=string(node["scene"].(json.String))
  scope:=string(node["scope"].(json.String))
  identity:=strings.concatenate({root,"\x00",key})
  defer delete(identity)
  parent_identity:=strings.concatenate({root,"\x00",parent})
  defer delete(parent_identity)
  ancestor:=""
  if _,exists:=paths[identity]; exists { json.destroy_value(value); return fail("Duplicate staged hierarchy identity.") }
  if depth==0 {
   if root!=key || parent!="" { json.destroy_value(value); return fail("Invalid rebuilt root parent.") }
   matched,selected:=false,false
   for summary in roots_value.(json.Array) {
    obj:=summary.(json.Object)
    if string(obj["key"].(json.String))!=root do continue
    selected=true
    a,_:=json.marshal(obj)
    compare:=prepare(`SELECT NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?1) EXCEPT SELECT fullkey,type,atom FROM json_tree(?2)) AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?2) EXCEPT SELECT fullkey,type,atom FROM json_tree(?1))`)
    if compare!=nil {
     matched=bind_text(compare,1,string(a)) && bind_text(compare,2,encoded) && sqlite3_step(compare)==100 && sqlite3_column_int64(compare,0)==1
     sqlite3_finalize(compare)
    }
    delete(a)
   }
   if selected && !matched { json.destroy_value(value); return fail("Staged roots disagree with their summaries.") }
   if selected do root_count+=1
  } else {
   parent_path,exists:=paths[parent_identity]
   if !exists || scopes[parent_identity]!=scope { json.destroy_value(value); return fail("Staged hierarchy parent or scope is inconsistent.") }
   ancestor=parent_path
   // Parent depth must be exactly one shallower, even when scene labels are empty.
   parent_row:=rebuild_prepare(stage,"SELECT depth FROM nodes WHERE root_key=?1 AND key=?2")
   if parent_row==nil { json.destroy_value(value); return false }
   parent_valid:=bind_text(parent_row,1,root) && bind_text(parent_row,2,parent) && sqlite3_step(parent_row)==100 && sqlite3_column_int64(parent_row,0)==depth-1
   sqlite3_finalize(parent_row)
   if !parent_valid { json.destroy_value(value); return fail("Staged hierarchy skipped a parent level.") }
  }
  expected:=strings.concatenate({ancestor," / ",scene}) if depth>0 else strings.clone(scene)
  if depth>0 && len(scene)>256 { delete(expected); json.destroy_value(value); return fail("Invalid staged ancestor path.") }
  paths[strings.clone(identity)]=expected
  scopes[strings.clone(identity)]=strings.clone(scope)
  namespaced:=strings.concatenate({prefix,root})
  defer delete(namespaced)
  if depth==0 { for field,old in node { if field=="key" { delete(string(old.(json.String))); node[field]=json.String(strings.clone(namespaced)); break } } }
  normalized_parent:=namespaced if parent==root else parent
  saved:=rebuild_existing_node_valid(published_transaction,namespaced,normalized_parent,node) if found else continuity_index_episode(i64(boundary),node,namespaced,normalized_parent,ancestor)
  if !saved { json.destroy_value(value); return false }
  json.destroy_value(value)
  node_count+=1
  if !found && archive_bytes()>ARCHIVE_GROUP_MAX do return fail("Rebuild exceeds the 16 MiB transaction budget; staging and original data were preserved.")
 }
 if root_count!=len(roots_value.(json.Array)) || (!found && !rebuild_roots_valid(i64(boundary),normalized_roots,true)) do return fail("Rebuilt roots are missing their saved hierarchy.")
 if found {
  check:=prepare("SELECT count(*) FROM archive_events WHERE transaction_id=?1 AND json_extract(event_json,'$.table')='episode_nodes'")
  if check==nil do return false
  identical:=bind_int(check,1,published_transaction) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==i64(node_count)
  sqlite3_finalize(check)
  if !identical do return fail("This rebuild request already published different nodes.")
  committed=execute("ROLLBACK")
  if committed do last_error=""
  return committed
 }
 stmt:=prepare("INSERT INTO episode_rebuilds(through_id,request_id,roots,created_at) VALUES(?1,?2,?3,"+CONTINUITY_PUBLICATION_TIME+")")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,i64(boundary)) && bind_text(stmt,2,request) && bind_text(stmt,3,normalized_roots) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !saved || !archive_capture("episode_rebuilds",sqlite3_last_insert_rowid(db)) || !archive_finish() || !execute("COMMIT") do return false
 committed=true
 last_error=""
 return true
}


rebuild_existing_node_valid :: proc(transaction:i64,root,parent:string,node:json.Object) -> bool {
 encoded,e:=json.marshal(node)
 defer delete(encoded)
 if e!=nil do return false
 stmt:=prepare(`SELECT 1 FROM archive_events e JOIN episode_nodes n ON n.memory_id=json_extract(e.event_json,'$.row.memory_id') WHERE e.transaction_id=?1 AND json_extract(e.event_json,'$.table')='episode_nodes' AND n.root_key=?2 AND n.parent_key=?3 AND n.node_key=json_extract(?4,'$.key') AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(n.node) EXCEPT SELECT fullkey,type,atom FROM json_tree(?4)) AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?4) EXCEPT SELECT fullkey,type,atom FROM json_tree(n.node))`)
 if stmt==nil do return false
 defer sqlite3_finalize(stmt)
 return (bind_int(stmt,1,transaction) && bind_text(stmt,2,root) && bind_text(stmt,3,parent) && bind_text(stmt,4,string(encoded)) && sqlite3_step(stmt)==100) || fail("This rebuild request already published different nodes.")
}

rebuild_roots_namespace_valid :: proc(request,roots:string) -> bool {
 if !person_uuid_valid(request) do return false
 prefix,_:=strings.replace_all(request,"-","")
 defer delete(prefix)
 value,ok:=parse_json(transmute([]byte)roots)
 if !ok do return false
 defer json.destroy_value(value)
 for item in value.(json.Array) {
  key:=string(item.(json.Object)["key"].(json.String))
  if !strings.has_prefix(key,prefix) || !valid_text(key[len(prefix):],96) do return false
 }
 return true
}
