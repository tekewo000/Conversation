#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Serialize all calls. Returned strings are borrowed until the next mutation.
// init opens an app-private directory; false means no history can be modified.
bool conversation_init(const char* storage_dir);
bool conversation_send(const char* utf8);
int32_t conversation_message_count(void);
// Invalid indices return NULL. Text preserves the original UTF-8 and whitespace.
const char* conversation_message_text(int32_t index);
// Repeatedly pinning the same message is a successful no-op.
bool conversation_remember(int32_t index);
int32_t conversation_memory_count(void);
const char* conversation_memory_text(int32_t index);
// Empty string after success; human-readable error after a failed mutation.
const char* conversation_error(void);
void conversation_close(void);

#ifdef __cplusplus
}
#endif
