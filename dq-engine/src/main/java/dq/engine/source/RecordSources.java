package dq.engine.source;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Built-in {@link RecordSource}s. JSON input lives in the separate {@code dq-engine-json} module
 * ({@code dq.engine.json.JsonRecordSources}), so this library has no runtime dependencies.
 */
public final class RecordSources {
    private RecordSources() {}

    /** Records already in memory. Each map is one record. */
    public static RecordSource of(List<? extends Map<String, ?>> records) {
        Objects.requireNonNull(records, "records");
        Iterator<? extends Map<String, ?>> it = List.copyOf(records).iterator();
        return () -> it.hasNext() ? SourceElement.of(it.next()) : null;
    }
}
