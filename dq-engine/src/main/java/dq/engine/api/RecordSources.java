package dq.engine.api;

import dq.engine.internal.JsonArrayRecordSource;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Built-in {@link RecordSource}s. */
public final class RecordSources {
    private RecordSources() {}

    /** Records already in memory. Each map is one record. */
    public static RecordSource of(List<? extends Map<String, ?>> records) {
        Objects.requireNonNull(records, "records");
        Iterator<? extends Map<String, ?>> it = List.copyOf(records).iterator();
        return () -> it.hasNext() ? SourceElement.of(it.next()) : null;
    }

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
