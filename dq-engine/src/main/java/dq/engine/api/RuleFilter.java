package dq.engine.api;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Selects the rules of a run. Combine filters with {@link #and}, {@link #or} and {@link #not}.
 * Whatever a filter selects, the engine additionally requires {@link RuleStatus#RELEASED}.
 *
 * <p>Example: {@code RuleFilter.category("tax").and(RuleFilter.countryScope("DE"))}.
 */
@FunctionalInterface
public interface RuleFilter {

    boolean test(Rule rule);

    /** Selects every rule. */
    static RuleFilter all() {
        return rule -> true;
    }

    /** Selects rules whose status is one of {@code statuses}. */
    static RuleFilter status(RuleStatus first, RuleStatus... more) {
        Set<RuleStatus> wanted = EnumSet.of(Objects.requireNonNull(first, "status"), more);
        return rule -> wanted.contains(rule.status());
    }

    /**
     * Run-level country selection: keeps rules whose scope is WORLD or one of {@code countries}.
     * (Independently, every rule is still checked against each record's own country.)
     */
    static RuleFilter countryScope(String... countries) {
        List<CountryScope> wanted = List.of(countries).stream().map(CountryScope::country).toList();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("countryScope needs at least one country");
        }
        return rule -> rule.scope() instanceof CountryScope.World || wanted.contains(rule.scope());
    }

    /** Selects rules that carry at least one of {@code categories}. */
    static RuleFilter category(String... categories) {
        Set<String> wanted = Set.copyOf(List.of(categories));
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("category needs at least one category");
        }
        return rule -> rule.categories().stream().anyMatch(wanted::contains);
    }

    /** Selects rules that {@code filter} rejects. */
    static RuleFilter not(RuleFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return rule -> !filter.test(rule);
    }

    default RuleFilter and(RuleFilter other) {
        Objects.requireNonNull(other, "other");
        return rule -> test(rule) && other.test(rule);
    }

    default RuleFilter or(RuleFilter other) {
        Objects.requireNonNull(other, "other");
        return rule -> test(rule) || other.test(rule);
    }
}
