package dq.engine.catalog;

/** How serious a failed rule is; copied onto every {@link dq.engine.run.Result} the rule produces. */
public enum Severity {
    ERROR,
    WARNING,
    INFO
}
