// Тонкая прослойка между Kotlin и llama.cpp: каждая функция — один-два вызова C API.
// Вся логика (кэш промпта, обрезка истории, грамматика, остановка) живёт в Kotlin, как в iOS-версии на Swift.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <atomic>

#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "json.h"
#include "jinja/value.h"
#include "jinja/runtime.h"
#include "prompt_state.h"

#define TAG "LocalChatEngine"

static void log_callback(ggml_log_level level, const char * text, void *) {
    // Подробный журнал загрузки не нужен; ошибки и предупреждения идут в logcat.
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static std::string to_string(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

/// Kotlin-строки в JNI идут в «модифицированном UTF-8», где эмодзи ломаются; поэтому текст передаём байтами.
static std::string to_string(JNIEnv * env, jbyteArray bytes) {
    if (!bytes) return {};
    jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

static jbyteArray to_bytes(JNIEnv * env, const std::string & text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

static void throw_engine(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, message.c_str());
}

static llama_model * as_model(jlong handle) { return reinterpret_cast<llama_model *>(handle); }
static llama_context * as_context(jlong handle) { return reinterpret_cast<llama_context *>(handle); }
static llama_sampler * as_sampler(jlong handle) { return reinterpret_cast<llama_sampler *>(handle); }

extern "C" {

#define JNI(name) Java_alexcattivo_localchat_engine_LlamaNative_##name

JNIEXPORT void JNICALL JNI(initBackend)(JNIEnv * env, jobject, jstring libraryDir) {
    llama_log_set(log_callback, nullptr);
    ggml_backend_load_all_from_path(to_string(env, libraryDir).c_str());
    llama_backend_init();
}

/// Что включено в выбранном варианте процессорного бэкенда (NEON, DOTPROD, MATMUL_INT8, KLEIDIAI…): для замеров в logcat.
JNIEXPORT jstring JNICALL JNI(systemInfo)(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL JNI(loadModel)(JNIEnv * env, jobject, jstring path, jint gpuLayers) {
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = gpuLayers;
    return reinterpret_cast<jlong>(llama_model_load_from_file(to_string(env, path).c_str(), params));
}

JNIEXPORT void JNICALL JNI(freeModel)(JNIEnv *, jobject, jlong model) {
    if (model) llama_model_free(as_model(model));
}

// Stop requested: llama.cpp checks this between the nodes of a graph, so a 512-token batch of the prompt stops within
// a fraction of a second instead of finishing first (tens of seconds deep into a chat on a phone CPU).
static std::atomic<bool> g_abort{false};

static bool abort_requested(void *) { return g_abort.load(std::memory_order_relaxed); }

JNIEXPORT void JNICALL JNI(setAbort)(JNIEnv *, jobject, jboolean value) {
    g_abort.store(value == JNI_TRUE, std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL JNI(newContext)(JNIEnv *, jobject, jlong model, jint contextLength, jint batch, jint threads) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(contextLength);
    params.n_batch = static_cast<uint32_t>(batch);
    params.n_ubatch = static_cast<uint32_t>(batch);
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.abort_callback = abort_requested;
    params.abort_callback_data = nullptr;
    return reinterpret_cast<jlong>(llama_init_from_model(as_model(model), params));
}

JNIEXPORT void JNICALL JNI(freeContext)(JNIEnv *, jobject, jlong context) {
    if (context) llama_free(as_context(context));
}

JNIEXPORT jint JNICALL JNI(contextLength)(JNIEnv *, jobject, jlong context) {
    return static_cast<jint>(llama_n_ctx(as_context(context)));
}

JNIEXPORT jstring JNICALL JNI(modelDescription)(JNIEnv * env, jobject, jlong model) {
    char buffer[256] = {0};
    llama_model_desc(as_model(model), buffer, sizeof(buffer));
    return env->NewStringUTF(buffer);
}

JNIEXPORT jlong JNICALL JNI(parameterCount)(JNIEnv *, jobject, jlong model) {
    return static_cast<jlong>(llama_model_n_params(as_model(model)));
}

JNIEXPORT jint JNICALL JNI(trainedContextLength)(JNIEnv *, jobject, jlong model) {
    return llama_model_n_ctx_train(as_model(model));
}

JNIEXPORT jbyteArray JNICALL JNI(chatTemplate)(JNIEnv * env, jobject, jlong model) {
    const char * source = llama_model_chat_template(as_model(model), nullptr);
    return source ? to_bytes(env, source) : nullptr;
}

JNIEXPORT jbyteArray JNICALL JNI(tokenText)(JNIEnv * env, jobject, jlong model, jint token) {
    const llama_vocab * vocab = llama_model_get_vocab(as_model(model));
    if (token < 0) return to_bytes(env, "");
    const char * text = llama_vocab_get_text(vocab, token);
    return to_bytes(env, text ? text : "");
}

JNIEXPORT jint JNICALL JNI(bosToken)(JNIEnv *, jobject, jlong model) {
    return llama_vocab_bos(llama_model_get_vocab(as_model(model)));
}

JNIEXPORT jint JNICALL JNI(eosToken)(JNIEnv *, jobject, jlong model) {
    return llama_vocab_eos(llama_model_get_vocab(as_model(model)));
}

JNIEXPORT jboolean JNICALL JNI(isEndOfGeneration)(JNIEnv *, jobject, jlong model, jint token) {
    return llama_vocab_is_eog(llama_model_get_vocab(as_model(model)), token);
}

JNIEXPORT jintArray JNICALL JNI(tokenize)(JNIEnv * env, jobject, jlong model, jbyteArray textBytes, jboolean addSpecial, jboolean parseSpecial) {
    const llama_vocab * vocab = llama_model_get_vocab(as_model(model));
    std::string text = to_string(env, textBytes);
    std::vector<llama_token> tokens(text.size() + 16);
    int32_t count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                                   static_cast<int32_t>(tokens.size()), addSpecial, parseSpecial);
    if (count < 0 && count != INT32_MIN) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), addSpecial, parseSpecial);
    }
    if (count < 0) return nullptr;
    jintArray out = env->NewIntArray(count);
    env->SetIntArrayRegion(out, 0, count, tokens.data());
    return out;
}

JNIEXPORT jbyteArray JNICALL JNI(tokenPiece)(JNIEnv * env, jobject, jlong model, jint token) {
    const llama_vocab * vocab = llama_model_get_vocab(as_model(model));
    std::vector<char> buffer(64);
    int32_t length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    return to_bytes(env, length > 0 ? std::string(buffer.data(), static_cast<size_t>(length)) : std::string());
}

JNIEXPORT jint JNICALL JNI(decode)(JNIEnv * env, jobject, jlong context, jintArray tokens, jint offset, jint count) {
    std::vector<llama_token> batch(static_cast<size_t>(count));
    env->GetIntArrayRegion(tokens, offset, count, batch.data());
    return llama_decode(as_context(context), llama_batch_get_one(batch.data(), count));
}

JNIEXPORT void JNICALL JNI(memoryClear)(JNIEnv *, jobject, jlong context) {
    llama_memory_clear(llama_get_memory(as_context(context)), true);
}

JNIEXPORT jboolean JNICALL JNI(memoryRemoveFrom)(JNIEnv *, jobject, jlong context, jint position) {
    return llama_memory_seq_rm(llama_get_memory(as_context(context)), 0, position, -1);
}

/// Сохраняет кэш (в нём ровно `tokens`) в файл; формат и причины — в prompt_state.h.
JNIEXPORT jboolean JNICALL JNI(stateSave)(JNIEnv * env, jobject, jlong context, jstring path, jintArray tokens) {
    std::vector<llama_token> list(static_cast<size_t>(env->GetArrayLength(tokens)));
    env->GetIntArrayRegion(tokens, 0, static_cast<jsize>(list.size()), list.data());
    return prompt_state::save(as_context(context), to_string(env, path).c_str(), list.data(), list.size());
}

/// Загружает сохранённый кэш, только если файл целый и сохранён ровно для `tokens`.
JNIEXPORT jboolean JNICALL JNI(stateLoad)(JNIEnv * env, jobject, jlong context, jstring path, jintArray tokens) {
    std::vector<llama_token> list(static_cast<size_t>(env->GetArrayLength(tokens)));
    env->GetIntArrayRegion(tokens, 0, static_cast<jsize>(list.size()), list.data());
    return prompt_state::load(as_context(context), to_string(env, path).c_str(), list.data(), list.size());
}

JNIEXPORT jlong JNICALL JNI(newSampler)(JNIEnv * env, jobject, jlong model, jfloat temperature, jfloat topP, jint topK,
                                        jint seed, jbyteArray grammarBytes, jobjectArray triggers) {
    llama_sampler * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Грамматика первой в цепочке: она отсекает всё, что запрещает структура. До триггера она ничего не делает.
    if (grammarBytes && triggers && env->GetArrayLength(triggers) > 0) {
        std::string grammar = to_string(env, grammarBytes);
        std::vector<std::string> patterns;
        for (jsize i = 0; i < env->GetArrayLength(triggers); i++) {
            auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(triggers, i));
            patterns.push_back(to_string(env, item));
            env->DeleteLocalRef(item);
        }
        std::vector<const char *> pointers;
        for (auto & pattern : patterns) pointers.push_back(pattern.c_str());
        llama_sampler * lazy = llama_sampler_init_grammar_lazy_patterns(
            llama_model_get_vocab(as_model(model)), grammar.c_str(), "triggered-root",
            pointers.data(), pointers.size(), nullptr, 0);
        if (lazy) llama_sampler_chain_add(chain, lazy);
        else __android_log_print(ANDROID_LOG_WARN, TAG, "грамматика вызова инструмента не разобралась, работаю без неё");
    }
    if (temperature <= 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }
    return reinterpret_cast<jlong>(chain);
}

JNIEXPORT jint JNICALL JNI(sample)(JNIEnv *, jobject, jlong sampler, jlong context) {
    return llama_sampler_sample(as_sampler(sampler), as_context(context), -1);
}

JNIEXPORT void JNICALL JNI(freeSampler)(JNIEnv *, jobject, jlong sampler) {
    if (sampler) llama_sampler_free(as_sampler(sampler));
}

// MARK: - Эмбеддинги

JNIEXPORT jlong JNICALL JNI(newEmbeddingContext)(JNIEnv *, jobject, jlong model, jint batchTokens, jint maxSequences, jint threads) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(batchTokens);
    // Для моделей с двунаправленным вниманием весь текст должен попасть в один физический пакет.
    params.n_batch = static_cast<uint32_t>(batchTokens);
    params.n_ubatch = static_cast<uint32_t>(batchTokens);
    params.n_seq_max = static_cast<uint32_t>(maxSequences);
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.embeddings = true;
    params.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;
    return reinterpret_cast<jlong>(llama_init_from_model(as_model(model), params));
}

