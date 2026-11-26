package dq.engine.run;

import dq.engine.catalog.Decision;
import dq.engine.catalog.Severity;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Totals of one validation run. Only outcomes whose listener call returned normally are counted.
 *
 * @param recordsRead        input elements evaluated, including unreadable ones
 * @param rulesSelected      RELEASED rules that matched the run's filter
 * @param resultCount        results delivered; the sum of {@code byDecision} and of {@code bySeverity}
 * @param errorCount         evaluation errors delivered
 * @param byDecision         results per decision; every key present
 * @param bySeverity         results per their rule's severity, whatever the decision; every key present
 * @param findingsBySeverity findings only ({@link Decision#INVALID} and {@link Decision#REVIEW} results)
 *                           per their rule's severity: how many problems of each severity the run found.
 *                           Every key present
 * @param duration           wall-clock time of the run
 * @param status             how the run ended
 */
public record RunSummary(
        long recordsRead,
        int rulesSelected,
        long resultCount,
        long errorCount,
        Map<Decision, Long> byDecision,
        Map<Severity, Long> bySeverity,
        Map<Severity, Long> findingsBySeverity,
        Duration duration,
        RunStatus status) {

    /** @throws IllegalArgumentException if {@code byDecision} or {@code bySeverity} does not sum to {@code resultCount} */
    public RunSummary {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(status, "status");
        byDecision = complete(Decision.class, byDecision);
        bySeverity = complete(Severity.class, bySeverity);
        findingsBySeverity = complete(Severity.class, findingsBySeverity);
        if (sum(byDecision) != resultCount || sum(bySeverity) != resultCount) {
            throw new IllegalArgumentException("byDecision and bySeverity must each sum to resultCount " + resultCount);
        }
    }

    private static long sum(Map<?, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static <K extends Enum<K>> Map<K, Long> complete(Class<K> type, Map<K, Long> counts) {
        Objects.requireNonNull(counts, type.getSimpleName() + " counts");
        var copy = new EnumMap<K, Long>(type);
        for (K key : type.getEnumConstants()) {
            copy.put(key, counts.getOrDefault(key, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }
}
