// JNI glue for io.github.yinvoker.foxlet.NativeBridge.
// Thin by design: batch in, batch out, blocking from the caller's view.
//
// Two execution modes behind one handle, chosen once at createService():
//   workers <= 1 -> BlockingService. The batch is translated on the *calling*
//     thread, which is the Kotlin engine thread and the only thread allowed to
//     touch this handle. No dispatch, no worker replicas, byte-identical output
//     across processes.
//   workers >= 2 -> AsyncService, for intra-batch parallelism on large batches.
// Either way the Kotlin layer owns threading policy, lifecycle and cancellation,
// and one engine has exactly one service plus one set of model handles.
#include <android/log.h>
#include <jni.h>

#include "ruy/context.h"
#include "ruy/cpuinfo.h"
#include "tensors/cpu/smmla_gemm.h"

#include "affinity.h"
#include "utf_codec.h"
#include <limits>

#include <memory>
#include <string>
#include <vector>

#include "translator/byte_array_util.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

namespace {

using marian::bergamot::AsyncService;
using marian::bergamot::BlockingService;
using marian::bergamot::MemoryBundle;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

using ModelHandle = std::shared_ptr<TranslationModel>;

/// The opaque `service` jlong. Exactly one of the two pointers is non-null;
/// `blocking != nullptr` is the mode test.
struct ServiceHandle {
  std::unique_ptr<BlockingService> blocking;
  std::unique_ptr<AsyncService> async;
  size_t workers = 1;  ///< 1 in blocking mode: the calling thread does the work.

  bool isBlocking() const { return blocking != nullptr; }
};

void throwJava(JNIEnv *env, const std::string &message) {
  if (env->ExceptionCheck()) return; // Preserve the original Java allocation/array error.
  // ThrowNew also expects MUTF-8. Keep diagnostics ASCII; paths may contain Unicode.
  std::string safe = message;
  for (auto& c : safe) if (static_cast<unsigned char>(c) >= 128) c = '?';
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls != nullptr) env->ThrowNew(cls, safe.c_str());
}

std::string toStdString(JNIEnv *env, jstring value) {
  if (!value) throw std::invalid_argument("Null string");
  jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (!chars) throw std::runtime_error("Cannot read Java string");
  try {
    // Copy code units without aliasing jchar as char16_t.
    std::u16string utf16(chars, chars + length);
    env->ReleaseStringChars(value, chars);
    chars = nullptr;
    return foxlet::toUtf8(utf16);
  } catch (...) {
    if (chars) env->ReleaseStringChars(value, chars);
    throw;
  }
}

std::vector<std::string> toStdStrings(JNIEnv *env, jobjectArray array) {
  if (!array) throw std::invalid_argument("Null texts array");
  jsize n = env->GetArrayLength(array);
  std::vector<std::string> result;
  result.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read texts array");
    try { result.push_back(toStdString(env, element)); }
    catch (...) { if (element) env->DeleteLocalRef(element); throw; }
    env->DeleteLocalRef(element);
  }
  return result;
}

/// Copies a (nullable) Java byte[] into the 64-byte-aligned buffer MemoryBundle
/// wants. A null or empty array yields an empty AlignedMemory, which the engine
/// reads as "not supplied" and falls back to the option path for.
marian::bergamot::AlignedMemory toAlignedMemory(JNIEnv *env, jbyteArray bytes) {
  if (bytes == nullptr) return marian::bergamot::AlignedMemory();
  const jsize length = env->GetArrayLength(bytes);
  if (length <= 0) return marian::bergamot::AlignedMemory();
  marian::bergamot::AlignedMemory memory(static_cast<size_t>(length), 64);
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(memory.begin()));
  if (env->ExceptionCheck()) throw std::runtime_error("Cannot read prefix bytes");
  return memory;
}

