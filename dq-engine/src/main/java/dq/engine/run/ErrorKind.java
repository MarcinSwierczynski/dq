package dq.engine.run;

import dq.engine.catalog.FieldReader;

/**
 * Why an evaluation produced an {@link EvaluationError} instead of a {@link Result}.
 *
 * <p>This enum is <b>open for additions</b> in minor releases. Code that switches over it must
 * include a {@code default} branch.
 */
public enum ErrorKind {
    /** The rule logic threw an exception (or a {@link StackOverflowError} / {@link LinkageError}). */
    RULE_EXCEPTION,
    /** The rule called {@link FieldReader#require(String)} on an absent or null field. */
    MISSING_REQUIRED_FIELD,
    /** The rule read a field as text but the record holds a non-string value. */
    TYPE_MISMATCH,
    /** The rule logic returned {@code null} instead of a value. */
    NULL_VALUE,
    /** The computed value is not in the decision mapping and the mapping has no default. */
    UNMAPPED_VALUE,
    /** The input element is not a record (e.g. not a JSON object); reported once per element, without a rule. */
    UNREADABLE_RECORD,
    /**
     * The rule did not finish within {@link RunOptions#ruleTimeout()}; it was interrupted and abandoned.
     * The error lists the fields it had read by then.
     */
    TIMEOUT
}
