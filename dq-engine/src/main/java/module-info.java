/**
 * Data-quality rule engine. Only {@code dq.engine.api} is exported; the evaluation loop and the JSON
 * parsing in {@code dq.engine.internal} are implementation details and may change in any release.
 */
module dq.engine {
    requires tools.jackson.databind;
    requires static lombok; // compile-time only

    exports dq.engine.api;
}
