package dq.engine.api;

/**
 * Per-run settings.
 *
 * @param batchSize records buffered, evaluated and acknowledged via
 *                  {@link ValidationListener#onBatchComplete} at a time; at least 1, default 1000
 */
public record RunOptions(int batchSize) {

    public static final int DEFAULT_BATCH_SIZE = 1000;

    public RunOptions {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
    }

    public static RunOptions defaults() {
        return new RunOptions(DEFAULT_BATCH_SIZE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int batchSize = DEFAULT_BATCH_SIZE;

        private Builder() {}

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public RunOptions build() {
            return new RunOptions(batchSize);
        }
    }
}
