package dq.http;

import dq.engine.api.Decision;
import dq.engine.api.EvaluationError;
import dq.engine.api.Result;
import dq.engine.api.RunSummary;
import dq.engine.api.Severity;
import java.util.List;
import java.util.Map;

/** JSON shapes of the HTTP API. Kept in the host so the library stays serialization-agnostic. */
final class Views {
    private Views() {}

    record ValidationResponse(SummaryView summary, List<Result> results, List<ErrorView> errors) {}

    record SummaryView(long recordsRead, int rulesSelected, long resultCount, long errorCount,
            Map<Decision, Long> byDecision, Map<Severity, Long> bySeverity, long durationMs, String status) {
        static SummaryView of(RunSummary s) {
            return new SummaryView(s.recordsRead(), s.rulesSelected(), s.resultCount(), s.errorCount(),
                    s.byDecision(), s.bySeverity(), s.duration().toMillis(), s.status().name());
        }
    }

    /** An {@link EvaluationError} without the Throwable, which must not leak to clients. */
    record ErrorView(String ruleId, String recordId, long recordIndex, String kind, String message,
            String causeType, Map<String, Object> fieldsRead) {
        static ErrorView of(EvaluationError e) {
            return new ErrorView(e.ruleId().orElse(null), e.recordId(), e.recordIndex(), e.kind().name(),
                    e.message(), e.causeType(), e.fieldsRead());
        }
    }
}
