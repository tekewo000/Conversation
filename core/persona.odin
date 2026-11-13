package conversation

import "base:runtime"
import "core:encoding/json"

PERSONA_PAYLOAD_MAX :: 32768
PERSONA_SCHEMA :: `
CREATE TABLE IF NOT EXISTS persona_revisions(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),changes TEXT NOT NULL CHECK(json_valid(changes) AND json_type(changes)='array'),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS persona_no_update BEFORE UPDATE ON persona_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS persona_no_delete BEFORE DELETE ON persona_revisions BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

// shortcut: current fields scan revision history; materialize them if measured device latency grows with long histories.
PERSONA_JSON :: `json_object('revision',(SELECT coalesce(max(id),0) FROM persona_revisions),'through_id',(SELECT coalesce(max(through_id),0) FROM persona_revisions),'fields',json((SELECT json_group_array(json(row)) FROM (
 SELECT row FROM (
 SELECT json_set(p.value,'$.revision',r.id,'$.through_id',r.through_id,'$.updated_at',r.created_at) AS row,
 json_extract(p.value,'$.field') AS field,row_number() OVER(PARTITION BY json_extract(p.value,'$.field') ORDER BY r.id DESC) AS position
 FROM persona_revisions r,json_each(r.changes) p
 ) WHERE position=1 ORDER BY field
))))`

PERSONA_EVIDENCE_CTE :: `WITH recent AS (
 SELECT id,role,json_object('id',id,'role',role,'content',substr(content,1,512),'created_at',created_at,'truncated',json(CASE WHEN length(content)>512 THEN 'true' ELSE 'false' END)) AS row
 FROM messages WHERE id<=?1 ORDER BY id DESC LIMIT 64
), budget AS (
 SELECT id,role,row,sum(length(CAST(row AS BLOB))+1) OVER(ORDER BY id DESC ROWS UNBOUNDED PRECEDING) AS bytes FROM recent
) `

PERSONA_PENDING_SQL :: PERSONA_EVIDENCE_CTE + `SELECT json_object(
 'through_id',?1,'after_id',(SELECT coalesce(max(through_id),0) FROM persona_revisions),
 'person',json(` + PERSON_JSON + `),'persona',json(` + PERSONA_JSON + `),
 'messages',json((SELECT json_group_array(json(row)) FROM (SELECT row FROM budget WHERE bytes<=40000 ORDER BY id))),
 'memories',json((SELECT json_group_array(json(row)) FROM (
 SELECT json_object('id',m.id,'kind',m.kind,'actor',m.actor,'subject',m.subject,'key',m.key,'text',substr(m.text,1,512),'scope',m.scope,'certainty',m.certainty,'source_ids',json((SELECT json_group_array(message_id) FROM memory_sources WHERE memory_id=m.id))) AS row
 FROM memories m JOIN memory_current c ON c.id=m.id
 WHERE EXISTS(SELECT 1 FROM memory_sources s WHERE s.memory_id=m.id AND s.message_id<=?1) AND m.id NOT IN(SELECT memory_id FROM memory_sources WHERE message_id>?1)
 AND (m.subject IN ('friend','relationship') OR m.actor IN ('assistant','friend')) ORDER BY m.id DESC LIMIT 16
))),
 'policy',json('{"fast_fields":["preferences","relationship"],"slow_fields":["outlook","speaking_style","disposition"],"fast_min_each_role":2,"slow_min_each_role":6,"slow_min_span":24,"slow_cooldown":24,"max_source_ids":32,"text_max_bytes":1024,"reason_max_bytes":512}')
)`

persona_pending_through :: proc() -> i64 {
 return scalar(`SELECT coalesce(max(id),0) FROM messages WHERE role='assistant' AND id<=min(
 (SELECT coalesce(max(through_id),0)+24 FROM persona_revisions),
 coalesce((SELECT min(first_id)-1 FROM jobs WHERE processed=0),(SELECT coalesce(max(id),0) FROM messages)))`)
}

@(export)
conversation_pending_persona :: proc "c" () -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 through:=persona_pending_through()
 after:=scalar("SELECT coalesce(max(through_id),0) FROM persona_revisions")
 if through<0 || after<0 do return nil
 last_error=""
 if through-after<12 do return result_text("{}")
 stmt:=prepare(PERSONA_PENDING_SQL)
 if stmt==nil do return nil
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,through) || sqlite3_step(stmt)!=100 { fail("Could not prepare persona reflection."); return nil }
 return result_text(sqlite3_column_text(stmt,0))
}

// shortcut: message counts approximate independent experiences; calibrate session-based gates with longitudinal conversation tests.
persona_revision_valid :: proc(through_id: i64, changes_text: string) -> bool {
 if len(changes_text)>PERSONA_PAYLOAD_MAX do return fail("Persona changes exceed 32 KiB.")
 root,parsed:=parse_json(transmute([]byte)changes_text)
 if !parsed do return false
 defer json.destroy_value(root)
 changes,array_ok:=root.(json.Array)
 if !array_ok || len(changes)>5 do return fail("Expected at most five persona field changes.")
 after:=scalar("SELECT coalesce(max(through_id),0) FROM persona_revisions")
 if after<0 || through_id-after<12 || through_id-after>24 do return fail("Persona review must cover 12 to 24 new messages.")
 boundary:=prepare(`SELECT count(*) FROM messages WHERE id=?1 AND role='assistant'
 AND NOT EXISTS(SELECT 1 FROM messages m WHERE m.id>?2 AND m.id<=?1 AND NOT EXISTS(SELECT 1 FROM jobs j WHERE j.processed=1 AND m.id BETWEEN j.first_id AND j.end_id))`)
 if boundary==nil do return false
 covered:=bind_int(boundary,1,through_id) && bind_int(boundary,2,after) && sqlite3_step(boundary)==100 && sqlite3_column_int64(boundary,0)==1
 sqlite3_finalize(boundary)
 if !covered do return fail("Persona evidence must end with a saved reply and processed memories.")
 visible:=prepare(PERSONA_EVIDENCE_CTE+"SELECT coalesce(min(id),0) FROM budget WHERE bytes<=40000")
 if visible==nil do return false
 valid:=bind_int(visible,1,through_id) && sqlite3_step(visible)==100
 first:i64
 if valid do first=sqlite3_column_int64(visible,0)
 sqlite3_finalize(visible)
 if !valid || first<=0 do return fail("Persona evidence is unavailable.")
 for value,i in changes {
  record,record_ok:=value.(json.Object)
  if !record_ok || len(record)!=4 do return fail("Invalid persona change fields.")
  field,f_ok:=string_field(record,"field",32)
  _,text_ok:=string_field(record,"text",1024)
  _,reason_ok:=string_field(record,"reason",512)
  sources,s_ok:=record["source_ids"].(json.Array)
  fast:=field=="preferences" || field=="relationship"
  slow:=field=="outlook" || field=="speaking_style" || field=="disposition"
  if !f_ok || !text_ok || !reason_ok || !s_ok || len(sources)>32 || (!fast && !slow) do return fail("Unsupported persona field, text or evidence.")
  for earlier in changes[:i] { if earlier.(json.Object)["field"].(json.String)==record["field"].(json.String) do return fail("Duplicate persona field.") }
  users,assistants,new_users,new_assistants:=0,0,0,0
  oldest,newest:=through_id,i64(0)
  for source,j in sources {
   id,id_ok:=source.(json.Integer)
   if !id_ok || i64(id)<first || i64(id)>through_id do return fail("Persona sources must be visible original-message IDs.")
   for previous in sources[:j] { if previous.(json.Integer)==id do return fail("Duplicate persona source.") }
   message:=prepare("SELECT role FROM messages WHERE id=?1")
   if message==nil do return false
   found:=bind_int(message,1,i64(id)) && sqlite3_step(message)==100
   if found {
    if string(sqlite3_column_text(message,0))=="user" { users+=1; if i64(id)>after do new_users+=1 }
    else { assistants+=1; if i64(id)>after do new_assistants+=1 }
   }
   sqlite3_finalize(message)
   if !found do return fail("Persona source does not exist.")
   oldest=min(oldest,i64(id))
   newest=max(newest,i64(id))
  }
  minimum:=2
  if slow do minimum=6
  if users<minimum || assistants<minimum || new_users<1 || new_assistants<1 || (slow && newest-oldest<24) do return fail("Persona change lacks repeated, fresh interaction evidence.")
  if slow {
   prior:=prepare("SELECT coalesce(max(r.through_id),0) FROM persona_revisions r,json_each(r.changes) p WHERE json_extract(p.value,'$.field')=?1")
   if prior==nil do return false
   valid=bind_text(prior,1,field) && sqlite3_step(prior)==100
   previous:i64
   if valid do previous=sqlite3_column_int64(prior,0)
   sqlite3_finalize(prior)
   if !valid || (previous>0 && through_id-previous<24) do return fail("Slow persona fields need more experience before changing again.")
  }
 }
 return true
}

@(export)
conversation_apply_persona :: proc "c" (through_id: i64, payload: cstring) -> bool {
 context=runtime.default_context()
 if !ready do return fail("History is not open.")
 if payload==nil || len(string(payload))>PERSONA_PAYLOAD_MAX do return fail("Persona response exceeds 32 KiB.")
 root,parsed:=parse_json(transmute([]byte)string(payload))
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 if !obj_ok || len(obj)!=1 do return fail("Expected persona changes only.")
 changes,changes_ok:=obj["changes"].(json.Array)
 if !changes_ok do return fail("Expected persona changes array.")
 encoded,encode_error:=json.marshal(changes)
 defer delete(encoded)
 if encode_error!=nil do return fail("Could not serialize persona changes.")
 if !execute("BEGIN IMMEDIATE") do return false
 committed:=false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 existing:=prepare(`SELECT NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(changes) EXCEPT SELECT fullkey,type,atom FROM json_tree(?2))
 AND NOT EXISTS(SELECT fullkey,type,atom FROM json_tree(?2) EXCEPT SELECT fullkey,type,atom FROM json_tree(changes)) FROM persona_revisions WHERE through_id=?1`)
 if existing==nil do return false
 valid:=bind_int(existing,1,through_id) && bind_text(existing,2,string(encoded))
 code:=i32(-1)
 same:=false
 if valid { code=sqlite3_step(existing); same=code==100 && sqlite3_column_int64(existing,0)==1 }
 sqlite3_finalize(existing)
 if !valid || (code!=100 && code!=101) do return false
 if code==100 {
  if !same do return fail("This persona review already contains different changes.")
  committed=execute("COMMIT")
  if committed do last_error=""
  return committed
 }
 if through_id!=persona_pending_through() || !persona_revision_valid(through_id,string(encoded)) do return fail("Persona review is stale or unsupported.")
 if !archive_begin() do return false
 stmt:=prepare("INSERT INTO persona_revisions(through_id,changes) VALUES(?1,json(?2))")
 if stmt==nil do return false
 valid=bind_int(stmt,1,through_id) && bind_text(stmt,2,string(encoded)) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !valid || !archive_capture("persona_revisions",sqlite3_last_insert_rowid(db)) || !archive_finish() || !execute("COMMIT") do return false
 committed=true
 last_error=""
 return true
}
