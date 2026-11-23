package dq.engine.api;

import java.util.Objects;

/**
 * Per-run settings.
 *
 * @param batchSize records buffered, evaluated and acknowledged via
 *                  {@link ValidationListener#onBatchComplete} at a time; at least 1, default 1000
 * @param filter    rule selection; default {@link RuleFilter#all()} (RELEASED is always enforced)
 */
public record RunOptions(int batchSize, RuleFilter filter) {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    public RunOptions {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        Objects.requireNonNull(filter, "filter");
    }

    public static RunOptions defaults() {
        return new RunOptions(DEFAULT_BATCH_SIZE, RuleFilter.all());
    }

    public static Builder builder() {
        return new Builder();
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

        public RunOptions build() {
            return new RunOptions(batchSize, filter);
        }
    }
}
