package dq.engine.api;

import java.util.Objects;

/**
 * Per-run settings. Immutable; create with {@link #builder()} or {@link #defaults()}.
 *
 * <p>A final class rather than a record, so that later minor versions can add settings (with builder
 * methods and defaults) without breaking callers.
 */
public final class RunOptions {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    private static final RunOptions DEFAULTS = new RunOptions(DEFAULT_BATCH_SIZE, RuleFilter.all());

    private final int batchSize;
    private final RuleFilter filter;

    private RunOptions(int batchSize, RuleFilter filter) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        this.batchSize = batchSize;
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    /** Batch size {@value #DEFAULT_BATCH_SIZE}, every rule selected (RELEASED is always enforced). */
    public static RunOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Records buffered, evaluated and acknowledged via {@link ValidationListener#onBatchComplete} at a
     * time; at least 1, default {@value #DEFAULT_BATCH_SIZE}. The engine holds one whole batch in memory,
     * so a host that takes this value from untrusted input should cap it.
     */
    public int batchSize() {
        return batchSize;
    }

    /** Rule selection; default {@link RuleFilter#all()} (RELEASED is always enforced). */
    public RuleFilter filter() {
        return filter;
    }

    @Override
    public String toString() {
        return "RunOptions[batchSize=" + batchSize + ", filter=" + filter + "]";
    }

    public static final class Builder {
        private int batchSize = DEFAULT_BATCH_SIZE;
        private RuleFilter filter = RuleFilter.all();

        private Builder() {}

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder filter(RuleFilter filter) {
            this.filter = filter;
            return this;
        }

        /** @throws IllegalArgumentException if {@code batchSize < 1}; NullPointerException if the filter is null */
        public RunOptions build() {
            return new RunOptions(batchSize, filter);
        }
    }
}
