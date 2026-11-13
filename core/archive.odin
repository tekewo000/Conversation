package conversation

import "base:runtime"
import "core:encoding/json"
import "core:fmt"
import "core:strings"
import "core:unicode/utf8"

ARCHIVE_SCHEMA :: `
CREATE TABLE IF NOT EXISTS archive_transactions(id INTEGER PRIMARY KEY,bytes INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS archive_events(transaction_id INTEGER NOT NULL REFERENCES archive_transactions(id),ordinal INTEGER NOT NULL,event_json TEXT NOT NULL CHECK(json_valid(event_json)),PRIMARY KEY(transaction_id,ordinal));
`
Archive_Table :: struct { name, columns, row_json: string }
ARCHIVE_TABLES := [?]Archive_Table{
 {"messages","id,role,content,created_at",`json_object('id',id,'role',role,'content',content,'created_at',created_at)`},
 {"jobs","id,first_id,end_id,processed",`json_object('id',id,'first_id',first_id,'end_id',end_id,'processed',processed)`},
 {"memories","id,job_id,ordinal,kind,actor,subject,key,text,scope,certainty,status,supersedes_id,valid_from,created_at",`json_object('id',id,'job_id',job_id,'ordinal',ordinal,'kind',kind,'actor',actor,'subject',subject,'key',key,'text',text,'scope',scope,'certainty',certainty,'status',status,'supersedes_id',supersedes_id,'valid_from',valid_from,'created_at',created_at)`},
 {"memory_sources","memory_id,message_id",`json_object('memory_id',memory_id,'message_id',message_id)`},
 {"person_profiles","slot,person_id,name,profile,source_sha256,version",`json_object('slot',slot,'person_id',person_id,'name',name,'profile',profile,'source_sha256',source_sha256,'version',version)`},
 {"source_documents","id,person_id,format,provenance,source_sha256,raw_markdown,created_at",`json_object('id',id,'person_id',person_id,'format',format,'provenance',provenance,'source_sha256',source_sha256,'raw_markdown',raw_markdown,'created_at',created_at)`},
 {"memory_document_sources","memory_id,document_id,section,occurred_at",`json_object('memory_id',memory_id,'document_id',document_id,'section',section,'occurred_at',occurred_at)`},
}
archive_group:i64
ARCHIVE_GROUP_MAX :: 16*1048576
ARCHIVE_DELTA_MAX :: 20*1048576

archive_table :: proc(name: string) -> ^Archive_Table {
 for &table in ARCHIVE_TABLES { if table.name==name do return &table }
 return nil
}
archive_begin :: proc() -> bool {
 if !execute("INSERT INTO archive_transactions DEFAULT VALUES") do return false
 archive_group=sqlite3_last_insert_rowid(db)
 return archive_group>0
}
archive_add_event :: proc(event: string) -> bool {
 if archive_group==0 do return true
 stmt:=prepare("INSERT INTO archive_events(transaction_id,ordinal,event_json) SELECT ?1,coalesce(max(ordinal)+1,0),?2 FROM archive_events WHERE transaction_id=?1")
 if stmt==nil do return false
 ok:=bind_int(stmt,1,archive_group) && bind_text(stmt,2,event) && sqlite3_step(stmt)==101
 sqlite3_finalize(stmt)
 if !ok do return fail("Could not journal the transaction.")
 bytes:=prepare("UPDATE archive_transactions SET bytes=bytes+?2 WHERE id=?1")
 if bytes==nil do return false
 defer sqlite3_finalize(bytes)
 return bind_int(bytes,1,archive_group) && bind_int(bytes,2,i64(len(event)+1)) && sqlite3_step(bytes)==101
}
archive_capture :: proc(name: string, rowid: i64, op: string = "insert") -> bool {
 if archive_group==0 do return true
 table:=archive_table(name)
 if table==nil do return fail("Unknown archive table.")
 sql:=fmt.aprintf("SELECT json_object('table','%s','op','%s','row',%s) FROM %s WHERE rowid=?1",table.name,op,table.row_json,table.name)
 defer delete(sql)
 csql:=strings.clone_to_cstring(sql)
 defer delete(csql)
 stmt:=prepare(csql)
 if stmt==nil do return false
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,rowid) || sqlite3_step(stmt)!=100 do return fail("Could not capture the archived row.")
 return archive_add_event(string(sqlite3_column_text(stmt,0)))
}
archive_bytes :: proc() -> i64 {
 stmt:=prepare("SELECT bytes FROM archive_transactions WHERE id=?1")
 if stmt==nil do return -1
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,archive_group) || sqlite3_step(stmt)!=100 do return -1
 return sqlite3_column_int64(stmt,0)
}
archive_finish :: proc() -> bool {
 bytes:=archive_bytes()
 if bytes<1 || bytes>ARCHIVE_GROUP_MAX do return fail("Archive transaction exceeds 16 MiB; nothing was saved.")
 archive_group=0
 return true
}
// Only the first upgrade scans old rows; every subsequent backup seeks the cursor index.
archive_backfill :: proc() -> bool {
 if scalar("SELECT count(*) FROM meta WHERE key='archive_version' AND value='1'")==1 do return true
 defer archive_group=0
 for table in ARCHIVE_TABLES {
  sql:=fmt.aprintf("SELECT rowid FROM %s ORDER BY rowid",table.name)
  csql:=strings.clone_to_cstring(sql)
  delete(sql)
  rows:=prepare(csql)
  delete(csql)
  if rows==nil do return false
  count:=0
  success:=true
  for {
   code:=sqlite3_step(rows)
   if code==101 do break
   if code!=100 { success=false; break }
   if archive_group==0 && !archive_begin() { success=false; break }
   if !archive_capture(table.name,sqlite3_column_int64(rows,0)) { success=false; break }
   count+=1
   if count==128 || archive_bytes()>=524288 {
    if !archive_finish() { success=false; break }
    count=0
   }
  }
  sqlite3_finalize(rows)
  if !success || (archive_group!=0 && !archive_finish()) do return false
 }
 return execute("INSERT OR REPLACE INTO meta VALUES('archive_version','1')")
}

