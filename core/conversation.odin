package conversation

import "base:runtime"
import "core:encoding/json"
import "core:fmt"
import "core:mem"
import "core:os"
import "core:strings"
import "core:unicode/utf8"

MAX_MESSAGE_BYTES :: 65536
MAX_WINDOW :: 80
Cached_Message :: struct { id: i64, text, role: cstring }
Allocation_Check :: struct { backing: mem.Allocator, failed: bool }
parsing_check: Allocation_Check
checked_allocation :: proc(data: rawptr, mode: mem.Allocator_Mode, size, alignment: int, old_memory: rawptr, old_size: int, location := #caller_location) -> ([]byte, mem.Allocator_Error) {
 check := (^Allocation_Check)(data)
 bytes, error := check.backing.procedure(check.backing.data, mode, size, alignment, old_memory, old_size, location)
 if size > 0 && (mode == .Alloc || mode == .Alloc_Non_Zeroed || mode == .Resize || mode == .Resize_Non_Zeroed) && (error != nil || bytes == nil) {
  check.failed = true
  if error == nil do error = .Out_Of_Memory
 }
 return bytes, error
}

db: SQLite
ready: bool
last_error: cstring = ""
directory: string
cache: [dynamic]Cached_Message
cache_first: i32
message_count: i32
result_buffer: cstring

fail :: proc(message: cstring) -> bool { last_error = message; return false }
valid_text :: proc(text: string, maximum: int = MAX_MESSAGE_BYTES) -> bool {
 return len(text) <= maximum && utf8.valid_string(text) && !strings.contains_rune(text, 0) && len(strings.trim_space(text)) > 0
}
make_storage_directories :: proc(path: string) -> os.Error {
 // Android permits traversal through /data but denies the ancestor directory reads used by mkdir_all.
 error:=os.make_directory(path)
 if error==.Exist {
  info,stat_error:=os.stat(path,context.allocator)
  defer os.file_info_delete(info,context.allocator)
  if stat_error!=nil do return stat_error
  if info.type!=.Directory do return .Invalid_Dir
  return .Exist
 }
 if error!=.Not_Exist do return error
 parent,_:=os.split_path(strings.trim_right(path,"/\\"))
 if len(parent)==0 || len(parent)>=len(path) do return error
 parent_error:=make_storage_directories(parent)
 if parent_error!=nil && parent_error!=.Exist do return parent_error
 return os.make_directory(path)
}
clear_cache :: proc() {
 for row in cache { delete(row.text); delete(row.role) }
 delete(cache)
 cache = nil
}
release_state :: proc() {
 archive_group=0
 clear_cache()
 if db != nil do sqlite3_close(db)
 db = nil
 delete(directory)
 directory = ""
 delete(result_buffer)
 result_buffer = nil
 ready = false
 message_count = 0
}
execute :: proc(sql: cstring) -> bool {
 if sqlite3_exec(db, sql, nil, nil, nil) != 0 do return fail("Database operation failed; no incomplete change was saved.")
 return true
}
prepare :: proc(sql: cstring) -> Statement {
 stmt: Statement
 if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) != 0 { fail("Could not prepare database operation."); return nil }
 return stmt
}
bind_text :: proc(stmt: Statement, index: i32, text: string) -> bool {
 pointer := rawptr(raw_data(text))
 if len(text)==0 do pointer=rawptr(raw_data(string(" ")))
 if sqlite3_bind_text(stmt, index, pointer, i32(len(text)), rawptr(uintptr(0xffffffffffffffff))) != 0 do return fail("Not enough memory for database input.")
 return true
}
bind_int :: proc(stmt: Statement, index: i32, value: i64) -> bool { return sqlite3_bind_int64(stmt, index, value) == 0 }
scalar :: proc(sql: cstring) -> i64 {
 stmt := prepare(sql)
 if stmt == nil do return -1
 defer sqlite3_finalize(stmt)
 if sqlite3_step(stmt) != 100 do return -1
 return sqlite3_column_int64(stmt, 0)
}
result_text :: proc(text: cstring) -> cstring {
 copy, e := strings.clone_to_cstring(string(text))
 if e != nil { fail("Not enough memory to read database output."); return nil }
 delete(result_buffer)
 result_buffer = copy
 return copy
}
json_query :: proc(sql: cstring, query: string = "", match: cstring = nil) -> cstring {
 stmt := prepare(sql)
 if stmt == nil do return nil
 defer sqlite3_finalize(stmt)
 if !bind_text(stmt, 1, query) do return nil
 if match!=nil && !bind_text(stmt,2,string(match)) do return nil
 if sqlite3_step(stmt) != 100 { fail("Could not read database output."); return nil }
 return result_text(sqlite3_column_text(stmt, 0))
}

