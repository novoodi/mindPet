// JNI bridge for Kev on llama.cpp: load a GGUF backbone, tokenize with its built-in vocab, and return the last
// hidden states at the requested positions (pooling none). The PointerHead runs in Kotlin (KevClassifier).
#include <jni.h>
#include <android/log.h>
#include <vector>
#include "llama.h"

#define LOG_TAG "KevNative"
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Kev {
    llama_model * model;
    llama_context * ctx;
    const llama_vocab * vocab;
    int n_embd;
    int n_ctx;
};

Kev * handle(jlong h) { return reinterpret_cast<Kev *>(h); }

// Errors, plus the load-time lines that say where memory goes (buffer sizes, repacking).
void forward_log(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    else if (strstr(text, "buffer size") || strstr(text, "REPACK") || strstr(text, "repack")) LOGI("%s", text);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_maeumpet_app_kev_KevNative_load(JNIEnv * env, jclass, jstring jpath, jint n_threads, jint n_ctx, jint n_ubatch,
                                         jint load_mode, jboolean repack) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(forward_log, nullptr);
        llama_backend_init();
        backend_ready = true;
    }
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = static_cast<llama_load_mode>(load_mode);
    mp.use_extra_bufts = repack;   // CPU weight repacking (i8mm/dotprod layouts)
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = n_ctx;
    cp.n_ubatch = n_ubatch;   // compute buffer ~ n_ubatch x n_vocab x 4 B (248k vocab: 1 MB per token)
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_NONE;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    return reinterpret_cast<jlong>(new Kev{model, ctx, llama_model_get_vocab(model), llama_model_n_embd(model), n_ctx});
}

extern "C" JNIEXPORT void JNICALL
Java_com_maeumpet_app_kev_KevNative_free(JNIEnv *, jclass, jlong h) {
    Kev * k = handle(h);
    if (!k) return;
    llama_free(k->ctx);
    llama_model_free(k->model);
    delete k;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maeumpet_app_kev_KevNative_embdDim(JNIEnv *, jclass, jlong h) {
    return handle(h)->n_embd;
}

// utf8: the text as real UTF-8 bytes (JNI's modified UTF-8 would mangle emoji).
extern "C" JNIEXPORT jintArray JNICALL
Java_com_maeumpet_app_kev_KevNative_tokenize(JNIEnv * env, jclass, jlong h, jbyteArray utf8, jboolean parse_special) {
    Kev * k = handle(h);
    const jsize len = env->GetArrayLength(utf8);
    std::vector<char> text(len);
    env->GetByteArrayRegion(utf8, 0, len, reinterpret_cast<jbyte *>(text.data()));
    std::vector<llama_token> out(len + 8);
    int n = llama_tokenize(k->vocab, text.data(), len, out.data(), (int) out.size(), false, parse_special);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(k->vocab, text.data(), len, out.data(), (int) out.size(), false, parse_special);
    }
    jintArray result = env->NewIntArray(n);
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint *>(out.data()));
    return result;
}

// Runs ids as one fresh causal row (positions 0..n-1) and returns the hidden states at `read` positions,
// concatenated: read.length * n_embd floats. Returns null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_maeumpet_app_kev_KevNative_hidden(JNIEnv * env, jclass, jlong h, jintArray jids, jintArray jread) {
    Kev * k = handle(h);
    const jsize n = env->GetArrayLength(jids);
    const jsize n_read = env->GetArrayLength(jread);
    if (n == 0 || n > k->n_ctx) return nullptr;
    std::vector<jint> ids(n), read(n_read);
    env->GetIntArrayRegion(jids, 0, n, ids.data());
    env->GetIntArrayRegion(jread, 0, n_read, read.data());

    llama_memory_clear(llama_get_memory(k->ctx), true);
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i] = ids[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;
    }
    for (jint r : read) {
        if (r < 0 || r >= n) { llama_batch_free(batch); return nullptr; }
        batch.logits[r] = 1;
    }
    batch.n_tokens = n;
    const int rc = llama_decode(k->ctx, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        LOGE("llama_decode failed: %d", rc);
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(n_read * k->n_embd);
    for (int j = 0; j < n_read; j++) {
        const float * e = llama_get_embeddings_ith(k->ctx, read[j]);
        if (!e) return nullptr;
        env->SetFloatArrayRegion(result, j * k->n_embd, k->n_embd, e);
    }
    return result;
}
