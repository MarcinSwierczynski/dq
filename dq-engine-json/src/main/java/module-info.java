/**
 * JSON adapters for {@code dq.engine}: a streaming JSON-array {@link dq.engine.source.RecordSource}.
 * Jackson is an internal dependency of this module; its types never appear in the exported API.
 */
module dq.engine.json {
    requires transitive dq.engine;
    requires tools.jackson.databind;

    exports dq.engine.json;
}
