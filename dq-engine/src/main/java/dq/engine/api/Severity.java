package dq.engine.api;

/** How serious a failed rule is; copied onto every result the rule produces. */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
