package dq.engine.run;

import dq.engine.catalog.RuleFilter;
import java.util.Objects;
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

    /**
     * @throws IllegalArgumentException if {@code batchSize < 1}
     * @throws NullPointerException     if {@code filter} is null
     */
    @lombok.Builder(builderClassName = "Builder")
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

    /** Builder for {@link RunOptions}; starts from the defaults. */
    public static final class Builder {
        private int batchSize = DEFAULT_BATCH_SIZE;
        private RuleFilter filter = RuleFilter.all();

        private Builder() {}
    }
}
