package dq.engine.run;

import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping.Resolution;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import dq.engine.run.Provenance.Basis;
import dq.engine.source.RecordSource;
import dq.engine.source.SourceElement;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * State and loop of a single validation run. Created per {@code validate} call and confined to the
 * calling thread.
 *
 * <p>Loop: pull up to {@code batchSize} elements into a buffer, evaluate each (record × selected rule)
 * pair, hand every outcome to the listener immediately, signal {@code onBatchComplete}, drop the buffer.
 * Live memory is therefore bounded by the batch size, not by the input size.
 */
final class RunExecution {
    private static final System.Logger LOG = System.getLogger("dq.engine");

    private final List<Rule> rules;
    private final String recordIdField;
    private final String countryField;
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
        this.recordIdField = recordIdField;
        this.countryField = countryField;
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
            for (SourceElement element : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    return RunStatus.CANCELLED;
                }
                evaluate(element, recordsRead);
                recordsRead++;
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

    private void evaluate(SourceElement element, long index) {
        switch (element) {
            case SourceElement.Unreadable unreadable -> emit(new EvaluationError(null, "#" + index, index,
                    ErrorKind.UNREADABLE_RECORD, unreadable.reason(), new LinkedHashMap<>(), null));
            case SourceElement.Fields(Map<String, ?> fields) -> {
                String recordId = recordId(fields, index);
                Object country = fields.get(countryField);
                String recordCountry = country instanceof String s ? s : null;
                for (Rule rule : selected) {
                    if (rule.scope().appliesTo(recordCountry)) {
                        evaluate(rule, fields, recordId, index);
                    } else {
                        SequencedMap<String, Object> read = new LinkedHashMap<>();
                        read.put(countryField, fields.containsKey(countryField) ? country : Provenance.ABSENT);
                        emit(new Result(rule.id(), recordId, Decision.NOT_APPLICABLE, rule.severity(),
                                new Provenance(rule.id(), rule.label(), recordId, index, read, null,
                                        Basis.OUT_OF_SCOPE, outOfScope(rule, fields, country))));
                    }
                }
            }
        }
    }

    /** The isolation boundary: whatever the rule does, the outcome is one Result or one EvaluationError. */
    private void evaluate(Rule rule, Map<String, ?> fields, String recordId, long index) {
        var reader = new TrackingFieldReader(fields);
        String value;
        try {
            value = rule.logic().compute(reader);
        } catch (FieldAccessException e) {
            emit(error(rule, recordId, index, e.kind(), e.getMessage(), null, reader));
            return;
        } catch (StackOverflowError e) {
            // Checked before VirtualMachineError: the stack has unwound, so it is safe to continue.
            emit(ruleException(rule, recordId, index, "rule logic threw: ", e, reader));
            return;
        } catch (Exception | LinkageError e) {
            keepInterrupt(e);
            emit(ruleException(rule, recordId, index, "rule logic threw: ", e, reader));
            return;
        }
        if (value == null) {
            emit(error(rule, recordId, index, ErrorKind.NULL_VALUE, "rule logic returned null", null, reader));
            return;
        }
        Decision decision;
        Basis basis;
        switch (rule.mapping().resolve(value)) {
            case Resolution.Mapped(Decision d) -> { decision = d; basis = Basis.MAPPED; }
            case Resolution.Defaulted(Decision d) -> { decision = d; basis = Basis.DEFAULT; }
            case Resolution.Unmapped() -> {
                emit(error(rule, recordId, index, ErrorKind.UNMAPPED_VALUE,
                        "computed value '" + value + "' is not mapped and the mapping has no default", null, reader));
                return;
            }
        }
        // The explainer is rule code too, so it sits inside the same isolation boundary. It runs after
        // the decision is fixed and sees only the evidence, so it cannot change the outcome.
        String explanation;
        try {
            explanation = rule.explainer().explain(new RecordedEvidence(reader.fieldsRead(), value));
        } catch (StackOverflowError e) {
            emit(ruleException(rule, recordId, index, "rule explanation threw: ", e, reader));
            return;
        } catch (Exception | LinkageError e) {
            keepInterrupt(e);
            emit(ruleException(rule, recordId, index, "rule explanation threw: ", e, reader));
            return;
        }
        emit(new Result(rule.id(), recordId, decision, rule.severity(), new Provenance(rule.id(), rule.label(),
                recordId, index, reader.fieldsRead(), value, basis, explanation)));
    }

    /** INVALID and REVIEW are findings; VALID and NOT_APPLICABLE are not, so they carry no severity weight. */
    private static boolean isFinding(Decision decision) {
        return decision == Decision.INVALID || decision == Decision.REVIEW;
    }

    /** Keeps a cancellation request visible to the loop's between-record check. */
    private static void keepInterrupt(Throwable e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /** The engine's own reason for {@link Basis#OUT_OF_SCOPE}: the rule's logic never ran. */
    private String outOfScope(Rule rule, Map<String, ?> fields, Object country) {
        String recordCountry = !fields.containsKey(countryField) || country == null ? "record has no " + countryField
                : country instanceof String s ? "record " + countryField + " is " + s
                : "record " + countryField + " is not text";
        return "applies to " + rule.scope() + " only; " + recordCountry;
    }

    private String recordId(Map<String, ?> fields, long index) {
        Object id = fields.get(recordIdField);
        if (id instanceof String s && !s.isBlank()) {
            return s;
        }
        if (id instanceof Number || id instanceof Boolean) {
            return id.toString();
        }
        return "#" + index;
    }

    private static EvaluationError ruleException(Rule rule, String recordId, long index, String prefix,
            Throwable e, TrackingFieldReader reader) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return error(rule, recordId, index, ErrorKind.RULE_EXCEPTION, prefix + message, e, reader);
    }

    private static EvaluationError error(Rule rule, String recordId, long index, ErrorKind kind, String message,
            Throwable cause, TrackingFieldReader reader) {
        return new EvaluationError(rule.id(), recordId, index, kind, message, reader.fieldsRead(), cause);
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
