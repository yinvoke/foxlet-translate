#pragma once
#include <cstddef>

// Best-effort "prefer the fast cores" pinning for translation threads.
//
// Topology is probed once from /sys/devices/system/cpu/cpufreq/policy*/
// (clusters sorted by cpuinfo_max_freq, fastest first); the calling thread is
// pinned to the fastest `count` cores. Every failure path is a silent no-op:
// missing sysfs, a single or near-uniform cluster set (all-big SoCs, where
// restricting cores would only take freedom from the scheduler), cpuset
// rejections, offline cores. Non-Linux builds compile to no-ops.
namespace bergamot_android {

// Pin the calling thread to the fastest `count` cores and remember its tid so
// reapplyAffinity() can heal it later — Android moves apps between fore- and
// background cpusets, which silently clears thread affinity.
void pinCurrentThread(std::size_t count);

// Re-pin every thread previously registered via pinCurrentThread. Cheap (one
// syscall per worker); safe to call once per translation batch. Threads that
// no longer exist are forgotten.
void reapplyAffinity();

// How many cores are *not* in the slowest cluster — the "big core" count the
// Kotlin thread-tier picker needs (8 Gen 1 -> 4, 8 Gen 3 -> 6, 865 -> 4).
// Clusters that tie for the lowest cpuinfo_max_freq all count as slow.
//
// Returns 0 on exactly the topologies where fastCoreOrder() gives up: probing
// failed, there is a single cluster, or the clusters are near-uniform. 0 means
// "no opinion" — the caller falls back to its own estimate; it never means
// "zero fast cores". Non-Linux builds always return 0.
//
// This is *topology*, not availability: it does not shrink when the app is
// moved into a background cpuset (see sched_getaffinity for that).
std::size_t fastCoreCount();

}  // namespace bergamot_android
