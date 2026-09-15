# Optional target for an exported engine tree. Use CMAKE_PROJECT_INCLUDE so
# no upstream source/CMake file needs editing. The native timing target stays
# unchanged; quality_reference is a separate binary with no timing claims.
if(NOT TARGET quality_reference)
  add_executable(quality_reference EXCLUDE_FROM_ALL "${CMAKE_CURRENT_LIST_DIR}/reference.cpp")
  target_compile_features(quality_reference PRIVATE cxx_std_17)
  target_include_directories(quality_reference PRIVATE "${CMAKE_SOURCE_DIR}/engine/src")
  target_link_libraries(quality_reference bergamot-translator-source)
endif()
