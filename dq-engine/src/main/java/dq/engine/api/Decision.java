package dq.engine.api;

/** The outcome a rule's {@link DecisionMapping} assigns to a record. */
public enum Decision {
    VALID,
    INVALID,
    REVIEW,
    NOT_APPLICABLE
}