JNIEXPORT jint JNICALL JNI(embeddingDimension)(JNIEnv *, jobject, jlong model) {
    return llama_model_n_embd(as_model(model));
}

/// Один пакет текстов (каждый — своя последовательность). Возвращает векторы подряд, по `dimension` чисел на текст.
JNIEXPORT jfloatArray JNICALL JNI(embedBatch)(JNIEnv * env, jobject, jlong model, jlong context, jintArray tokens, jintArray lengths) {
    auto * ctx = as_context(context);
    jsize total = env->GetArrayLength(tokens);
    jsize sequences = env->GetArrayLength(lengths);
    std::vector<jint> flat(static_cast<size_t>(total));
    std::vector<jint> sizes(static_cast<size_t>(sequences));
    env->GetIntArrayRegion(tokens, 0, total, flat.data());
    env->GetIntArrayRegion(lengths, 0, sequences, sizes.data());

    llama_batch batch = llama_batch_init(total, 0, 1);
    int position = 0;
    for (int sequence = 0; sequence < sequences; sequence++) {
        for (int offset = 0; offset < sizes[sequence]; offset++) {
            batch.token[position] = flat[position];
            batch.pos[position] = offset;
            batch.n_seq_id[position] = 1;
            batch.seq_id[position][0] = sequence;
            batch.logits[position] = 1;
            position++;
        }
    }
    batch.n_tokens = total;

    llama_memory_clear(llama_get_memory(ctx), true);
    const llama_model * m = as_model(model);
    bool encoderOnly = llama_model_has_encoder(m) && !llama_model_has_decoder(m);
    int code = encoderOnly ? llama_encode(ctx, batch) : llama_decode(ctx, batch);
    if (code != 0) {
        llama_batch_free(batch);
        throw_engine(env, "embedding decode failed: " + std::to_string(code));
        return nullptr;
    }
    int dimension = llama_model_n_embd(m);
    jfloatArray out = env->NewFloatArray(sequences * dimension);
    for (int sequence = 0; sequence < sequences; sequence++) {
        const float * vector = llama_get_embeddings_seq(ctx, sequence);
        if (!vector) {
            llama_batch_free(batch);
            throw_engine(env, "no embeddings for sequence");
            return nullptr;
        }
        env->SetFloatArrayRegion(out, sequence * dimension, dimension, vector);
    }
    llama_batch_free(batch);
    return out;
}