SCHEMA :: `
PRAGMA foreign_keys=ON;
PRAGMA journal_mode=WAL;
PRAGMA synchronous=FULL;
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, role TEXT NOT NULL CHECK(role IN ('user','assistant')),content TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')));
CREATE TABLE IF NOT EXISTS jobs(id INTEGER PRIMARY KEY,first_id INTEGER NOT NULL REFERENCES messages(id),end_id INTEGER NOT NULL REFERENCES messages(id),processed INTEGER NOT NULL DEFAULT 0 CHECK(processed IN (0,1)),UNIQUE(first_id,end_id));
CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY,job_id INTEGER REFERENCES jobs(id),ordinal INTEGER,kind TEXT NOT NULL CHECK(kind IN ('fact','event','interpretation','promise')),actor TEXT NOT NULL,subject TEXT NOT NULL,key TEXT NOT NULL,text TEXT NOT NULL,scope TEXT NOT NULL CHECK(scope IN ('real','fiction','shared')),certainty TEXT NOT NULL CHECK(certainty IN ('explicit','inferred')),status TEXT NOT NULL DEFAULT 'active' CHECK(status IN ('active','fulfilled','cancelled')),supersedes_id INTEGER REFERENCES memories(id),valid_from TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%dT%H:%M:%fZ','now')),UNIQUE(job_id,ordinal));
CREATE TABLE IF NOT EXISTS memory_sources(memory_id INTEGER NOT NULL REFERENCES memories(id),message_id INTEGER NOT NULL REFERENCES messages(id),PRIMARY KEY(memory_id,message_id));
CREATE INDEX IF NOT EXISTS memories_identity ON memories(subject,key,scope,kind);
CREATE INDEX IF NOT EXISTS memories_previous ON memories(supersedes_id);
CREATE INDEX IF NOT EXISTS jobs_pending ON jobs(processed,id);
CREATE INDEX IF NOT EXISTS memories_subject ON memories(subject,id DESC);
CREATE INDEX IF NOT EXISTS memories_key ON memories(key,id DESC);
CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(subject,key,text,valid_from,content='memories',content_rowid='id',tokenize='trigram');
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content,content='messages',content_rowid='id',tokenize='trigram');
CREATE TABLE IF NOT EXISTS memory_current(id INTEGER PRIMARY KEY REFERENCES memories(id),kind TEXT NOT NULL,status TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS current_promises ON memory_current(kind,status,id DESC);
CREATE TRIGGER IF NOT EXISTS messages_search_insert AFTER INSERT ON messages BEGIN
 INSERT INTO messages_fts(rowid,content) VALUES(new.id,new.content);
END;
CREATE TRIGGER IF NOT EXISTS memories_search_insert AFTER INSERT ON memories BEGIN
 INSERT INTO memories_fts(rowid,subject,key,text,valid_from) VALUES(new.id,new.subject,new.key,new.text,new.valid_from);
 DELETE FROM memory_current WHERE id=new.supersedes_id;
 INSERT INTO memory_current(id,kind,status) VALUES(new.id,new.kind,new.status);
END;
CREATE TRIGGER IF NOT EXISTS messages_no_update BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS messages_no_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS memories_no_update BEFORE UPDATE ON memories BEGIN SELECT RAISE(ABORT,'immutable'); END;
CREATE TRIGGER IF NOT EXISTS memories_no_delete BEFORE DELETE ON memories BEGIN SELECT RAISE(ABORT,'immutable'); END;
`

insert_message :: proc(role, text: string) -> i64 {
 stmt := prepare("INSERT INTO messages(role,content) VALUES(?1,?2)")
 if stmt == nil do return 0
 defer sqlite3_finalize(stmt)
 if !bind_text(stmt, 1, role) || !bind_text(stmt, 2, text) || sqlite3_step(stmt) != 101 { fail("Could not append the message."); return 0 }
 id:=sqlite3_last_insert_rowid(db)
 if !archive_capture("messages",id) do return 0
 return id
}
// Jobs are bounded before enqueueing, including JSON escaping. Long source text stays intact in messages.
enqueue_uncovered :: proc() -> bool {
 stmt := prepare("SELECT id,length(CAST(json_object('id',id,'role',role,'content',substr(content,1,4000),'created_at',created_at,'truncated',json(CASE WHEN length(content)>4000 THEN 'true' ELSE 'false' END)) AS BLOB)) FROM messages WHERE id>coalesce((SELECT max(end_id) FROM jobs),0) ORDER BY id")
 if stmt == nil do return false
 defer sqlite3_finalize(stmt)
 first, end: i64
 count, bytes: int
 for {
  code := sqlite3_step(stmt)
  if code == 101 do break
  if code != 100 do return fail("Could not schedule memory extraction.")
  id := sqlite3_column_int64(stmt, 0)
  size := int(sqlite3_column_int64(stmt, 1))
  if count == 16 || bytes + size > 60000 {
   if !insert_job(first, end) do return false
   count, bytes, first = 0, 0, 0
  }
  if first == 0 do first = id
  end = id
  count += 1
  bytes += size + 1
 }
 return count == 0 || insert_job(first, end)
}
insert_job :: proc(first, end: i64) -> bool {
 stmt := prepare("INSERT INTO jobs(first_id,end_id) VALUES(?1,?2)")
 if stmt == nil do return false
 defer sqlite3_finalize(stmt)
 return bind_int(stmt, 1, first) && bind_int(stmt, 2, end) && sqlite3_step(stmt) == 101 && archive_capture("jobs",sqlite3_last_insert_rowid(db))
}

parse_json :: proc(bytes: []byte, max_depth: int = 6) -> (json.Value, bool) {
 p := json.make_parser(bytes, .JSON, true)
 if !snapshot_tokens_valid(bytes, max_depth) || !json.validate_value(&p) || p.curr_token.kind != .EOF { fail("Malformed JSON input."); return nil, false }
 parsing_check = Allocation_Check{backing = context.allocator}
 root, e := json.parse(bytes, .JSON, true, {checked_allocation, &parsing_check})
 if e != nil || parsing_check.failed { json.destroy_value(root); fail("Could not parse JSON input."); return nil, false }
 return root, true
}

