package dq.engine.api;

/**
 * Passed to {@link ValidationListener#onBatchComplete} after every outcome of a batch was delivered.
 *
 * @param batchNumber    1-based batch counter
 * @param recordsInBatch records evaluated in this batch
 * @param recordsSoFar   records evaluated in the run so far, this batch included
 */
public record BatchProgress(long batchNumber, int recordsInBatch, long recordsSoFar) {}
