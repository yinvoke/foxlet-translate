
#include "aggregate_batching_pool.h"

namespace marian {
namespace bergamot {

AggregateBatchingPool::AggregateBatchingPool() {
  // TODO(@jerinphilip): Set aggregate limits
}

size_t AggregateBatchingPool::enqueueRequest(Ptr<TranslationModel> model, Ptr<Request> request) {
  size_t sentencesEnqueued = model->enqueueRequest(request);
  aggregateQueue_.insert(model);
  return sentencesEnqueued;
}

size_t AggregateBatchingPool::enqueueRequests(Ptr<TranslationModel> model, const std::vector<Ptr<Request>>& requests,
                                             size_t numWorkers) {
  size_t sentencesEnqueued = model->enqueueRequests(requests, numWorkers);
  // Insert once, and only if there is something to find: an all-cache-hit set of requests adds no sentences, and the
  // aggregate queue must not be left holding a model whose pool is empty.
  if (sentencesEnqueued > 0) aggregateQueue_.insert(model);
  return sentencesEnqueued;
}

size_t AggregateBatchingPool::generateBatch(Ptr<TranslationModel>& model, Batch& batch) {
  while (!aggregateQueue_.empty()) {
    auto candidateItr = aggregateQueue_.begin();
    Ptr<TranslationModel> candidate = *candidateItr;
    size_t numSentences = candidate->generateBatch(batch);
    if (numSentences > 0) {
      model = candidate;
      return numSentences;
    } else {
      // Try the next model's batching pool.
      aggregateQueue_.erase(candidateItr);
    }
  }
  return /*numSentences=*/0;
}

void AggregateBatchingPool::clear() { aggregateQueue_.clear(); }

}  // namespace bergamot
}  // namespace marian
