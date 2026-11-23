package dq.engine;

import static dq.engine.TestRules.okRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.api.BatchProgress;
import dq.engine.api.DqEngine;
import dq.engine.api.EvaluationError;
import dq.engine.api.RecordSources;
import dq.engine.api.Result;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RunOptions;
import dq.engine.api.RunStatus;
import dq.engine.api.RunSummary;
import dq.engine.api.ValidationListener;
import dq.engine.fixtures.Fixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BatchingTest {

    private final DqEngine engine = DqEngine.builder().catalog(RuleCatalog.of(okRule("a"), okRule("b"))).build();

    private static List<Map<String, Object>> records(int n) {
        return IntStream.range(0, n).mapToObj(i -> Fixture.record("id", "r" + i)).toList();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static class Recording implements ValidationListener {
        final List<BatchProgress> batches = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        long results;

        @Override
        public void onResult(Result result) {
            results++;
            events.add("result");
        }

        @Override
        public void onError(EvaluationError error) {}

        @Override
        public void onBatchComplete(BatchProgress progress) {
            batches.add(progress);
            events.add("batch");
        }
    }

    @Test
    void callsOnBatchCompleteAfterEachBatch() {
        var listener = new Recording();
        RunSummary summary = engine.validate(RecordSources.of(records(2_500)), listener,
                RunOptions.builder().batchSize(1000).build());

        assertThat(listener.batches).containsExactly(
                new BatchProgress(1, 1000, 1000), new BatchProgress(2, 1000, 2000), new BatchProgress(3, 500, 2500));
        assertThat(summary.recordsRead()).isEqualTo(2_500);
        assertThat(summary.resultCount()).isEqualTo(5_000);
        assertThat(listener.events.subList(0, 2001)).endsWith("batch").filteredOn("batch"::equals).hasSize(1);
    }

    @Test
    void emptyInputHasNoBatches() {
        var listener = new Recording();
        engine.validate(RecordSources.of(List.of()), listener, RunOptions.defaults());
        assertThat(listener.batches).isEmpty();
    }

    @Test
    void exactMultipleOfBatchSizeHasNoTrailingEmptyBatch() {
        var listener = new Recording();
        engine.validate(RecordSources.of(records(4)), listener, RunOptions.builder().batchSize(2).build());
        assertThat(listener.batches).extracting(BatchProgress::recordsInBatch).containsExactly(2, 2);
    }

    @Test
    void rejectsBatchSizeBelowOne() {
        assertThatThrownBy(() -> RunOptions.builder().batchSize(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> RunOptions.builder().batchSize(-5).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRunCancelledInsideABatchGetsNoCallbackForThatPartialBatch() {
        DqEngine interruptingOnR3 = DqEngine.builder().catalog(RuleCatalog.of(TestRules.rule("a", f -> {
            if (f.require("id").equals("r3")) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        }).build())).build();
        var listener = new Recording();

        RunSummary summary = interruptingOnR3.validate(RecordSources.of(records(10)), listener,
                RunOptions.builder().batchSize(5).build());

        assertThat(summary.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(summary.recordsRead()).isEqualTo(4);
        assertThat(listener.results).isEqualTo(4);
        assertThat(listener.batches).as("records r0-r3 were delivered, but batch 1 never completed").isEmpty();
    }

    @Test
    void anInterruptedExceptionInARuleIsIsolatedButStillCancelsTheRun() {
        DqEngine interrupting = DqEngine.builder().catalog(RuleCatalog.of(
                TestRules.rule("sleepy", f -> { throw new InterruptedException("stop"); }).build())).build();
        var listener = new Recording();

        RunSummary summary = interrupting.validate(RecordSources.of(records(10)), listener,
                RunOptions.builder().batchSize(5).build());

        assertThat(summary.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(summary.errorCount()).isEqualTo(1);
        assertThat(summary.recordsRead()).isEqualTo(1);
    }

    @Test
    void cancellationDoesNotConsumeTheNextBatchFromTheSource() {
        var pulled = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = RecordSources.of(records(250));
        dq.engine.api.RecordSource counting = () -> {
            var element = delegate.next();
            if (element != null) {
                pulled.incrementAndGet();
            }
            return element;
        };
        ValidationListener interruptAfterFirstBatch = new Recording() {
            @Override
            public void onBatchComplete(BatchProgress progress) {
                Thread.currentThread().interrupt();
            }
        };

        engine.validate(counting, interruptAfterFirstBatch, RunOptions.builder().batchSize(100).build());

        assertThat(pulled).hasValue(100);
    }

    @Test
    void interruptStopsTheRunBetweenRecordsAsCancelled() {
        ValidationListener interruptAfterFirstBatch = new Recording() {
            @Override
            public void onBatchComplete(BatchProgress progress) {
                super.onBatchComplete(progress);
                Thread.currentThread().interrupt();
            }
        };

        RunSummary summary = engine.validate(RecordSources.of(records(250)), interruptAfterFirstBatch,
                RunOptions.builder().batchSize(100).build());

        assertThat(summary.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(summary.recordsRead()).isEqualTo(100);
        assertThat(summary.resultCount()).isEqualTo(200);
        assertThat(((Recording) interruptAfterFirstBatch).results).isEqualTo(200);
        assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag is preserved").isTrue();
    }
}
