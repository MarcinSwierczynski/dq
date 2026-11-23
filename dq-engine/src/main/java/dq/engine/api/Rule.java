package dq.engine.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One data-quality rule: identity, logic, decision mapping and metadata. Immutable; build it with
 * {@link #builder()}.
 *
 * @param id         stable identifier, unique within a catalog
 * @param label      human-readable name
 * @param logic      computes a value from the record's fields
 * @param mapping    turns that value into a decision
 * @param status     lifecycle state; only {@link RuleStatus#RELEASED} rules run
 * @param severity   copied onto every result of this rule
 * @param scope      countries the rule applies to; {@link CountryScope#world()} by default
 * @param categories zero or more free-form categories used for selection
 * @param explainer  turns what the logic read and computed into a reason; {@link RuleExplainer#none()}
 *                   by default
 */
public record Rule(
        String id,
        String label,
        RuleLogic logic,
        DecisionMapping mapping,
        RuleStatus status,
        Severity severity,
        CountryScope scope,
        Set<String> categories,
        RuleExplainer explainer) {

    /** @throws InvalidCatalogException if a required component is missing or blank */
    public Rule {
        if (id == null || id.isBlank()) {
            throw new InvalidCatalogException("rule id must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new InvalidCatalogException("rule '" + id + "': label must not be blank");
        }
        requirePresent(id, logic, "logic");
        requirePresent(id, mapping, "decision mapping");
        requirePresent(id, status, "status");
        requirePresent(id, severity, "severity");
        requirePresent(id, scope, "country scope");
        requirePresent(id, categories, "categories");
        var copy = new LinkedHashSet<String>();
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                throw new InvalidCatalogException("rule '" + id + "': categories must not be blank");
            }
            copy.add(category);
        }
        categories = Collections.unmodifiableSequencedSet(copy);
        explainer = explainer == null ? RuleExplainer.none() : explainer;
    }

    private static void requirePresent(String id, Object value, String what) {
        if (value == null) {
            throw new InvalidCatalogException("rule '" + id + "': " + what + " is required");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link Rule}. {@code scope} defaults to WORLD and {@code categories} to none. */
    public static final class Builder {
        private String id;
        private String label;
        private RuleLogic logic;
        private DecisionMapping mapping;
        private RuleStatus status;
        private Severity severity;
        private CountryScope scope = CountryScope.world();
        private final Set<String> categories = new LinkedHashSet<>();
        private RuleExplainer explainer = RuleExplainer.none();

        private Builder() {}

        public Builder id(String id) { this.id = id; return this; }

        public Builder label(String label) { this.label = label; return this; }

        public Builder logic(RuleLogic logic) { this.logic = logic; return this; }

        public Builder mapping(DecisionMapping mapping) { this.mapping = mapping; return this; }

        public Builder status(RuleStatus status) { this.status = status; return this; }

        public Builder severity(Severity severity) { this.severity = severity; return this; }

        public Builder scope(CountryScope scope) { this.scope = scope; return this; }

        /** Optional: a short reason for each result, e.g. {@code "len 4 instead of 9"}. */
        public Builder explain(RuleExplainer explainer) { this.explainer = explainer; return this; }

        public Builder category(String category) { this.categories.add(category); return this; }

        public Builder categories(Set<String> categories) {
            this.categories.clear();
            this.categories.addAll(Objects.requireNonNull(categories, "categories"));
            return this;
        }

        /** @throws InvalidCatalogException if a required component is missing or blank */
        public Rule build() {
            return new Rule(id, label, logic, mapping, status, severity, scope, categories, explainer);
        }
    }
}
