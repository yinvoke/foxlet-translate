#include "batching_pool.h"

#include <algorithm>
#include <cassert>

#include "batch.h"
#include "common/logging.h"

namespace marian {
namespace bergamot {

BatchingPool::BatchingPool(Ptr<Options> options)
    : miniBatchWords_(options->get<int>("mini-batch-words")), maxActiveBucketLength_(0) {
  size_t maxLengthBreak = options->get<int>("max-length-break");
  float maxLengthFactor = options->get<float>("max-length-factor", 3.0);

  // For the time being, we add some slack, which only BatchingPool is aware of. Since the TextProcessor still wraps at
  // first request in, most of the Batches generated will be under max-length break.
  //
  // In the unlikely event of a few sentences overflowing, this allows the exceeding words to be put in the slack area.
  // Very few batches are expected to be generated at a higher length.
  size_t pivotSlack = maxLengthBreak * maxLengthFactor - maxLengthBreak;
  bucket_.resize(maxLengthBreak + pivotSlack + 1);

  ABORT_IF(bucket_.size() - 1 > miniBatchWords_,
           "Fatal: max-length-break > mini-batch-words  will lead to sentences "
           "longer than what can fit in a batch.");
}

size_t BatchingPool::generateBatch(Batch &batch) {
  size_t sentencesInBatch = fillBatch(batch);
  assert(sentencesInBatch <= pending_);
  pending_ -= sentencesInBatch;
  // A sentence cap describes one submission (see enqueueRequests). Once the pool has drained there is no submission
  // left to describe, so put the pool back to unbounded batches -- which is what the incremental enqueueRequest()
  // path expects to find.
  if (pending_ == 0) maxSentencesPerBatch_ = kUnboundedSentencesPerBatch;
  return sentencesInBatch;
}

size_t BatchingPool::fillBatch(Batch &batch) {
  // For now simply iterates on buckets and converts batches greedily.  This
  // has to be enhanced with optimizing over priority. The baseline
  // implementation should at least be as fast as marian's maxi-batch with full
  // corpus size as maxi-batch size.
  batch.clear();
  size_t paddedBatchSize = 0;

  for (size_t length = 0; length <= maxActiveBucketLength_; length++) {
    auto p = bucket_[length].begin();
    while (p != bucket_[length].end()) {
      paddedBatchSize = (batch.size() + 1) * length;
      if (paddedBatchSize <= miniBatchWords_) {
        auto q = p++;
        batch.add(*q);
        bucket_[length].erase(q);
        // Cap reached: end the batch here. The traversal order is untouched, so this only ever cuts a batch short at
        // a point that is itself a function of the submission -- the next batch resumes exactly where this one left.
        if (batch.size() >= maxSentencesPerBatch_) {
          return batch.size();
        }
      } else {
        // Check if elements exist
        assert(batch.size() > 0);
        return batch.size();
      }
    }
  }

  return batch.size();
}

size_t BatchingPool::enqueueRequest(Ptr<Request> request) {
  size_t toBeFreshlyTranslated = 0;
  for (size_t i = 0; i < request->numSegments(); i++) {
    if (!request->cacheHitPrefilled(i)) {
      RequestSentence sentence(i, request);
      size_t bucket_id = sentence.numTokens();

      // Due to a workaround for pivoting, unless we can discipline the
      // vocabulary to get stronger static requirements, it is difficult to
      // rework the rest of the components. Instead, we allow dynamic growth
      // here. We let std::vector take care of the dynamic growth.
      // https://en.cppreference.com/w/cpp/container/vector/resize#Complexity
      if (bucket_id >= bucket_.size()) {
        bucket_.resize(bucket_id + 1);
      }

      bucket_[bucket_id].insert(sentence);
      maxActiveBucketLength_ = std::max<size_t>(bucket_id, maxActiveBucketLength_);

      toBeFreshlyTranslated += 1;
    }
  }

  pending_ += toBeFreshlyTranslated;
  return toBeFreshlyTranslated;
}

size_t BatchingPool::countBatches() const {
  // Walks the buckets exactly as fillBatch() does, without consuming them. Cheap: one pass over the pool.
  size_t batches = 0;
  size_t sentencesInBatch = 0;
  for (size_t length = 0; length <= maxActiveBucketLength_ && length < bucket_.size(); length++) {
    for (size_t i = 0; i < bucket_[length].size(); i++) {
      if ((sentencesInBatch + 1) * length > miniBatchWords_) {
        // fillBatch() would have returned here; the next call resumes at this very sentence, because every bucket
        // below this one is empty by then.
        batches += 1;
        sentencesInBatch = 0;
      }
      sentencesInBatch += 1;
    }
  }
  if (sentencesInBatch > 0) batches += 1;
  return batches;
}

size_t BatchingPool::enqueueRequests(const std::vector<Ptr<Request>> &requests, size_t numWorkers) {
  size_t toBeFreshlyTranslated = 0;
  for (const Ptr<Request> &request : requests) {
    toBeFreshlyTranslated += enqueueRequest(request);
  }

  // Only step in when the submission would otherwise leave workers with nothing to do. Anything that already fills
  // every worker is left exactly as it was -- which is what keeps a page or a document byte-identical to what
  // BlockingService produces, at any worker count. An all-cache-hit submission enqueues nothing and must not set a
  // cap of 0.
  //
  // Ceiling division: S sentences over W workers gives at least W batches whenever S >= W, and never a cap below 1,
  // so the pool can always make progress.
  const size_t workers = std::max<size_t>(numWorkers, 1);
  if (toBeFreshlyTranslated > 0 && countBatches() < workers) {
    maxSentencesPerBatch_ = (toBeFreshlyTranslated + workers - 1) / workers;
  }

  return toBeFreshlyTranslated;
}

void BatchingPool::clear() {
  for (size_t length = 0; length < bucket_.size(); length++) {
    bucket_[length].clear();
  }
  pending_ = 0;
  maxSentencesPerBatch_ = kUnboundedSentencesPerBatch;
}

}  // namespace bergamot
}  // namespace marian
