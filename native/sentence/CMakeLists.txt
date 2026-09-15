# SPDX-License-Identifier: MIT
add_library(foxlet_sentence STATIC segmenter.cpp)
target_include_directories(foxlet_sentence PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/..")
target_compile_features(foxlet_sentence PUBLIC cxx_std_17)
set_target_properties(foxlet_sentence PROPERTIES POSITION_INDEPENDENT_CODE ON)
