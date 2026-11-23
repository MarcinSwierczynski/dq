package dq.engine;

import static dq.engine.TestRules.okRule;
import static dq.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dq.engine.api.CollectingListener;
import dq.engine.api.CountryScope;
import dq.engine.api.Decision;
import dq.engine.api.DqEngine;
import dq.engine.api.Provenance;
import dq.engine.api.RecordSources;
import dq.engine.api.Result;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RuleStatus;
import dq.engine.api.RunSummary;
import dq.engine.fixtures.Fixture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ScopeAndSelectionTest {

    @Test
    void aRuleScopedToAnotherCountryIsOutOfScopeAndItsLogicDoesNotRun() {
        var calls = new AtomicInteger();
        var deOnly = rule("deOnly", f -> { calls.incrementAndGet(); return "ok"; })
                .scope(CountryScope.country("DE")).build();
        var listener = new CollectingListener();

        DqEngine.builder().catalog(RuleCatalog.of(deOnly)).build()
                .validate(RecordSources.of(Fixture.RECORDS), listener);

        assertThat(calls).hasValue(1);
        Result r2 = listener.results().get(1);
        assertThat(r2.recordId()).isEqualTo("r2");
        assertThat(r2.decision()).isEqualTo(Decision.NOT_APPLICABLE);
        assertThat(r2.provenance().basis()).isEqualTo(Provenance.Basis.OUT_OF_SCOPE);
        assertThat(r2.provenance().computedValue()).isNull();
        assertThat(r2.provenance().fieldsRead()).containsExactly(Map.entry("country", "FR"));
        assertThat(listener.results().getFirst().decision()).isEqualTo(Decision.VALID);
    }

    @Test
    void aRecordWithoutCountryIsOutOfScopeForCountryRules() {
        var listener = new CollectingListener();
        DqEngine.builder().catalog(RuleCatalog.of(rule("de", f -> "ok").scope(CountryScope.country("DE")).build(),
                        okRule("world"))).build()
                .validate(RecordSources.of(List.of(Fixture.record("id", "x"))), listener);

        assertThat(listener.results()).extracting(r -> r.provenance().basis())
                .containsExactly(Provenance.Basis.OUT_OF_SCOPE, Provenance.Basis.MAPPED);
        assertThat(listener.results().getFirst().provenance().fieldsRead())
                .containsExactly(Map.entry("country", Provenance.ABSENT));
    }

    @Test
    void onlyReleasedRulesRun() {
        var listener = new CollectingListener();
        RunSummary summary = DqEngine.builder().catalog(RuleCatalog.of(
                        okRule("released"),
                        rule("draft", f -> "ok").status(RuleStatus.DRAFT).build(),
                        rule("deprecated", f -> "ok").status(RuleStatus.DEPRECATED).build())).build()
                .validate(RecordSources.of(Fixture.RECORDS), listener);

        assertThat(summary.rulesSelected()).isEqualTo(1);
        assertThat(listener.results()).extracting(Result::ruleId).containsOnly("released");
    }

    @Test
    void recordIdFallsBackToPositionAndFieldNamesAreConfigurable() {
        var listener = new CollectingListener();
        DqEngine.builder().catalog(RuleCatalog.of(okRule("any"), rule("pl", f -> "ok")
                        .scope(CountryScope.country("PL")).build()))
                .recordIdField("partnerId").countryField("land").build()
                .validate(RecordSources.of(List.of(
                        Fixture.record("partnerId", "p-1", "land", "PL"),
                        Fixture.record("id", "ignored", "land", "DE"),
                        Fixture.record("partnerId", 77))), listener);

        assertThat(listener.results()).extracting(Result::recordId, r -> r.provenance().recordIndex())
                .containsExactly(
                        tuple("p-1", 0L), tuple("p-1", 0L),
                        tuple("#1", 1L), tuple("#1", 1L),
                        tuple("77", 2L), tuple("77", 2L));
        assertThat(listener.results().get(1).provenance().basis()).isEqualTo(Provenance.Basis.MAPPED);
        assertThat(listener.results().get(3).provenance().basis()).isEqualTo(Provenance.Basis.OUT_OF_SCOPE);
    }

    @Test
    void emptyInputAndEmptySelectionCompleteWithZeroCounts() {
        RunSummary empty = DqEngine.builder().catalog(Fixture.catalog()).build()
                .validate(RecordSources.of(List.of()), new CollectingListener());
        assertThat(empty.recordsRead()).isZero();
        assertThat(empty.resultCount()).isZero();
        assertThat(empty.byDecision()).containsOnlyKeys(Decision.values()).allSatisfy((k, v) -> assertThat(v).isZero());

        RunSummary noRules = DqEngine.builder().catalog(RuleCatalog.of()).build()
                .validate(RecordSources.of(Fixture.RECORDS), new CollectingListener());
        assertThat(noRules.recordsRead()).isEqualTo(3);
        assertThat(noRules.rulesSelected()).isZero();
        assertThat(noRules.resultCount()).isZero();
    }

    @Test
    void oneEngineServesConcurrentRuns() throws Exception {
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> executor.submit(() ->
                            engine.validate(RecordSources.of(Fixture.RECORDS), new CollectingListener())))
                    .toList();
            for (var future : futures) {
                assertThat(future.get().resultCount()).isEqualTo(9);
            }
        }
    }
}
