#include "../native/conversation_core.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(int argc, char** argv) {
    assert(argc == 3);
    assert(sizeof(bool) == 1);
    assert(!conversation_init(NULL));
    assert(!conversation_send("closed"));
    assert(conversation_message_count() == 0);
    assert(strlen(conversation_error()) > 0);
    assert(conversation_init(argv[1]));
    const char* text = "こんにちは\n\"UTF-8\" 🎾";
    if (strcmp(argv[2], "write") == 0) {
        assert(conversation_message_count() == 0);
        assert(conversation_send(text));
        assert(conversation_remember(0));
    }
    assert(conversation_message_count() == 1);
    assert(strcmp(conversation_message_text(0), text) == 0);
    assert(conversation_memory_count() == 1);
    assert(strcmp(conversation_memory_text(0), text) == 0);
    assert(strcmp(conversation_error(), "") == 0);
    conversation_close();
    assert(!conversation_send("closed"));
    assert(conversation_init(argv[1]));
    assert(conversation_message_count() == 1);
    assert(strcmp(conversation_memory_text(0), text) == 0);
    conversation_close();
    conversation_close();
    puts("C ABI roundtrip passed");
    return 0;
}
