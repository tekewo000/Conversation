package conversation

import "core:encoding/json"

CONTINUITY_MAX_BYTES :: 12288
CONTINUITY_SCHEMA :: `
CREATE TABLE IF NOT EXISTS continuity_revisions(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),state TEXT NOT NULL CHECK(json_valid(state) AND json_type(state)='object'),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS continuity_no_update BEFORE UPDATE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS continuity_no_delete BEFORE DELETE ON continuity_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

CONTINUITY_JSON :: `coalesce((SELECT json_set(state,'$.through_id',through_id) FROM continuity_revisions ORDER BY id DESC LIMIT 1),'{}')`

CONTINUITY_REFERENCES_SQL :: `SELECT CAST(s.value AS INTEGER) AS id,CASE WHEN CAST(s.key AS INTEGER)>0 THEN 4 WHEN json_extract(item.value,'$.status')='active' THEN 0 WHEN json_extract(item.value,'$.status')='paused' THEN 1 ELSE 3 END AS priority FROM continuity_revisions r,json_each(r.state,'$.threads') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)
UNION ALL SELECT CAST(s.value AS INTEGER),CASE WHEN CAST(s.key AS INTEGER)=0 THEN 2 ELSE 4 END FROM continuity_revisions r,json_each(r.state,'$.episodes') item,json_each(item.value,'$.source_ids') s WHERE r.id=(SELECT max(id) FROM continuity_revisions)`
CONTINUITY_SOURCES_SQL :: "SELECT id FROM ("+CONTINUITY_REFERENCES_SQL+")"

continuity_revision_valid :: proc(through_id: i64, state_text: string) -> bool {
 if len(state_text)>CONTINUITY_MAX_BYTES do return fail("Conversation state exceeds 12 KiB.")
 root,parsed:=parse_json(transmute([]byte)state_text)
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 if !obj_ok || len(obj)!=2 do return fail("Expected conversation threads and episodes only.")
 threads,threads_ok:=obj["threads"].(json.Array)
 episodes,episodes_ok:=obj["episodes"].(json.Array)
 if !threads_ok || !episodes_ok || len(threads)>6 || len(episodes)>6 do return fail("Expected at most six threads and six episodes.")
 boundary:=prepare(`SELECT first_id FROM jobs WHERE end_id=?1 AND end_id>(SELECT coalesce(max(through_id),0) FROM continuity_revisions)`)
 if boundary==nil do return false
 valid:=bind_int(boundary,1,through_id) && sqlite3_step(boundary)==100
 first:i64
 if valid do first=max(1,sqlite3_column_int64(boundary,0)-12)
 sqlite3_finalize(boundary)
 if !valid do return fail("Conversation state must advance to an existing extraction boundary.")
 for entries,group in ([2]json.Array{threads,episodes}) {
  for item,i in entries {
   entry,entry_ok:=item.(json.Object)
   expected:=4
   if group==1 do expected=6
   if !entry_ok || len(entry)!=expected do return fail("Invalid conversation entry fields.")
   key,key_ok:=string_field(entry,"key",128)
   _,text_ok:=string_field(entry,"text",1536)
   sources,sources_ok:=entry["source_ids"].(json.Array)
   if !key_ok || !text_ok || !sources_ok || len(sources)<1 || len(sources)>8 do return fail("Conversation entries need text and one to eight evidence IDs.")
   for previous in entries[:i] {
    if string(previous.(json.Object)["key"].(json.String))==key do return fail("Duplicate conversation entry key.")
   }
   if group==0 {
    status,status_ok:=string_field(entry,"status",16)
    if !status_ok || (status!="active" && status!="paused" && status!="resolved") do return fail("Invalid conversation thread status.")
   } else {
    scope,scope_ok:=string_field(entry,"scope",16)
    if !scope_ok || (scope!="real" && scope!="fiction" && scope!="shared") do return fail("Invalid episode scope.")
    for field in ([2]string{"emotion","scene"}) {
     value,value_ok:=entry[field].(json.String)
     if !value_ok || (string(value)!="" && !valid_text(string(value),768)) do return fail("Invalid episode emotion or scene.")
    }
   }
   for source,j in sources {
    id,id_ok:=source.(json.Integer)
    if !id_ok || id<1 || i64(id)>through_id do return fail("Conversation evidence must be a saved message at or before the job boundary.")
    for previous in sources[:j] { if previous.(json.Integer)==id do return fail("Duplicate conversation evidence.") }
    check:=prepare("SELECT count(*) FROM messages WHERE id=?1 AND (id>=?2 OR id IN ("+CONTINUITY_SOURCES_SQL+"))")
    if check==nil do return false
    found:=bind_int(check,1,i64(id)) && bind_int(check,2,first) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
    sqlite3_finalize(check)
    if !found do return fail("Conversation evidence must come from the extraction window or previous conversation state.")
   }
  }
 }
 return true
}

continuity_save :: proc(through_id: i64, value: json.Value) -> bool {
 encoded,e:=json.marshal(value)
 defer delete(encoded)
 if e!=nil || !continuity_revision_valid(through_id,string(encoded)) do return false
 stmt:=prepare("INSERT INTO continuity_revisions(through_id,state) VALUES(?1,?2)")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,through_id) && bind_text(stmt,2,string(encoded)) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 return saved && archive_capture("continuity_revisions",sqlite3_last_insert_rowid(db))
}
