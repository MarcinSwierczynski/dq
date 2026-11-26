package dq.engine;

import static dq.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.fixtures.Fixture;
import dq.engine.run.CollectingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.ErrorKind;
import dq.engine.run.EvaluationError;
import dq.engine.run.Result;
import dq.engine.run.RunOptions;
import dq.engine.run.RunStatus;
import dq.engine.run.RunSummary;
import dq.engine.source.RecordSources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Per-rule timeouts: a rule that hangs fails only its own cell, and the run keeps going. */
@Timeout(30)
class RuleTimeoutTest {

    private static final RunOptions TIMED = RunOptions.builder().ruleTimeout(Duration.ofMillis(200)).build();

    private final AtomicBoolean release = new AtomicBoolean();

    @AfterEach
    void releaseRunawayRules() {
        release.set(true);
        Thread.interrupted();
    }

    private static RunSummary run(CollectingListener listener, RunOptions options, Rule... extra) {
        var rules = new ArrayList<>(Fixture.RULES);
        rules.addAll(List.of(extra));
        return DqEngine.builder().catalog(RuleCatalog.of(rules)).build()
                .validate(RecordSources.of(Fixture.RECORDS), listener, options);
    }

    @Test
    void aHangingRuleTimesOutInItsOwnCellOnly() {
        Rule hangsOnR2 = rule("hangsOnR2", f -> {
            if (f.require("id").equals("r2")) {
                f.text("vatId");
                Thread.sleep(Long.MAX_VALUE);
            }
            return "ok";
        }).build();
        var listener = new CollectingListener();

        RunSummary summary = run(listener, TIMED, hangsOnR2);

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(listener.results().stream().filter(r -> !r.ruleId().equals("hangsOnR2"))
                .collect(Collectors.toMap(r -> r.recordId() + "/" + r.ruleId(), Result::decision)))
                .isEqualTo(Fixture.EXPECTED);
        assertThat(listener.results()).filteredOn(r -> r.ruleId().equals("hangsOnR2"))
                .extracting(Result::recordId).containsExactly("r1", "r3");
        EvaluationError error = listener.errors().getFirst();
        assertThat(listener.errors()).hasSize(1);
        assertThat(error.kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(error.ruleId()).contains("hangsOnR2");
        assertThat(error.recordId()).isEqualTo("r2");
        assertThat(error.message()).isEqualTo("rule did not finish within 200 ms");
        assertThat(error.fieldsRead()).as("what the rule had read before it hung")
                .containsExactly(Map.entry("id", "r2"), Map.entry("vatId", "FR22"));
        assertThat(error.causeType()).isEmpty();
    }

    @Test
    void aRuleThatIgnoresInterruptsIsAbandonedAndTheRunDoesNotWaitForIt() {
        var stillRunning = new CountDownLatch(1);
        Rule spins = rule("spins", f -> {
            while (!release.get()) {
                Thread.onSpinWait(); // ignores the interrupt
            }
            stillRunning.countDown();
            return "ok";
        }).build();
        var listener = new CollectingListener();

        RunSummary summary = run(listener, TIMED, spins);

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(listener.errors()).hasSize(3).extracting(EvaluationError::kind).containsOnly(ErrorKind.TIMEOUT);
        assertThat(listener.results()).hasSize(9);
        assertThat(stillRunning.getCount()).as("the abandoned evaluations are still spinning").isEqualTo(1);
    }

    @Test
    void withATimeoutEveryOtherOutcomeIsTheSameAsWithout() {
        Rule[] faulty = {
                rule("throws", f -> { throw new IllegalStateException("boom"); }).build(),
                rule("nulls", f -> null).build(),
                rule("unmapped", f -> "other").build(),
                rule("requires", f -> f.require("missing")).build(),
                rule("explainerThrows", f -> "ok").explain(e -> { throw new IllegalArgumentException("why"); }).build(),
                rule("overflows", f -> recurse(0)).build()};
        var inline = new CollectingListener();
        var timed = new CollectingListener();

        run(inline, RunOptions.defaults(), faulty);
        run(timed, RunOptions.builder().ruleTimeout(Duration.ofSeconds(10)).build(), faulty);

        assertThat(timed.results()).isEqualTo(inline.results());
        assertThat(timed.errors()).isEqualTo(inline.errors()).hasSize(18);
    }

    @Test
    void fatalErrorsStillPropagate() {
        Rule oom = rule("oom", f -> { throw new OutOfMemoryError("simulated"); }).build();
        assertThatThrownBy(() -> run(new CollectingListener(), TIMED, oom))
                .isInstanceOf(OutOfMemoryError.class).hasMessage("simulated");
    }

    @Test
    void interruptingTheCallerWhileItWaitsCancelsTheRun() throws InterruptedException {
        var waiting = new CountDownLatch(1);
        Rule hangs = rule("hangs", f -> {
            waiting.countDown();
            Thread.sleep(Long.MAX_VALUE);
            return "ok";
        }).build();
        var listener = new CollectingListener();
        var summary = new RunSummary[1];
        Thread caller = Thread.ofPlatform().start(() -> summary[0] = run(listener,
                RunOptions.builder().ruleTimeout(Duration.ofMinutes(5)).build(), hangs));

        assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(10_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(summary[0].status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(summary[0].recordsRead()).as("r1 was not fully evaluated").isZero();
        assertThat(listener.results()).as("a partly evaluated record delivers nothing").isEmpty();
    }

    @Test
    void anInterruptedExceptionInATimedRuleIsAnErrorAndDoesNotCancelTheRun() {
        var listener = new CollectingListener();
        RunSummary summary = run(listener, TIMED, rule("sleepy", f -> { throw new InterruptedException("stop"); }).build());

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(listener.errors()).hasSize(3).extracting(EvaluationError::kind).containsOnly(ErrorKind.RULE_EXCEPTION);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void theTimeoutMustBePositive() {
        assertThatThrownBy(() -> RunOptions.builder().ruleTimeout(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("ruleTimeout must be positive, was PT0S");
        assertThatThrownBy(() -> RunOptions.builder().ruleTimeout(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RunOptions.defaults().ruleTimeout()).isEmpty();
    }

    private static String recurse(int depth) {
        return recurse(depth + 1) + depth;
    }
}
