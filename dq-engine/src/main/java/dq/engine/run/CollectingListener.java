package dq.engine.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every outcome in memory. Convenient for small batches and tests; memory grows with the run,
 * so prefer a streaming listener for large inputs. Not thread-safe.
 */
public final class CollectingListener implements ValidationListener {
    private final List<Result> results = new ArrayList<>();
    private final List<EvaluationError> errors = new ArrayList<>();

    public CollectingListener() {}

    @Override
    public void onResult(Result result) {
        results.add(result);
    }

    @Override
    public void onError(EvaluationError error) {
        errors.add(error);
    }

    /** An unmodifiable snapshot of the results received so far. */
    public List<Result> results() {
        return List.copyOf(results);
    }

    /** An unmodifiable snapshot of the errors received so far. */
    public List<EvaluationError> errors() {
        return List.copyOf(errors);
    }
}
