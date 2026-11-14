package conversation

import "core:encoding/json"

ASSISTANT_STATE_MAX_BYTES :: 12288
ASSISTANT_STATE_SCHEMA :: `
CREATE TABLE IF NOT EXISTS assistant_states(id INTEGER PRIMARY KEY,through_id INTEGER NOT NULL UNIQUE REFERENCES messages(id),state TEXT NOT NULL CHECK(json_valid(state) AND json_type(state) IN ('object','null')),created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TRIGGER IF NOT EXISTS assistant_states_no_update BEFORE UPDATE ON assistant_states BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS assistant_states_no_delete BEFORE DELETE ON assistant_states BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

CURRENT_ASSISTANT_STATE_JSON :: `coalesce((SELECT state FROM assistant_states ORDER BY id DESC LIMIT 1),json_extract(`+EXTRACTED_CONTINUITY_JSON+`,'$.assistant_state'))`

assistant_state_fields_valid :: proc(entry: json.Object, with_sources: bool) -> bool {
 expected:=5 if with_sources else 4
 if len(entry)!=expected do return fail("Invalid assistant state fields.")
 for field in ([3]string{"emotion","reason","event"}) {
  if _,ok:=string_field(entry,field,768); !ok do return fail("Assistant state needs a feeling, reason and event within 768 bytes each.")
 }
 scope,ok:=string_field(entry,"scope",16)
 return (ok && (scope=="shared" || scope=="fiction")) || fail("Invalid assistant state scope.")
}

assistant_state_valid :: proc(through_id: i64, encoded: string) -> bool {
 if len(encoded)>ASSISTANT_STATE_MAX_BYTES do return fail("Assistant state is too large.")
 root,parsed:=parse_json(transmute([]byte)encoded)
 if !parsed do return false
 defer json.destroy_value(root)
 boundary:=prepare("SELECT coalesce((SELECT max(id) FROM messages WHERE role='user' AND id<?1),0) WHERE EXISTS(SELECT 1 FROM messages WHERE id=?1 AND role='assistant') AND ?1>(SELECT coalesce(max(through_id),0) FROM assistant_states)")
 if boundary==nil do return false
 valid:=bind_int(boundary,1,through_id) && sqlite3_step(boundary)==100
 user_id:i64
 if valid do user_id=sqlite3_column_int64(boundary,0)
 sqlite3_finalize(boundary)
 if !valid || user_id<1 do return fail("Assistant state must advance to a saved reply with a user source.")
 if _,empty:=root.(json.Null); empty do return true
 entry,ok:=root.(json.Object)
 if !ok do return fail("Expected an assistant state object or null.")
 if !assistant_state_fields_valid(entry,true) do return false
 sources,sources_ok:=entry["source_ids"].(json.Array)
 if !sources_ok || len(sources)!=2 do return fail("Assistant state needs the input and reply as evidence.")
 first,first_ok:=sources[0].(json.Integer)
 last,last_ok:=sources[1].(json.Integer)
 return (first_ok && last_ok && i64(first)==user_id && i64(last)==through_id) || fail("Invalid assistant state evidence.")
}

assistant_state_save :: proc(through_id: i64, payload: string) -> bool {
 // Leave room for the two source IDs added during save.
 if len(payload)>ASSISTANT_STATE_MAX_BYTES-128 do return fail("Assistant state is too large.")
 root,parsed:=parse_json(transmute([]byte)payload)
 if !parsed do return false
 defer json.destroy_value(root)
 if _,empty:=root.(json.Null); !empty {
  entry,ok:=root.(json.Object)
  if !ok do return fail("Expected an assistant state object or null.")
  if !assistant_state_fields_valid(entry,false) do return false
 }
 if scalar("SELECT EXISTS(SELECT 1 FROM messages WHERE role='user')")!=1 do return fail("Assistant state needs a user source.")
 // Evidence comes from the saved exchange, never model-generated IDs.
 stmt:=prepare("INSERT INTO assistant_states(through_id,state) VALUES(?1,CASE WHEN json_type(?2)='null' THEN 'null' ELSE json_set(?2,'$.source_ids',json_array((SELECT max(id) FROM messages WHERE role='user'),?1)) END)")
 if stmt==nil do return false
 saved:=bind_int(stmt,1,through_id) && bind_text(stmt,2,payload) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 return saved && archive_capture("assistant_states",sqlite3_last_insert_rowid(db))
}
