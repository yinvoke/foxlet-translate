# Native build entry. Consumed two ways:
#   - host (ARM only): engine + tools/smoke, driven by plain cmake
#   - Android: engine + jni, driven by AGP externalNativeBuild (see foxlet/)
cmake_minimum_required(VERSION 3.22)
project(foxlet_android CXX C)

# Old cmake_minimum_required() calls throughout the vendored tree.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Consumers outside engine/ (tools/smoke, jni/) include marian/bergamot/ssplit
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
  # The NDK puts -ffunction-sections -fdata-sections into CMAKE_CXX_FLAGS
  # (android-legacy.toolchain.cmake:463-464), but engine/marian-fork/CMakeLists.txt:367
  # and :377 *set* that variable rather than appending to it, so the whole
  # marian subtree -- marian itself plus ruy, sentencepiece, protobuf, yaml-cpp,
  # CLI11, spdlog, zlib, cpuinfo, ~275 of the 294 translation units -- loses
  # them. Without per-function sections the --gc-sections on the link line can
  # only drop whole object files.
  #
  # add_compile_options here is the one place that survives: CMake appends it
  # after CMAKE_CXX_FLAGS on the command line, so it outlives the overwrite.
  # Android only -- the host CLI is the reference platform for the canonical
  # hashes and its code generation is left exactly as it was.
  add_compile_options(-ffunction-sections -fdata-sections)

  # Hidden by default. --exclude-libs already demoted everything that arrives
  # from an archive; this covers what is compiled straight into the .so
  # (jni/foxlet_jni.cpp, jni/affinity.cpp and the templates, vtables and
  # type_infos they emit) and lets the compiler stop routing intra-library
  # calls through the PLT. The seven Java_* stay exported: jni.h defines
  # JNIEXPORT as visibility("default"), which wins over the default here.
  #
  # -fvisibility-inlines-hidden is C++-only and would draw
  # -Wunused-command-line-argument on the C sources; 78 marian TUs compile with
  # -Werror, so the generator expression is load-bearing, not tidiness.
  #
  # This is the one step of the three that changes code generation: without
  # interposition clang can inline and devirtualise across TUs more freely,
  # which -- at the default -ffp-contract=on -- could create FMA contractions
  # that were not there before. Hence Android only, and hence the device hash
  # gate is what decides whether it lands.
  add_compile_options(
    -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)

  # Same overwrite as above, second casualty: CMAKE_CXX_FLAGS_RELEASE is set to
  # "-O3 ${BUILD_WIDTH} -funroll-loops" at marian-fork/CMakeLists.txt:368 (and
  # :378 for C), dropping the -DNDEBUG the NDK had put there. So the marian
  # subtree builds Release with asserts live -- including ruy, where
  # 3rd_party/ruy/ruy/check_macros.h:123 turns that into
  # RUY_DCHECK_IS_ENABLED = true right inside the GEMM path.
  #
  # This is a speed change, not a size one, and it is not free of risk: NDEBUG
  # removes assert() everywhere in that subtree, so anything relying on an
  # assert's side effect changes behaviour. Land it only behind a device hash
  # gate plus a three-phone A/B.
  #
  # The 19 TUs that kept the NDK flags already have -DNDEBUG in Release; a
  # second identical -D is a no-op, so this is applied to the whole Android
  # tree rather than being surgically aimed at the marian subtree.
  #
  # Keep NDK hardening even when a vendored subdirectory replaces CMAKE_*_FLAGS.
  add_compile_options(-fstack-protector-strong)
  add_compile_definitions(_FORTIFY_SOURCE=2)
  add_compile_definitions($<$<CONFIG:Release>:NDEBUG>)

  # bionic ships pthreads inside libc; the ancient FindThreads invocations in
  # vendored subprojects (ruy/cpuinfo) fail to detect that. Pre-seed the result.
  set(CMAKE_THREAD_LIBS_INIT "")
  set(CMAKE_HAVE_THREADS_LIBRARY 1)
  set(CMAKE_USE_PTHREADS_INIT 1)
  set(THREADS_PREFER_PTHREAD_FLAG OFF)
  set(Threads_FOUND TRUE)
endif()

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
  add_subdirectory(jni)
  if(BUILD_SMOKE)
    add_subdirectory(tools/smoke)
  endif()
else()
  add_subdirectory(tools/smoke)
endif()