migrate_legacy :: proc() -> bool {
 path := strings.concatenate({directory, "/notebook.json"})
 defer delete(path)
 bytes, read_e := os.read_entire_file(path, context.allocator)
 defer delete(bytes)
 if read_e == .Not_Exist do return execute("INSERT INTO meta VALUES('initialized','1')")
 if read_e != nil do return fail("Could not read legacy history; original file preserved.")
 root, ok := parse_json(bytes, 2)
 if !ok do return false
 defer json.destroy_value(root)
 obj, obj_ok := root.(json.Object)
 if !obj_ok || len(obj) != 3 do return fail("Unsupported legacy history format.")
 version, v_ok := obj["version"].(json.Integer)
 texts, t_ok := obj["messages"].(json.Array)
 pins, p_ok := obj["memories"].(json.Array)
 if !v_ok || version != 1 || !t_ok || !p_ok || len(texts) > 0x7fffffff do return fail("Unsupported legacy history format.")
 for value in texts { text, valid := value.(json.String); if !valid || !valid_text(string(text)) do return fail("Invalid legacy message; original file preserved.") }
 for value, i in pins {
  index, valid := value.(json.Integer)
  if !valid || index < 0 || index >= i64(len(texts)) do return fail("Invalid legacy pin; original file preserved.")
  for prev in pins[:i] { if prev.(json.Integer) == index do return fail("Duplicate legacy pin.") }
 }
 for value in texts { if insert_message("user", string(value.(json.String))) == 0 do return false }
 for value in pins {
  source_id := i64(value.(json.Integer)) + 1
  stmt := prepare("INSERT INTO memories(kind,actor,subject,key,text,scope,certainty,valid_from) SELECT 'event','user','user','legacy_pin',content,'shared','explicit',created_at FROM messages WHERE id=?1")
  if stmt == nil do return false
  success := bind_int(stmt, 1, source_id) && sqlite3_step(stmt) == 101
  sqlite3_finalize(stmt)
  if !success || !insert_source(sqlite3_last_insert_rowid(db), source_id) do return false
 }
 return enqueue_uncovered() && execute("INSERT INTO meta VALUES('initialized','1')")
}

@(export)
conversation_init :: proc "c" (storage_dir: cstring) -> bool {
 context = runtime.default_context()
 release_state()
 last_error = ""
 if storage_dir == nil || !valid_text(string(storage_dir)) do return fail("A valid storage directory is required.")
 e: mem.Allocator_Error
 directory, e = strings.clone(string(storage_dir))
 if e != nil do return fail("Not enough memory to open history.")
 info, stat_e := os.stat(directory, context.allocator)
 if stat_e == .Not_Exist {
  create_e := make_storage_directories(directory)
  if create_e != nil && create_e != .Exist { release_state(); return fail("Could not create storage directory.") }
  info, stat_e = os.stat(directory, context.allocator)
 }
 defer os.file_info_delete(info, context.allocator)
 if stat_e != nil || info.type != .Directory { release_state(); return fail("Could not open storage directory.") }
 path := strings.concatenate({directory, "/conversation.sqlite3"})
 defer delete(path)
 cpath, path_e := strings.clone_to_cstring(path)
 defer delete(cpath)
 if path_e != nil || sqlite3_open_v2(cpath, &db, 6, nil) != 0 { release_state(); return fail("Could not open SQLite database.") }
 sqlite3_busy_timeout(db, 5000)
 version:=scalar("PRAGMA user_version")
 if version!=0 && version!=1 && version!=2 { release_state(); return fail("Unsupported database version; database was preserved.") }
 tables:=scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
 if tables<0 { release_state(); return fail("Could not inspect database schema.") }
 if tables>0 {
  meta_exists:=scalar("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='meta'")
  if meta_exists!=1 { release_state(); return fail("Unrecognized nonempty database; database was preserved.") }
  marker:=scalar("SELECT count(*) FROM meta WHERE key='initialized' AND value='1'")
  if marker!=1 {
   rows:=scalar("SELECT (SELECT count(*) FROM messages)+(SELECT count(*) FROM memories)+(SELECT count(*) FROM jobs)")
   if rows!=0 { release_state(); return fail("Database initialization marker is missing; legacy history was not imported.") }
  }
 }
 if !execute(SCHEMA) || !execute("BEGIN IMMEDIATE") { release_state(); return false }
 if !execute(PERSON_SCHEMA) || !execute(ARCHIVE_SCHEMA) { execute("ROLLBACK"); release_state(); return false }
 initialized := scalar("SELECT count(*) FROM meta WHERE key='initialized' AND value='1'")
 search_ready:=scalar("SELECT count(*) FROM meta WHERE key='search_version' AND value='1'")
 if initialized < 0 || search_ready<0 || (initialized == 0 && !migrate_legacy()) ||
  (search_ready==0 && !execute(`INSERT INTO messages_fts(messages_fts) VALUES('rebuild'); INSERT INTO memories_fts(memories_fts) VALUES('rebuild'); DELETE FROM memory_current; INSERT INTO memory_current SELECT id,kind,status FROM memories m WHERE NOT EXISTS(SELECT 1 FROM memories n WHERE n.supersedes_id=m.id); INSERT OR REPLACE INTO meta VALUES('search_version','1');`)) || !archive_backfill() || !execute("PRAGMA user_version=2") || !execute("COMMIT") {
  execute("ROLLBACK")
  release_state()
  return false
 }
 // IDs start at one and are contiguous: only successful appends can change messages.
 total := scalar("SELECT coalesce(max(id),0) FROM messages")
 if total < 0 || total > 0x7fffffff { release_state(); return fail("Invalid message count.") }
 message_count = i32(total)
 ready = true
 if !conversation_load_window(max(0, message_count - MAX_WINDOW), MAX_WINDOW) { release_state(); return false }
 last_error = ""
 return true
}

