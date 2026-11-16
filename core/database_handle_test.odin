package conversation

import "core:testing"
import "core:strings"
import "core:os"

@(test)
database_handle_ownership :: proc(t: ^testing.T) {
 path, error := os.make_directory_temp("", "database-handle-*", context.allocator)
 assert(error == nil)
 defer delete(path)
 defer os.remove_all(path)
 db_path := strings.concatenate({path, "/conversation.sqlite3"})
 defer delete(db_path)
 cpath := strings.clone_to_cstring(db_path)
 defer delete(cpath)
 external: SQLite
 testing.expect(t, sqlite3_open_v2(cpath, &external, 6, nil) == 0)
 cdir := strings.clone_to_cstring(path)
 defer delete(cdir)
 testing.expect(t, conversation_init_database(cdir, rawptr(external)))
 testing.expect(t, conversation_add_message("user", "external handle") > 0)
 conversation_close()
 testing.expect(t, conversation_init(cdir))
 testing.expect(t, conversation_message_count() == 1)
 conversation_close()
}