ARCHIVE_RECORD_JSON :: `json_object('cursor',t.id,'events',json((SELECT json_group_array(json(event_json)) FROM (SELECT event_json FROM archive_events WHERE transaction_id=t.id ORDER BY ordinal))))`

@(export)
conversation_archive_delta :: proc "c" (after_cursor: i64) -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 if after_cursor<0 { fail("Archive cursor cannot be negative."); return nil }
 // The read transaction fixes both the selected boundary and the envelope snapshot.
 if !execute("BEGIN") do return nil
 defer execute("ROLLBACK")
 if after_cursor>0 {
  check:=prepare("SELECT id FROM archive_transactions WHERE id=?1")
  if check==nil do return nil
  valid:=bind_int(check,1,after_cursor) && sqlite3_step(check)==100
  sqlite3_finalize(check)
  if !valid { fail("Archive cursor does not belong to this history."); return nil }
 }
 batch:=prepare("SELECT id,bytes FROM archive_transactions WHERE id>?1 ORDER BY id LIMIT 256")
 if batch==nil do return nil
 if !bind_int(batch,1,after_cursor) { sqlite3_finalize(batch); return nil }
 through:=after_cursor
 bytes:=0
 for {
  code:=sqlite3_step(batch)
  if code==101 do break
  if code!=100 { sqlite3_finalize(batch); fail("Could not read archive boundary."); return nil }
  size:=int(sqlite3_column_int64(batch,1))+64
  if through>after_cursor && bytes+size>1048576 do break
  through=sqlite3_column_int64(batch,0)
  bytes+=size
 }
 sqlite3_finalize(batch)
 sql:=fmt.aprintf(`SELECT json_object('format',1,'person',json(%s),'from_cursor',?1,'through_cursor',?2,'has_more',json(CASE WHEN EXISTS(SELECT 1 FROM archive_transactions WHERE id>?2) THEN 'true' ELSE 'false' END),'records',json((SELECT json_group_array(json(record)) FROM (SELECT %s AS record FROM archive_transactions t WHERE t.id>?1 AND t.id<=?2 ORDER BY t.id))))`,PERSON_JSON,ARCHIVE_RECORD_JSON)
 csql:=strings.clone_to_cstring(sql)
 delete(sql)
 stmt:=prepare(csql)
 delete(csql)
 if stmt==nil do return nil
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,after_cursor) || !bind_int(stmt,2,through) || sqlite3_step(stmt)!=100 { fail("Could not serialize archive delta."); return nil }
 output:=sqlite3_column_text(stmt,0)
 if len(string(output))>ARCHIVE_DELTA_MAX { fail("Archive delta exceeds 20 MiB."); return nil }
 last_error=""
 return result_text(output)
}

