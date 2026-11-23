package dq.engine.run;

import dq.engine.catalog.Decision;
import dq.engine.catalog.Severity;
import java.util.Objects;

/**
 * The outcome of one rule on one record.
 *
 * @param ruleId     the rule's id
 * @param recordId   the record's id (see {@link Provenance#recordId()})
 * @param decision   the decision from the rule's mapping
 * @param severity   the rule's severity
 * @param provenance the explanation of the decision
 */
public record Result(String ruleId, String recordId, Decision decision, Severity severity, Provenance provenance) {
    public Result {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(provenance, "provenance");
    }
}
