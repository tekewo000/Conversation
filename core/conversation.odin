package conversation

import "base:runtime"
import "core:encoding/json"
import "core:mem"
import "core:os"
import "core:strings"
import "core:unicode/utf8"

Snapshot :: struct {
	version:  i64,
	messages: []string,
	memories: []i32,
}

directory: string
history_path: string
messages: [dynamic]cstring
memories: [dynamic]i32
ready: bool
last_error: cstring = ""
MAX_MESSAGE_BYTES :: 65536

Allocation_Check :: struct {
	backing: mem.Allocator,
	failed: bool,
}

checked_allocation :: proc(data: rawptr, mode: mem.Allocator_Mode, size, alignment: int,
                          old_memory: rawptr, old_size: int, location := #caller_location) -> ([]byte, mem.Allocator_Error) {
	check := (^Allocation_Check)(data)
	bytes, error := check.backing.procedure(check.backing.data, mode, size, alignment, old_memory, old_size, location)
	allocating := mode == .Alloc || mode == .Alloc_Non_Zeroed || mode == .Resize || mode == .Resize_Non_Zeroed
	if allocating && size > 0 && (error != nil || bytes == nil) {
		check.failed = true
		if error == nil do error = .Out_Of_Memory
	}
	return bytes, error
}

fail :: proc(message: cstring) -> bool {
	last_error = message
	return false
}

valid_text :: proc(text: string) -> bool {
	return len(text) <= MAX_MESSAGE_BYTES && utf8.valid_string(text) && !strings.contains_rune(text, 0) && len(strings.trim_space(text)) > 0
}

release_state :: proc() {
	for text in messages do delete(text)
	delete(messages)
	delete(memories)
	delete(directory)
	delete(history_path)
	messages = nil
	memories = nil
	directory = ""
	history_path = ""
	ready = false
}

load_snapshot :: proc(bytes: []byte) -> bool {
	validation := json.make_parser(bytes, .JSON, true)
	if !snapshot_tokens_valid(bytes) || !json.validate_value(&validation) || validation.curr_token.kind != .EOF {
		return fail("History is malformed; the original file was preserved.")
	}
	allocations := Allocation_Check{backing = context.allocator}
	root, parse_error := json.parse(bytes, .JSON, true, {checked_allocation, &allocations})
	if parse_error != nil do return fail("History could not be parsed; the original file was preserved.")
	defer json.destroy_value(root, {checked_allocation, &allocations})
	// The JSON library can ignore an internal append failure.
	if allocations.failed do return fail("Not enough memory to open history.")
	object, is_object := root.(json.Object)
	if !is_object || len(object) != 3 do return fail("History has an unsupported format.")
	version, has_version := object["version"].(json.Integer)
	texts, has_messages := object["messages"].(json.Array)
	pins, has_memories := object["memories"].(json.Array)
	if !has_version || version != 1 || !has_messages || !has_memories {
		return fail("History has an unsupported format.")
	}
	if len(texts) > 0x7fffffff do return fail("History contains too many messages.")
	for value in texts {
		text, is_string := value.(json.String)
		if !is_string || !valid_text(string(text)) do return fail("History contains an invalid message.")
	}
	for value, i in pins {
		index, is_integer := value.(json.Integer)
		if !is_integer || index < 0 || index >= i64(len(texts)) do return fail("History contains an invalid memory.")
		for previous in pins[:i] {
			if previous.(json.Integer) == index do return fail("History contains a duplicate memory.")
		}
	}
	if reserve(&messages, len(texts)) != nil || reserve(&memories, len(pins)) != nil {
		return fail("Not enough memory to open history.")
	}
	for value in texts {
		text, allocation_error := strings.clone_to_cstring(string(value.(json.String)))
		if allocation_error != nil {
			for loaded in messages do delete(loaded)
			clear(&messages)
			return fail("Not enough memory to open history.")
		}
		append(&messages, text)
	}
	for value in pins do append(&memories, i32(value.(json.Integer)))
	return true
}

