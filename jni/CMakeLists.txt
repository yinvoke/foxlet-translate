add_library(foxlet SHARED foxlet_jni.cpp affinity.cpp)
target_include_directories(foxlet PRIVATE ${CMAKE_SOURCE_DIR}/engine/src ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy/third_party/cpuinfo/include)
target_link_libraries(foxlet bergamot-translator-source log)

# The only symbols anything outside this .so resolves are the seven Java_* the
# JNI runtime dlsym()s by name; JNIEXPORT (jni.h) already marks those
# visibility("default"), so nothing here can hide them. Everything else --
# marian, ruy, sentencepiece, protobuf, yaml-cpp, spdlog, CLI11 -- arrives from
# static archives and is exported only because that is the archive default.
# Every one of those exports is a --gc-sections root, which is why the
# --gc-sections the NDK toolchain already passes
# (android-legacy.toolchain.cmake:518) has almost nothing it is allowed to drop.
# Demoting the archive symbols to local is what gives it something to collect.
#
# Precondition: ANDROID_STL=c++_shared (foxlet/build.gradle.kts). --exclude-libs
# only touches static archives, so libc++ -- which owns the std:: exception
# type_infos this library throws across -- is untouched. Switching back to
# c++_static would put libc++abi's __cxa_* and _ZTISt* under this flag: still
# workable for a single .so, but re-run the exception test
# (NativeSmokeTest.serviceLifecycleAndBadModelRejection) before believing it.
#
# PRIVATE, and on this target only: tools/smoke is a separate executable and
# does not want its symbols touched.
target_link_options(foxlet PRIVATE -Wl,--exclude-libs,ALL)
