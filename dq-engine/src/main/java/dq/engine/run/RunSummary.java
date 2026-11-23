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
 * @param recordsRead   input elements evaluated, including unreadable ones
 * @param rulesSelected RELEASED rules that matched the run's filter
 * @param resultCount   results delivered
 * @param errorCount    evaluation errors delivered
 * @param byDecision    results per decision; every key present
 * @param bySeverity    findings ({@link Decision#INVALID} and {@link Decision#REVIEW} results) per their
 *                      rule's severity; {@code VALID} and {@code NOT_APPLICABLE} results are not counted.
 *                      Every key present
 * @param duration      wall-clock time of the run
 * @param status        how the run ended
 */
public record RunSummary(
        long recordsRead,
        int rulesSelected,
        long resultCount,
        long errorCount,
        Map<Decision, Long> byDecision,
        Map<Severity, Long> bySeverity,
        Duration duration,
        RunStatus status) {

    public RunSummary {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(status, "status");
        byDecision = complete(Decision.class, byDecision);
        bySeverity = complete(Severity.class, bySeverity);
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
