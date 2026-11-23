package dq.engine.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Supplies the rules an engine evaluates. Hosts may implement it over a database or a catalog service;
 * {@link DqEngine.Builder#build()} reads it once and validates the result, so the engine never sees a
 * catalog change mid-run.
 */
@FunctionalInterface
public interface RuleCatalog {

    /** The rules, in evaluation order. */
    List<Rule> rules();

    /** An in-memory catalog. @throws InvalidCatalogException on duplicate ids */
    static RuleCatalog of(Rule... rules) {
        return of(List.of(rules));
    }

    /** An in-memory catalog. @throws InvalidCatalogException on duplicate ids */
    static RuleCatalog of(List<Rule> rules) {
        List<Rule> copy = validated(rules);
        return () -> copy;
    }

    /**
     * Checks a rule list for null entries and duplicate ids and returns an immutable copy.
     *
     * @throws InvalidCatalogException if the list is invalid
     */
    static List<Rule> validated(List<Rule> rules) {
        Objects.requireNonNull(rules, "rules");
        var ids = new HashSet<String>();
        for (Rule rule : rules) {
            if (rule == null) {
                throw new InvalidCatalogException("catalog contains a null rule");
            }
            if (!ids.add(rule.id())) {
                throw new InvalidCatalogException("duplicate rule id '" + rule.id() + "'");
            }
        }
        return List.copyOf(rules);
    }
}
