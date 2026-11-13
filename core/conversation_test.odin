package conversation

import "core:os"
import "core:strings"
import "core:testing"

@(test)
notebook_persists_utf8_and_preserves_failed_changes :: proc(t: ^testing.T) {
	dir, dir_error := os.make_directory_temp("", "conversation-test-*", context.allocator)
	assert(dir_error == nil)
	defer delete(dir)
	defer os.remove_all(dir)
	dir_c := strings.clone_to_cstring(dir)
	defer delete(dir_c)
	defer conversation_close()

	assert(conversation_init(dir_c))
	testing.expect_value(t, conversation_message_count(), 0)
	testing.expect(t, !conversation_send(" \t\n　"))
	testing.expect(t, !conversation_send(nil))
	testing.expect(t, !conversation_send("\xff"))
	long_text := make([]byte, 65537)
	defer delete(long_text)
	for &character in long_text do character = 'a'
	long_c := strings.clone_to_cstring(string(long_text))
	defer delete(long_c)
	assert(!conversation_send(long_c))
	testing.expect_value(t, conversation_message_count(), 0)
	testing.expect(t, len(string(conversation_error())) > 0)

	text :: "こんにちは。\n\"メモ\"と\\パス、🎾"
	assert(conversation_send(text))
	assert(conversation_send("  空白は保持する  "))
	testing.expect_value(t, string(conversation_message_text(0)), text)
	testing.expect_value(t, string(conversation_message_text(1)), "  空白は保持する  ")
	testing.expect(t, !conversation_remember(-1))
	testing.expect(t, !conversation_remember(2))
	assert(conversation_remember(0))
	assert(conversation_remember(0))
	testing.expect_value(t, conversation_memory_count(), 1)
	testing.expect_value(t, string(conversation_memory_text(0)), text)
	testing.expect(t, conversation_message_text(-1) == nil)
	testing.expect(t, conversation_memory_text(1) == nil)

	conversation_close()
	assert(conversation_init(dir_c))
	testing.expect_value(t, conversation_message_count(), 2)
	testing.expect_value(t, conversation_memory_count(), 1)
	testing.expect_value(t, string(conversation_message_text(0)), text)
	testing.expect_value(t, string(conversation_memory_text(0)), text)

	// Replacing the storage directory with a file forces a real save error.
	moved := strings.concatenate({dir, "-moved"})
	defer delete(moved)
	defer os.remove_all(moved)
	assert(os.rename(dir, moved) == nil)
	assert(os.write_entire_file(dir, "blocked") == nil)
	testing.expect(t, !conversation_send("保存されてはいけない"))
	testing.expect(t, !conversation_remember(1))
	testing.expect_value(t, conversation_message_count(), 2)
	testing.expect_value(t, conversation_memory_count(), 1)
	testing.expect(t, len(string(conversation_error())) > 0)
	assert(os.remove(dir) == nil)
	assert(os.rename(moved, dir) == nil)
	conversation_close()
	assert(conversation_init(dir_c))
	testing.expect_value(t, conversation_message_count(), 2)
	testing.expect_value(t, conversation_memory_count(), 1)
	conversation_close()

	file := strings.concatenate({dir, "/notebook.json"})
	defer delete(file)
	bad_files := []string{
		"",
		"{truncated",
		"{\"version\":1,\"messages\":[\"\xff\"],\"memories\":[]}",
		`{"version":1,"messages":["ok"],"memories":[1]}`,
		`{"version":1,"messages":["ok"],"memories":[0,0]}`,
		`{"version":2,"messages":[],"memories":[]}`,
		`{"version":1,"messages":[""],"memories":[]}`,
		`{"version":1,"messages":["\u0000"],"memories":[]}`,
		`{"version":1,"messages":[],"memories":[]} garbage`,
		`{"version":1,"messages":[],"memories":[],"unknown":true}`,
		`{"version":01,"messages":["ok"],"memories":[]}`,
		`{"version":18446744073709551617,"messages":[],"memories":[]}`,
		"{\"version\":1,\"messages\":[],\"memories\":[]}\x00garbage",
		`{"version":1,"messages":[],"memories":[],"":true}`,
		`{"version":1,"messages":["\ud800"],"memories":[]}`,
	}
	for bad in bad_files {
		assert(os.write_entire_file(file, bad) == nil)
		testing.expect(t, !conversation_init(dir_c))
		testing.expect(t, !conversation_send("破損した履歴を上書きしない"))
		testing.expect_value(t, conversation_message_count(), 0)
		original, read_error := os.read_entire_file(file, context.allocator)
		assert(read_error == nil)
		testing.expect_value(t, string(original), bad)
		delete(original)
		conversation_close()
	}
}
