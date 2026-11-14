#include "../native/conversation_core.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
    assert(argc == 3 && sizeof(bool) == 1);
    assert(!conversation_init(NULL));
    assert(conversation_init(argv[1]));
    if (!strcmp(argv[2], "write")) {
        assert(conversation_add_message("user", "こんにちは 🎾") == 1);
        assert(conversation_add_reply("覚えました", "{\"emotion\":\"嬉しい\",\"reason\":\"話してくれた\",\"event\":\"挨拶した会話\",\"scope\":\"shared\"}") == 2);
        assert(strstr(conversation_pending_job(), "こんにちは"));
        assert(!conversation_apply_job(1, "{\"memories\":[{\"kind\":\"fact\",\"subject\":\"user\",\"key\":\"sport\",\"text\":\"tennis\",\"scope\":\"real\",\"certainty\":\"explicit\",\"source_ids\":[99]}]}"));
        assert(conversation_apply_job(1, "{\"memories\":[{\"kind\":\"fact\",\"subject\":\"user\",\"key\":\"sport\",\"text\":\"tennis\",\"scope\":\"real\",\"certainty\":\"explicit\",\"source_ids\":[1]}]}"));
        assert(conversation_apply_job(1, "{\"memories\":[]}"));
    }
    assert(conversation_message_count() == 2);
    assert(!strcmp(conversation_assistant_emotion(), "嬉しい"));
    assert(conversation_load_window(0, 80));
    assert(conversation_message_id(0) == 1);
    assert(!strcmp(conversation_message_role(0), "user"));
    assert(!strcmp(conversation_message_text(0), "こんにちは 🎾"));
    assert(strstr(conversation_memories("tennis"), "tennis"));
    assert(strstr(conversation_context("tennis"), "tennis"));
    assert(!strcmp(conversation_pending_job(), "{}"));
    assert(!strcmp(conversation_pending_persona(), "{}"));
    assert(!conversation_apply_persona(0, "{\"changes\":[]}"));
    conversation_close();
    puts("C ABI durable roundtrip passed");
    return 0;
}
