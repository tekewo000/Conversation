package conversation

import "base:runtime"
import "core:strings"
import "core:fmt"

Export_Writer :: proc "c" (user: rawptr, path: cstring, data: rawptr, size: i32) -> bool

@(export)
conversation_export_with_writer :: proc "c" (writer: Export_Writer, user: rawptr) -> bool {
 context=runtime.default_context()
 if !ready || writer==nil do return fail("An open database and export writer are required.")
 if !execute("BEGIN") do return false
 defer execute("ROLLBACK")
 tables:=[]string{"messages","memories","memory_sources","jobs","person_profiles","source_documents","memory_document_sources","persona_revisions","continuity_revisions","assistant_states","episode_nodes","message_annotations","episode_rebuilds"}
 counts:[13]i64
 for table,i in tables {
  sql:=fmt.aprintf("SELECT %s FROM %s ORDER BY rowid",archive_table(table).row_json,table)
  csql:=strings.clone_to_cstring(sql)
  stmt:=prepare(csql)
  delete(csql)
  delete(sql)
  if stmt==nil do return false
  buffer:[dynamic]u8
  chunk:=0
  success:=true
  for {
   code:=sqlite3_step(stmt)
   if code==101 do break
   if code!=100 { success=false; break }
   line:=string(sqlite3_column_text(stmt,0))
   if len(line)+1>16777216 { success=false; break }
   if len(buffer)>0 && len(buffer)+len(line)+1>1048576 {
    name:=fmt.caprintf("%s-%06d.jsonl",table,chunk)
    success=writer(user,name,raw_data(buffer),i32(len(buffer)))
    delete(name)
    if !success do break
    clear(&buffer)
    chunk+=1
   }
   append(&buffer,..transmute([]byte)line)
   append(&buffer,'\n')
   counts[i]+=1
  }
  if success && len(buffer)>0 {
   name:=fmt.caprintf("%s-%06d.jsonl",table,chunk)
   success=writer(user,name,raw_data(buffer),i32(len(buffer)))
   delete(name)
  }
  delete(buffer)
  sqlite3_finalize(stmt)
  if !success do return fail("Could not write the encrypted export; the original history was preserved.")
 }
 person_sql:=strings.clone_to_cstring("SELECT "+PERSON_JSON+" WHERE ?1=''")
 person:=json_query(person_sql)
 delete(person_sql)
 if person==nil do return false
 metadata:=fmt.aprintf(`{{"format":3,"person":%s,"counts":[%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d]}}`,string(person),counts[0],counts[1],counts[2],counts[3],counts[4],counts[5],counts[6],counts[7],counts[8],counts[9],counts[10],counts[11],counts[12])
 defer delete(metadata)
 if !writer(user,"metadata.json",raw_data(metadata),i32(len(metadata))) do return fail("Could not finish the encrypted export.")
 last_error=""
 return true
}
