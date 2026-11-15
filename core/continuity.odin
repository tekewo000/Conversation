package conversation

import "core:encoding/json"
import "core:fmt"
import "core:strings"
import "base:runtime"

CONTINUITY_MAX_BYTES :: 65536
CONTINUITY_SCHEMA :: `
CREATE TABLE IF NOT EXISTS continuity_revisions(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),state TEXT NOT NULL CHECK(json_valid(state) AND json_type(state)='object'),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS continuity_no_update BEFORE UPDATE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS continuity_no_delete BEFORE DELETE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

BASE_CONTINUITY_JSON :: `coalesce((SELECT json_set(state,'$.through_id',through_id) FROM continuity_revisions ORDER BY id DESC LIMIT 1),'{}')`
// Publication order must survive table-wise snapshots; exact legacy timestamp ties prefer continuity.
CONTINUITY_PUBLICATION_TIME :: `strftime('%Y-%m-%dT%H:%M:%fZ',max(julianday('now'),coalesce(julianday((SELECT created_at FROM continuity_revisions ORDER BY id DESC LIMIT 1)),0)+1.0/86400000,coalesce(julianday((SELECT created_at FROM episode_rebuilds ORDER BY id DESC LIMIT 1)),0)+1.0/86400000))`
EXTRACTED_CONTINUITY_JSON :: `(CASE WHEN EXISTS(SELECT 1 FROM episode_rebuilds WHERE id=(SELECT max(id) FROM episode_rebuilds) AND (through_id>(SELECT coalesce(max(through_id),0) FROM continuity_revisions) OR (through_id=(SELECT coalesce(max(through_id),0) FROM continuity_revisions) AND created_at>coalesce((SELECT created_at FROM continuity_revisions ORDER BY id DESC LIMIT 1),'')))) THEN json_set(`+BASE_CONTINUITY_JSON+`,'$.episodes',json((SELECT roots FROM episode_rebuilds ORDER BY id DESC LIMIT 1))) ELSE `+BASE_CONTINUITY_JSON+` END)`

CONTINUITY_JSON :: `(CASE WHEN EXISTS(SELECT 1 FROM assistant_states) THEN json_set(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state',json(`+CURRENT_ASSISTANT_STATE_JSON+`),'$.assistant_state_through_id',(SELECT through_id FROM assistant_states ORDER BY id DESC LIMIT 1)) ELSE `+EXTRACTED_CONTINUITY_JSON+` END)`

CONTINUITY_REFERENCES_SQL :: `SELECT CAST(s.value AS INTEGER) AS id,CASE WHEN CAST(s.key AS INTEGER)>0 THEN 4 WHEN json_extract(item.value,'$.status')='active' THEN 0 WHEN json_extract(item.value,'$.status')='paused' THEN 1 ELSE 3 END AS priority FROM continuity_revisions r,json_each(r.state,'$.threads') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),CASE WHEN CAST(s.key AS INTEGER)=0 THEN 2 ELSE 4 END FROM continuity_revisions r,json_each(r.state,'$.episodes') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM continuity_revisions r,json_each(r.state,'$.assistant_state.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM continuity_revisions r,json_each(r.state,'$.relationship.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),CASE WHEN CAST(s.key AS INTEGER)=0 THEN 2 ELSE 4 END FROM json_each(`+EXTRACTED_CONTINUITY_JSON+`,'$.episodes') item,json_each(item.value,'$.source_ids') s
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM json_each((SELECT state FROM assistant_states ORDER BY id DESC LIMIT 1),'$.source_ids') s`
CONTINUITY_SOURCES_SQL :: "SELECT id FROM ("+CONTINUITY_REFERENCES_SQL+")"

EPISODE_SOURCES_SQL :: "SELECT s.message_id FROM memory_sources s JOIN episode_nodes n ON n.memory_id=s.memory_id JOIN memory_current c ON c.id=n.memory_id"

@(export)
conversation_assistant_emotion :: proc "c" () -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 return json_query(`SELECT coalesce(json_extract(`+CURRENT_ASSISTANT_STATE_JSON+`,'$.emotion'),'') WHERE ?1=''`)
}

continuity_revision_valid :: proc(through_id: i64, state_text: string, delta:bool=false) -> bool {
 if len(state_text)>131072 do return fail("Conversation state exceeds the extraction budget.")
 root,parsed:=parse_json(transmute([]byte)state_text,12)
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 if !obj_ok || len(obj)<2 || len(obj)>4 do return fail("Expected conversation threads, episodes and optional assistant or relationship state.")
 for key in obj { if key!="threads" && key!="episodes" && key!="assistant_state" && key!="relationship" do return fail("Unknown conversation state field.") }
 threads,threads_ok:=obj["threads"].(json.Array)
 episodes,episodes_ok:=obj["episodes"].(json.Array)
 if !threads_ok || !episodes_ok || len(threads)>(32 if delta else 6) || len(episodes)>(128 if delta else 30) do return fail("Expected at most six threads and thirty episodes, or 32 threads and 128 roots in a delta.")
 if !delta {
  compact:=episode_compact(state_text)
  if compact==nil || len(string(compact))>CONTINUITY_MAX_BYTES do return fail("Conversation state exceeds 64 KiB.")
 }
 episode_entries: json.Array
 defer delete(episode_entries)
 seen:=make(map[string]bool)
 defer { for key in seen do delete(key); delete(seen) }
 for value in episodes {
  ep,ok:=value.(json.Object)
  if !ok do return fail("Expected episode objects.")
  key,key_ok:=ep["key"].(json.String)
  scope,scope_ok:=ep["scope"].(json.String)
  if !key_ok || !scope_ok || !episode_collect(&episode_entries,value,string(key),"",string(scope),0,&seen) do return false
 }
 boundary:=prepare(`SELECT min(first_id) FROM jobs WHERE end_id=?1 AND end_id>(SELECT coalesce(max(through_id),0) FROM continuity_revisions) HAVING count(*)>0`)
 if boundary==nil do return false
 valid:=bind_int(boundary,1,through_id) && sqlite3_step(boundary)==100
 first,job_first:i64
 if valid { job_first=sqlite3_column_int64(boundary,0); first=max(1,job_first-12) }
 sqlite3_finalize(boundary)
 if !valid do return fail("Conversation state must advance to an existing extraction boundary.")
 assistant_entries:json.Array
 defer delete(assistant_entries)
 if state,exists:=obj["assistant_state"]; exists {
  if _,is_null:=state.(json.Null); !is_null { append(&assistant_entries,state) }
 }
 relationship_entries:json.Array
 defer delete(relationship_entries)
 relationship_unchanged:=false
 if state,exists:=obj["relationship"]; exists {
  append(&relationship_entries,state)
  same:=prepare(`WITH old AS (SELECT coalesce((SELECT state FROM continuity_revisions ORDER BY id DESC LIMIT 1),'{}') AS state)
   SELECT NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?1,'$.relationship') EXCEPT SELECT fullkey,type,atom FROM old,json_tree(old.state,'$.relationship'))
   AND NOT EXISTS(SELECT fullkey,type,atom FROM old,json_tree(old.state,'$.relationship') EXCEPT SELECT fullkey,type,atom FROM json_tree(?1,'$.relationship'))`)
  if same==nil do return false
  valid=bind_text(same,1,state_text) && sqlite3_step(same)==100
  if valid do relationship_unchanged=sqlite3_column_int64(same,0)==1
  sqlite3_finalize(same)
  if !valid do return false
 }
 for entries,group in ([4]json.Array{threads,episode_entries,assistant_entries,relationship_entries}) {
  for item,i in entries {
   entry,entry_ok:=item.(json.Object)
   expected:=4
   if group==1 { expected=6; if _,exists:=entry["children"]; exists do expected=7 }
   if group==2 do expected=5
   if !entry_ok || len(entry)!=expected do return fail("Invalid conversation entry fields.")
   sources,sources_ok:=entry["source_ids"].(json.Array)
   if !sources_ok || len(sources)<1 || len(sources)>8 do return fail("Conversation entries need one to eight evidence IDs.")
   if group<2 {
    key,key_ok:=string_field(entry,"key",128)
    _,text_ok:=string_field(entry,"text",1536)
    if !key_ok || !text_ok do return fail("Conversation entries need key and text.")
    if group==0 { for previous in entries[:i] {
     if string(previous.(json.Object)["key"].(json.String))==key do return fail("Duplicate conversation entry key.")
    } }
   }
   if group==3 {
    if _,ok:=string_field(entry,"trust",768); !ok do return fail("Relationship trust must fit within 768 bytes.")
    if _,ok:=string_field(entry,"openness",768); !ok do return fail("Relationship openness must fit within 768 bytes.")
    if _,ok:=string_field(entry,"basis",1536); !ok do return fail("Relationship basis must fit within 1536 bytes.")
   } else if group==0 {
    status,status_ok:=string_field(entry,"status",16)
    if !status_ok || (status!="active" && status!="paused" && status!="resolved") do return fail("Invalid conversation thread status.")
   } else {
    scope,scope_ok:=string_field(entry,"scope",16)
    if !scope_ok || (scope!="real" && scope!="fiction" && scope!="shared") do return fail("Invalid episode scope.")
    if group==1 {
     for field in ([2]string{"emotion","scene"}) {
      value,value_ok:=entry[field].(json.String)
      if !value_ok || (string(value)!="" && !valid_text(string(value),768)) do return fail("Invalid episode emotion or scene.")
     }
    } else {
     for field in ([3]string{"emotion","reason","event"}) {
      if _,ok:=string_field(entry,field,768); !ok do return fail("Assistant state needs a feeling, reason and event within 768 bytes each.")
     }
    }
   }
   users,assistants,new_users,new_assistants:=0,0,0,0
   for source,j in sources {
    id,id_ok:=source.(json.Integer)
    if !id_ok || id<1 || i64(id)>through_id do return fail("Conversation evidence must be a saved message at or before the job boundary.")
    for previous in sources[:j] { if previous.(json.Integer)==id do return fail("Duplicate conversation evidence.") }
    check:=prepare("SELECT role FROM messages WHERE id=?1 AND (id>=?2 OR id IN ("+CONTINUITY_SOURCES_SQL+") OR id IN ("+EPISODE_SOURCES_SQL+"))")
    if check==nil do return false
    found:=bind_int(check,1,i64(id)) && bind_int(check,2,first) && sqlite3_step(check)==100
    if found && group==3 && !message_is_annotation(i64(id)) {
     if string(sqlite3_column_text(check,0))=="user" { users+=1; if i64(id)>=job_first do new_users+=1 }
     else { assistants+=1; if i64(id)>=job_first do new_assistants+=1 }
    }
    sqlite3_finalize(check)
    if !found do return fail("Conversation evidence must come from the extraction window or previous conversation state.")
   }
   if group==3 && (users<1 || assistants<1 || (!relationship_unchanged && (new_users<1 || new_assistants<1))) do return fail("Relationship changes need both sides of a fresh exchange; unchanged state may retain its evidence.")
  }
 }
 return true
}

continuity_delta_merge :: proc(through_id:i64,delta:string) -> cstring {
 boundary:=fmt.caprintf("%d",through_id)
 defer delete(boundary)
 // A completed rebuild may be ahead of ordinary extraction; adopt it only once the job catches up.
 return json_query(`WITH old AS (SELECT CASE WHEN (SELECT through_id FROM episode_rebuilds ORDER BY id DESC LIMIT 1)<=CAST(?2 AS INTEGER) THEN `+EXTRACTED_CONTINUITY_JSON+` ELSE `+BASE_CONTINUITY_JSON+` END AS state),
 groups(kind) AS (VALUES('threads'),('episodes')),
 changes AS (SELECT kind,CAST(item.key AS INTEGER) AS position,item.value AS entry FROM groups,json_each(?1,'$.'||kind) item),
 previous AS (SELECT kind,CAST(item.key AS INTEGER) AS position,item.value AS entry FROM old,groups,json_each(old.state,'$.'||kind) item),
 merged AS (
 SELECT p.kind,p.position,coalesce(c.entry,p.entry) AS entry,c.entry IS NOT NULL AS changed FROM previous p LEFT JOIN changes c ON c.kind=p.kind AND json_extract(c.entry,'$.key')=json_extract(p.entry,'$.key')
 UNION ALL SELECT c.kind,1000+c.position,c.entry,1 FROM changes c WHERE NOT EXISTS(SELECT 1 FROM previous p WHERE p.kind=c.kind AND json_extract(p.entry,'$.key')=json_extract(c.entry,'$.key'))
 ), ranked AS (SELECT *,row_number() OVER(PARTITION BY kind ORDER BY
 CASE WHEN kind='threads' AND json_extract(entry,'$.status')='resolved' THEN 1 ELSE 0 END,
 CASE WHEN kind='threads' THEN changed ELSE 0 END DESC,
 CASE WHEN kind='threads' THEN (SELECT max(CAST(value AS INTEGER)) FROM json_each(entry,'$.source_ids')) ELSE 0 END DESC,position DESC) AS rank FROM merged)
 SELECT json_set(?1,'$.threads',json((SELECT json_group_array(json(entry)) FROM (SELECT entry FROM ranked WHERE kind='threads' AND rank<=6 ORDER BY position))),
 '$.episodes',json((SELECT json_group_array(json(entry)) FROM (SELECT entry FROM ranked WHERE kind='episodes' AND rank<=30 ORDER BY position))))`,delta,boundary)
}

continuity_save :: proc(through_id: i64, value: json.Value, delta:bool=false) -> bool {
 encoded,e:=json.marshal(value)
 defer delete(encoded)
 if e!=nil do return false
 incoming:=string(encoded)
 normalized,merged_delta:string
 defer { delete(normalized); delete(merged_delta) }
 if delta {
  obj,ok:=value.(json.Object)
  if !ok do return fail("Expected a conversation delta object.")
  for key in obj { if key!="threads" && key!="episodes" && key!="relationship" do return fail("Unknown conversation delta field.") }
  for key in ([2]string{"threads","episodes"}) {
   if entry,exists:=obj[key]; exists { if _,array_ok:=entry.(json.Array); !array_ok do return fail("Conversation delta entries must be arrays.") }
  }
  result:=json_query(`SELECT json_set(?1,'$.threads',json(coalesce(json_extract(?1,'$.threads'),'[]')),'$.episodes',json(coalesce(json_extract(?1,'$.episodes'),'[]')))`,incoming)
  if result==nil do return false
  normalized=strings.clone(string(result))
  if !continuity_revision_valid(through_id,normalized,true) do return false
  result=continuity_delta_merge(through_id,normalized)
  if result==nil do return false
  merged_delta=strings.clone(string(result))
  incoming=merged_delta
 }
 // Omitted fields retain state; a topic-only extraction must not erase feelings or trust.
 merged:=json_query(`WITH mood AS (SELECT CASE WHEN json_type(?1,'$.assistant_state') IS NULL AND json_type(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state') IS NOT NULL THEN json_set(?1,'$.assistant_state',json_extract(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state')) ELSE ?1 END AS state)
 SELECT CASE WHEN json_type(state,'$.relationship') IS NULL AND json_type(`+EXTRACTED_CONTINUITY_JSON+`,'$.relationship') IS NOT NULL THEN json_set(state,'$.relationship',json_extract(`+EXTRACTED_CONTINUITY_JSON+`,'$.relationship')) ELSE state END FROM mood`,incoming)
 if merged==nil do return false
 state:=strings.clone(string(merged))
 defer delete(state)
 if !continuity_revision_valid(through_id,state) do return false
 compact:=episode_compact(state)
 if compact==nil do return false
 stmt:=prepare("INSERT INTO continuity_revisions(through_id,state,created_at) VALUES(?1,?2,"+CONTINUITY_PUBLICATION_TIME+")")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,through_id) && bind_text(stmt,2,string(compact)) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !saved || !archive_capture("continuity_revisions",sqlite3_last_insert_rowid(db)) do return false
 // Index the original changed trees, including roots pruned from a delta's recent window.
 episodes,_:=value.(json.Object)["episodes"].(json.Array)
 for item in episodes {
  if !continuity_index_episode(through_id,item.(json.Object)) do return false
 }
 return true
}

