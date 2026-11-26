package dq.engine.run;

import dq.engine.catalog.RuleFilter;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Per-run settings. Immutable; create with {@link #builder()} or {@link #defaults()}.
 *
 * <p>A final class rather than a record, so that later minor versions can add settings (with builder
 * methods and defaults) without breaking callers.
 */
@Value
@Accessors(fluent = true)
public class RunOptions {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    private static final RunOptions DEFAULTS = builder().build();

    /**
     * Records buffered, evaluated and acknowledged via {@link ValidationListener#onBatchComplete} at a
     * time; at least 1, default {@value #DEFAULT_BATCH_SIZE}. The engine holds one whole batch in memory,
     * so a host that takes this value from untrusted input should cap it.
     */
    int batchSize;

    /** Rule selection; default {@link RuleFilter#all()} (RELEASED is always enforced). */
    RuleFilter filter;

    /** Null when there is none; exposed as {@link #ruleTimeout()}. */
    Duration ruleTimeout;

    /**
     * @throws IllegalArgumentException if {@code batchSize < 1} or {@code ruleTimeout} is not positive
     * @throws NullPointerException     if {@code filter} is null
     */
    @lombok.Builder(builderClassName = "Builder")
    private RunOptions(int batchSize, RuleFilter filter, Duration ruleTimeout) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        if (ruleTimeout != null && (ruleTimeout.isNegative() || ruleTimeout.isZero())) {
            throw new IllegalArgumentException("ruleTimeout must be positive, was " + ruleTimeout);
        }
        this.batchSize = batchSize;
        this.filter = Objects.requireNonNull(filter, "filter");
        this.ruleTimeout = ruleTimeout;
    }

    /**
     * How long one (rule, record) evaluation — logic and explainer — may take; empty (the default) for no
     * limit. An evaluation that overruns yields {@link ErrorKind#TIMEOUT} and the run moves on.
     *
     * <p>With a timeout, each evaluation runs on its own virtual thread while the calling thread waits,
     * so rule code must not depend on running on the calling thread (thread-locals, or interrupting it
     * to cancel the run). An overrunning rule is interrupted and abandoned: one that ignores the
     * interrupt keeps its thread, and a CPU-bound one keeps a carrier thread busy, until it returns.
     * Listener callbacks still run on the calling thread.
     */
    public Optional<Duration> ruleTimeout() {
        return Optional.ofNullable(ruleTimeout);
    }

    /** Batch size {@value #DEFAULT_BATCH_SIZE}, every rule selected (RELEASED is always enforced), no rule timeout. */
    public static RunOptions defaults() {
        return DEFAULTS;
    }

    /** Builder for {@link RunOptions}; starts from the defaults. */
    public static final class Builder {
        private int batchSize = DEFAULT_BATCH_SIZE;
        private RuleFilter filter = RuleFilter.all();
        private Duration ruleTimeout;

        private Builder() {}
    }
}
