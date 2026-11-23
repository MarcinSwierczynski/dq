package dq.engine.api;

/** Lifecycle state of a rule. The engine only ever runs {@link #RELEASED} rules. */
public enum RuleStatus {
    DRAFT,
    RELEASED,
    DEPRECATED
}
