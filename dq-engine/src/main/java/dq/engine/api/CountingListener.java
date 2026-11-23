package dq.engine.api;

import lombok.Getter;
import lombok.experimental.Accessors;

/** Counts outcomes and batches without keeping them; constant memory. Not thread-safe. */
@Getter
@Accessors(fluent = true)
public final class CountingListener implements ValidationListener {
    /** Results received so far. */
    private long results;
    /** Errors received so far. */
    private long errors;
    /** Completed batches so far. */
    private long batches;

    public CountingListener() {}

    @Override
    public void onResult(Result result) {
        results++;
    }

    @Override
    public void onError(EvaluationError error) {
        errors++;
    }

    @Override
    public void onBatchComplete(BatchProgress progress) {
        batches++;
    }
}
