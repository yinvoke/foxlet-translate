// Quality-only companion: export translations from a pinned engine build.
// Never substitute its timings/binary for the fixed native benchmark harness.
// Usage: quality_reference workers config.yml [pivot-target.yml] < corpus.txt
#include <condition_variable>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

using namespace marian::bergamot;

std::string jsonString(const std::string& value) {
  std::ostringstream out;
  out << '"';
  for (unsigned char c : value) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c < 32) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << int(c);
    else out << c;
  }
  out << '"';
  return out.str();
}

int main(int argc, char** argv) {
  if (argc != 3 && argc != 4) return 2;
  const int workers = std::stoi(argv[1]);
  if (workers < 0) return 2;
  std::vector<std::string> inputs;
  for (std::string line; std::getline(std::cin, line);) inputs.push_back(line);
  if (inputs.size() != 200) return 2;
  std::vector<Response> responses(inputs.size());
  if (workers == 0) {
    BlockingService::Config config;
    config.cacheSize = 0;
    BlockingService service(config);
    auto first = marian::New<TranslationModel>(parseOptionsFromFilePath(argv[2]), 1);
    std::vector<ResponseOptions> options(inputs.size());
    if (argc == 4) {
      auto second = marian::New<TranslationModel>(parseOptionsFromFilePath(argv[3]), 1);
      responses = service.pivotMultiple(first, second, std::move(inputs), options);
    } else responses = service.translateMultiple(first, std::move(inputs), options);
  } else {
    AsyncService::Config config;
    config.numWorkers = workers;
    config.cacheSize = 0;
    AsyncService service(config);
    auto first = service.createCompatibleModel(parseOptionsFromFilePath(argv[2]));
    std::shared_ptr<TranslationModel> second;
    if (argc == 4) second = service.createCompatibleModel(parseOptionsFromFilePath(argv[3]));
    std::mutex mutex;
    std::condition_variable done;
    size_t pending = inputs.size();
    for (size_t i = 0; i < inputs.size(); ++i) {
      auto callback = [&, i](Response&& response) {
        std::lock_guard<std::mutex> lock(mutex);
        responses[i] = std::move(response);
        if (--pending == 0) done.notify_all();
      };
      if (second) service.pivot(first, second, std::move(inputs[i]), std::move(callback), ResponseOptions{});
      else service.translate(first, std::move(inputs[i]), std::move(callback), ResponseOptions{});
    }
    std::unique_lock<std::mutex> lock(mutex);
    done.wait(lock, [&] { return pending == 0; });
  }
  std::cout << '[';
  for (size_t i = 0; i < responses.size(); ++i) {
    if (i) std::cout << ',';
    std::cout << jsonString(responses[i].target.text);
  }
  std::cout << "]\n";
}
