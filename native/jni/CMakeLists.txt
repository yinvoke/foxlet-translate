add_library(foxlet SHARED foxlet_jni.cpp affinity.cpp)
target_include_directories(foxlet PRIVATE ${CMAKE_SOURCE_DIR}/engine/src ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy/third_party/cpuinfo/include)
target_link_libraries(foxlet bergamot-translator-source log)

# Keep archive symbols local so unused engine code remains eligible for linker
# garbage collection. JNIEXPORT preserves the Java_* entry points.
# ANDROID_STL=c++_shared keeps libc++ exception/runtime symbols outside these
# static archives. Any STL change requires JNI exception and lifecycle validation.
# Scope the option to the Android shared library; host tools are separate targets.
target_link_options(foxlet PRIVATE -Wl,--exclude-libs,ALL)