@(export)
conversation_add_message :: proc "c" (role, text: cstring) -> i64 {
 context = runtime.default_context()
 if !ready { fail("History is not open."); return 0 }
 if role == nil || (string(role) != "user" && string(role) != "assistant") || text == nil || !valid_text(string(text)) { fail("A user/assistant role and nonempty UTF-8 text of at most 64KiB are required."); return 0 }
 if message_count == 0x7fffffff || !execute("BEGIN IMMEDIATE") do return 0
 committed := false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 if !archive_begin() do return 0
 id := insert_message(string(role), string(text))
 if id == 0 || (string(role) == "assistant" && !enqueue_uncovered()) || !archive_finish() || !execute("COMMIT") do return 0
 committed = true
 message_count += 1
 clear_cache()
 last_error = ""
 return id
}
@(export)
conversation_send :: proc "c" (text: cstring) -> bool { return conversation_add_message("user", text) != 0 }
@(export)
conversation_message_count :: proc "c" () -> i32 { return message_count }
@(export)
conversation_load_window :: proc "c" (first, limit: i32) -> bool {
 context = runtime.default_context()
 if !ready || first < 0 || first > message_count || limit < 0 || limit > MAX_WINDOW do return fail("Invalid message window (maximum 80).")
 next := make([dynamic]Cached_Message)
 success := false
 defer if !success { for row in next { delete(row.text); delete(row.role) }; delete(next) }
 stmt := prepare("SELECT id,role,content FROM messages WHERE id>?2 ORDER BY id LIMIT ?1")
 if stmt == nil do return false
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt, 1, i64(limit)) || !bind_int(stmt, 2, i64(first)) do return false
 for {
  code := sqlite3_step(stmt)
  if code == 101 do break
  if code != 100 do return fail("Could not load messages.")
  role, er := strings.clone_to_cstring(string(sqlite3_column_text(stmt, 1)))
  text, et := strings.clone_to_cstring(string(sqlite3_column_text(stmt, 2)))
  if er != nil || et != nil { delete(role); delete(text); return fail("Not enough memory to display history.") }
  if reserve(&next,len(next)+1) != nil { delete(role); delete(text); return fail("Not enough memory to display history.") }
  append(&next,Cached_Message{sqlite3_column_int64(stmt,0),text,role})
 }
 clear_cache()
 cache, cache_first, success = next, first, true
 last_error = ""
 return true
}
@(export)
conversation_message_text :: proc "c" (index: i32) -> cstring {
 if index < cache_first || int(index - cache_first) >= len(cache) do return nil
 return cache[index - cache_first].text
}
@(export)
conversation_message_role :: proc "c" (index: i32) -> cstring {
 if index < cache_first || int(index - cache_first) >= len(cache) do return nil
 return cache[index - cache_first].role
}
@(export)
conversation_message_id :: proc "c" (index: i32) -> i64 {
 if index < cache_first || int(index - cache_first) >= len(cache) do return 0
 return cache[index - cache_first].id
}
@(export)
conversation_pending_job :: proc "c" () -> cstring {
 context = runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 return json_query(`SELECT coalesce((SELECT json_object('id',j.id,'messages',json((SELECT json_group_array(json_object('id',id,'role',role,'content',substr(content,1,4000),'created_at',created_at,'truncated',json(CASE WHEN length(content)>4000 THEN 'true' ELSE 'false' END))) FROM (SELECT * FROM messages WHERE id BETWEEN j.first_id AND j.end_id ORDER BY id)))) FROM jobs j WHERE processed=0 ORDER BY id LIMIT 1),'{}') WHERE ?1=''`)
}

insert_source :: proc(memory_id, message_id: i64) -> bool {
 stmt := prepare("INSERT INTO memory_sources(memory_id,message_id) VALUES(?1,?2)")
 if stmt == nil do return false
 defer sqlite3_finalize(stmt)
 return bind_int(stmt, 1, memory_id) && bind_int(stmt, 2, message_id) && sqlite3_step(stmt) == 101 && archive_capture("memory_sources",sqlite3_last_insert_rowid(db))
}
string_field :: proc(obj: json.Object, key: string, max_size: int) -> (string, bool) {
 value, ok := obj[key].(json.String)
 text := string(value)
 return text, ok && valid_text(text,max_size)
}
valid_date :: proc(text: string) -> bool {
 // An ISO calendar date or UTC timestamp; SQLite additionally validates the calendar.
 if len(text) != 10 && len(text) != 20 && len(text) != 24 do return false
 for ch, i in text {
  if i == 4 || i == 7 { if ch != '-' do return false }
  else if i == 10 { if ch != 'T' do return false }
  else if i == 13 || i == 16 { if ch != ':' do return false }
  else if i == 19 && len(text) == 24 { if ch != '.' do return false }
  else if i == len(text)-1 && len(text) > 10 { if ch != 'Z' do return false }
  else if ch < '0' || ch > '9' do return false
 }
 stmt := prepare("SELECT date(?1) IS NOT NULL AND date(?1,'+0 days')=substr(?1,1,10)")
 if stmt == nil do return false
 defer sqlite3_finalize(stmt)
 return bind_text(stmt, 1, text) && sqlite3_step(stmt) == 100 && sqlite3_column_int64(stmt, 0) == 1
}

