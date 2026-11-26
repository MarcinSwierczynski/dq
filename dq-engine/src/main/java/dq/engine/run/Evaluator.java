package dq.engine.run;

import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping.Resolution;
import dq.engine.catalog.Rule;
import dq.engine.run.Provenance.Basis;
import dq.engine.source.SourceElement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Computes the outcomes of one input element: one {@link Result} or {@link EvaluationError} per selected
 * rule, or one record-level error. It never calls the listener and holds no per-run state, so one
 * instance can evaluate records on any thread.
 *
 * <p>Without a rule timeout, rule code runs on the calling thread. With one, each (rule, record)
 * evaluation runs on its own virtual thread and the caller waits at most the timeout for it.
 */
final class Evaluator {
    private final String recordIdField;
    private final String countryField;
    private final Duration ruleTimeout; // null: run rule code inline

    Evaluator(String recordIdField, String countryField, Duration ruleTimeout) {
        this.recordIdField = recordIdField;
        this.countryField = countryField;
        this.ruleTimeout = ruleTimeout;
    }

    /**
     * Every outcome of {@code element}, in {@code rules} order.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting for a timed rule;
     *     the element's outcomes are then incomplete and discarded
     */
    List<Object> evaluate(SourceElement element, long index, List<Rule> rules) throws InterruptedException {
        return switch (element) {
            case SourceElement.Unreadable unreadable -> List.of(new EvaluationError(null, "#" + index, index,
                    ErrorKind.UNREADABLE_RECORD, unreadable.reason(), new LinkedHashMap<>(), null));
            case SourceElement.Fields(Map<String, ?> fields) -> {
                String recordId = recordId(fields, index);
                Object country = fields.get(countryField);
                String recordCountry = country instanceof String s ? s : null;
                var outcomes = new ArrayList<Object>(rules.size());
                for (Rule rule : rules) {
                    if (rule.scope().appliesTo(recordCountry)) {
                        outcomes.add(ruleTimeout == null
                                ? evaluate(rule, fields, recordId, index, new TrackingFieldReader(fields))
                                : timed(rule, fields, recordId, index));
                    } else {
                        SequencedMap<String, Object> read = new LinkedHashMap<>();
                        read.put(countryField, fields.containsKey(countryField) ? country : Provenance.ABSENT);
                        outcomes.add(new Result(rule.id(), recordId, Decision.NOT_APPLICABLE, rule.severity(),
                                new Provenance(rule.id(), rule.label(), recordId, index, read, null,
                                        Basis.OUT_OF_SCOPE, outOfScope(rule, fields, country))));
                    }
                }
                yield outcomes;
            }
        };
    }

    /**
     * Runs one evaluation on a fresh virtual thread and waits for it at most {@link #ruleTimeout}. A rule
     * that overruns is interrupted and abandoned: the run does not wait for it to stop.
     */
    private Object timed(Rule rule, Map<String, ?> fields, String recordId, long index) throws InterruptedException {
        var reader = TrackingFieldReader.shared(fields); // read by this thread if the rule overruns
        var task = new FutureTask<>(() -> evaluate(rule, fields, recordId, index, reader));
        Thread.ofVirtual().name("dq-rule-" + rule.id()).start(task);
        try {
            return task.get(ruleTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return error(rule, recordId, index, ErrorKind.TIMEOUT,
                    "rule did not finish within " + ruleTimeout.toMillis() + " ms", null, reader);
        } catch (InterruptedException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            // evaluate() isolates everything a rule may throw; only fatal Errors escape it.
            if (e.getCause() instanceof Error fatal) {
                throw fatal;
            }
            if (e.getCause() instanceof RuntimeException bug) {
                throw bug;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** The isolation boundary: whatever the rule does, the outcome is one Result or one EvaluationError. */
    private static Object evaluate(Rule rule, Map<String, ?> fields, String recordId, long index,
            TrackingFieldReader reader) {
        String value;
        try {
            value = rule.logic().compute(reader);
        } catch (FieldAccessException e) {
            return error(rule, recordId, index, e.kind(), e.getMessage(), null, reader);
        } catch (StackOverflowError e) {
            // Checked before VirtualMachineError: the stack has unwound, so it is safe to continue.
            return ruleException(rule, recordId, index, "rule logic threw: ", e, reader);
        } catch (Exception | LinkageError e) {
            keepInterrupt(e);
            return ruleException(rule, recordId, index, "rule logic threw: ", e, reader);
        }
        if (value == null) {
            return error(rule, recordId, index, ErrorKind.NULL_VALUE, "rule logic returned null", null, reader);
        }
        Decision decision;
        Basis basis;
        switch (rule.mapping().resolve(value)) {
            case Resolution.Mapped(Decision d) -> { decision = d; basis = Basis.MAPPED; }
            case Resolution.Defaulted(Decision d) -> { decision = d; basis = Basis.DEFAULT; }
            case Resolution.Unmapped() -> {
                return error(rule, recordId, index, ErrorKind.UNMAPPED_VALUE,
                        "computed value '" + value + "' is not mapped and the mapping has no default", null, reader);
            }
        }
        // The explainer is rule code too, so it sits inside the same isolation boundary. It runs after
        // the decision is fixed and sees only the evidence, so it cannot change the outcome.
        String explanation;
        try {
            explanation = rule.explainer().explain(new RecordedEvidence(reader.fieldsRead(), value));
        } catch (StackOverflowError e) {
            return ruleException(rule, recordId, index, "rule explanation threw: ", e, reader);
        } catch (Exception | LinkageError e) {
            keepInterrupt(e);
            return ruleException(rule, recordId, index, "rule explanation threw: ", e, reader);
        }
        return new Result(rule.id(), recordId, decision, rule.severity(), new Provenance(rule.id(), rule.label(),
                recordId, index, reader.fieldsRead(), value, basis, explanation));
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
}
