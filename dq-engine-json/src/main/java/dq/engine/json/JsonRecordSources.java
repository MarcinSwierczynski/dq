package dq.engine.json;

import dq.engine.source.RecordSource;
import dq.engine.source.RecordSourceException;
import dq.engine.source.SourceElement;
import java.io.InputStream;
import java.util.Objects;

/** {@link RecordSource}s that read JSON. */
public final class JsonRecordSources {
    private JsonRecordSources() {}

    /**
     * A JSON array of objects, streamed: one element is parsed at a time, so memory does not grow with
     * the array. Parsing starts on the first {@link RecordSource#next()} call (inside {@code validate}).
     * Non-object elements become {@link SourceElement.Unreadable}; malformed JSON or I/O failure throws
     * {@link RecordSourceException}. The source owns {@code json} and closes it.
     */
    public static RecordSource jsonArray(InputStream json) {
        return new JsonArrayRecordSource(Objects.requireNonNull(json, "json"));
    }
}