archive_row_valid :: proc(table: ^Archive_Table, row: json.Object) -> bool {
 columns:=strings.split(table.columns,",")
 defer delete(columns)
 if len(row)!=len(columns) do return false
 for column in columns {
  value,exists:=row[column]
  if !exists do return false
  nullable:=column=="job_id" || column=="ordinal" || column=="supersedes_id" || column=="occurred_at"
  _,is_null:=value.(json.Null)
  if is_null { if !nullable do return false; continue }
  integer:=column=="id" || column=="slot" || column=="version" || column=="job_id" || column=="ordinal" || column=="first_id" || column=="end_id" || column=="processed" || column=="memory_id" || column=="message_id" || column=="document_id" || column=="supersedes_id"
  if integer {
   number,ok:=value.(json.Integer)
   if !ok || number<0 || (number==0 && column!="ordinal" && column!="processed") || (column=="processed" && number>1) do return false
   continue
  }
  text_value,ok:=value.(json.String)
  text:=string(text_value)
  maximum:=256
  switch column {
  case "content":maximum=65536
  case "text":
   maximum=4096
   _,legacy_job:=row["job_id"].(json.Null)
   key,key_ok:=row["key"].(json.String)
   if table.name=="memories" && legacy_job do maximum=PERSON_SEED_TEXT_MAX_BYTES
   if table.name=="memories" && legacy_job && key_ok && string(key)=="legacy_pin" do maximum=MAX_MESSAGE_BYTES
  case "profile":maximum=PERSON_PROFILE_MAX_BYTES
  case "raw_markdown":maximum=PERSON_SOURCE_MAX_BYTES
  case "section":maximum=512
  }
  if !ok || len(text)>maximum || !utf8.valid_string(text) || strings.contains_rune(text,0) || (column!="valid_from" && len(strings.trim_space(text))==0) do return false
  if (column=="created_at" || column=="occurred_at" || (column=="valid_from" && text!="")) && !valid_date(text) do return false
  if column=="person_id" && !person_uuid_valid(text) do return false
 }
 if table.name=="memories" {
  kind:=string(row["kind"].(json.String))
  certainty:=string(row["certainty"].(json.String))
  if (kind=="interpretation" && certainty!="inferred") || (kind!="interpretation" && certainty!="explicit") do return false
  if kind!="promise" && string(row["status"].(json.String))!="active" do return false
 }
 if table.name=="source_documents" && !source_hash_valid(string(row["raw_markdown"].(json.String)),string(row["source_sha256"].(json.String))) do return false
 return true
}

archive_restore_event :: proc(event: json.Object, encoded: string) -> bool {
 if len(event)!=3 do return fail("Invalid archive event fields.")
 name,name_ok:=event["table"].(json.String)
 op,op_ok:=event["op"].(json.String)
 row,row_ok:=event["row"].(json.Object)
 table:=archive_table(string(name))
 if !name_ok || !op_ok || !row_ok || table==nil || !archive_row_valid(table,row) || (string(op)!="insert" && !(table.name=="jobs" && string(op)=="update")) do return fail("Invalid archive event schema.")
 if table.name=="messages" {
  id:=i64(row["id"].(json.Integer))
  if id>0x7fffffff || id!=scalar("SELECT coalesce(max(id),0)+1 FROM messages") do return fail("Archived message IDs must remain contiguous and fit the history window.")
 }
 columns:=strings.split(table.columns,",")
 defer delete(columns)
 select_values:=strings.builder_make()
 defer strings.builder_destroy(&select_values)
 for column,i in columns {
  if i>0 do strings.write_byte(&select_values,',')
  fmt.sbprintf(&select_values,"json_extract(?1,'$.row.%s')",column)
 }
 sql:=""
 if string(op)=="update" {
  sql=strings.clone(`UPDATE jobs SET processed=1 WHERE id=json_extract(?1,'$.row.id') AND first_id=json_extract(?1,'$.row.first_id') AND end_id=json_extract(?1,'$.row.end_id') AND processed=0 AND json_extract(?1,'$.row.processed')=1 RETURNING id`)
 } else {
  sql=fmt.aprintf("INSERT INTO %s(%s) SELECT %s",table.name,table.columns,strings.to_string(select_values))
 }
 defer delete(sql)
 csql:=strings.clone_to_cstring(sql)
 defer delete(csql)
 stmt:=prepare(csql)
 if stmt==nil do return false
 code:i32
 if bind_text(stmt,1,encoded) do code=sqlite3_step(stmt)
 valid:=code==101 if string(op)=="insert" else code==100
 sqlite3_finalize(stmt)
 if !valid do return fail("Archive rows do not match this history; nothing was restored.")
 return archive_add_event(encoded)
}