@(export)
conversation_apply_job :: proc "c" (job_id: i64, payload: cstring) -> bool {
 context = runtime.default_context()
 if !ready do return fail("History is not open.")
 if payload == nil || len(string(payload)) > 65536 do return fail("Extraction JSON is too large.")
 root, parsed := parse_json(transmute([]byte)string(payload))
 if !parsed do return false
 defer json.destroy_value(root)
 obj, obj_ok := root.(json.Object)
 items, items_ok := obj["memories"].(json.Array)
 if !obj_ok || len(obj) != 1 || !items_ok || len(items) > 32 do return fail("Expected at most 32 memory records.")
 if !execute("BEGIN IMMEDIATE") do return false
 committed := false
 defer { archive_group=0; if !committed do execute("ROLLBACK") }
 job := prepare("SELECT end_id,processed FROM jobs WHERE id=?1")
 if job == nil do return false
 defer sqlite3_finalize(job)
 if !bind_int(job, 1, job_id) || sqlite3_step(job) != 100 do return fail("Unknown extraction job.")
 end_id := sqlite3_column_int64(job, 0)
 if sqlite3_column_int64(job, 1) == 1 {
  committed = execute("COMMIT")
  if committed do last_error = ""
  return committed
 }
 if !archive_begin() do return false
 for value, ordinal in items {
  record, is_record := value.(json.Object)
  if !is_record do return fail("Every memory must be an object.")
  for key in record {
   if key != "kind" && key != "actor" && key != "subject" && key != "key" && key != "text" && key != "scope" && key != "certainty" && key != "status" && key != "source_ids" && key != "supersedes_id" && key != "valid_from" do return fail("Unknown memory field.")
  }
  kind, k_ok := string_field(record, "kind", 32)
  subject, s_ok := string_field(record, "subject", 256)
  key, key_ok := string_field(record, "key", 256)
  text, t_ok := string_field(record, "text", 4096)
  scope, sc_ok := string_field(record, "scope", 32)
  certainty, c_ok := string_field(record, "certainty", 32)
  actor := "user"
  if _, exists := record["actor"]; exists {
   var_ok: bool
   actor, var_ok = string_field(record, "actor", 256)
   if !var_ok do return fail("Invalid memory actor.")
  }
  sources, sources_ok := record["source_ids"].(json.Array)
  if !k_ok || !s_ok || !key_ok || !t_ok || !sc_ok || !c_ok || !sources_ok || len(sources) == 0 || len(sources) > 16 || (kind != "fact" && kind != "event" && kind != "interpretation" && kind != "promise") || (scope != "real" && scope != "fiction" && scope != "shared") || (kind=="interpretation" && certainty!="inferred") || (kind!="interpretation" && certainty!="explicit") { return fail("Invalid typed memory or missing source IDs.") }
  status := "active"
  if _, exists := record["status"]; exists {
   status_ok: bool
   status,status_ok=string_field(record,"status",16)
   if !status_ok || kind!="promise" || (status!="active" && status!="fulfilled" && status!="cancelled") do return fail("Invalid promise status.")
  }
  previous: i64
  if prev, exists := record["supersedes_id"]; exists {
   id, id_ok := prev.(json.Integer)
   if !id_ok || id <= 0 do return fail("Invalid superseded memory ID.")
   previous = i64(id)
   check := prepare("SELECT count(*) FROM memories m WHERE id=?1 AND kind=?2 AND scope=?3 AND subject=?4 AND key=?5 AND NOT EXISTS(SELECT 1 FROM memories n WHERE n.supersedes_id=m.id)")
   if check == nil do return false
   valid := bind_int(check,1,previous) && bind_text(check,2,kind) && bind_text(check,3,scope) && bind_text(check,4,subject) && bind_text(check,5,key) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
   sqlite3_finalize(check)
   if !valid do return fail("A revision must match the current memory's kind, scope, subject and key.")
  }
  date := ""
  if _, exists := record["valid_from"]; exists {
   date_ok: bool
   date, date_ok = string_field(record,"valid_from",32)
   if !date_ok || !valid_date(date) do return fail("Invalid ISO date.")
  }
  for src, i in sources {
   id, id_ok := src.(json.Integer)
   if !id_ok || id <= 0 || i64(id) > end_id do return fail("Source IDs must exist at or before the extraction job.")
   for old in sources[:i] { if old.(json.Integer)==id do return fail("Duplicate source ID.") }
   check := prepare("SELECT count(*) FROM messages WHERE id=?1")
   if check == nil do return false
   valid := bind_int(check,1,i64(id)) && sqlite3_step(check)==100 && sqlite3_column_int64(check,0)==1
   sqlite3_finalize(check)
   if !valid do return fail("Source message does not exist.")
  }
  stmt := prepare("INSERT INTO memories(job_id,ordinal,kind,actor,subject,key,text,scope,certainty,supersedes_id,valid_from,status) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,nullif(?10,0),coalesce(nullif(?11,''),(SELECT created_at FROM messages WHERE id=?12)),?13)")
  if stmt == nil do return false
  valid := bind_int(stmt,1,job_id) && bind_int(stmt,2,i64(ordinal)) && bind_text(stmt,3,kind) && bind_text(stmt,4,actor) && bind_text(stmt,5,subject) && bind_text(stmt,6,key) && bind_text(stmt,7,text) && bind_text(stmt,8,scope) && bind_text(stmt,9,certainty) && bind_int(stmt,10,previous) && bind_text(stmt,11,date) && bind_int(stmt,12,i64(sources[0].(json.Integer))) && bind_text(stmt,13,status) && sqlite3_step(stmt)==101
  sqlite3_finalize(stmt)
  if !valid do return fail("Could not save extracted memory.")
  memory_id := sqlite3_last_insert_rowid(db)
  if !archive_capture("memories",memory_id) do return false
  for src in sources { if !insert_source(memory_id, i64(src.(json.Integer))) do return false }
 }
 done := prepare("UPDATE jobs SET processed=1 WHERE id=?1")
 if done == nil do return false
 success := bind_int(done,1,job_id) && sqlite3_step(done)==101
 sqlite3_finalize(done)
 if !success || !archive_capture("jobs",job_id,"update") || !archive_finish() || !execute("COMMIT") do return false
 committed = true
 last_error = ""
 return true
}