continuity_index_episode :: proc(through_id:i64, episode:json.Object, root_key:string="", parent_key:string="", ancestor_path:string="") -> bool {
 node_key:=string(episode["key"].(json.String))
 root:=root_key
 if root=="" do root=node_key
 key:=fmt.aprintf("episode:%s",node_key) if parent_key=="" else fmt.aprintf("episode-node:%d:%s:%s",len(root),root,node_key)
 defer delete(key)
 scene:=string(episode["scene"].(json.String))
 path:=fmt.aprintf("%s / %s",ancestor_path,scene) if parent_key!="" else fmt.aprintf("%s",scene)
 defer delete(path)
 text:=fmt.aprintf("場面: %s\n出来事: %s\n感情: %s",path,string(episode["text"].(json.String)),string(episode["emotion"].(json.String)))
 defer delete(text)
 scope:=string(episode["scope"].(json.String))
 previous:i64
 current:=prepare("SELECT m.id,m.text FROM memories m JOIN memory_current c ON c.id=m.id WHERE m.kind='event' AND m.subject='episode' AND m.actor='conversation' AND m.key=?1 AND m.scope=?2 ORDER BY m.id DESC LIMIT 1")
 if current==nil do return false
 if !bind_text(current,1,key) || !bind_text(current,2,scope) { sqlite3_finalize(current); return false }
 step:=sqlite3_step(current)
 unchanged:=false
 if step==100 {
  previous=sqlite3_column_int64(current,0)
  unchanged=string(sqlite3_column_text(current,1))==text
 }
 sqlite3_finalize(current)
 if step!=100 && step!=101 do return fail("Could not read the previous scene memory.")
 if unchanged {
  encoded,e:=json.marshal(episode["source_ids"])
  defer delete(encoded)
  if e!=nil do return false
  check:=prepare("SELECT count(*) FROM (SELECT message_id FROM memory_sources WHERE memory_id=?1 UNION SELECT CAST(value AS INTEGER) FROM json_each(?2))")
  if check==nil do return false
  valid:=bind_int(check,1,previous) && bind_text(check,2,string(encoded)) && sqlite3_step(check)==100
  if valid do unchanged=sqlite3_column_int64(check,0)<=16
  sqlite3_finalize(check)
  if !valid do return false
 }
 if unchanged {
  for source in episode["source_ids"].(json.Array) {
   check:=prepare("SELECT 1 FROM memory_sources WHERE memory_id=?1 AND message_id=?2")
   if check==nil do return false
   valid:=bind_int(check,1,previous) && bind_int(check,2,i64(source.(json.Integer)))
   code:=sqlite3_step(check) if valid else -1
   sqlite3_finalize(check)
   if code!=100 && (code!=101 || !insert_source(previous,i64(source.(json.Integer)))) do return false
  }
  if !episode_save_link(previous,root,parent_key,episode) do return false
  if children,exists:=episode["children"]; exists { for child in children.(json.Array) { if !continuity_index_episode(through_id,child.(json.Object),root,node_key,path) do return false } }
  return true
 }
 stmt:=prepare("INSERT INTO memories(job_id,kind,actor,subject,key,text,scope,certainty,supersedes_id,valid_from) VALUES((SELECT id FROM jobs WHERE end_id=?1 ORDER BY first_id LIMIT 1),'event','conversation','episode',?2,?3,?4,'explicit',nullif(?5,0),'')")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,through_id) && bind_text(stmt,2,key) && bind_text(stmt,3,text) && bind_text(stmt,4,scope) && bind_int(stmt,5,previous) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !saved do return fail("Could not save the scene memory.")
 id:=sqlite3_last_insert_rowid(db)
 if !archive_capture("memories",id) do return false
 for source in episode["source_ids"].(json.Array) {
  if !insert_source(id,i64(source.(json.Integer))) do return false
 }
 if !episode_save_link(id,root,parent_key,episode) do return false
 if children,exists:=episode["children"]; exists { for child in children.(json.Array) { if !continuity_index_episode(through_id,child.(json.Object),root,node_key,path) do return false } }
 return true
}