jobjectArray toJavaStrings(JNIEnv *env, const std::vector<Response> &responses) {
  if (responses.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
    throw std::length_error("Too many translations");
  jclass stringClass = env->FindClass("java/lang/String");
  if (!stringClass) return nullptr;
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(responses.size()), stringClass, nullptr);
  env->DeleteLocalRef(stringClass);
  if (!result) return nullptr;
  for (jsize i = 0; i < static_cast<jsize>(responses.size()); ++i) {
    auto utf16 = foxlet::toUtf16(responses[i].target.text);
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("Translation too long");
    std::vector<jchar> chars(utf16.begin(), utf16.end());
    static const jchar empty = 0;
    jstring text = env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
    if (!text) return nullptr;
    env->SetObjectArrayElement(result, i, text);
    env->DeleteLocalRef(text);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

/// The blocking API takes one ResponseOptions per source text; this JNI surface
/// applies a single html flag across the whole batch.
std::vector<ResponseOptions> perTextOptions(size_t n, bool html) {
  ResponseOptions options;
  options.HTML = html;
  return std::vector<ResponseOptions>(n, options);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_createService(JNIEnv *env, jobject, jint workers, jint cacheSize) {
  try {
    const size_t numWorkers = workers < 1 ? 1 : static_cast<size_t>(workers);
    const bool blocking = numWorkers <= 1;
    {
      ruy::Context probe;
      ruy::CpuInfo cpuInfo;
      // cache_local/cache_llc equal to 32768/524288 mean cpuinfo failed inside
      // the app sandbox and ruy fell back to dummy cache params (block_map then
      // tiles for a 512KB last-level cache). The adb-shell context reads real
      // values; whether the app context does is an open question — this log
      // line answers it.
      // smmla=1 means the app-sandbox HWCAP read saw i8mm and the SMMLA GEMM
      // path is live in this process (0 = ruy SDOT fallback).
      // mode/workers say which service this engine got: blocking translates on
      // the caller's thread, async on `workers` engine threads.
      __android_log_print(ANDROID_LOG_INFO, "foxlet",
                          "ruy runtime paths=0x%x dotprod=%d cache_local=%d cache_llc=%d smmla=%d "
                          "mode=%s workers=%zu",
                          static_cast<int>(probe.get_runtime_enabled_paths()),
                          cpuInfo.NeonDotprod() ? 1 : 0,
                          cpuInfo.CacheParams().local_cache_size,
                          cpuInfo.CacheParams().last_level_cache_size,
                          marian::cpu::integer::smmla::available() ? 1 : 0,
                          blocking ? "blocking" : "async", numWorkers);
    }
    auto handle = std::make_unique<ServiceHandle>();
    handle->workers = numWorkers;
    if (blocking) {
      BlockingService::Config config;
      config.cacheSize = cacheSize < 0 ? 0 : static_cast<size_t>(cacheSize);
      handle->blocking = std::make_unique<BlockingService>(config);
    } else {
      AsyncService::Config config;
      config.numWorkers = numWorkers;
      config.cacheSize = cacheSize < 0 ? 0 : static_cast<size_t>(cacheSize);
      handle->async = std::make_unique<AsyncService>(config);
    }
    return reinterpret_cast<jlong>(handle.release());
  } catch (const std::exception &e) {
    throwJava(env, std::string("createService failed: ") + e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_destroyService(JNIEnv *, jobject, jlong service) {
  delete reinterpret_cast<ServiceHandle *>(service);
}

// The CPU half of the thread-tier input. Cores outside the slowest cluster,
// or 0 when the topology gives no usable answer (see affinity.h). Free of any
// service handle on purpose — the tier is picked before the engine exists.
JNIEXPORT jint JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_fastCoreCount(JNIEnv *, jobject) {
  return static_cast<jint>(foxlet_android::fastCoreCount());
}

JNIEXPORT jlong JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_loadModel(JNIEnv *env, jobject, jlong service, jstring configYaml,
                                                        jbyteArray ssplitPrefix) {
  try {
    auto *svc = reinterpret_cast<ServiceHandle *>(service);
    auto options = marian::bergamot::parseOptionsFromString(toStdString(env, configYaml), /*validate=*/false);
    // Same bundle the path-only constructor builds for itself (model, shortlist
    // and vocab bytes read from the YAML paths -- the loading path every memory
    // figure was measured on; a missing file fails here, before any engine
    // object exists), with just the sentence-splitter prefixes replaced by the
    // bytes the Kotlin side looked up. Handing the engine an otherwise-empty
    // bundle would switch model loading to marian's own per-replica file
    // readers, and a bad config would reach Vocabs' ABORT (std::abort, not an
    // exception) instead of the file loader.
    MemoryBundle bundle = marian::bergamot::getMemoryBundleFromConfig(options);
    if (ssplitPrefix != nullptr) bundle.ssplitPrefixFile = toAlignedMemory(env, ssplitPrefix);
    // Replicas are backends, one per thread that may translate with the model.
    // createCompatibleModel sizes them per worker; a bare TranslationModel has
    // one and crashes (SIGBUS) as soon as worker id > 0 touches it. Blocking
    // has no workers -- deviceId is always 0 -- so one replica is both correct
    // and the cheapest.
    ModelHandle model = svc->isBlocking()
                            ? marian::New<TranslationModel>(options, std::move(bundle), /*replicas=*/1)
                            : svc->async->createCompatibleModel(options, std::move(bundle));
    return reinterpret_cast<jlong>(new ModelHandle(std::move(model)));
  } catch (const std::exception &e) {
    throwJava(env, std::string("loadModel failed: ") + e.what());
    return 0;
  }
}

// releasing a model needs the service. Dropping the handle alone frees
// nothing. Under async, each worker keeps an owning reference to the model it
// last translated with, the aggregate queue keeps one too, and the per-thread
// GEMM weight-packing caches only clear at the next GEMM on that thread --
// which never comes once the app stops translating. Under blocking there are
// no workers, but the aggregate queue and this thread's packing caches hold the
// same kind of reference. Both services' release() close all of that and report
// whether the model was actually destroyed.
JNIEXPORT jboolean JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_releaseModel(JNIEnv *, jobject, jlong service, jlong model) {
  auto *handle = reinterpret_cast<ModelHandle *>(model);
  if (handle == nullptr) return JNI_TRUE;
  bool destroyed = false;
  if (service != 0) {
    auto *svc = reinterpret_cast<ServiceHandle *>(service);
    destroyed = svc->isBlocking() ? svc->blocking->release(std::move(*handle))
                                  : svc->async->release(std::move(*handle));
  } else {
    handle->reset();
    destroyed = true;
  }
  delete handle;
  return destroyed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_translate(JNIEnv *env, jobject, jlong service, jlong model,
                                                        jobjectArray texts, jboolean html) {
  try {
    auto *svc = reinterpret_cast<ServiceHandle *>(service);
    auto &handle = *reinterpret_cast<ModelHandle *>(model);
    auto sources = toStdStrings(env, texts);
    std::vector<Response> responses;
    if (svc->isBlocking()) {
      const std::vector<ResponseOptions> options = perTextOptions(sources.size(), html);
      responses = svc->blocking->translateMultiple(handle, std::move(sources), options);
    } else {
      ResponseOptions responseOptions;
      responseOptions.HTML = html;
      // The batch API, not translate() per text: it submits the whole array in one step, which is what makes the
      // output bytes the same as the blocking path's regardless of how many workers are running.
      responses = svc->async->translateMultiple(handle, std::move(sources), responseOptions);
    }
    return toJavaStrings(env, responses);
  } catch (const std::exception &e) {
    throwJava(env, std::string("translate failed: ") + e.what());
    return nullptr;
  }
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_yinvoker_foxlet_NativeBridge_translatePivot(JNIEnv *env, jobject, jlong service, jlong first,
                                                             jlong second, jobjectArray texts, jboolean html) {
  try {
    auto *svc = reinterpret_cast<ServiceHandle *>(service);
    auto &firstHandle = *reinterpret_cast<ModelHandle *>(first);
    auto &secondHandle = *reinterpret_cast<ModelHandle *>(second);
    auto sources = toStdStrings(env, texts);
    std::vector<Response> responses;
    if (svc->isBlocking()) {
      const std::vector<ResponseOptions> options = perTextOptions(sources.size(), html);
      responses = svc->blocking->pivotMultiple(firstHandle, secondHandle, std::move(sources), options);
    } else {
      ResponseOptions responseOptions;
      responseOptions.HTML = html;
      responses = svc->async->pivotMultiple(firstHandle, secondHandle, std::move(sources), responseOptions);
    }
    return toJavaStrings(env, responses);
  } catch (const std::exception &e) {
    throwJava(env, std::string("translatePivot failed: ") + e.what());
    return nullptr;
  }
}

}  // extern "C"
