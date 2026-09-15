#ifndef SRC_BERGAMOT_DEFINITIONS_H_
#define SRC_BERGAMOT_DEFINITIONS_H_

#include <vector>

#include "aligned.h"
#include "data/types.h"
#include "data/vocab_base.h"

namespace marian {
namespace bergamot {

typedef marian::Words Segment;
typedef std::vector<Segment> Segments;

/// Shortcut to AlignedVector<char> for byte arrays
typedef AlignedVector<char> AlignedMemory;

/// Memory bundle for all byte-arrays.
/// Can be a set/subset of model, shortlist, vocabs and ssplitPrefixFile bytes.
struct MemoryBundle {
  std::vector<AlignedMemory> models{};  ///< Byte-array of model (each element is aligned to 256)
  AlignedMemory shortlist{};            ///< Byte-array of shortlist (aligned to 64)

  /// Vector of vocabulary memories (aligned to 64).
  /// If two vocabularies are the same (based on the filenames), two entries (shared
  /// pointers) will be generated which share the same AlignedMemory object.
  std::vector<std::shared_ptr<AlignedMemory>> vocabs{};

  /// @todo Not implemented yet
  AlignedMemory ssplitPrefixFile{};

  AlignedMemory qualityEstimatorMemory;  ///< Byte-array of qe model (aligned to 64)

  /// Clear the memory after it has been loaded in.
  void clear() {
    models.clear();
    shortlist.release();
    vocabs.clear();
    ssplitPrefixFile.release();
    qualityEstimatorMemory.release();
  }
};

/// ByteRange stores indices for half-interval [begin, end) in a string. Can be
/// used to represent a sentence, word.
struct ByteRange {
  size_t begin;
  size_t end;
  const size_t size() const { return end - begin; }
  bool operator==(ByteRange other) const { return begin == other.begin && end == other.end; }
};

/// A Subword range is mechanically the same as a `ByteRange`, but instead of
/// describing a span of bytes, it describes a span of Subword tokens. Using
/// `Annotation.word()` you can switch between the two.
struct SubwordRange {
  size_t begin;
  size_t end;
  const size_t size() const { return end - begin; }
  bool operator==(SubwordRange other) const { return begin == other.begin && end == other.end; }
};

class Response;
using CallbackType = std::function<void(Response &&)>;

}  // namespace bergamot
}  // namespace marian

// Marian retains its own string_view alias. The project sentence scanner uses C++17 std::string_view.

#if defined(__GNUC__) && __GNUC__ < 6 && !defined(__clang__)
#include <experimental/string_view>
namespace std {
using string_view = std::experimental::string_view;
}  // namespace std
#else
#include <string_view>
#endif

#endif  // SRC_BERGAMOT_DEFINITIONS_H_
