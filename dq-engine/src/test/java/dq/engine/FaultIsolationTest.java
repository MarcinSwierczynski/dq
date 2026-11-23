package dq.engine;

import static dq.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dq.engine.api.CollectingListener;
import dq.engine.api.Decision;
import dq.engine.api.DqEngine;
import dq.engine.api.ErrorKind;
import dq.engine.api.EvaluationError;
import dq.engine.api.RecordSources;
import dq.engine.api.Result;
import dq.engine.api.Rule;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RunStatus;
import dq.engine.api.RunSummary;
import dq.engine.api.ValidationListener;
import dq.engine.api.ValidationRunException;
import dq.engine.fixtures.Fixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FaultIsolationTest {

    private static RunSummary run(CollectingListener listener, Rule... extra) {
        var rules = new ArrayList<>(Fixture.RULES);
        rules.addAll(List.of(extra));
        return DqEngine.builder().catalog(RuleCatalog.of(rules)).build()
                .validate(RecordSources.of(Fixture.RECORDS), listener);
    }

    @Test
    void aThrowingRuleFailsOnlyItsOwnCell() {
        Rule faulty = rule("faulty", f -> {
            if (f.require("id").equals("r2")) {
                throw new IllegalStateException("boom on r2");
            }
            return "ok";
        }).build();
        var listener = new CollectingListener();

        RunSummary summary = run(listener, faulty);

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.resultCount()).isEqualTo(11);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(listener.results().stream().filter(r -> !r.ruleId().equals("faulty"))
                .collect(java.util.stream.Collectors.toMap(r -> r.recordId() + "/" + r.ruleId(), Result::decision)))
                .isEqualTo(Fixture.EXPECTED);
        assertThat(listener.results()).filteredOn(r -> r.ruleId().equals("faulty"))
                .extracting(Result::recordId, Result::decision)
                .containsExactly(tuple("r1", Decision.VALID),
                        tuple("r3", Decision.VALID));

        EvaluationError error = listener.errors().getFirst();
        assertThat(error.ruleId()).contains("faulty");
        assertThat(error.recordId()).isEqualTo("r2");
        assertThat(error.recordIndex()).isEqualTo(1);
        assertThat(error.kind()).isEqualTo(ErrorKind.RULE_EXCEPTION);
        assertThat(error.message()).contains("boom on r2");
        assertThat(error.causeType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(error.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(error.fieldsRead()).containsExactly(Map.entry("id", "r2"));
    }

    @Test
    void nullValueIsAnError() {
        var listener = new CollectingListener();
        run(listener, rule("nullish", f -> null).build());

        assertThat(listener.errors()).hasSize(3)
                .allSatisfy(e -> assertThat(e.kind()).isEqualTo(ErrorKind.NULL_VALUE));
        assertThat(listener.results()).hasSize(9);
    }

    @Test
    void unmappedValueWithoutDefaultIsAnErrorNamingTheValue() {
        var listener = new CollectingListener();
        RunSummary summary = run(listener, rule("odd", f -> "surprise").build());

        assertThat(summary.resultCount()).isEqualTo(9);
        assertThat(listener.errors()).hasSize(3).allSatisfy(e -> {
            assertThat(e.kind()).isEqualTo(ErrorKind.UNMAPPED_VALUE);
            assertThat(e.ruleId()).contains("odd");
            assertThat(e.message()).contains("'surprise'");
            assertThat(e.causeType()).isNull();
        });
    }

    @Test
    void missingRequiredFieldIsAnErrorWithFieldsReadSoFar() {
        var listener = new CollectingListener();
        DqEngine.builder().catalog(Fixture.catalog()).build().validate(
                RecordSources.of(List.of(Fixture.record("id", "r4", "iban", "DE1"))), listener);

        assertThat(listener.results()).extracting(Result::ruleId, Result::decision).containsExactly(
                tuple("countryBlocked", Decision.NOT_APPLICABLE),
                tuple("vatFormat", Decision.INVALID));
        EvaluationError error = listener.errors().getFirst();
        assertThat(error.kind()).isEqualTo(ErrorKind.MISSING_REQUIRED_FIELD);
        assertThat(error.ruleId()).contains("ibanFormat");
        assertThat(error.recordId()).isEqualTo("r4");
        assertThat(error.fieldsRead()).containsExactly(Map.entry("country", "<absent>"));
    }

    @Test
    void typeMismatchIsAnError() {
        var listener = new CollectingListener();
        DqEngine.builder().catalog(RuleCatalog.of(Fixture.VAT_FORMAT)).build().validate(
                RecordSources.of(List.of(Fixture.record("id", "n", "vatId", 123456789))), listener);

        assertThat(listener.errors()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(ErrorKind.TYPE_MISMATCH);
            assertThat(e.fieldsRead()).containsExactly(Map.entry("vatId", 123456789));
        });
    }

    @Test
    void stackOverflowInARuleIsIsolated() {
        var listener = new CollectingListener();
        RunSummary summary = run(listener, rule("recursive", f -> recurse(0)).build());

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.resultCount()).isEqualTo(9);
        assertThat(listener.errors()).hasSize(3).allSatisfy(e -> {
            assertThat(e.kind()).isEqualTo(ErrorKind.RULE_EXCEPTION);
            assertThat(e.causeType()).isEqualTo(StackOverflowError.class.getName());
        });
    }

    @Test
    void checkedExceptionAndLinkageErrorAreIsolated() {
        var listener = new CollectingListener();
        run(listener,
                rule("checked", f -> { throw new java.io.IOException("io"); }).build(),
                rule("linkage", f -> { throw new NoClassDefFoundError("gone"); }).build());

        assertThat(listener.errors()).hasSize(6).extracting(EvaluationError::causeType)
                .containsOnly("java.io.IOException", "java.lang.NoClassDefFoundError");
    }

    @Test
    void otherErrorsAreNotSwallowed() {
        var listener = new CollectingListener();
        assertThatThrownBy(() -> run(listener, rule("oom", f -> { throw new OutOfMemoryError("simulated"); }).build()))
                .isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    void aThrowingListenerAbortsTheRunWithAPartialSummary() {
        var delivered = new ArrayList<Result>();
        ValidationListener listener = new ValidationListener() {
            @Override
            public void onResult(Result result) {
                if (delivered.size() == 1) {
                    throw new IllegalStateException("sink is full");
                }
                delivered.add(result);
            }

            @Override
            public void onError(EvaluationError error) {}
        };
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();

        assertThatThrownBy(() -> engine.validate(RecordSources.of(Fixture.RECORDS), listener))
                .isInstanceOfSatisfying(ValidationRunException.class, e -> {
                    assertThat(e.getCause()).hasMessage("sink is full");
                    assertThat(e.partialSummary().status()).isEqualTo(RunStatus.ABORTED);
                    assertThat(e.partialSummary().resultCount()).isEqualTo(1);
                    assertThat(e.partialSummary().byDecision()).containsEntry(Decision.NOT_APPLICABLE, 1L);
                });
        assertThat(delivered).hasSize(1);
    }

    @Test
    void anErrorKeepsOtherRulesOfTheSameRecordRunning() {
        var listener = new CollectingListener();
        Rule first = rule("first", f -> { throw new RuntimeException("x"); }).build();
        DqEngine.builder().catalog(RuleCatalog.of(first, Fixture.VAT_FORMAT)).build()
                .validate(RecordSources.of(List.of(Fixture.RECORDS.getFirst())), listener);

        assertThat(listener.errors()).singleElement().extracting(EvaluationError::ruleId).isEqualTo(Optional.of("first"));
        assertThat(listener.results()).singleElement().extracting(Result::ruleId).isEqualTo("vatFormat");
    }

    @Test
    void aThrowingExplainerFailsOnlyItsOwnCellAsARuleException() {
        var listener = new CollectingListener();
        Rule shaky = rule("shaky", f -> { f.text("id"); return "ok"; })
                .explain(e -> { throw new IllegalStateException("cannot explain"); }).build();
        DqEngine.builder().catalog(RuleCatalog.of(shaky, Fixture.VAT_FORMAT)).build()
                .validate(RecordSources.of(List.of(Fixture.RECORDS.getFirst())), listener);

        assertThat(listener.errors()).singleElement().satisfies(e -> {
            assertThat(e.ruleId()).contains("shaky");
            assertThat(e.kind()).isEqualTo(ErrorKind.RULE_EXCEPTION);
            assertThat(e.message()).isEqualTo("rule explanation threw: cannot explain");
            assertThat(e.fieldsRead()).containsOnlyKeys("id");
        });
        assertThat(listener.results()).singleElement().extracting(Result::ruleId).isEqualTo("vatFormat");
    }

    @Test
    void anExplainerSeesOnlyWhatTheLogicReadAndMayGiveNoReason() {
        var listener = new CollectingListener();
        Rule reads = rule("reads", f -> { f.text("vatId"); f.text("missing"); return "ok"; })
                .explain(e -> e.text("vatId").orElse("-") + "|" + e.text("country").orElse("unseen")
                        + "|" + e.raw("missing").isPresent() + "|" + e.value()).build();
        Rule silent = rule("silent", f -> "ok").explain(e -> null).build();
        DqEngine.builder().catalog(RuleCatalog.of(reads, silent, rule("plain", f -> "ok").build())).build()
                .validate(RecordSources.of(List.of(Fixture.RECORDS.getFirst())), listener);

        assertThat(listener.results()).extracting(r -> r.provenance().explanation())
                .containsExactly(Optional.of("DE111111111|unseen|false|ok"), Optional.empty(), Optional.empty());
    }

    @Test
    void outcomeTypesNameAMissingFieldsReadMap() {
        assertThatThrownBy(() -> new EvaluationError(Optional.of("r"), "id", 0, ErrorKind.NULL_VALUE, "m", null,
                null, null)).isInstanceOf(NullPointerException.class).hasMessage("fieldsRead");
        assertThatThrownBy(() -> new dq.engine.api.Provenance("r", "l", "id", 0, null, "v",
                dq.engine.api.Provenance.Basis.MAPPED)).isInstanceOf(NullPointerException.class).hasMessage("fieldsRead");
    }

    private static String recurse(int depth) {
        return recurse(depth + 1) + depth;
    }
}
