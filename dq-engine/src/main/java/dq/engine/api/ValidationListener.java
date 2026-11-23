package dq.engine.api;

/**
 * Receives every outcome of a run as it is produced. Callbacks arrive on the thread that called
 * {@code validate}, one at a time and in order (rules in catalog order within input order), so
 * implementations need not be thread-safe.
 */
public interface ValidationListener {

    void onResult(Result result);

    void onError(EvaluationError error);
}
