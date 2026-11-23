package dq.engine.api;

/** Counts outcomes and batches without keeping them; constant memory. Not thread-safe. */
public final class CountingListener implements ValidationListener {
    private long results;
    private long errors;
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

    public long results() {
        return results;
    }

    public long errors() {
        return errors;
    }

    public long batches() {
        return batches;
    }
}
