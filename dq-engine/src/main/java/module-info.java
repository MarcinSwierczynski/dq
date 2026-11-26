/**
 * Data-quality rule engine, grouped by feature: {@code dq.engine.catalog} (rules and their selection),
 * {@code dq.engine.source} (record input) and {@code dq.engine.run} (the engine, listeners and results).
 * Implementation classes such as the evaluation loop are package-private inside those packages and may
 * change in any release. The module has no runtime dependencies; JSON input is in {@code dq.engine.json}.
 */
module dq.engine {
    requires static lombok; // compile-time only

    exports dq.engine.catalog;
    exports dq.engine.source;
    exports dq.engine.run;
}