// User text becomes quoted FTS phrases; operators and quotes never become query syntax.
search_arguments :: proc(query: cstring) -> (text: string, match: cstring, ok: bool) {
 if query!=nil do text=string(query)
 if len(text)>256 || !utf8.valid_string(text) || strings.contains_rune(text,0) { fail("Search must be valid UTF-8 of at most 256 bytes."); return "",nil,false }
 points:[66]rune
 count:=0
 for point in text { if count==len(points) do break; points[count]=point; count+=1 }
 expression:=strings.builder_make()
 defer strings.builder_destroy(&expression)
 if count<3 {
  strings.write_string(&expression,`""`)
 } else {
  for i in 0..<min(count-2,64) {
   if i>0 do strings.write_string(&expression," OR ")
   strings.write_byte(&expression,'"')
   for point in points[i:i+3] {
    strings.write_rune(&expression,point)
    if point=='"' do strings.write_byte(&expression,'"')
   }
   strings.write_byte(&expression,'"')
  }
 }
 error:mem.Allocator_Error
 match,error=strings.clone_to_cstring(strings.to_string(expression))
 if error!=nil { fail("Not enough memory to search."); return "",nil,false }
 return text,match,true
}

MEMORY_JSON :: `WITH RECURSIVE query_grams(i,token) AS (
 SELECT 1,substr(?1,1,3) UNION ALL SELECT i+1,substr(?1,i+1,3) FROM query_grams WHERE i<min(length(?1)-1,64)
), fts_hits AS MATERIALIZED (
 SELECT rowid AS id,rank AS score FROM memories_fts WHERE memories_fts MATCH ?2 ORDER BY rank LIMIT 48
), recent_memories AS MATERIALIZED (
 SELECT * FROM memories ORDER BY id DESC LIMIT 200
), exact_hits AS (
 SELECT id FROM (SELECT id FROM memories WHERE subject=?1 LIMIT 30)
 UNION SELECT id FROM (SELECT id FROM memories WHERE key=?1 LIMIT 30)
), memory_hits AS (
 SELECT id,score FROM fts_hits
 UNION ALL SELECT id,-100.0 FROM exact_hits WHERE length(?1)>0
 UNION ALL SELECT id,0.0 FROM recent_memories recent WHERE length(?1)>0 AND
 ((length(?1)<3 AND instr(lower(subject||' '||key||' '||text||' '||valid_from),lower(?1))>0) OR EXISTS(SELECT 1 FROM query_grams WHERE length(token)>=2 AND unicode(token)>127 AND instr(lower(recent.subject||' '||recent.key||' '||recent.text||' '||recent.valid_from),lower(substr(token,1,2)))>0))
), selected AS (
 SELECT m.*,min(h.score) AS score FROM memory_hits h JOIN memories m ON m.id=h.id GROUP BY m.id
 UNION ALL SELECT *,0.0 FROM (SELECT * FROM memories ORDER BY id DESC LIMIT 30) WHERE ?1=''
) SELECT json_object('memories',json_group_array(json_object('id',m.id,'kind',kind,'actor',actor,'subject',subject,'key',key,'text',text,'scope',scope,'certainty',certainty,'status',status,'supersedes_id',supersedes_id,'valid_from',valid_from,'created_at',created_at,'imported_sources',json(` + IMPORTED_SOURCES_JSON + `),'source_ids',json((SELECT json_group_array(message_id) FROM memory_sources WHERE memory_id=m.id)),'sources',json((SELECT json_group_array(json_object('id',x.id,'role',x.role,'content',substr(x.content,1,256),'created_at',x.created_at)) FROM memory_sources s JOIN messages x ON x.id=s.message_id WHERE s.memory_id=m.id)),'current',json(CASE WHEN EXISTS(SELECT 1 FROM memory_current c WHERE c.id=m.id) THEN 'true' ELSE 'false' END)))) FROM (SELECT * FROM selected ORDER BY score,id DESC LIMIT 30) m`
@(export)
conversation_memories :: proc "c" (query: cstring) -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 text,match,ok:=search_arguments(query)
 if !ok do return nil
 defer delete(match)
 return json_query(MEMORY_JSON,text,match)
}
// Serialize only selected hits/recent rows, with explicit escaped-byte budgets.
CONTEXT_JSON :: `WITH RECURSIVE query_grams(i,token) AS (
 SELECT 1,substr(?1,1,3) UNION ALL SELECT i+1,substr(?1,i+1,3) FROM query_grams WHERE i<min(length(?1)-1,64)
), fts_hits AS MATERIALIZED (
 SELECT rowid AS id,rank AS score FROM memories_fts WHERE memories_fts MATCH ?2 ORDER BY rank LIMIT 48
), recent_memories AS MATERIALIZED (
 SELECT * FROM memories ORDER BY id DESC LIMIT 200
), exact_hits AS (
 SELECT id FROM (SELECT id FROM memories WHERE subject=?1 LIMIT 30)
 UNION SELECT id FROM (SELECT id FROM memories WHERE key=?1 LIMIT 30)
), memory_hits AS (
 SELECT id,score FROM fts_hits
 UNION ALL SELECT id,-100.0 FROM exact_hits WHERE length(?1)>0
 UNION ALL SELECT id,0.0 FROM recent_memories recent WHERE length(?1)>0 AND
 ((length(?1)<3 AND instr(lower(subject||' '||key||' '||text||' '||valid_from),lower(?1))>0) OR EXISTS(SELECT 1 FROM query_grams WHERE length(token)>=2 AND unicode(token)>127 AND instr(lower(recent.subject||' '||recent.key||' '||recent.text||' '||recent.valid_from),lower(substr(token,1,2)))>0))
), matched AS (
 SELECT m.id,min(h.score) AS score FROM memory_hits h JOIN memories m ON m.id=h.id
 WHERE EXISTS(SELECT 1 FROM memory_current c WHERE c.id=m.id) OR instr(?1,char(26132)) OR instr(?1,char(20197,21069)) OR instr(?1,char(21069)) OR instr(?1,char(24403,26178)) OR instr(?1,char(23653,27508)) OR instr(?1,char(12356,12388)) OR instr(lower(?1),'history') OR instr(lower(?1),'previous') OR ?1 GLOB '*[0-9][0-9][0-9][0-9]-[0-9]*'
 GROUP BY m.id ORDER BY score,m.id DESC LIMIT 12
), active_promises AS (
 SELECT id FROM memory_current WHERE kind='promise' AND status='active' ORDER BY id DESC LIMIT 4
), current_recent AS (
 SELECT id FROM memory_current ORDER BY id DESC LIMIT 12
), prioritized AS (
 SELECT id,0 AS priority,score FROM matched
 UNION ALL SELECT id,1,0.0 FROM active_promises
 UNION ALL SELECT id,2,0.0 FROM current_recent
), selected AS (
 SELECT m.*,EXISTS(SELECT 1 FROM memory_current c WHERE c.id=m.id) AS current FROM memories m
 JOIN (SELECT id,min(priority) AS priority,min(score) AS score FROM prioritized GROUP BY id ORDER BY priority,score,id DESC LIMIT 12) pick ON pick.id=m.id
 ORDER BY pick.priority,pick.score,m.id DESC
), memory_rows AS (
 SELECT json_object('id',id,'kind',kind,'actor',actor,'subject',subject,'key',key,'text',substr(text,1,512),'scope',scope,'certainty',certainty,'status',status,'valid_from',valid_from,'supersedes_id',supersedes_id,'current',json(CASE WHEN current THEN 'true' ELSE 'false' END),'truncated',json(CASE WHEN length(text)>512 THEN 'true' ELSE 'false' END),'imported_sources',json(` + IMPORTED_SOURCES_JSON + `),'source_ids',json((SELECT json_group_array(message_id) FROM memory_sources WHERE memory_id=m.id))) AS row FROM selected m
), memory_budget AS (
 SELECT row,sum(length(CAST(row AS BLOB))+1) OVER(ROWS UNBOUNDED PRECEDING) AS bytes FROM memory_rows
), past_messages AS MATERIALIZED (
 SELECT rowid AS id,substr(snippet(messages_fts,0,'','',char(8230),64),1,512) AS excerpt FROM messages_fts WHERE messages_fts MATCH ?2 ORDER BY rank LIMIT 4
), recent_messages AS (
 SELECT id FROM messages ORDER BY id DESC LIMIT 12
), message_rows AS (
 SELECT m.id,json_object('id',m.id,'role',role,'content',coalesce(past.excerpt,substr(content,1,512)),'created_at',created_at,'truncated',json(CASE WHEN length(content)>512 OR coalesce(past.excerpt,content)!=content THEN 'true' ELSE 'false' END)) AS row FROM messages m
 JOIN (SELECT id FROM past_messages UNION SELECT id FROM recent_messages) pick ON pick.id=m.id LEFT JOIN past_messages past ON past.id=m.id
), message_budget AS (
 SELECT id,row,sum(length(CAST(row AS BLOB))+1) OVER(ORDER BY id DESC ROWS UNBOUNDED PRECEDING) AS bytes FROM message_rows
)
SELECT json_object('person',json(` + PERSON_JSON + `),'messages',json((SELECT json_group_array(json(row)) FROM (SELECT row FROM message_budget WHERE bytes<=18000 ORDER BY id))),'memories',json((SELECT json_group_array(json(row)) FROM memory_budget WHERE bytes<=14000)))`
@(export)
conversation_context :: proc "c" (query: cstring) -> cstring {
 context=runtime.default_context()
 if !ready { fail("History is not open."); return nil }
 text,match,ok:=search_arguments(query)
 if !ok do return nil
 defer delete(match)
 return json_query(CONTEXT_JSON,text,match)
}
@(export)
conversation_memory_count :: proc "c" () -> i32 {
 context = runtime.default_context()
 if !ready do return 0
 return i32(max(0,scalar("SELECT count(*) FROM memories")))
}
@(export)
conversation_memory_text :: proc "c" (index: i32) -> cstring {
 context = runtime.default_context()
 if !ready || index < 0 do return nil
 stmt := prepare("SELECT text FROM memories ORDER BY id LIMIT 1 OFFSET ?1")
 if stmt == nil do return nil
 defer sqlite3_finalize(stmt)
 if !bind_int(stmt,1,i64(index)) || sqlite3_step(stmt)!=100 do return nil
 return result_text(sqlite3_column_text(stmt,0))
}
@(export)
conversation_remember :: proc "c" (index: i32) -> bool {
 context = runtime.default_context()
 return fail("Manual pinning is retired; existing pins were retained as sourced legacy events.")
}
@(export)
conversation_error :: proc "c" () -> cstring { return last_error }
@(export)
conversation_close :: proc "c" () { context=runtime.default_context(); release_state(); last_error="" }