// MARK: - Шаблон чата

JNIEXPORT jlong JNICALL JNI(newTemplate)(JNIEnv * env, jobject, jbyteArray source, jbyteArray bos, jbyteArray eos) {
    try {
        auto * tmpl = new common_chat_template(to_string(env, source), to_string(env, bos), to_string(env, eos));
        return reinterpret_cast<jlong>(tmpl);
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "шаблон чата не разобрался: %s", error.what());
        return 0;
    }
}

JNIEXPORT void JNICALL JNI(freeTemplate)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<common_chat_template *>(handle);
}

/// Исполняет шаблон с теми же переменными, что iOS-версия передаёт swift-jinja:
/// messages, add_generation_prompt, enable_thinking, bos_token, eos_token. Переменные приходят одной строкой JSON.
JNIEXPORT jbyteArray JNICALL JNI(renderTemplate)(JNIEnv * env, jobject, jlong handle, jbyteArray variablesJson) {
    auto * tmpl = reinterpret_cast<common_chat_template *>(handle);
    try {
        common_json variables = common_json::parse(to_string(env, variablesJson));
        jinja::context ctx(tmpl->source());
        jinja::global_from_json(ctx, variables, false);
        ctx.set_val("bos_token", jinja::mk_val<jinja::value_string>(tmpl->bos_token()));
        ctx.set_val("eos_token", jinja::mk_val<jinja::value_string>(tmpl->eos_token()));
        jinja::runtime runtime(ctx);
        std::string out = jinja::runtime::gather_string_parts(runtime.execute(tmpl->prog))->as_string().str();
        return to_bytes(env, out);
    } catch (const std::exception & error) {
        throw_engine(env, std::string("template: ") + error.what());
        return nullptr;
    }
}

} // extern "C"
