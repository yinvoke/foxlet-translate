#ifndef SRC_BERGAMOT_BATCHING_POOL_H_
#define SRC_BERGAMOT_BATCHING_POOL_H_

#include <limits>
#include <set>
#include <vector>

#include "batch.h"
#include "common/options.h"
#include "data/corpus_base.h"
#include "definitions.h"
#include "request.h"

namespace marian {
namespace bergamot {

class BatchingPool {
 public:
  explicit BatchingPool(Ptr<Options> options);

  // RequestSentence incorporates (tentative) notions of priority with each
  // sentence. This method inserts the sentence into the internal data-structure
  // which maintains priority among sentences from multiple concurrent requests.
  size_t enqueueRequest(Ptr<Request> request);

  /// Enqueue a whole submission at once, and -- only if the submission would otherwise yield fewer batches than
  /// there are workers -- bound how many sentences any one batch drawn from it may hold, so that it is spread over
  /// `numWorkers` batches instead of being handed to a single worker.
  ///
  /// The bound is ceil(S / numWorkers) over the S sentences this call actually enqueues (cache hits do not count).
  /// It is a function of the submission and the worker count alone, which is what keeps the output bytes
  /// reproducible: the greedy fill below still walks the buckets in exactly the same order, it just stops earlier.
  ///
  /// A submission that already fills every worker is left completely untouched, so its batches -- and its bytes --
  /// stay identical to what the same texts produce with one worker, or through BlockingService.
  ///
  /// Sentences, not words: a word bound would have to be at least bucket_.size() - 1 for a single long sentence to
  /// still fit, which is far more than a handful of short lines add up to and so would never bind where it is needed.
  /// One sentence always fits in a batch, so no bound can stall the pool.
  ///
  /// The bound applies to this submission only -- it is dropped again as soon as the pool has drained -- and is never
  /// set by enqueueRequest(), whose batches stay unbounded as they have always been.
  ///
  /// @param [in] requests: Requests to enqueue, in the order given.
  /// @param [in] numWorkers: How many consumers will draw batches from this pool. Treated as 1 if 0.
  /// @returns number of sentences added for translation.
  size_t enqueueRequests(const std::vector<Ptr<Request>> &requests, size_t numWorkers);

  // Loads sentences with sentences compiled from (tentatively) multiple
  // requests optimizing for both padding and priority.
  size_t generateBatch(Batch &batch);

  // Removes any pending requests from the pool.
  void clear();

 private:
  /// The greedy fill itself. generateBatch() wraps it with the bookkeeping that retires a drained submission.
  size_t fillBatch(Batch &batch);

  /// How many batches the pool's current contents would yield, uncapped. Dry run of fillBatch().
  size_t countBatches() const;

  static constexpr size_t kUnboundedSentencesPerBatch = std::numeric_limits<size_t>::max();

  size_t miniBatchWords_;
  std::vector<std::set<RequestSentence>> bucket_;
  size_t batchNumber_{0};
  size_t maxActiveBucketLength_;

  /// Sentences sitting in bucket_, i.e. enqueued and not yet handed out in a batch.
  size_t pending_{0};

  /// Ceiling on the sentences a single generated batch may hold. See enqueueRequests().
  size_t maxSentencesPerBatch_{kUnboundedSentencesPerBatch};
};

}  // namespace bergamot
}  // namespace marian

#endif  // SRC_BERGAMOT_BATCHING_POOL_H_
