package dq.engine.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The declarative part of a rule: a lookup table from a computed value to a {@link Decision}, with an
 * optional fallback. Rule logic never sees this type, so a rule cannot pick its own decision.
 *
 * @param cases           value → decision, in declaration order (unmodifiable)
 * @param defaultDecision decision for values not in {@code cases}, if any
 */
public record DecisionMapping(Map<String, Decision> cases, Optional<Decision> defaultDecision) {

    /**
     * @throws InvalidCatalogException if the mapping has neither cases nor a default
     */
    public DecisionMapping {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(defaultDecision, "defaultDecision");
        var copy = new LinkedHashMap<String, Decision>();
        cases.forEach((value, decision) -> copy.put(
                Objects.requireNonNull(value, "mapping value"),
                Objects.requireNonNull(decision, "mapping decision for '" + value + "'")));
        if (copy.isEmpty() && defaultDecision.isEmpty()) {
            throw new InvalidCatalogException("decision mapping must have at least one case or a default");
        }
        cases = Collections.unmodifiableSequencedMap(copy);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Looks up {@code value}: an exact case wins, then the default; otherwise the value is unmapped. */
    public Resolution resolve(String value) {
        Objects.requireNonNull(value, "value");
        Decision mapped = cases.get(value);
        if (mapped != null) {
            return new Resolution.Mapped(mapped);
        }
        return defaultDecision.<Resolution>map(Resolution.Defaulted::new).orElse(Resolution.Unmapped.INSTANCE);
    }

    /** Outcome of {@link #resolve(String)}. */
    public sealed interface Resolution {
        /** The value matched a case. */
        record Mapped(Decision decision) implements Resolution {}

        /** The value matched no case; the default applied. */
        record Defaulted(Decision decision) implements Resolution {}

        /** The value matched no case and there is no default. */
        record Unmapped() implements Resolution {
            static final Unmapped INSTANCE = new Unmapped();
        }
    }

    /** Fluent builder: {@code DecisionMapping.builder().when("ok", VALID).otherwise(INVALID).build()}. */
    public static final class Builder {
        private final Map<String, Decision> cases = new LinkedHashMap<>();
        private Decision defaultDecision;

        private Builder() {}

        public Builder when(String value, Decision decision) {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(decision, "decision");
            if (cases.putIfAbsent(value, decision) != null) {
                throw new InvalidCatalogException("decision mapping lists value '" + value + "' twice");
            }
            return this;
        }

        public Builder otherwise(Decision decision) {
            this.defaultDecision = Objects.requireNonNull(decision, "decision");
            return this;
        }

        public DecisionMapping build() {
            return new DecisionMapping(cases, Optional.ofNullable(defaultDecision));
        }
    }
}
