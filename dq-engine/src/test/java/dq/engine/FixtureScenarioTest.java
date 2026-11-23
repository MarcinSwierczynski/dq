package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dq.engine.catalog.Decision;
import dq.engine.catalog.Severity;
import dq.engine.fixtures.Fixture;
import dq.engine.run.CollectingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.Provenance.Basis;
import dq.engine.run.Result;
import dq.engine.run.RunStatus;
import dq.engine.run.RunSummary;
import dq.engine.source.RecordSources;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** §5 acceptance test: the fixture records against the fixture rules. */
class FixtureScenarioTest {

    private final DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();
    private final CollectingListener listener = new CollectingListener();
    private final RunSummary summary = engine.validate(RecordSources.of(Fixture.RECORDS), listener);
    private final Map<String, Result> byCell = listener.results().stream()
            .collect(Collectors.toMap(r -> r.recordId() + "/" + r.ruleId(), Function.identity()));

    @Test
    void producesTheExpectedDecisionForEveryCell() {
        assertThat(listener.errors()).isEmpty();
        assertThat(byCell).hasSize(9);
        Fixture.EXPECTED.forEach((cell, decision) ->
                assertThat(byCell.get(cell).decision()).as(cell).isEqualTo(decision));
    }

    @Test
    void explainsEveryCellInPlainWords() {
        Map<String, String> expected = Map.of(
                "r1/countryBlocked", "country DE is not blocked", "r1/vatFormat", "len 11",
                "r1/ibanFormat", "iban starts with DE",
                "r2/countryBlocked", "country FR is not blocked", "r2/vatFormat", "len 4 instead of 9",
                "r2/ibanFormat", "iban starts with FR",
                "r3/countryBlocked", "country ZZ is blocked", "r3/vatFormat", "no vatId",
                "r3/ibanFormat", "empty iban");
        expected.forEach((cell, why) ->
                assertThat(byCell.get(cell).provenance().explanation()).as(cell).contains(why));
    }

    @Test
    void emitsInInputOrderThenCatalogOrder() {
        assertThat(listener.results()).extracting(r -> r.recordId() + "/" + r.ruleId()).containsExactly(
                "r1/countryBlocked", "r1/vatFormat", "r1/ibanFormat",
                "r2/countryBlocked", "r2/vatFormat", "r2/ibanFormat",
                "r3/countryBlocked", "r3/vatFormat", "r3/ibanFormat");
    }

    @Test
    void explainsTheDecisionWithProvenance() {
        Result r2Vat = byCell.get("r2/vatFormat");
        assertThat(r2Vat.severity()).isEqualTo(Severity.ERROR);
        assertThat(r2Vat.provenance().ruleId()).isEqualTo("vatFormat");
        assertThat(r2Vat.provenance().ruleLabel()).isEqualTo("VAT id has a plausible length");
        assertThat(r2Vat.provenance().recordId()).isEqualTo("r2");
        assertThat(r2Vat.provenance().recordIndex()).isEqualTo(1);
        assertThat(r2Vat.provenance().fieldsRead()).containsExactly(Map.entry("vatId", "FR22"));
        assertThat(r2Vat.provenance().computedValue()).isEqualTo("bad");
        assertThat(r2Vat.provenance().basis()).isEqualTo(Basis.MAPPED);
    }

    @Test
    void recordsAbsentFieldsAndDefaultFallback() {
        Result r3Vat = byCell.get("r3/vatFormat");
        assertThat(r3Vat.provenance().fieldsRead()).containsExactly(Map.entry("vatId", "<absent>"));

        Result r1Country = byCell.get("r1/countryBlocked");
        assertThat(r1Country.decision()).isEqualTo(Decision.NOT_APPLICABLE);
        assertThat(r1Country.provenance().computedValue()).isEqualTo("ok");
        assertThat(r1Country.provenance().basis()).isEqualTo(Basis.DEFAULT);

        Result r1Iban = byCell.get("r1/ibanFormat");
        assertThat(r1Iban.provenance().fieldsRead())
                .containsExactly(Map.entry("country", "DE"), Map.entry("iban", "DE123456789"));
    }

    @Test
    void summarisesTheRun() {
        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.recordsRead()).isEqualTo(3);
        assertThat(summary.rulesSelected()).isEqualTo(3);
        assertThat(summary.resultCount()).isEqualTo(9);
        assertThat(summary.errorCount()).isZero();
        assertThat(summary.byDecision()).containsExactly(
                Map.entry(Decision.VALID, 3L), Map.entry(Decision.INVALID, 4L),
                Map.entry(Decision.REVIEW, 0L), Map.entry(Decision.NOT_APPLICABLE, 2L));
        assertThat(summary.bySeverity()).containsExactly(
                Map.entry(Severity.ERROR, 3L), Map.entry(Severity.WARNING, 1L), Map.entry(Severity.INFO, 0L));
        assertThat(summary.duration()).isPositive();
    }
}
