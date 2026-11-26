package dq.engine.run;

import dq.engine.catalog.RuleFilter;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
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

    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

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

    /** Null for sequential evaluation; exposed as {@link #executor()}. */
    Executor executor;

    /**
     * With an {@link #executor()}: records submitted and not yet delivered, at most; at least 1, default
     * {@value #DEFAULT_MAX_IN_FLIGHT}. Bounds both concurrency and the outcomes held for reordering.
     * Ignored for sequential runs.
     */
    int maxInFlight;

    /**
     * @throws IllegalArgumentException if {@code batchSize < 1}, {@code maxInFlight < 1}, or
     *     {@code ruleTimeout} is not positive
     * @throws NullPointerException     if {@code filter} is null
     */
    @lombok.Builder(builderClassName = "Builder")
    private RunOptions(int batchSize, RuleFilter filter, Duration ruleTimeout, Executor executor, int maxInFlight) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be at least 1, was " + maxInFlight);
        }
        if (ruleTimeout != null && (ruleTimeout.isNegative() || ruleTimeout.isZero())) {
            throw new IllegalArgumentException("ruleTimeout must be positive, was " + ruleTimeout);
        }
        this.batchSize = batchSize;
        this.filter = Objects.requireNonNull(filter, "filter");
        this.ruleTimeout = ruleTimeout;
        this.executor = executor;
        this.maxInFlight = maxInFlight;
    }

    /**
     * Where records are evaluated; empty (the default) to evaluate them one by one on the calling thread.
     *
     * <p>With an executor, each record is one task, and up to {@link #maxInFlight()} run at a time. The
     * output is unchanged: the calling thread delivers every outcome, in input order and then catalog
     * order, so listeners still need not be thread-safe, and a batch is still delivered completely
     * before {@code onBatchComplete}. Use virtual threads for rules that block, or a fixed pool for
     * CPU-bound ones. The engine never shuts the executor down; a task it rejects aborts the run. Rule
     * code then runs on the executor's threads, so it must be thread-safe (as {@code RuleLogic}
     * already requires) and cannot cancel the run by interrupting itself.
     */
    public Optional<Executor> executor() {
        return Optional.ofNullable(executor);
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
        private Executor executor;
        private int maxInFlight = DEFAULT_MAX_IN_FLIGHT;

        private Builder() {}
    }
}
