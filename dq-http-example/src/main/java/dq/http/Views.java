package dq.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import dq.engine.catalog.Decision;
import dq.engine.catalog.Severity;
import dq.engine.run.EvaluationError;
import dq.engine.run.Result;
import dq.engine.run.RunSummary;
import java.util.List;
import java.util.Map;

/** JSON shapes of the HTTP API. Kept in the host so the library stays serialization-agnostic. */
final class Views {
    private Views() {}

    /** The buffered response: the run summary and one entry per input record, in input order. */
    record ValidationResponse(SummaryView summary, List<RecordView> records) {}

    /**
     * Every outcome for one input record.
     *
     * @param results the record's results keyed by rule id, in catalog order
     * @param errors  the record's evaluation errors (rule-level, or one record-level error for an
     *                unreadable element)
     */
    record RecordView(String recordId, Map<String, RuleResultView> results,
            List<ErrorView> errors) {}

    /**
     * One rule's result inside a {@link RecordView}; the rule and record are given by the enclosing entry.
     *
     * @param severity   how serious the finding is; omitted for {@link Decision#VALID} and
     *                   {@link Decision#NOT_APPLICABLE}, which are no findings
     * @param provenance why: the rule's explanation (e.g. {@code "len 4 instead of 9"}), or its label
     *                   when it gives none
     */
    record RuleResultView(Decision decision, @JsonInclude(JsonInclude.Include.NON_NULL) Severity severity,
            String provenance) {
        static RuleResultView of(Result r) {
            boolean finding = r.decision() != Decision.VALID && r.decision() != Decision.NOT_APPLICABLE;
            Severity severity = finding ? r.severity() : null;
            return new RuleResultView(r.decision(), severity,
                    r.provenance().explanation().orElse(r.provenance().ruleLabel()));
        }
    }

    /** The run's totals. The number of results is the sum of {@code byDecision}, so it is not repeated. */
    record SummaryView(long recordsRead, int rulesSelected, long errorCount,
            Map<Decision, Long> byDecision, Map<Severity, Long> bySeverity, long durationMs, String status) {
        static SummaryView of(RunSummary s) {
            return new SummaryView(s.recordsRead(), s.rulesSelected(), s.errorCount(),
                    s.byDecision(), s.bySeverity(), s.duration().toMillis(), s.status().name());
        }
    }

    /** An {@link EvaluationError} without the Throwable, which must not leak to clients. */
    record ErrorView(String ruleId, String recordId, String kind, String message,
            String causeType, Map<String, Object> fieldsRead) {
        static ErrorView of(EvaluationError e) {
            return new ErrorView(e.ruleId().orElse(null), e.recordId(), e.kind().name(),
                    e.message(), e.causeType().orElse(null), e.fieldsRead());
        }
    }
}
