# Native build entry. Consumed two ways:
#   - host (ARM only): engine + tools/smoke, driven by plain cmake
#   - Android: engine + native/jni, driven by AGP externalNativeBuild (see foxlet/)
cmake_minimum_required(VERSION 3.22)
project(foxlet_android CXX C)

# Old cmake_minimum_required() calls throughout the vendored tree.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Consumers outside engine/ (tools/smoke, native/jni/) include marian/bergamot
# headers, whose contents are shaped by these macros. The vendored tree sets
# them directory-scoped only, so sibling directories must match them here or
# header code paths diverge from the compiled engine (raw x86 intrinsics on
# ARM, wrong string_view, etc.). Mirrors the flag set the engine itself is
# built with.
if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|armv8)")
  message(FATAL_ERROR "Foxlet Translate is ARM-only (see engine/UPSTREAM.md)")
endif()
add_compile_definitions(ARM FMA SSE COMPILE_CPU=1 USE_PTHREADS USE_SENTENCEPIECE _USE_INTERNAL_STRING_VIEW)
add_compile_options(-flax-vector-conversions -fno-strict-aliasing)

if(ANDROID)
  # Vendor CMAKE_*_FLAGS assignments can remove NDK defaults. Directory options
  # retain per-function/data sections so the linker can discard unused code.
  add_compile_options(-ffunction-sections -fdata-sections)

  # Hide native implementation symbols; JNIEXPORT retains the bridge exports.
  # Apply inline visibility only to C++ to avoid warnings in C translation units.
  add_compile_options(
    -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)

  # Preserve NDK stack/fortify hardening and Release assertion policy despite
  # vendor flag overrides. These options apply only to Android builds.
  add_compile_options(-fstack-protector-strong)
  add_compile_definitions(_FORTIFY_SOURCE=2)
  add_compile_definitions($<$<CONFIG:Release>:NDEBUG>)

  # bionic ships pthreads inside libc; FindThreads invocations in
  # vendored subprojects (ruy/cpuinfo) fail to detect that. Pre-seed the result.
  set(CMAKE_THREAD_LIBS_INIT "")
  set(CMAKE_HAVE_THREADS_LIBRARY 1)
  set(CMAKE_USE_PTHREADS_INIT 1)
  set(THREADS_PREFER_PTHREAD_FLAG OFF)
  set(Threads_FOUND TRUE)
endif()

add_subdirectory(native/sentence)
add_subdirectory(engine)

option(BUILD_SMOKE "Also build the smoke CLI on Android (adb-shell benchmarking)" OFF)
option(BUILD_I8MM_BENCH "SDOT-vs-SMMLA microbench (i8mm backend go/no-go data)" OFF)
option(BUILD_SMMLA_TEST "SMMLA GEMM correctness test set (ctest target smmla_test)" OFF)
option(BUILD_FLOAT_BENCH "Attention float GEMM microbench (ruy vs small-GEMM kernels; needs USE_RUY_SGEMM)" OFF)
if(BUILD_SMMLA_TEST)
  enable_testing()
  add_subdirectory(tools/smmla-test)
endif()
if(BUILD_I8MM_BENCH)
  add_subdirectory(tools/i8mm-bench)
endif()
if(BUILD_FLOAT_BENCH)
  add_subdirectory(tools/float-bench)
endif()
if(ANDROID)
  add_subdirectory(native/jni)
  if(BUILD_SMOKE)
    add_subdirectory(tools/smoke)
  endif()
else()
  add_subdirectory(tools/smoke)
endif()
