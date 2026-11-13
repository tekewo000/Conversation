package conversation
import "core:os"
import "core:strings"
import "core:testing"
@(test)
malformed_migration_never_marks_database_ready :: proc(t: ^testing.T) {
 dir, e := os.make_directory_temp("", "conversation-invalid-*", context.allocator)
 assert(e == nil)
 defer delete(dir)
 defer os.remove_all(dir)
 path := strings.concatenate({dir, "/notebook.json"})
 defer delete(path)
 c := strings.clone_to_cstring(dir)
 defer delete(c)
 defer conversation_close()
 bad := []string{`{`, `{"version":1,"messages":["ok"],"memories":[1]}`, `{"version":1,"messages":["\ud800"],"memories":[]}`, `{"version":1,"messages":["ok"],"memories":[0,0]}`}
 for text in bad {
  assert(os.write_entire_file(path, text) == nil)
  testing.expect(t, !conversation_init(c))
  testing.expect(t, !conversation_send("must fail"))
 }
 assert(os.write_entire_file(path, `{"version":1,"messages":["repaired"],"memories":[]}`) == nil)
 assert(conversation_init(c))
 assert(conversation_message_count() == 1)
}
