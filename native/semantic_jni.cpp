#include "semantic_index.h"
#include <sentencepiece_processor.h>
#include <sentencepiece_model.pb.h>
#include <jni.h>
#include <memory>
#include <string>

namespace {
void failure(JNIEnv* env) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "意味検索の処理に失敗しました。");
}
std::string bytes(JNIEnv* env, jbyteArray input, int maximum) {
    if (!input || env->GetArrayLength(input) > maximum) throw std::runtime_error("Invalid input size");
    std::string value(static_cast<std::size_t>(env->GetArrayLength(input)), '\0');
    env->GetByteArrayRegion(input, 0, static_cast<jsize>(value.size()), reinterpret_cast<jbyte*>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read input");
    return value;
}
std::vector<float> vector(JNIEnv* env, jfloatArray input) {
    if (!input || env->GetArrayLength(input) != 256) throw std::runtime_error("Invalid vector dimensions");
    std::vector<float> result(256);
    env->GetFloatArrayRegion(input, 0, 256, result.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read embedding");
    return result;
}
template<class T> T* handle(jlong value) {
    if (!value) throw std::runtime_error("Closed semantic engine");
    return reinterpret_cast<T*>(value);
}
jlongArray ids(JNIEnv* env, const std::vector<std::int64_t>& values) {
    std::vector<jlong> converted(values.begin(), values.end());
    auto result = env->NewLongArray(static_cast<jsize>(converted.size()));
    if (result && !converted.empty()) env->SetLongArrayRegion(result, 0, static_cast<jsize>(converted.size()), converted.data());
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_jp_tekewo_conversation_NativeRag_openTokenizer(JNIEnv* env, jclass, jbyteArray input) {
    try {
        auto model = bytes(env, input, 4 * 1024 * 1024);
        sentencepiece::ModelProto proto;
        if (!proto.ParseFromString(model)) throw std::runtime_error("Invalid tokenizer");
        // Match the model's non-legacy LlamaTokenizer configuration.
        proto.mutable_normalizer_spec()->set_add_dummy_prefix(false);
        auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
        if (!processor->Load(proto).ok()) throw std::runtime_error("Could not load tokenizer");
        return reinterpret_cast<jlong>(processor.release());
    } catch (const std::exception&) { failure(env); return 0; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_jp_tekewo_conversation_NativeRag_tokenize(JNIEnv* env, jclass, jlong tokenizer, jbyteArray input) {
    try {
        auto text = bytes(env, input, 262144);
        auto tokens = handle<sentencepiece::SentencePieceProcessor>(tokenizer)->EncodeAsIds(text);
        std::vector<std::int64_t> result{1};
        // shortcut: embed at most 256 tokens; add chunking if long-memory recall needs later passages.
        for (std::size_t i = 0; i < tokens.size() && i < 254; ++i) result.push_back(tokens[i]);
        result.push_back(2);
        return ids(env, result);
    } catch (const std::exception&) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_NativeRag_closeTokenizer(JNIEnv*, jclass, jlong tokenizer) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(tokenizer);
}
extern "C" JNIEXPORT jlong JNICALL
Java_jp_tekewo_conversation_NativeRag_openIndex(JNIEnv* env, jclass) {
    try { return reinterpret_cast<jlong>(new SemanticIndex()); }
    catch (const std::exception&) { failure(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_NativeRag_put(JNIEnv* env, jclass, jlong index, jlong id, jfloatArray input) {
    try { handle<SemanticIndex>(index)->put(id, vector(env, input)); }
    catch (const std::exception&) { failure(env); }
}
extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_NativeRag_remove(JNIEnv* env, jclass, jlong index, jlong id) {
    try { handle<SemanticIndex>(index)->erase(id); }
    catch (const std::exception&) { failure(env); }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_jp_tekewo_conversation_NativeRag_search(JNIEnv* env, jclass, jlong index, jfloatArray input) {
    try { return ids(env, handle<SemanticIndex>(index)->search(vector(env, input))); }
    catch (const std::exception&) { failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_NativeRag_closeIndex(JNIEnv*, jclass, jlong index) {
    delete reinterpret_cast<SemanticIndex*>(index);
}
