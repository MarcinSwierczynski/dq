package dq.engine.api;

import java.util.Map;
import java.util.Objects;

/** One element pulled from a {@link RecordSource}: a record's fields, or something that is not a record. */
public sealed interface SourceElement permits SourceElement.Fields, SourceElement.Unreadable {

    static SourceElement of(Map<String, ?> fields) {
        return new Fields(fields);
    }

    static SourceElement unreadable(String reason) {
        return new Unreadable(reason);
    }

    /** A readable record. Values are JSON-like: String, Number, Boolean, List, Map or null. */
    record Fields(Map<String, ?> fields) implements SourceElement {
        public Fields {
            Objects.requireNonNull(fields, "fields");
        }
    }

    /** An element that is not a record; reported as {@link ErrorKind#UNREADABLE_RECORD}, and the run continues. */
    record Unreadable(String reason) implements SourceElement {
        public Unreadable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
