package dq.engine.run;

/** How a validation run ended. */
public enum RunStatus {
    /** Every record of the source was evaluated. */
    COMPLETED,
    /** The calling thread was interrupted; the run stopped between records. */
    CANCELLED,
    /** A host-owned failure (listener or input stream) stopped the run; see {@link ValidationRunException}. */
    ABORTED
}
