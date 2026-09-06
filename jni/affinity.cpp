#include "affinity.h"

#if defined(__linux__)

#include <dirent.h>
#include <sched.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace bergamot_android {
namespace {

struct PinnedThread {
  pid_t tid;
  std::size_t count;
};

std::mutex gMutex;
std::vector<PinnedThread> gPinned;

// Cores ordered fastest-cluster-first. Empty when detection failed, there is
// only one cluster, or the clusters are near-uniform (all-big SoCs).
const std::vector<int> &fastCoreOrder() {
  static const std::vector<int> order = [] {
    std::vector<int> result;
    std::vector<std::pair<long, std::vector<int>>> clusters;  // (maxFreq, cpus)
    DIR *dir = opendir("/sys/devices/system/cpu/cpufreq");
    if (dir == nullptr) return result;
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
    if (clusters.size() < 2) return result;
    std::sort(clusters.begin(), clusters.end(),
              [](const auto &a, const auto &b) { return a.first > b.first; });
    if (clusters.back().first * 100 >= clusters.front().first * 95) return result;
    for (auto &cluster : clusters)
      for (int cpu : cluster.second) result.push_back(cpu);
    return result;
  }();
  return order;
}

// 0 on success, -1 when there is nothing to pin to, errno otherwise.
int applyMask(pid_t tid, std::size_t count) {
  const std::vector<int> &order = fastCoreOrder();
  if (order.empty()) return -1;
  count = std::min(std::max<std::size_t>(count, 1), order.size());
  cpu_set_t set;
  CPU_ZERO(&set);
  for (std::size_t i = 0; i < count; ++i) CPU_SET(order[i], &set);
  // The kernel intersects the mask with the process cpuset; EINVAL means the
  // fast cores are currently outside it (backgrounded app) or offline.
  return sched_setaffinity(tid, sizeof(set), &set) == 0 ? 0 : errno;
}

}  // namespace

void pinCurrentThread(std::size_t count) {
  pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
  // Register even when the first attempt fails: the cpuset may open up once
  // the app is foregrounded, and reapplyAffinity() will heal it then.
  applyMask(tid, count);
  std::lock_guard<std::mutex> lock(gMutex);
  for (const PinnedThread &p : gPinned)
    if (p.tid == tid) return;
  gPinned.push_back({tid, count});
}

void reapplyAffinity() {
  std::lock_guard<std::mutex> lock(gMutex);
  for (auto it = gPinned.begin(); it != gPinned.end();) {
    if (applyMask(it->tid, it->count) == ESRCH) {
      it = gPinned.erase(it);
    } else {
      ++it;
    }
  }
}

}  // namespace bergamot_android

#else  // !defined(__linux__)

namespace bergamot_android {
void pinCurrentThread(std::size_t) {}
void reapplyAffinity() {}
}  // namespace bergamot_android

#endif
