package dq.engine.run;

import java.util.Objects;

/**
 * A host-owned failure — the listener threw, or the record source failed — stopped the run. Outcomes
 * delivered before the failure stay delivered; {@link #partialSummary()} counts exactly those.
 * {@link #getCause()} is the original listener or source exception.
 */
public final class ValidationRunException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient RunSummary partialSummary;

    ValidationRunException(String message, Throwable cause, RunSummary partialSummary) {
        super(message, cause);
        this.partialSummary = Objects.requireNonNull(partialSummary, "partialSummary");
    }

    /** Counts of what was delivered before the failure; status {@link RunStatus#ABORTED}. */
    public RunSummary partialSummary() {
        return partialSummary;
    }
}
