add_executable(smoke smoke.cpp)
if(ANDROID)
  target_sources(smoke PRIVATE ${CMAKE_SOURCE_DIR}/jni/affinity.cpp)
endif()
target_include_directories(smoke PRIVATE ${CMAKE_SOURCE_DIR}/engine/src
  ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy
  ${CMAKE_SOURCE_DIR}/engine/marian-fork/src/3rd_party/ruy/third_party/cpuinfo/include)
target_link_libraries(smoke bergamot-translator-source)
