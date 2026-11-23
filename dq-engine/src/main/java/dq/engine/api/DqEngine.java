package dq.engine.api;

import dq.engine.internal.RunExecution;
import java.util.List;
import java.util.Objects;

/**
 * Validates records against a rule catalog. Immutable and thread-safe: one instance can serve many
 * concurrent runs, each run keeping its state on its own call stack.
 *
 * <pre>{@code
 * DqEngine engine = DqEngine.builder().catalog(RuleCatalog.of(rules)).build();
 * RunSummary summary = engine.validate(RecordSources.jsonArray(in), listener);
 * }</pre>
 */
public final class DqEngine {
    private final List<Rule> rules;
    private final String recordIdField;
    private final String countryField;

    private DqEngine(Builder builder) {
        this.rules = builder.rules;
        this.recordIdField = builder.recordIdField;
        this.countryField = builder.countryField;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Equivalent to {@code validate(source, listener, RunOptions.defaults())}. */
    public RunSummary validate(RecordSource source, ValidationListener listener) {
        return validate(source, listener, RunOptions.defaults());
    }

    /**
     * Evaluates every selected rule on every record of {@code source}, delivering each outcome to
     * {@code listener} as soon as it is produced, and closes the source.
     *
     * @return the summary, with status {@link RunStatus#COMPLETED}, or {@link RunStatus#CANCELLED} when
     *     the calling thread was interrupted (its interrupt flag stays set)
     * @throws ValidationRunException if the listener or the source fails; carries the partial summary
     * @throws RuntimeException whatever the run's {@link RuleFilter} throws, unchanged: a failing filter is
     *     a programming error. It happens before any record is read, and the source is still closed.
     */
    public RunSummary validate(RecordSource source, ValidationListener listener, RunOptions options) {
        Objects.requireNonNull(source, "source");
        if (listener == null || options == null) {
            // The source is closed on every path, misuse included; a failing close must not hide the misuse.
            var misuse = new NullPointerException(listener == null ? "listener" : "options");
            try {
                source.close();
            } catch (RuntimeException closeFailure) {
                misuse.addSuppressed(closeFailure);
            }
            throw misuse;
        }
        return new RunExecution(rules, recordIdField, countryField, source, listener, options).run();
    }

    /** The validated catalog snapshot this engine evaluates, in order. */
    public List<Rule> rules() {
        return rules;
    }

    public static final class Builder {
        private RuleCatalog catalog;
        private List<Rule> rules;
        private String recordIdField = "id";
        private String countryField = "country";

        private Builder() {}

        /** Required. Read once, at {@link #build()}. */
        public Builder catalog(RuleCatalog catalog) {
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            return this;
        }

        /** Field holding the record id; default {@code "id"}. Records without it get {@code #<index>}. */
        public Builder recordIdField(String recordIdField) {
            this.recordIdField = requireName(recordIdField, "recordIdField");
            return this;
        }

        /** Field holding the record's country, used for scope checks; default {@code "country"}. */
        public Builder countryField(String countryField) {
            this.countryField = requireName(countryField, "countryField");
            return this;
        }

        /** @throws InvalidCatalogException if the catalog is invalid (null rule, duplicate id) */
        public DqEngine build() {
            if (catalog == null) {
                throw new IllegalStateException("catalog is required");
            }
            List<Rule> supplied = catalog.rules();
            if (supplied == null) {
                throw new InvalidCatalogException("catalog returned no rule list");
            }
            this.rules = RuleCatalog.validated(supplied);
            return new DqEngine(this);
        }

        private static String requireName(String name, String what) {
            Objects.requireNonNull(name, what);
            if (name.isBlank()) {
                throw new IllegalArgumentException(what + " must not be blank");
            }
            return name;
        }
    }
}
