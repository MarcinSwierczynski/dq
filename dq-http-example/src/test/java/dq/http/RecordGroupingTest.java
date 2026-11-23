package dq.http;

import static org.assertj.core.api.Assertions.assertThat;

import dq.engine.catalog.Decision;
import dq.engine.catalog.Severity;
import dq.engine.run.BatchProgress;
import dq.engine.run.ErrorKind;
import dq.engine.run.EvaluationError;
import dq.engine.run.Provenance;
import dq.engine.run.Result;
import dq.http.Views.RecordView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordGroupingTest {

    private final List<RecordView> records = new ArrayList<>();
    private final RecordGrouping grouping = new RecordGrouping(records::add);

    private static Result result(String rule, String recordId, long index) {
        return new Result(rule, recordId, Decision.VALID, Severity.INFO,
                new Provenance(rule, rule, recordId, index, new LinkedHashMap<>(), "ok", Provenance.Basis.MAPPED));
    }

    private static EvaluationError error(Optional<String> rule, String recordId, long index) {
        return new EvaluationError(rule, recordId, index, ErrorKind.RULE_EXCEPTION, "boom", null,
                new LinkedHashMap<>(), null);
    }

    @Test
    void groupsByIndexSoRepeatedIdsStaySeparate() {
        grouping.onResult(result("a", "dup", 0));
        grouping.onResult(result("b", "dup", 0));
        grouping.onResult(result("a", "dup", 1));
        grouping.flush();

        assertThat(records).extracting(RecordView::recordId).containsExactly("dup", "dup");
        assertThat(records.getFirst().results()).containsOnlyKeys("a", "b");
        assertThat(records.get(1).results()).containsOnlyKeys("a");
    }

    @Test
    void aRecordCanHoldResultsAndErrorsOrOnlyErrors() {
        grouping.onResult(result("a", "r1", 0));
        grouping.onError(error(Optional.of("b"), "r1", 0));
        grouping.onError(error(Optional.empty(), "#1", 1));
        grouping.flush();

        assertThat(records.getFirst().results()).containsOnlyKeys("a");
        assertThat(records.getFirst().errors()).extracting(Views.ErrorView::ruleId).containsExactly("b");
        assertThat(records.get(1).results()).isEmpty();
        assertThat(records.get(1).errors()).hasSize(1);
    }

    @Test
    void theEndOfABatchEmitsTheCurrentRecordAndFlushIsIdempotent() {
        grouping.onResult(result("a", "r1", 0));
        grouping.onBatchComplete(new BatchProgress(1, 1, 1));
        assertThat(records).hasSize(1);

        grouping.flush();
        grouping.flush();
        assertThat(records).hasSize(1);
    }

    @Test
    void provenanceIsTheExplanationOrElseTheRuleLabel() {
        var explained = new Result("vat", "r1", Decision.INVALID, Severity.ERROR, new Provenance("vat", "VAT label",
                "r1", 0, new LinkedHashMap<>(), "bad", Provenance.Basis.MAPPED, "len 4 instead of 9"));

        assertThat(Views.RuleResultView.of(explained).provenance()).isEqualTo("len 4 instead of 9");
        assertThat(Views.RuleResultView.of(result("plain", "r1", 0)).provenance()).isEqualTo("plain");
    }

    @Test
    void aRunWithoutOutcomesEmitsNothing() {
        grouping.flush();
        assertThat(records).isEmpty();
    }
}
