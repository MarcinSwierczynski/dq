package dq.engine;

import static dq.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.fixtures.Fixture;
import dq.engine.run.BatchProgress;
import dq.engine.run.CollectingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.ErrorKind;
import dq.engine.run.EvaluationError;
import dq.engine.run.Result;
import dq.engine.run.RunOptions;
import dq.engine.run.RunStatus;
import dq.engine.run.RunSummary;
import dq.engine.run.ValidationListener;
import dq.engine.run.ValidationRunException;
import dq.engine.source.RecordSources;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Parallel evaluation: records run concurrently, but the listener sees exactly the sequential output. */
@Timeout(60)
class ParallelEvaluationTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
        Thread.interrupted();
    }

    private static List<Map<String, Object>> records(int count) {
        String[] countries = {"DE", "FR", "ZZ", "PL"};
        return IntStream.range(0, count).mapToObj(i -> i % 7 == 6
                ? Fixture.record("id", "r" + i) // no country, no vatId: errors and INVALIDs
                : Fixture.record("id", "r" + i, "country", countries[i % 4], "vatId", countries[i % 4] + "12345678" + i,
                        "iban", countries[(i + 1) % 4] + "00" + i)).toList();
    }

    private static RuleCatalog mixedCatalog() {
        return mixedCatalog(ConcurrentHashMap.newKeySet());
    }

    /** The fixture rules plus faulty and slow ones, so out-of-order completion is likely. */
    private static RuleCatalog mixedCatalog(Set<String> ruleThreads) {
        var rules = new ArrayList<>(Fixture.RULES);
        rules.add(rule("jitter", f -> {
            ruleThreads.add(Thread.currentThread().getName());
            Thread.sleep(ThreadLocalRandom.current().nextInt(3));
            return f.text("country").isPresent() ? "ok" : "unmapped";
        }).build());
        rules.add(rule("throwsOnEvery5th", f -> {
            if (f.require("id").endsWith("5")) {
                throw new IllegalStateException("boom on " + f.require("id"));
            }
            return "ok";
        }).build());
        return RuleCatalog.of(rules);
    }

    private static Recording run(RuleCatalog catalog, List<Map<String, Object>> records, RunOptions options) {
        var listener = new Recording();
        listener.summary = DqEngine.builder().catalog(catalog).build()
                .validate(RecordSources.of(records), listener, options);
        return listener;
    }

    @Test
    void theOutputEqualsTheSequentialOutputExactly() {
        List<Map<String, Object>> records = records(500);
        Recording sequential = run(mixedCatalog(), records, RunOptions.builder().batchSize(64).build());
        Set<String> ruleThreads = ConcurrentHashMap.newKeySet();
        Recording parallel = run(mixedCatalog(ruleThreads), records,
                RunOptions.builder().batchSize(64).executor(pool).maxInFlight(16).build());

        assertThat(parallel.outcomes).hasSize(500 * 5).isEqualTo(sequential.outcomes);
        assertThat(parallel.batches).isEqualTo(sequential.batches);
        assertThat(parallel.summary.byDecision()).isEqualTo(sequential.summary.byDecision());
        assertThat(parallel.summary.bySeverity()).isEqualTo(sequential.summary.bySeverity());
        assertThat(parallel.summary.errorCount()).isEqualTo(sequential.summary.errorCount()).isPositive();
        assertThat(parallel.summary.recordsRead()).isEqualTo(500);
        assertThat(parallel.callbackThreads).containsExactly(Thread.currentThread().getName());
        assertThat(ruleThreads).as("rules ran on the pool").isNotEmpty().allMatch(t -> t.startsWith("pool-"));
    }

    @Test
    void timeoutsComposeWithParallelEvaluation() {
        List<Map<String, Object>> records = records(100);
        Recording sequential = run(mixedCatalog(), records, RunOptions.defaults());
        Recording parallel = run(mixedCatalog(), records, RunOptions.builder().executor(pool)
                .ruleTimeout(Duration.ofSeconds(10)).build());

        assertThat(parallel.outcomes).isEqualTo(sequential.outcomes);
    }

    @Test
    void recordsReallyRunConcurrently() {
        // Each evaluation waits until four of them are running at once; sequentially this would time out.
        var barrier = new CyclicBarrier(4);
        Rule meets = rule("meets", f -> {
            barrier.await(10, TimeUnit.SECONDS);
            return "ok";
        }).build();

        Recording parallel = run(RuleCatalog.of(meets), records(8), RunOptions.builder().executor(pool).maxInFlight(4).build());

        assertThat(parallel.summary.byDecision().get(Decision.VALID)).isEqualTo(8);
        assertThat(parallel.summary.errorCount()).isZero();
    }

    @Test
    void maxInFlightBoundsConcurrency() {
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        Rule counts = rule("counts", f -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(2);
            running.decrementAndGet();
            return "ok";
        }).build();

        run(RuleCatalog.of(counts), records(200), RunOptions.builder().executor(pool).maxInFlight(3).build());

        assertThat(peak.get()).isBetween(1, 3);
    }

    @Test
    void aThrowingListenerAbortsWithAnInOrderPrefixAndCancelsTheWindow() {
        var delivered = new ArrayList<String>();
        ValidationListener failsOnTheTwentiethResult = new Recording() {
            @Override
            public void onResult(Result result) {
                if (delivered.size() == 20) {
                    throw new IllegalStateException("disk full");
                }
                delivered.add(result.recordId() + "/" + result.ruleId());
            }
        };
        var started = new AtomicInteger();
        Rule slow = rule("slow", f -> {
            started.incrementAndGet();
            Thread.sleep(5);
            return "ok";
        }).build();
        DqEngine engine = DqEngine.builder().catalog(RuleCatalog.of(Fixture.VAT_FORMAT, slow)).build();

        assertThatThrownBy(() -> engine.validate(RecordSources.of(records(1000)), failsOnTheTwentiethResult,
                RunOptions.builder().executor(pool).maxInFlight(8).build()))
                .isInstanceOf(ValidationRunException.class)
                .satisfies(e -> {
                    RunSummary partial = ((ValidationRunException) e).partialSummary();
                    assertThat(partial.status()).isEqualTo(RunStatus.ABORTED);
                    assertThat(partial.recordsRead()).isEqualTo(10);
                });
        assertThat(delivered).containsExactlyElementsOf(IntStream.range(0, 10).boxed()
                .flatMap(i -> List.of("r" + i + "/vatFormat", "r" + i + "/slow").stream()).toList());
        assertThat(started.get()).as("no record beyond the window was submitted").isLessThanOrEqualTo(11 + 8);
    }

    @Test
    void interruptingTheCallerCancelsTheRunWithAnInOrderPrefix() {
        ValidationListener interruptAfterFirstBatch = new Recording() {
            @Override
            public void onBatchComplete(BatchProgress progress) {
                super.onBatchComplete(progress);
                Thread.currentThread().interrupt();
            }
        };
        DqEngine engine = DqEngine.builder().catalog(mixedCatalog()).build();

        RunSummary summary = engine.validate(RecordSources.of(records(250)), interruptAfterFirstBatch,
                RunOptions.builder().batchSize(100).executor(pool).build());

        assertThat(summary.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(summary.recordsRead()).isEqualTo(100);
        assertThat(((Recording) interruptAfterFirstBatch).outcomes).hasSize(100 * 5);
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag is preserved").isTrue();
    }

    @Test
    void aRuleInterruptingItselfIsIsolatedAndDoesNotLeakIntoThePoolThread() {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            Rule interrupts = rule("interrupts", f -> {
                if (f.require("id").equals("r0")) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("stop");
                }
                return Thread.currentThread().isInterrupted() ? "leaked" : "ok";
            }).mapping(DecisionMapping.builder().when("ok", Decision.VALID).when("leaked", Decision.INVALID).build())
                    .build();

            Recording parallel = run(RuleCatalog.of(interrupts), records(5), RunOptions.builder().executor(single).build());

            assertThat(parallel.summary.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(parallel.summary.byDecision().get(Decision.VALID)).isEqualTo(4);
            assertThat(parallel.outcomes.getFirst()).isInstanceOf(EvaluationError.class)
                    .extracting(o -> ((EvaluationError) o).kind()).isEqualTo(ErrorKind.RULE_EXCEPTION);
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void aRejectedTaskAbortsTheRun() {
        pool.shutdown();
        DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();

        assertThatThrownBy(() -> engine.validate(RecordSources.of(Fixture.RECORDS), new CollectingListener(),
                RunOptions.builder().executor(pool).build()))
                .isInstanceOf(ValidationRunException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class)
                .satisfies(e -> assertThat(((ValidationRunException) e).partialSummary().recordsRead()).isZero());
    }

    @Test
    void fatalErrorsStillPropagate() {
        Rule oom = rule("oom", f -> { throw new OutOfMemoryError("simulated"); }).build();
        assertThatThrownBy(() -> run(RuleCatalog.of(oom), records(10), RunOptions.builder().executor(pool).build()))
                .isInstanceOf(OutOfMemoryError.class).hasMessage("simulated");
    }

    @Test
    void anInlineExecutorBehavesLikeASequentialRun() {
        List<Map<String, Object>> records = records(50);
        assertThat(run(mixedCatalog(), records, RunOptions.builder().executor(Runnable::run).build()).outcomes)
                .isEqualTo(run(mixedCatalog(), records, RunOptions.defaults()).outcomes);
    }

    @Test
    void maxInFlightMustBePositive() {
        assertThatThrownBy(() -> RunOptions.builder().maxInFlight(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("maxInFlight must be at least 1, was 0");
        assertThat(RunOptions.defaults().executor()).isEmpty();
        assertThat(RunOptions.defaults().maxInFlight()).isEqualTo(RunOptions.DEFAULT_MAX_IN_FLIGHT);
    }

    /** Records every outcome in delivery order, and which threads delivered and computed them. */
    private static class Recording implements ValidationListener {
        final List<Object> outcomes = new ArrayList<>();
        final List<BatchProgress> batches = new ArrayList<>();
        final Set<String> callbackThreads = new HashSet<>();
        RunSummary summary;

        @Override
        public void onResult(Result result) {
            callbackThreads.add(Thread.currentThread().getName());
            outcomes.add(result);
        }

        @Override
        public void onError(EvaluationError error) {
            callbackThreads.add(Thread.currentThread().getName());
            outcomes.add(error);
        }

        @Override
        public void onBatchComplete(BatchProgress progress) {
            callbackThreads.add(Thread.currentThread().getName());
            batches.add(progress);
        }
    }
}