save_snapshot :: proc(new_message: cstring = nil, new_memory: i32 = -1) -> bool {
	texts, texts_error := make([]string, len(messages) + int(new_message != nil))
	if texts_error != nil || len(texts) != len(messages) + int(new_message != nil) do return fail("Not enough memory to save history.")
	defer delete(texts)
	for text, i in messages do texts[i] = string(text)
	if new_message != nil do texts[len(messages)] = string(new_message)
	pins, pins_error := make([]i32, len(memories) + int(new_memory >= 0))
	if pins_error != nil || len(pins) != len(memories) + int(new_memory >= 0) do return fail("Not enough memory to save history.")
	defer delete(pins)
	copy(pins, memories[:])
	if new_memory >= 0 do pins[len(memories)] = new_memory
	allocations := Allocation_Check{backing = context.allocator}
	bytes, encode_error := json.marshal(Snapshot{1, texts, pins}, allocator = {checked_allocation, &allocations})
	defer delete(bytes)
	if encode_error != nil || allocations.failed do return fail("Could not encode history.")

	temporary, temporary_error := strings.concatenate({directory, "/notebook.json.tmp"})
	if temporary_error != nil || len(temporary) == 0 do return fail("Not enough memory to save history.")
	defer delete(temporary)
	file, open_error := os.open(temporary, {.Write, .Create, .Trunc})
	if open_error != nil do return fail("Could not create a history file. Check available storage.")
	defer os.remove(temporary)
	n, write_error := os.write(file, bytes)
	if write_error != nil || n != len(bytes) {
		os.close(file)
		return fail("Could not write history. Your change was not saved.")
	}
	sync_error := os.sync(file)
	close_error := os.close(file)
	if sync_error != nil || close_error != nil do return fail("Could not finish saving history. Your change was not saved.")
	// shortcut: the directory entry is not fsynced; add commit recovery before requiring sudden-power-loss durability.
	if os.rename(temporary, history_path) != nil do return fail("Could not replace history. Your change was not saved.")
	return true
}

@(export)
conversation_init :: proc "c" (storage_dir: cstring) -> bool {
	context = runtime.default_context()
	return open_notebook(storage_dir)
}

open_notebook :: proc(storage_dir: cstring) -> bool {
	release_state()
	last_error = ""
	if storage_dir == nil || !valid_text(string(storage_dir)) do return fail("A valid storage directory is required.")
	allocation_error: mem.Allocator_Error
	directory, allocation_error = strings.clone(string(storage_dir))
	if allocation_error != nil || len(directory) == 0 do return fail("Not enough memory to open history.")
	history_path, allocation_error = strings.concatenate({directory, "/notebook.json"})
	if allocation_error != nil || len(history_path) == 0 {
		release_state()
		return fail("Not enough memory to open history.")
	}
	// Android allows the app's directory but may deny reading its /data ancestors.
	info, directory_error := os.stat(directory, context.allocator)
	if directory_error == .Not_Exist {
		create_error := os.make_directory_all(directory)
		if create_error != nil && create_error != .Exist {
			release_state()
			return fail("Could not create the storage directory.")
		}
		info, directory_error = os.stat(directory, context.allocator)
	}
	defer os.file_info_delete(info, context.allocator)
	if directory_error != nil || info.type != .Directory {
		release_state()
		return fail("Could not open the storage directory.")
	}
	bytes, read_error := os.read_entire_file(history_path, context.allocator)
	defer delete(bytes)
	if read_error != nil && read_error != .Not_Exist {
		release_state()
		return fail("Could not read history; the original file was preserved.")
	}
	if read_error == nil && !load_snapshot(bytes) {
		release_state()
		return false
	}
	ready = true
	return true
}
@(export)
conversation_send :: proc "c" (text: cstring) -> bool {
	context = runtime.default_context()
	return send_message(text)
}

send_message :: proc(text: cstring) -> bool {
	if !ready do return fail("History is not open.")
	if text == nil || !valid_text(string(text)) do return fail("空白以外のUTF-8テキストを64 KiB以内で入力してください。")
	if len(messages) >= 0x7fffffff do return fail("History contains too many messages.")
	if reserve(&messages, len(messages) + 1) != nil do return fail("Not enough memory to save the message.")
	copy, allocation_error := strings.clone_to_cstring(string(text))
	if allocation_error != nil do return fail("Not enough memory to save the message.")
	if !save_snapshot(new_message = copy) {
		delete(copy)
		return false
	}
	append(&messages, copy)
	last_error = ""
	return true
}
@(export)
conversation_message_count :: proc "c" () -> i32 { return i32(len(messages)) }
@(export)
conversation_message_text :: proc "c" (index: i32) -> cstring {
	if index < 0 || int(index) >= len(messages) do return nil
	return messages[index]
}
@(export)
conversation_remember :: proc "c" (index: i32) -> bool {
	context = runtime.default_context()
	return remember_message(index)
}

remember_message :: proc(index: i32) -> bool {
	if !ready do return fail("History is not open.")
	if index < 0 || int(index) >= len(messages) do return fail("That message does not exist.")
	for pin in memories {
		if pin == index {
			last_error = ""
			return true
		}
	}
	if reserve(&memories, len(memories) + 1) != nil do return fail("Not enough memory to save the memory.")
	if !save_snapshot(new_memory = index) do return false
	append(&memories, index)
	last_error = ""
	return true
}
@(export)
conversation_memory_count :: proc "c" () -> i32 { return i32(len(memories)) }
@(export)
conversation_memory_text :: proc "c" (index: i32) -> cstring {
	if index < 0 || int(index) >= len(memories) do return nil
	return messages[memories[index]]
}
@(export)
conversation_error :: proc "c" () -> cstring { return last_error }
@(export)
conversation_close :: proc "c" () {
	context = runtime.default_context()
	release_state()
	last_error = ""
}