@(export)
conversation_export :: proc "c" (output_dir: cstring) -> bool {
 context=runtime.default_context()
 if !ready || output_dir==nil || !valid_text(string(output_dir)) do return fail("A valid export directory is required.")
 base:=string(output_dir)
 create_e:=make_storage_directories(base)
 if create_e!=nil && create_e!=.Exist do return fail("Could not create export directory.")
 if !execute("BEGIN") do return false
 defer execute("ROLLBACK")
 token_stmt:=prepare("SELECT lower(hex(randomblob(8)))")
 if token_stmt==nil do return false
 if sqlite3_step(token_stmt)!=100 { sqlite3_finalize(token_stmt); return fail("Could not create export snapshot.") }
 name:=fmt.aprintf("snapshot-%s",string(sqlite3_column_text(token_stmt,0)))
 sqlite3_finalize(token_stmt)
 defer delete(name)
 folder:=strings.concatenate({base,"/",name})
 defer delete(folder)
 if os.make_directory(folder)!=nil do return fail("Could not create export snapshot directory.")
 counts:[7]i64
 chunks:[7]int
 for table,i in ([]string{"messages","memories","memory_sources","jobs","person_profiles","source_documents","memory_document_sources"}) {
  row_sql:=archive_table(table).row_json
  sql:=fmt.aprintf("SELECT %s FROM %s ORDER BY rowid",row_sql,table)
  csql,e:=strings.clone_to_cstring(sql)
  delete(sql)
  if e!=nil do return fail("Not enough memory to export.")
  stmt:=prepare(csql)
  delete(csql)
  if stmt==nil do return false
  file:^os.File
  opened:=false
  bytes:=0
  success:=true
  for {
   code:=sqlite3_step(stmt)
   if code==101 do break
   if code!=100 { success=false; break }
   line:=string(sqlite3_column_text(stmt,0))
   if opened && bytes+len(line)+1>1048576 {
    sync_e:=os.sync(file)
    close_e:=os.close(file)
    opened=false
    if sync_e!=nil || close_e!=nil { success=false; break }
   }
   if !opened {
    path:=fmt.aprintf("%s/%s-%06d.jsonl",folder,table,chunks[i])
    open_e:os.Error
    file,open_e=os.open(path,{.Write,.Create,.Excl})
    delete(path)
    if open_e!=nil { success=false; break }
    opened=true
    bytes=0
    chunks[i]+=1
   }
   n,write_e:=os.write(file,transmute([]byte)line)
   n2,newline_e:=os.write(file,[]byte{'\n'})
   if write_e!=nil || n!=len(line) || newline_e!=nil || n2!=1 { success=false; break }
   bytes+=n+1
   counts[i]+=1
  }
  if opened {
   sync_e:=os.sync(file)
   close_e:=os.close(file)
   if sync_e!=nil || close_e!=nil do success=false
  }
  sqlite3_finalize(stmt)
  if !success do return fail("Could not finish export; previous export manifest was preserved.")
 }
 person_sql:=strings.clone_to_cstring("SELECT "+PERSON_JSON+" WHERE ?1=''")
 person:=json_query(person_sql)
 delete(person_sql)
 if person==nil do return false
 metadata:=fmt.aprintf(`{{"format":2,"snapshot":"%s","person":%s,"messages":%d,"memories":%d,"sources":%d,"jobs":%d,"profiles":%d,"documents":%d,"document_sources":%d,"chunks":{{"messages":%d,"memories":%d,"memory_sources":%d,"jobs":%d,"person_profiles":%d,"source_documents":%d,"memory_document_sources":%d}},"chunk_bytes_target":1048576,"chunk_bytes_max":16777216}}`,name,string(person),counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6],chunks[0],chunks[1],chunks[2],chunks[3],chunks[4],chunks[5],chunks[6])
 defer delete(metadata)
 temporary:=strings.concatenate({base,"/metadata.json.tmp"})
 defer delete(temporary)
 path:=strings.concatenate({base,"/metadata.json"})
 defer delete(path)
 file,open_e:=os.open(temporary,{.Write,.Create,.Trunc})
 if open_e!=nil do return fail("Could not create export manifest.")
 n,write_e:=os.write(file,transmute([]byte)metadata)
 sync_e:=os.sync(file)
 close_e:=os.close(file)
 if write_e!=nil || n!=len(metadata) || sync_e!=nil || close_e!=nil || os.rename(temporary,path)!=nil do return fail("Could not commit export manifest.")
 last_error=""
 return true
}
