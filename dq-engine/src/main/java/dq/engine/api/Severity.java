package dq.engine.api;

/** How serious a failed rule is; copied onto every {@link Result} the rule produces. */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
