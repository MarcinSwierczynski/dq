package dq.engine.run;

/**
 * Receives every outcome of a run as it is produced. Callbacks arrive on the thread that called
 * {@code validate}, one at a time and in order (rules in catalog order within input order), so
 * implementations need not be thread-safe.
 *
 * <p>An exception thrown from a callback aborts the run with {@link ValidationRunException}: the engine
 * never silently drops outcomes.
 */
public interface ValidationListener {

    void onResult(Result result);

    void onError(EvaluationError error);

    /**
     * Called after each fully evaluated batch; a natural flush or commit point. Does nothing by default.
     *
     * <p>A run that ends early does <b>not</b> get a call for its last, partial batch: when it is
     * {@link RunStatus#CANCELLED} (the interrupt is checked between records, also inside a batch) or
     * {@link RunStatus#ABORTED}, outcomes may have been delivered after the last {@code onBatchComplete}.
     * Hosts that commit per batch should also flush once {@code validate} returns or throws.
     */
    default void onBatchComplete(BatchProgress progress) {}
}
