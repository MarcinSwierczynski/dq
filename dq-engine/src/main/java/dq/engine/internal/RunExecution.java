package dq.engine.internal;

import dq.engine.api.Decision;
import dq.engine.api.DecisionMapping.Resolution;
import dq.engine.api.ErrorKind;
import dq.engine.api.EvaluationError;
import dq.engine.api.Provenance;
import dq.engine.api.Provenance.Basis;
import dq.engine.api.RecordSource;
import dq.engine.api.Result;
import dq.engine.api.Rule;
import dq.engine.api.RuleStatus;
import dq.engine.api.RunStatus;
import dq.engine.api.RunSummary;
import dq.engine.api.Severity;
import dq.engine.api.SourceElement;
import dq.engine.api.ValidationListener;
import dq.engine.api.ValidationRunException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * State and loop of a single validation run. Created per {@code validate} call and confined to the
 * calling thread. Every outcome is handed to the listener as soon as it is produced.
 */
public final class RunExecution {
    private static final System.Logger LOG = System.getLogger("dq.engine");

    private final List<Rule> rules;
    private final String recordIdField;
    private final String countryField;
    private final RecordSource source;
    private final ValidationListener listener;

    private final long startNanos = System.nanoTime();
    private final EnumMap<Decision, Long> byDecision = new EnumMap<>(Decision.class);
    private final EnumMap<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
    private List<Rule> selected = List.of();
    private long recordsRead;
    private long resultCount;
    private long errorCount;

    public RunExecution(List<Rule> rules, String recordIdField, String countryField, RecordSource source,
            ValidationListener listener) {
        this.rules = rules;
        this.recordIdField = recordIdField;
        this.countryField = countryField;
        this.source = source;
        this.listener = listener;
    }

    public RunSummary run() {
        try {
            selected = rules.stream().filter(rule -> rule.status() == RuleStatus.RELEASED).toList();
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
        while (true) {
            SourceElement element;
            try {
                element = source.next();
            } catch (RuntimeException e) {
                throw new Abort("record source failed after " + recordsRead + " records: " + e.getMessage(), e);
            }
            if (element == null) {
                return RunStatus.COMPLETED;
            }
            evaluate(element, recordsRead);
            recordsRead++;
        }
    }

    private void evaluate(SourceElement element, long index) {
        switch (element) {
            case SourceElement.Unreadable unreadable -> emit(new EvaluationError(Optional.empty(), "#" + index,
                    index, ErrorKind.UNREADABLE_RECORD, unreadable.reason(), null, new LinkedHashMap<>(), null));
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
                                        Basis.OUT_OF_SCOPE)));
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
            emit(ruleException(rule, recordId, index, e, reader));
            return;
        } catch (Exception | LinkageError e) {
            emit(ruleException(rule, recordId, index, e, reader));
            return;
        }
        if (value == null) {
            emit(error(rule, recordId, index, ErrorKind.NULL_VALUE, "rule logic returned null", null, reader));
            return;
        }
        Resolution resolution = rule.mapping().resolve(value);
        switch (resolution) {
            case Resolution.Mapped(Decision decision) ->
                    emit(result(rule, recordId, index, decision, value, Basis.MAPPED, reader));
            case Resolution.Defaulted(Decision decision) ->
                    emit(result(rule, recordId, index, decision, value, Basis.DEFAULT, reader));
            case Resolution.Unmapped() -> emit(error(rule, recordId, index, ErrorKind.UNMAPPED_VALUE,
                    "computed value '" + value + "' is not mapped and the mapping has no default", null, reader));
        }
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

    private static Result result(Rule rule, String recordId, long index, Decision decision, String value,
            Basis basis, TrackingFieldReader reader) {
        return new Result(rule.id(), recordId, decision, rule.severity(),
                new Provenance(rule.id(), rule.label(), recordId, index, reader.fieldsRead(), value, basis));
    }

    private static EvaluationError ruleException(Rule rule, String recordId, long index, Throwable e,
            TrackingFieldReader reader) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return error(rule, recordId, index, ErrorKind.RULE_EXCEPTION, "rule logic threw: " + message, e, reader);
    }

    private static EvaluationError error(Rule rule, String recordId, long index, ErrorKind kind, String message,
            Throwable cause, TrackingFieldReader reader) {
        return new EvaluationError(Optional.of(rule.id()), recordId, index, kind, message,
                cause == null ? null : cause.getClass().getName(), reader.fieldsRead(), cause);
    }

    /** An outcome is counted only after the listener call returned normally. */
    private void emit(Result result) {
        deliver(() -> listener.onResult(result));
        resultCount++;
        byDecision.merge(result.decision(), 1L, Long::sum);
        bySeverity.merge(result.severity(), 1L, Long::sum);
    }

    private void emit(EvaluationError error) {
        // Never log field values: provenance may hold tax ids or IBANs.
        LOG.log(Level.DEBUG, () -> "evaluation error: rule=" + error.ruleId().orElse("-") + " record="
                + error.recordId() + " kind=" + error.kind() + " cause=" + error.causeType());
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
        return new RunSummary(recordsRead, selected.size(), resultCount, errorCount, byDecision, bySeverity,
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
