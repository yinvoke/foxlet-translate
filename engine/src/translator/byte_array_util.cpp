#include "byte_array_util.h"

#include <cstdlib>
#include <memory>
#include <fstream>
#include <stdexcept>
#include "common/binary_validation.h"

#include "common/io.h"
#include "data/shortlist.h"

namespace marian {
namespace bergamot {

// Modified by Foxlet Translate: validate before any native metadata parser runs.
bool validateBinaryModel(const AlignedMemory& model, uint64_t fileSize) {
  if (fileSize > model.size()) return false;
  try {
    marian::io::binary::validateModelBytes(model.begin(), static_cast<size_t>(fileSize));
    return true;
  } catch (const std::invalid_argument&) { return false; }
}

AlignedMemory loadFileToMemory(const std::string& path, size_t alignment) {
  std::ifstream in(path, std::ios::binary | std::ios::ate);
  if (!in) throw std::invalid_argument("Cannot open model file: " + path);
  const auto end = in.tellg();
  if (end <= 0 || static_cast<uint64_t>(end) > 1024ULL * 1024 * 1024)
    throw std::invalid_argument("Empty or oversized model file: " + path);
  size_t fileSize = static_cast<size_t>(end);
  AlignedMemory alignedMemory(fileSize, alignment);
  in.seekg(0);
  if (!in.read(reinterpret_cast<char*>(alignedMemory.begin()), fileSize))
    throw std::invalid_argument("Incomplete model read: " + path);
  return alignedMemory;
}

std::vector<AlignedMemory> getModelMemoryFromConfig(marian::Ptr<marian::Options> options) {
  auto models = options->get<std::vector<std::string>>("models");

  std::vector<AlignedMemory> modelMemories(models.size());
  for (size_t i = 0; i < models.size(); ++i) {
    const auto model = models[i];
    if (marian::io::isBin(model)) {
      modelMemories[i] = loadFileToMemory(model, 256);
      marian::io::binary::validateModelBytes(modelMemories[i].begin(), modelMemories[i].size());
    } else if (marian::io::isNpz(model)) {
      // if any of the models are npz format, we revert to loading from file for all models.
      LOG(debug, "Encountered an npz file {}; will use file loading for {} models", model, models.size());
      return {};
    } else {
      throw std::invalid_argument("Unsupported model extension: " + model);
    }
  }

  return modelMemories;
}

AlignedMemory getShortlistMemoryFromConfig(marian::Ptr<marian::Options> options) {
  auto shortlist = options->get<std::vector<std::string>>("shortlist");
  if (!shortlist.empty()) {
    if (!marian::data::isBinaryShortlist(shortlist[0]))
      throw std::invalid_argument("Invalid binary shortlist");
    return loadFileToMemory(shortlist[0], 64);
  }
  return AlignedMemory();
}

void getVocabsMemoryFromConfig(marian::Ptr<marian::Options> options,
                               std::vector<std::shared_ptr<AlignedMemory>>& vocabMemories) {
  auto vfiles = options->get<std::vector<std::string>>("vocabs");
  if (vfiles.size() != 2) throw std::invalid_argument("Expected two vocabularies");
  vocabMemories.resize(vfiles.size());
  std::unordered_map<std::string, std::shared_ptr<AlignedMemory>> vocabMap;
  for (size_t i = 0; i < vfiles.size(); ++i) {
    if (marian::filesystem::Path(vfiles[i]).extension() != marian::filesystem::Path(".spm"))
      throw std::invalid_argument("Expected SentencePiece vocabulary");
    auto m = vocabMap.emplace(std::make_pair(vfiles[i], std::shared_ptr<AlignedMemory>()));
    if (m.second) {
      m.first->second = std::make_shared<AlignedMemory>(loadFileToMemory(vfiles[i], 64));
    }
    vocabMemories[i] = m.first->second;
  }
}

AlignedMemory getQualityEstimatorModel(const marian::Ptr<marian::Options>& options) {
  const auto qualityEstimatorPath = options->get<std::string>("quality", "");
  if (qualityEstimatorPath.empty()) {
    return {};
  }
  return loadFileToMemory(qualityEstimatorPath, 64);
}

AlignedMemory getQualityEstimatorModel(MemoryBundle& memoryBundle, const marian::Ptr<marian::Options>& options) {
  if (memoryBundle.qualityEstimatorMemory.size() == 0) {
    return getQualityEstimatorModel(options);
  }

  return std::move(memoryBundle.qualityEstimatorMemory);
}

MemoryBundle getMemoryBundleFromConfig(marian::Ptr<marian::Options> options) {
  MemoryBundle memoryBundle;
  memoryBundle.models = getModelMemoryFromConfig(options);
  memoryBundle.shortlist = getShortlistMemoryFromConfig(options);
  getVocabsMemoryFromConfig(options, memoryBundle.vocabs);
  memoryBundle.ssplitPrefixFile = getSsplitPrefixFileMemoryFromConfig(options);
  memoryBundle.qualityEstimatorMemory = getQualityEstimatorModel(options);

  return memoryBundle;
}

AlignedMemory getSsplitPrefixFileMemoryFromConfig(marian::Ptr<marian::Options> options) {
  std::string fpath = options->get<std::string>("ssplit-prefix-file", "");
  if (!fpath.empty()) {
    return loadFileToMemory(fpath, 64);
  }
  // Return empty AlignedMemory
  return AlignedMemory();
}

}  // namespace bergamot
}  // namespace marian
