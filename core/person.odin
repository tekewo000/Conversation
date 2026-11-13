package conversation

import "base:runtime"
import "core:crypto/sha2"
import "core:encoding/json"
import "core:strings"
import "core:unicode/utf8"

PERSON_SCHEMA :: `
CREATE TABLE IF NOT EXISTS person_profiles(slot INTEGER PRIMARY KEY CHECK(slot=1),person_id TEXT NOT NULL UNIQUE,name TEXT NOT NULL,profile TEXT NOT NULL,source_sha256 TEXT NOT NULL,version INTEGER NOT NULL CHECK(version=1));
CREATE TABLE IF NOT EXISTS source_documents(id INTEGER PRIMARY KEY,person_id TEXT NOT NULL REFERENCES person_profiles(person_id),format TEXT NOT NULL CHECK(format='conversation-person-v1'),provenance TEXT NOT NULL CHECK(provenance='reported_handover'),source_sha256 TEXT NOT NULL UNIQUE,raw_markdown TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TABLE IF NOT EXISTS memory_document_sources(memory_id INTEGER NOT NULL REFERENCES memories(id),document_id INTEGER NOT NULL REFERENCES source_documents(id),section TEXT NOT NULL,occurred_at TEXT,PRIMARY KEY(memory_id,document_id,section));
CREATE INDEX IF NOT EXISTS document_sources_document ON memory_document_sources(document_id,memory_id);
CREATE TRIGGER IF NOT EXISTS profile_no_update BEFORE UPDATE ON person_profiles BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS profile_no_delete BEFORE DELETE ON person_profiles BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS documents_no_update BEFORE UPDATE ON source_documents BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS documents_no_delete BEFORE DELETE ON source_documents BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

PERSON_JSON :: `coalesce((SELECT json_object('id',person_id,'name',name,'profile',profile,'source_sha256',source_sha256,'version',version) FROM person_profiles WHERE slot=1),'null')`
IMPORTED_SOURCES_JSON :: `(SELECT json_group_array(json_object('document_id',d.id,'format',d.format,'provenance',d.provenance,'source_sha256',d.source_sha256,'section',s.section,'occurred_at',s.occurred_at,'snippet',substr(d.raw_markdown,1,256))) FROM memory_document_sources s JOIN source_documents d ON d.id=s.document_id WHERE s.memory_id=m.id)`

person_uuid_valid :: proc(id: string) -> bool {
 if len(id)!=36 do return false
 for c,i in id {
  if i==8 || i==13 || i==18 || i==23 { if c!='-' do return false }
  else if !(c>='0' && c<='9') && !(c>='a' && c<='f') do return false
 }
 return true
}
person_text_valid :: proc(text: string, maximum: int) -> bool {
 return len(text)>0 && len(text)<=maximum && utf8.valid_string(text) && !strings.contains_rune(text,0) && len(strings.trim_space(text))>0
}
source_hash_valid :: proc(source, expected: string) -> bool {
 if len(expected)!=64 do return false
 digest:[32]byte
 // Direct state avoids the generic hash's reflected union assertion in Android object builds.
 state:sha2.Context_256
 sha2.init_256(&state)
 sha2.update(&state,transmute([]byte)source)
 sha2.final(&state,digest[:])
 hex := "0123456789abcdef"
 for b,i in digest { if expected[i*2]!=hex[int(b>>4)] || expected[i*2+1]!=hex[int(b&15)] do return false }
 return true
}

@(export)
conversation_import_person :: proc "c" (payload: cstring) -> bool {
 context=runtime.default_context()
 if !ready do return fail("History is not open.")
 if payload==nil || len(string(payload))>4*1048576 do return fail("Person import exceeds 4 MiB.")
 root,parsed:=parse_json(transmute([]byte)string(payload),6)
 if !parsed do return false
 defer json.destroy_value(root)
 obj,obj_ok:=root.(json.Object)
 if !obj_ok || len(obj)!=6 do return fail("Expected the version 1 person import fields.")
 id,id_ok:=string_field(obj,"person_id",36)
 name,name_ok:=string_field(obj,"name",256)
 profile,profile_ok:=string_field(obj,"profile",32768)
 source_value,source_ok:=obj["source_md"].(json.String)
 source:=string(source_value)
 sha,sha_ok:=string_field(obj,"source_sha256",64)
 seeds,seeds_ok:=obj["seeds"].(json.Array)
 if !id_ok || !person_uuid_valid(id) || !name_ok || !profile_ok || !source_ok || !person_text_valid(source,524288) || !sha_ok || !source_hash_valid(source,sha) || !seeds_ok || len(seeds)>512 do return fail("Invalid person profile, source hash, or seed count.")
 if !execute("BEGIN IMMEDIATE") do return false
 committed:=false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 existing:=prepare("SELECT person_id,source_sha256 FROM person_profiles WHERE slot=1")
 if existing==nil do return false
 code:=sqlite3_step(existing)
 same:=code==100 && string(sqlite3_column_text(existing,0))==id && string(sqlite3_column_text(existing,1))==sha
 sqlite3_finalize(existing)
 if code==100 {
  if !same do return fail("This history already belongs to a different person or source.")
  committed=execute("COMMIT")
  if committed do last_error=""
  return committed
 }
 if code!=101 || scalar("SELECT (SELECT count(*) FROM messages)+(SELECT count(*) FROM memories)+(SELECT count(*) FROM jobs)+(SELECT count(*) FROM source_documents)")!=0 do return fail("A person can only be imported into a fresh history.")
 if !archive_begin() do return false
 profile_stmt:=prepare("INSERT INTO person_profiles(slot,person_id,name,profile,source_sha256,version) VALUES(1,?1,?2,?3,?4,1)")
 if profile_stmt==nil do return false
 valid:=bind_text(profile_stmt,1,id) && bind_text(profile_stmt,2,name) && bind_text(profile_stmt,3,profile) && bind_text(profile_stmt,4,sha) && sqlite3_step(profile_stmt)==101
 sqlite3_finalize(profile_stmt)
 if !valid || !archive_capture("person_profiles",1) do return fail("Could not save person profile.")
 document:=prepare("INSERT INTO source_documents(person_id,format,provenance,source_sha256,raw_markdown) VALUES(?1,'conversation-person-v1','reported_handover',?2,?3)")
 if document==nil do return false
 valid=bind_text(document,1,id) && bind_text(document,2,sha) && bind_text(document,3,source) && sqlite3_step(document)==101
 sqlite3_finalize(document)
 if !valid do return fail("Could not retain the original person document.")
 document_id:=sqlite3_last_insert_rowid(db)
 if !archive_capture("source_documents",document_id) do return false
 for item in seeds {
  record,record_ok:=item.(json.Object)
  if !record_ok do return fail("Person seed must be an object.")
  for key in record {
   if key!="kind" && key!="actor" && key!="subject" && key!="key" && key!="text" && key!="scope" && key!="certainty" && key!="status" && key!="section" && key!="occurred_at" do return fail("Unknown person seed field.")
  }
  kind,k_ok:=string_field(record,"kind",32)
  actor,a_ok:=string_field(record,"actor",256)
  subject,s_ok:=string_field(record,"subject",256)
  key,key_ok:=string_field(record,"key",256)
  text,t_ok:=string_field(record,"text",4096)
  scope,sc_ok:=string_field(record,"scope",32)
  certainty,c_ok:=string_field(record,"certainty",32)
  section,section_ok:=string_field(record,"section",512)
  if !k_ok || !a_ok || !s_ok || !key_ok || !t_ok || !sc_ok || !c_ok || !section_ok || (kind!="fact" && kind!="event" && kind!="interpretation" && kind!="promise") || (scope!="real" && scope!="fiction" && scope!="shared") || (kind=="interpretation" && certainty!="inferred") || (kind!="interpretation" && certainty!="explicit") do return fail("Invalid imported memory type, certainty or source section.")
  status:="active"
  if _,exists:=record["status"];exists {
   status_ok:bool
   status,status_ok=string_field(record,"status",16)
   if !status_ok || kind!="promise" || (status!="active" && status!="fulfilled" && status!="cancelled") do return fail("Invalid imported promise status.")
  }
  date:=""
  if _,exists:=record["occurred_at"];exists {
   date_ok:bool
   date,date_ok=string_field(record,"occurred_at",32)
   if !date_ok || !valid_date(date) do return fail("Invalid imported occurrence date.")
  }
  memory:=prepare("INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,status,valid_from) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)")
  if memory==nil do return false
  valid=bind_text(memory,1,kind) && bind_text(memory,2,actor) && bind_text(memory,3,subject) && bind_text(memory,4,key) && bind_text(memory,5,text) && bind_text(memory,6,scope) && bind_text(memory,7,certainty) && bind_text(memory,8,status) && bind_text(memory,9,date) && sqlite3_step(memory)==101
  sqlite3_finalize(memory)
  if !valid do return fail("Could not import sourced memory.")
  memory_id:=sqlite3_last_insert_rowid(db)
  if !archive_capture("memories",memory_id) do return false
  link:=prepare("INSERT INTO memory_document_sources(memory_id,document_id,section,occurred_at) VALUES(?1,?2,?3,nullif(?4,''))")
  if link==nil do return false
  valid=bind_int(link,1,memory_id) && bind_int(link,2,document_id) && bind_text(link,3,section) && bind_text(link,4,date) && sqlite3_step(link)==101
  sqlite3_finalize(link)
  if !valid || !archive_capture("memory_document_sources",sqlite3_last_insert_rowid(db)) do return false
 }
 if !archive_finish() || !execute("COMMIT") do return false
 committed=true
 last_error=""
 return true
}
