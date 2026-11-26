package dq.engine.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rule code by name, so rule definitions can be data. A catalog stored as data (for example
 * {@code dq.engine.json.JsonRuleCatalog}) names the {@link RuleLogic} and {@link RuleExplainer} each rule
 * uses, and resolves them here when it is loaded. Logic and explainers have separate name spaces.
 * Immutable and thread-safe; build it with {@link #builder()}.
 */
public final class LogicRegistry {
    private final Map<String, RuleLogic> logic;
    private final Map<String, RuleExplainer> explainers;

    private LogicRegistry(Builder builder) {
        this.logic = Collections.unmodifiableMap(new LinkedHashMap<>(builder.logic));
        this.explainers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.explainers));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The logic registered as {@code name}, if any. */
    public Optional<RuleLogic> logic(String name) {
        return Optional.ofNullable(logic.get(Objects.requireNonNull(name, "name")));
    }

    /** The explainer registered as {@code name}, if any. */
    public Optional<RuleExplainer> explainer(String name) {
        return Optional.ofNullable(explainers.get(Objects.requireNonNull(name, "name")));
    }

    /** Names of the registered logic, in registration order. */
    public Set<String> logicNames() {
        return logic.keySet();
    }

    /** Names of the registered explainers, in registration order. */
    public Set<String> explainerNames() {
        return explainers.keySet();
    }

    /** Builder for {@link LogicRegistry}. A name may be registered once per kind. */
    public static final class Builder {
        private final Map<String, RuleLogic> logic = new LinkedHashMap<>();
        private final Map<String, RuleExplainer> explainers = new LinkedHashMap<>();

        private Builder() {}

        /** @throws IllegalArgumentException if {@code name} is blank or already has logic */
        public Builder logic(String name, RuleLogic logic) {
            register(this.logic, name, Objects.requireNonNull(logic, "logic"), "logic");
            return this;
        }

        /** @throws IllegalArgumentException if {@code name} is blank or already has an explainer */
        public Builder explainer(String name, RuleExplainer explainer) {
            register(explainers, name, Objects.requireNonNull(explainer, "explainer"), "explainer");
            return this;
        }

        public LogicRegistry build() {
            return new LogicRegistry(this);
        }

        private static <T> void register(Map<String, T> into, String name, T value, String what) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException(what + " name must not be blank");
            }
            if (into.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException(what + " '" + name + "' is registered twice");
            }
        }
    }
}
