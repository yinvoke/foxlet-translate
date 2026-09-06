// JNI glue for io.github.yinvoker.bergamot.NativeBridge.
// Thin by design: batch in, batch out, blocking from the caller's view.
//
// E4: two execution modes behind one handle, chosen once at createService().
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

#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

namespace {

using marian::bergamot::AsyncService;
using marian::bergamot::BlockingService;
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
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

std::string toStdString(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars == nullptr ? "" : chars);
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

std::vector<std::string> toStdStrings(JNIEnv *env, jobjectArray array) {
  jsize n = env->GetArrayLength(array);
  std::vector<std::string> result;
  result.reserve(n);
  for (jsize i = 0; i < n; ++i) {
    auto element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    result.push_back(toStdString(env, element));
    env->DeleteLocalRef(element);
  }
  return result;
}

jobjectArray toJavaStrings(JNIEnv *env, const std::vector<Response> &responses) {
  jclass stringClass = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(responses.size()), stringClass, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(responses.size()); ++i) {
    jstring text = env->NewStringUTF(responses[i].target.text.c_str());
    env->SetObjectArrayElement(result, i, text);
    env->DeleteLocalRef(text);
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

/// Fans texts out to the AsyncService and blocks until every callback fired.
/// submit(i, text, callback) issues request i.
template <typename Submit>
std::vector<Response> collectAll(std::vector<std::string> &&sources, Submit submit) {
  const size_t n = sources.size();
  std::vector<Response> responses(n);
  std::mutex mutex;
  std::condition_variable done;
  size_t pending = n;
  for (size_t i = 0; i < n; ++i) {
    submit(i, std::move(sources[i]), [&, i](Response &&response) {
      std::lock_guard<std::mutex> lock(mutex);
      responses[i] = std::move(response);
      if (--pending == 0) done.notify_all();
    });
  }
  std::unique_lock<std::mutex> lock(mutex);
  done.wait(lock, [&] { return pending == 0; });
  return responses;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_yinvoker_bergamot_NativeBridge_createService(JNIEnv *env, jobject, jint workers,
                                                            jboolean pinToFastCores, jint cacheSize) {
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
      __android_log_print(ANDROID_LOG_INFO, "bergamot",
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
      // No worker to hook: the translation runs right here, so the thread that
      // creates the service is the one to pin. It stays the translating thread
      // for the engine's whole life (Kotlin runs every native call on it), and
      // reapplyAffinity() re-pins it before every batch.
      if (pinToFastCores) bergamot_android::pinCurrentThread(1);
      BlockingService::Config config;
      config.cacheSize = cacheSize < 0 ? 0 : static_cast<size_t>(cacheSize);
      handle->blocking = std::make_unique<BlockingService>(config);
    } else {
      AsyncService::Config config;
      config.numWorkers = numWorkers;
      config.cacheSize = cacheSize < 0 ? 0 : static_cast<size_t>(cacheSize);
      if (pinToFastCores) {
        // Each worker pins itself to the N fastest cores (N = worker count);
        // no-op on uniform topologies or when the cpuset refuses.
        size_t fastCores = config.numWorkers;
        config.onWorkerStart = [fastCores](size_t) { bergamot_android::pinCurrentThread(fastCores); };
      }
      handle->async = std::make_unique<AsyncService>(config);
    }
    return reinterpret_cast<jlong>(handle.release());
  } catch (const std::exception &e) {
    throwJava(env, std::string("createService failed: ") + e.what());
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_io_github_yinvoker_bergamot_NativeBridge_destroyService(JNIEnv *, jobject, jlong service) {
  delete reinterpret_cast<ServiceHandle *>(service);
}

JNIEXPORT jlong JNICALL
Java_io_github_yinvoker_bergamot_NativeBridge_loadModel(JNIEnv *env, jobject, jlong service, jstring configYaml) {
  try {
    auto *svc = reinterpret_cast<ServiceHandle *>(service);
    auto options = marian::bergamot::parseOptionsFromString(toStdString(env, configYaml), /*validate=*/false);
    // Replicas are backends, one per thread that may translate with the model.
    // createCompatibleModel sizes them per worker; a bare TranslationModel has
    // one and crashes (SIGBUS) as soon as worker id > 0 touches it. Blocking
    // has no workers -- deviceId is always 0 -- so one replica is both correct
    // and the cheapest.
    ModelHandle model = svc->isBlocking() ? marian::New<TranslationModel>(options, /*replicas=*/1)
                                          : svc->async->createCompatibleModel(options);
    return reinterpret_cast<jlong>(new ModelHandle(std::move(model)));
  } catch (const std::exception &e) {
    throwJava(env, std::string("loadModel failed: ") + e.what());
    return 0;
  }
}

// D0: releasing a model needs the service. Dropping the handle alone frees
// nothing. Under async, each worker keeps an owning reference to the model it
// last translated with, the aggregate queue keeps one too, and the per-thread
// GEMM weight-packing caches only clear at the next GEMM on that thread --
// which never comes once the app stops translating. Under blocking there are
// no workers, but the aggregate queue and this thread's packing caches hold the
// same kind of reference. Both services' release() close all of that and report
// whether the model was actually destroyed.
JNIEXPORT jboolean JNICALL
Java_io_github_yinvoker_bergamot_NativeBridge_releaseModel(JNIEnv *, jobject, jlong service, jlong model) {
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
Java_io_github_yinvoker_bergamot_NativeBridge_translate(JNIEnv *env, jobject, jlong service, jlong model,
                                                        jobjectArray texts, jboolean html) {
  try {
    bergamot_android::reapplyAffinity();
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
      responses = collectAll(std::move(sources), [&](size_t, std::string &&text, auto callback) {
        svc->async->translate(handle, std::move(text), std::move(callback), responseOptions);
      });
    }
    return toJavaStrings(env, responses);
  } catch (const std::exception &e) {
    throwJava(env, std::string("translate failed: ") + e.what());
    return nullptr;
  }
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_yinvoker_bergamot_NativeBridge_translatePivot(JNIEnv *env, jobject, jlong service, jlong first,
                                                             jlong second, jobjectArray texts, jboolean html) {
  try {
    bergamot_android::reapplyAffinity();
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
      responses = collectAll(std::move(sources), [&](size_t, std::string &&text, auto callback) {
        svc->async->pivot(firstHandle, secondHandle, std::move(text), std::move(callback), responseOptions);
      });
    }
    return toJavaStrings(env, responses);
  } catch (const std::exception &e) {
    throwJava(env, std::string("translatePivot failed: ") + e.what());
    return nullptr;
  }
}

}  // extern "C"
