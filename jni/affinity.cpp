#include "affinity.h"

#if defined(__linux__)

#include <dirent.h>

#include <algorithm>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

namespace foxlet_android {

std::size_t fastCoreCount() {
  static const std::size_t probed = [] {
    std::vector<std::pair<long, std::vector<int>>> clusters;  // (maxFreq, cpus)
    DIR *dir = opendir("/sys/devices/system/cpu/cpufreq");
    if (dir == nullptr) return std::size_t{0};
    while (dirent *entry = readdir(dir)) {
      if (std::string(entry->d_name).rfind("policy", 0) != 0) continue;
      std::string base = std::string("/sys/devices/system/cpu/cpufreq/") + entry->d_name;
      long freq = 0;
      if (FILE *f = fopen((base + "/cpuinfo_max_freq").c_str(), "r")) {
        if (fscanf(f, "%ld", &freq) != 1) freq = 0;
        fclose(f);
      }
      std::vector<int> cpus;
      if (FILE *f = fopen((base + "/affected_cpus").c_str(), "r")) {
        int cpu;
        while (fscanf(f, "%d", &cpu) == 1) cpus.push_back(cpu);
        fclose(f);
      }
      if (freq > 0 && !cpus.empty()) clusters.emplace_back(freq, std::move(cpus));
    }
    closedir(dir);
    if (clusters.size() < 2) return std::size_t{0};
    std::sort(clusters.begin(), clusters.end(),
              [](const auto &a, const auto &b) { return a.first > b.first; });
    if (clusters.back().first * 100 >= clusters.front().first * 95) return std::size_t{0};
    // Ties at the bottom are all "slow": some SoCs split the little cluster
    // across two policies at the same cpuinfo_max_freq.
    const long slowestFreq = clusters.back().first;
    std::size_t fast = 0;
    for (auto &cluster : clusters)
      if (cluster.first > slowestFreq) fast += cluster.second.size();
    return fast;
  }();
  return probed;
}

}  // namespace foxlet_android

#else  // !defined(__linux__)

namespace foxlet_android {
std::size_t fastCoreCount() { return 0; }
}  // namespace foxlet_android

#endif
