package conversation

import "core:mem"
import "core:os"
import "core:strings"
import "core:testing"

Failing_Allocation :: struct {
	backing: mem.Allocator,
	fail_at: int,
	calls: int,
	failed: bool,
}

fail_one_allocation :: proc(data: rawptr, mode: mem.Allocator_Mode, size, alignment: int,
                           old_memory: rawptr, old_size: int, location := #caller_location) -> ([]byte, mem.Allocator_Error) {
	failure := (^Failing_Allocation)(data)
	allocating := mode == .Alloc || mode == .Alloc_Non_Zeroed || mode == .Resize || mode == .Resize_Non_Zeroed
	if allocating && size > 0 {
		failure.calls += 1
		if failure.calls == failure.fail_at {
			failure.failed = true
			return nil, .Out_Of_Memory
		}
	}
	return failure.backing.procedure(failure.backing.data, mode, size, alignment, old_memory, old_size, location)
}

@(test)
allocation_failure_cannot_commit_an_invisible_message_or_memory :: proc(t: ^testing.T) {
	dir, dir_error := os.make_directory_temp("", "conversation-allocation-*", context.allocator)
	assert(dir_error == nil)
	defer delete(dir)
	defer os.remove_all(dir)
	dir_c := strings.clone_to_cstring(dir)
	defer delete(dir_c)
	defer conversation_close()
	assert(conversation_init(dir_c))

	messages.allocator = mem.nil_allocator()
	testing.expect(t, !conversation_send("first"))
	testing.expect_value(t, conversation_message_count(), 0)
	testing.expect(t, !os.exists(history_path))
	messages.allocator = context.allocator
	assert(conversation_send("second"))
	testing.expect_value(t, conversation_message_count(), 1)
	before, before_error := os.read_entire_file(history_path, context.allocator)
	assert(before_error == nil)
	defer delete(before)

	memories.allocator = mem.nil_allocator()
	testing.expect(t, !conversation_remember(0))
	testing.expect_value(t, conversation_memory_count(), 0)
	after, after_error := os.read_entire_file(history_path, context.allocator)
	assert(after_error == nil)
	defer delete(after)
	testing.expect_value(t, string(after), string(before))
	memories.allocator = context.allocator
	assert(conversation_remember(0))
	conversation_close()
	assert(conversation_init(dir_c))
	testing.expect_value(t, conversation_message_count(), 1)
	testing.expect_value(t, string(conversation_message_text(0)), "second")
	testing.expect_value(t, conversation_memory_count(), 1)
}

@(test)
snapshot_workspace_allocation_failure_keeps_the_previous_file :: proc(t: ^testing.T) {
	dir, dir_error := os.make_directory_temp("", "conversation-workspace-*", context.allocator)
	assert(dir_error == nil)
	defer delete(dir)
	defer os.remove_all(dir)
	dir_c := strings.clone_to_cstring(dir)
	defer delete(dir_c)
	defer conversation_close()
	assert(conversation_init(dir_c))
	assert(conversation_send("saved"))
	before, read_error := os.read_entire_file(history_path, context.allocator)
	assert(read_error == nil)
	defer delete(before)
	allocator := context.allocator
	context.allocator = mem.nil_allocator()
	saved := save_snapshot(new_message = "unsaved")
	context.allocator = allocator
	testing.expect(t, !saved)
	after, after_error := os.read_entire_file(history_path, context.allocator)
	assert(after_error == nil)
	defer delete(after)
	testing.expect_value(t, string(after), string(before))
}

@(test)
load_capacity_failure_cannot_expose_partial_history :: proc(t: ^testing.T) {
	conversation_close()
	defer release_state()
	messages.allocator = mem.nil_allocator()
	snapshot: string : `{"version":1,"messages":["first","second"],"memories":[0]}`
	testing.expect(t, !load_snapshot(transmute([]byte)snapshot))
	testing.expect_value(t, conversation_message_count(), 0)
	testing.expect_value(t, conversation_memory_count(), 0)
}

@(test)
each_open_send_and_pin_allocation_failure_preserves_history :: proc(t: ^testing.T) {
	conversation_close()
	dir, dir_error := os.make_directory_temp("", "conversation-fail-each-*", context.allocator)
	assert(dir_error == nil)
	defer delete(dir)
	defer os.remove_all(dir)
	dir_c := strings.clone_to_cstring(dir)
	defer delete(dir_c)
	file := strings.concatenate({dir, "/notebook.json"})
	defer delete(file)
	snapshot: string : `{"version":1,"messages":["saved"],"memories":[]}`
	backing := context.allocator
	for operation in 0..<3 {
		for nth in 1..=64 {
			assert(os.write_entire_file(file, snapshot) == nil)
			if operation != 0 do assert(open_notebook(dir_c))
			failure := Failing_Allocation{backing = backing, fail_at = nth}
			context.allocator = {fail_one_allocation, &failure}
			ok: bool
			switch operation {
			case 0: ok = open_notebook(dir_c)
			case 1: ok = send_message("candidate")
			case 2: ok = remember_message(0)
			}
			context.allocator = backing
			if failure.failed {
				testing.expectf(t, !ok, "operation %d reported success after allocation %d failed", operation, nth)
				testing.expect_value(t, conversation_message_count(), 0 if operation == 0 else 1)
				testing.expect_value(t, conversation_memory_count(), 0)
				after, read_error := os.read_entire_file(file, backing)
				assert(read_error == nil)
				testing.expect_value(t, string(after), snapshot)
				delete(after)
			} else {
				testing.expect(t, ok)
				testing.expect_value(t, conversation_message_count(), 2 if operation == 1 else 1)
				testing.expect_value(t, conversation_memory_count(), 1 if operation == 2 else 0)
			}
			release_state()
		}
	}
}
