package conversation

import "core:encoding/json"
import "core:os"
import "core:strings"
import "core:testing"

@(test)
storage_and_export_create_missing_nested_directories_without_replacing_files :: proc(t: ^testing.T) {
 root,e:=os.make_directory_temp("","conversation-direct-mkdir-*",context.allocator)
 assert(e==nil)
 defer delete(root)
 defer os.remove_all(root)
 storage:=strings.concatenate({root,"/missing-parent/人物"})
 defer delete(storage)
 storage_c:=strings.clone_to_cstring(storage)
 defer delete(storage_c)
 defer conversation_close()
 assert(conversation_init(storage_c),string(conversation_error()))
 assert(conversation_add_message("user","ファイルを保ったまま保存")==1)
 output:=strings.concatenate({storage,"/snapshots/missing-child/export"})
 defer delete(output)
 output_c:=strings.clone_to_cstring(output)
 defer delete(output_c)
 assert(conversation_export(output_c),string(conversation_error()))
 assert(conversation_export(output_c),string(conversation_error()))
 manifest:=strings.concatenate({output,"/metadata.json"})
 defer delete(manifest)
 bytes,read_e:=os.read_entire_file(manifest,context.allocator)
 defer delete(bytes)
 assert(read_e==nil)
 value,ok:=parse_json(bytes,6)
 assert(ok)
 json.destroy_value(value)
 blocked:=strings.concatenate({storage,"/blocked"})
 defer delete(blocked)
 assert(os.write_entire_file(blocked,"preserve this file")==nil)
 blocked_c:=strings.clone_to_cstring(blocked)
 defer delete(blocked_c)
 testing.expect(t,!conversation_export(blocked_c))
 blocked_child:=strings.concatenate({blocked,"/child"})
 defer delete(blocked_child)
 blocked_child_c:=strings.clone_to_cstring(blocked_child)
 defer delete(blocked_child_c)
 testing.expect(t,!conversation_export(blocked_child_c))
 preserved,preserved_e:=os.read_entire_file(blocked,context.allocator)
 defer delete(preserved)
 testing.expect(t,preserved_e==nil && string(preserved)=="preserve this file")
}