@(export)
conversation_apply_archive :: proc "c" (payload: cstring) -> bool {
 context=runtime.default_context()
 if !ready do return fail("History is not open.")
 if payload==nil || len(string(payload))>ARCHIVE_DELTA_MAX do return fail("Archive delta exceeds 20 MiB.")
 root,parsed:=parse_json(transmute([]byte)string(payload),10)
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 format,format_ok:=obj["format"].(json.Integer)
 from,from_ok:=obj["from_cursor"].(json.Integer)
 through,through_ok:=obj["through_cursor"].(json.Integer)
 _,more_ok:=obj["has_more"].(json.Boolean)
 records,records_ok:=obj["records"].(json.Array)
 person,person_ok:=obj["person"].(json.Object)
 _,person_null:=obj["person"].(json.Null)
 if !obj_ok || len(obj)!=6 || !format_ok || format!=1 || !from_ok || from<0 || !through_ok || through<from || !more_ok || !records_ok || len(records)>256 || i64(through-from)!=i64(len(records)) || (!person_ok && !person_null) do return fail("Invalid archive envelope.")
 if person_ok {
  id,id_ok:=string_field(person,"id",36)
  name,name_ok:=string_field(person,"name",256)
  profile,profile_ok:=string_field(person,"profile",PERSON_PROFILE_MAX_BYTES)
  sha,sha_ok:=string_field(person,"source_sha256",64)
  version,v_ok:=person["version"].(json.Integer)
  if len(person)!=5 || !id_ok || !person_uuid_valid(id) || !name_ok || !profile_ok || !sha_ok || len(sha)!=64 || !v_ok || version!=1 do return fail("Invalid archive person.")
  _=name; _=profile
 }
 if !execute("BEGIN IMMEDIATE") do return false
 committed:=false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 latest:=scalar("SELECT coalesce(max(id),0) FROM archive_transactions")
 if latest<i64(from) || (latest==0 && scalar("SELECT (SELECT count(*) FROM messages)+(SELECT count(*) FROM memories)+(SELECT count(*) FROM person_profiles)")!=0) do return fail("Archive must start at the current history cursor.")
 for record_value,i in records {
  record,record_ok:=record_value.(json.Object)
  cursor,cursor_ok:=record["cursor"].(json.Integer)
  events,events_ok:=record["events"].(json.Array)
  if !record_ok || len(record)!=2 || !cursor_ok || i64(cursor)!=i64(from)+i64(i)+1 || !events_ok || len(events)==0 || len(events)>32768 do return fail("Invalid archive transaction.")
  if i64(cursor)<=latest {
   sql:=fmt.aprintf("SELECT (SELECT json_group_array(json(event_json)) FROM (SELECT event_json FROM archive_events WHERE transaction_id=?1 ORDER BY ordinal))=json_extract(?2,'$.records[%d].events')",i)
   csql:=strings.clone_to_cstring(sql)
   delete(sql)
   check:=prepare(csql)
   delete(csql)
   if check==nil do return false
   valid:=bind_int(check,1,i64(cursor)) && bind_text(check,2,string(payload)) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
   sqlite3_finalize(check)
   if !valid do return fail("Archive cursor contains different events.")
   continue
  }
  if i64(cursor)!=latest+1 || !archive_begin() || archive_group!=i64(cursor) do return fail("Archive cursor is not contiguous.")
  path:=fmt.aprintf("$.records[%d].events",i)
  extract:=prepare("SELECT key,value FROM json_each(?1,?2) ORDER BY key")
  if extract==nil { delete(path); return false }
  valid:=bind_text(extract,1,string(payload)) && bind_text(extract,2,path)
  delete(path)
  event_index:=0
  for valid {
   code:=sqlite3_step(extract)
   if code==101 { valid=event_index==len(events); break }
   if code!=100 || event_index>=len(events) || sqlite3_column_int64(extract,0)!=i64(event_index) { valid=false; break }
   event,event_ok:=events[event_index].(json.Object)
   valid=event_ok && archive_restore_event(event,string(sqlite3_column_text(extract,1)))
   event_index+=1
  }
  sqlite3_finalize(extract)
  if !valid do return fail("Invalid archive event sequence; nothing was restored.")
  if !archive_finish() do return false
  latest=i64(cursor)
 }
 // The immutable profile and original document must agree with the claimed owner.
 if person_ok {
  check:=prepare("SELECT count(*) FROM person_profiles p JOIN source_documents d ON d.person_id=p.person_id AND d.source_sha256=p.source_sha256 WHERE p.person_id=?1 AND p.source_sha256=?2 AND p.name=?3 AND p.profile=?4")
  if check==nil do return false
  valid:=bind_text(check,1,string(person["id"].(json.String))) && bind_text(check,2,string(person["source_sha256"].(json.String))) && bind_text(check,3,string(person["name"].(json.String))) && bind_text(check,4,string(person["profile"].(json.String))) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
  sqlite3_finalize(check)
  if !valid do return fail("Archive owner does not match its source document.")
 } else if scalar("SELECT count(*) FROM person_profiles")!=0 do return fail("Legacy archive cannot replace a person's history.")
 if !execute("COMMIT") do return false
 committed=true
 message_count=i32(scalar("SELECT coalesce(max(id),0) FROM messages"))
 clear_cache()
 last_error=""
 return true
}
