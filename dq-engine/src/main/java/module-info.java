/**
 * Data-quality rule engine, grouped by feature: {@code dq.engine.catalog} (rules and their selection),
 * {@code dq.engine.source} (record input) and {@code dq.engine.run} (the engine, listeners and results).
 * Implementation classes such as the evaluation loop and the JSON parser are package-private inside those
 * packages and may change in any release.
 */
module dq.engine {
    requires tools.jackson.databind;
    requires static lombok; // compile-time only

    exports dq.engine.catalog;
    exports dq.engine.source;
    exports dq.engine.run;
}
