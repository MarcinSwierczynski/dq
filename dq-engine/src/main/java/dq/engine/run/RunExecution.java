package dq.engine.run;

import dq.engine.catalog.Decision;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import dq.engine.source.RecordSource;
import dq.engine.source.SourceElement;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * State and loop of a single validation run. Created per {@code validate} call and confined to the
 * calling thread; with {@link RunOptions#executor()}, only {@link Evaluator} calls run elsewhere.
 *
 * <p>Loop: pull up to {@code batchSize} elements into a buffer, evaluate each record against the selected
 * rules ({@link Evaluator}), hand its outcomes to the listener, signal {@code onBatchComplete}, drop the
 * buffer.
 * Live memory is therefore bounded by the batch size, not by the input size.
 */
final class RunExecution {
    private static final System.Logger LOG = System.getLogger("dq.engine");

    private final List<Rule> rules;
    private final Evaluator evaluator;
    private final RecordSource source;
    private final ValidationListener listener;
    private final RunOptions options;

    private final long startNanos = System.nanoTime();
    private final EnumMap<Decision, Long> byDecision = new EnumMap<>(Decision.class);
    private final EnumMap<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
    private List<Rule> selected = List.of();
    private long recordsRead;
    private long errorCount;

    RunExecution(List<Rule> rules, String recordIdField, String countryField, RecordSource source,
            ValidationListener listener, RunOptions options) {
        this.rules = rules;
        this.evaluator = new Evaluator(recordIdField, countryField, options.ruleTimeout().orElse(null));
        this.source = source;
        this.listener = listener;
        this.options = options;
    }

    RunSummary run() {
        try {
            selected = rules.stream()
                    .filter(rule -> rule.status() == RuleStatus.RELEASED)
                    .filter(options.filter()::test)
                    .toList();
            return summary(loop());
        } catch (Abort abort) {
            RunSummary partial = summary(RunStatus.ABORTED);
            LOG.log(Level.WARNING, "validation run aborted after {0} records: {1}", recordsRead, abort.getMessage());
            throw new ValidationRunException(abort.getMessage(), abort.getCause(), partial);
        } finally {
            closeSource();
        }
    }

    /**
     * Every outcome was already delivered when the source is closed, so a failing close must not discard
     * the run's summary (or mask the exception that ended it); it is logged instead.
     */
    private void closeSource() {
        try {
            source.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "closing the record source failed: " + e.getClass().getName(), e);
        }
    }

    private RunStatus loop() {
        int batchSize = options.batchSize();
        long batchNumber = 0;
        boolean exhausted = false;
        while (!exhausted) {
            if (Thread.currentThread().isInterrupted()) {
                return RunStatus.CANCELLED; // before pulling: do not consume records that will not run
            }
            List<SourceElement> batch = new ArrayList<>(Math.min(batchSize, 4096));
            RuntimeException sourceFailure = null;
            while (batch.size() < batchSize) {
                SourceElement element;
                try {
                    element = source.next();
                } catch (RuntimeException e) {
                    sourceFailure = e;
                    break;
                }
                if (element == null) {
                    exhausted = true;
                    break;
                }
                batch.add(element);
            }
            // Records buffered before a source failure are still evaluated and delivered.
            boolean finished = options.executor().isPresent()
                    ? evaluateInWindow(batch, options.executor().get())
                    : evaluateInline(batch);
            if (!finished) {
                return RunStatus.CANCELLED;
            }
            if (sourceFailure != null) {
                throw new Abort("record source failed after " + recordsRead + " records: "
                        + sourceFailure.getMessage(), sourceFailure);
            }
            if (!batch.isEmpty()) {
                batchNumber++;
                var progress = new BatchProgress(batchNumber, batch.size(), recordsRead);
                deliver(() -> listener.onBatchComplete(progress));
            }
        }
        return RunStatus.COMPLETED;
    }

    /** Evaluates and delivers a batch on the calling thread. @return false if the run was cancelled */
    private boolean evaluateInline(List<SourceElement> batch) {
        for (SourceElement element : batch) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            List<Object> outcomes;
            try {
                outcomes = evaluator.evaluate(element, recordsRead, selected);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // keep the flag set, as for any cancelled run
                return false;
            }
            emitAll(outcomes);
            recordsRead++;
        }
        return true;
    }

    /**
     * Evaluates a batch on the host's executor, one task per record, with at most {@code maxInFlight}
     * tasks submitted and not yet delivered. The window is a reorder buffer: tasks finish in any order,
     * but the calling thread delivers their outcomes oldest first, so the listener sees exactly the
     * sequential order, on the calling thread. The window is drained before the batch completes.
     *
     * @return false if the run was cancelled; the delivered outcomes are then an in-order prefix
     */
    private boolean evaluateInWindow(List<SourceElement> batch, Executor executor) {
        int maxInFlight = options.maxInFlight();
        List<Rule> rules = selected;
        var window = new ArrayDeque<FutureTask<List<Object>>>(Math.min(maxInFlight, batch.size()));
        try {
            long index = recordsRead;
            for (SourceElement element : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
                if (window.size() == maxInFlight && !deliverOldest(window)) {
                    return false;
                }
                long recordIndex = index++;
                var task = new FutureTask<>(() -> evaluateOnWorker(element, recordIndex, rules));
                try {
                    executor.execute(task);
                } catch (RejectedExecutionException e) {
                    throw new Abort("the executor rejected an evaluation: " + e.getMessage(), e);
                }
                window.addLast(task);
            }
            while (!window.isEmpty()) {
                if (!deliverOldest(window)) {
                    return false;
                }
            }
            return true;
        } finally {
            // Cancelled, aborted or failed: evaluations nobody will deliver are stopped.
            window.forEach(task -> task.cancel(true));
        }
    }

    private List<Object> evaluateOnWorker(SourceElement element, long index, List<Rule> rules)
            throws InterruptedException {
        try {
            return evaluator.evaluate(element, index, rules);
        } finally {
            // A rule that interrupted itself (or our cancel) must not leave the host's pool thread interrupted.
            Thread.interrupted();
        }
    }

    /** Waits for the oldest task and delivers its outcomes. @return false if the calling thread was interrupted */
    private boolean deliverOldest(ArrayDeque<FutureTask<List<Object>>> window) {
        List<Object> outcomes;
        try {
            outcomes = window.getFirst().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the flag set, as for any cancelled run
            return false;
        } catch (ExecutionException e) {
            // The Evaluator isolates rule failures, so what arrives here is a fatal Error, an engine bug,
            // or the host's executor interrupting its own thread.
            switch (e.getCause()) {
                case Error fatal -> throw fatal;
                case RuntimeException bug -> throw bug;
                case Throwable other -> throw new Abort("an evaluation was interrupted on its executor thread", other);
            }
        }
        window.removeFirst();
        emitAll(outcomes);
        recordsRead++;
        return true;
    }

    /** INVALID and REVIEW are findings; VALID and NOT_APPLICABLE are not, so they carry no severity weight. */
    private static boolean isFinding(Decision decision) {
        return decision == Decision.INVALID || decision == Decision.REVIEW;
    }

    private void emitAll(List<Object> outcomes) {
        for (Object outcome : outcomes) {
            switch (outcome) {
                case Result result -> emit(result);
                case EvaluationError error -> emit(error);
                default -> throw new IllegalStateException("unexpected outcome " + outcome.getClass());
            }
        }
    }

    private void emit(Result result) {
        deliver(() -> listener.onResult(result));
        byDecision.merge(result.decision(), 1L, Long::sum);
        if (isFinding(result.decision())) {
            bySeverity.merge(result.severity(), 1L, Long::sum);
        }
    }

    private void emit(EvaluationError error) {
        // Never log field values: provenance may hold tax ids or IBANs.
        LOG.log(Level.DEBUG, () -> "evaluation error: rule=" + error.ruleId().orElse("-") + " record="
                + error.recordId() + " kind=" + error.kind() + " cause=" + error.causeType().orElse("-"));
        deliver(() -> listener.onError(error));
        errorCount++;
    }

    /** Calls the listener; an exception from it aborts the run (outcomes are never dropped silently). */
    private static void deliver(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            throw new Abort("listener failed: " + e, e);
        }
    }

    private RunSummary summary(RunStatus status) {
        return new RunSummary(recordsRead, selected.size(), errorCount, byDecision, bySeverity,
                Duration.ofNanos(System.nanoTime() - startNanos), status);
    }

    /** Unwinds the loop on a host-owned failure. */
    private static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }
}
