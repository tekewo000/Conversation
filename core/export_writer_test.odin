package conversation

import "core:testing"
import "base:runtime"
import "core:os"
import "core:strings"
import "core:encoding/json"

Export_Test :: struct { messages: int, metadata: bool, fail: bool }
export_test_writer :: proc "c" (user: rawptr, path: cstring, data: rawptr, size: i32) -> bool {
 context=runtime.default_context()
 state:=cast(^Export_Test)user
 if state.fail do return false
 if strings.has_prefix(string(path),"messages-") {
  text:=string((cast([^]u8)data)[:size])
  if strings.contains(text,"export private canary") do state.messages+=1
 }
 if string(path)=="metadata.json" {
  value,ok:=parse_json((cast([^]u8)data)[:size])
  if ok { json.destroy_value(value); state.metadata=true }
 }
 return true
}
@(test)
export_writer_is_complete_and_failure_preserves_history :: proc(t: ^testing.T) {
 dir,e:=os.make_directory_temp("","export-writer-*",context.allocator)
 assert(e==nil)
 defer delete(dir)
 defer os.remove_all(dir)
 path:=strings.clone_to_cstring(dir)
 defer delete(path)
 assert(conversation_init(path))
 defer conversation_close()
 assert(conversation_add_message("user","export private canary")>0)
 state:Export_Test
 testing.expect(t,conversation_export_with_writer(export_test_writer,&state))
 testing.expect(t,state.messages==1 && state.metadata)
 state.fail=true
 testing.expect(t,!conversation_export_with_writer(export_test_writer,&state))
 testing.expect(t,conversation_message_count()==1)
}
