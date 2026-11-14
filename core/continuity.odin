package conversation

import "core:encoding/json"
import "core:fmt"
import "base:runtime"

CONTINUITY_MAX_BYTES :: 65536
CONTINUITY_SCHEMA :: `
CREATE TABLE IF NOT EXISTS continuity_revisions(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),state TEXT NOT NULL CHECK(json_valid(state) AND json_type(state)='object'),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS continuity_no_update BEFORE UPDATE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS continuity_no_delete BEFORE DELETE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

EXTRACTED_CONTINUITY_JSON :: `coalesce((SELECT json_set(state,'$.through_id',through_id) FROM continuity_revisions ORDER BY id DESC LIMIT 1),'{}')`
CONTINUITY_JSON :: `(CASE WHEN EXISTS(SELECT 1 FROM assistant_states) THEN json_set(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state',json(`+CURRENT_ASSISTANT_STATE_JSON+`),'$.assistant_state_through_id',(SELECT through_id FROM assistant_states ORDER BY id DESC LIMIT 1)) ELSE `+EXTRACTED_CONTINUITY_JSON+` END)`

CONTINUITY_REFERENCES_SQL :: `SELECT CAST(s.value AS INTEGER) AS id,CASE WHEN CAST(s.key AS INTEGER)>0 THEN 4 WHEN json_extract(item.value,'$.status')='active' THEN 0 WHEN json_extract(item.value,'$.status')='paused' THEN 1 ELSE 3 END AS priority FROM continuity_revisions r,json_each(r.state,'$.threads') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),CASE WHEN CAST(s.key AS INTEGER)=0 THEN 2 ELSE 4 END FROM continuity_revisions r,json_each(r.state,'$.episodes') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM continuity_revisions r,json_each(r.state,'$.assistant_state.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM continuity_revisions r,json_each(r.state,'$.relationship.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),2 FROM json_each((SELECT state FROM assistant_states ORDER BY id DESC LIMIT 1),'$.source_ids') s`
CONTINUITY_SOURCES_SQL :: "SELECT id FROM ("+CONTINUITY_REFERENCES_SQL+")"

@(export)
conversation_assistant_emotion :: proc "c" () -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 return json_query(`SELECT coalesce(json_extract(`+CURRENT_ASSISTANT_STATE_JSON+`,'$.emotion'),'') WHERE ?1=''`)
}

continuity_revision_valid :: proc(through_id: i64, state_text: string) -> bool {
 if len(state_text)>CONTINUITY_MAX_BYTES do return fail("Conversation state exceeds 64 KiB.")
 root,parsed:=parse_json(transmute([]byte)state_text)
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 if !obj_ok || len(obj)<2 || len(obj)>4 do return fail("Expected conversation threads, episodes and optional assistant or relationship state.")
 for key in obj { if key!="threads" && key!="episodes" && key!="assistant_state" && key!="relationship" do return fail("Unknown conversation state field.") }
 threads,threads_ok:=obj["threads"].(json.Array)
 episodes,episodes_ok:=obj["episodes"].(json.Array)
 if !threads_ok || !episodes_ok || len(threads)>6 || len(episodes)>30 do return fail("Expected at most six threads and thirty episodes.")
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
 for entries,group in ([4]json.Array{threads,episodes,assistant_entries,relationship_entries}) {
  for item,i in entries {
   entry,entry_ok:=item.(json.Object)
   expected:=4
   if group==1 do expected=6
   if group==2 do expected=5
   if !entry_ok || len(entry)!=expected do return fail("Invalid conversation entry fields.")
   sources,sources_ok:=entry["source_ids"].(json.Array)
   if !sources_ok || len(sources)<1 || len(sources)>8 do return fail("Conversation entries need one to eight evidence IDs.")
   if group<2 {
    key,key_ok:=string_field(entry,"key",128)
    _,text_ok:=string_field(entry,"text",1536)
    if !key_ok || !text_ok do return fail("Conversation entries need key and text.")
    for previous in entries[:i] {
     if string(previous.(json.Object)["key"].(json.String))==key do return fail("Duplicate conversation entry key.")
    }
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
    check:=prepare("SELECT role FROM messages WHERE id=?1 AND (id>=?2 OR id IN ("+CONTINUITY_SOURCES_SQL+"))")
    if check==nil do return false
    found:=bind_int(check,1,i64(id)) && bind_int(check,2,first) && sqlite3_step(check)==100
    if found && group==3 {
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

continuity_save :: proc(through_id: i64, value: json.Value) -> bool {
 encoded,e:=json.marshal(value)
 defer delete(encoded)
 if e!=nil do return false
 // Omitted fields retain state; a topic-only extraction must not erase feelings or trust.
 merged:=json_query(`WITH mood AS (SELECT CASE WHEN json_type(?1,'$.assistant_state') IS NULL AND json_type(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state') IS NOT NULL THEN json_set(?1,'$.assistant_state',json_extract(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state')) ELSE ?1 END AS state)
 SELECT CASE WHEN json_type(state,'$.relationship') IS NULL AND json_type(`+EXTRACTED_CONTINUITY_JSON+`,'$.relationship') IS NOT NULL THEN json_set(state,'$.relationship',json_extract(`+EXTRACTED_CONTINUITY_JSON+`,'$.relationship')) ELSE state END FROM mood`,string(encoded))
 if merged==nil || !continuity_revision_valid(through_id,string(merged)) do return false
 stmt:=prepare("INSERT INTO continuity_revisions(through_id,state) VALUES(?1,?2)")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,through_id) && bind_text(stmt,2,string(merged)) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !saved || !archive_capture("continuity_revisions",sqlite3_last_insert_rowid(db)) do return false
 // Searchable event revisions preserve scenes after they leave the recent window.
 for item in value.(json.Object)["episodes"].(json.Array) {
  if !continuity_index_episode(through_id,item.(json.Object)) do return false
 }
 return true
}

continuity_index_episode :: proc(through_id:i64, episode:json.Object) -> bool {
 key:=fmt.aprintf("episode:%s",string(episode["key"].(json.String)))
 defer delete(key)
 text:=fmt.aprintf("場面: %s\n出来事: %s\n感情: %s",string(episode["scene"].(json.String)),string(episode["text"].(json.String)),string(episode["emotion"].(json.String)))
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
 if unchanged do return true
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
 return true
}
