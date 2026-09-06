#pragma once
#include <cstddef>

// CPU topology probe: how many cores are *not* in the slowest cluster -- the
// "big core" count the Kotlin thread-tier picker needs (8 Gen 1 -> 4,
// 8 Gen 3 -> 6, 865 -> 4). Clusters that tie for the lowest cpuinfo_max_freq
// all count as slow.
//
// Probed once from /sys/devices/system/cpu/cpufreq/policy*/ (clusters ordered
// by cpuinfo_max_freq). Returns 0 when there is no usable answer: probing
// failed, a single cluster, or near-uniform clusters (all-big SoCs). 0 means
// "no opinion" -- the caller falls back to its own estimate; it never means
// "zero fast cores". Non-Linux builds always return 0.
//
// This is *topology*, not availability: it does not shrink when the app is
// moved into a background cpuset.
namespace bergamot_android {

std::size_t fastCoreCount();

}  // namespace bergamot_android
