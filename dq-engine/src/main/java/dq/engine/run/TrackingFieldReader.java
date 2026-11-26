package dq.engine.run;

import dq.engine.catalog.FieldReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * A {@link FieldReader} that records every field read, in order, for provenance. Confined to one thread,
 * unless created with {@link #shared}.
 */
final class TrackingFieldReader implements FieldReader {
    private final Map<String, ?> fields;
    private final SequencedMap<String, Object> read = new LinkedHashMap<>();
    private final Object lock; // null when confined to one thread

    TrackingFieldReader(Map<String, ?> fields) {
        this(fields, null);
    }

    private TrackingFieldReader(Map<String, ?> fields, Object lock) {
        this.fields = fields;
        this.lock = lock;
    }

    /**
     * A reader whose {@link #fieldsRead()} may be called from another thread while rule logic is still
     * reading through it: the engine reports what a timed-out rule had read so far.
     */
    static TrackingFieldReader shared(Map<String, ?> fields) {
        return new TrackingFieldReader(fields, new Object());
    }

    @Override
    public Optional<String> text(String name) {
        Object value = track(name);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String s) {
            return Optional.of(s);
        }
        throw new FieldAccessException(ErrorKind.TYPE_MISMATCH,
                "field '" + name + "' is " + typeName(value) + ", expected text");
    }

    @Override
    public String require(String name) {
        return text(name).orElseThrow(() -> new FieldAccessException(ErrorKind.MISSING_REQUIRED_FIELD,
                "required field '" + name + "' is missing"));
    }

    @Override
    public Optional<Object> raw(String name) {
        return Optional.ofNullable(track(name));
    }

    @Override
    public boolean has(String name) {
        return track(name) != null;
    }

    /**
     * Fields read so far, with their values or {@link Provenance#ABSENT}: the live map for a confined
     * reader, a snapshot for a {@link #shared} one.
     */
    SequencedMap<String, Object> fieldsRead() {
        if (lock == null) {
            return read;
        }
        synchronized (lock) {
            return new LinkedHashMap<>(read);
        }
    }

    private Object track(String name) {
        Objects.requireNonNull(name, "field name");
        if (lock == null) {
            return record(name);
        }
        synchronized (lock) {
            return record(name);
        }
    }

    private Object record(String name) {
        Object value = fields.get(name);
        read.putIfAbsent(name, fields.containsKey(name) ? value : Provenance.ABSENT);
        return value;
    }

    private static String typeName(Object value) {
        return switch (value) {
            case Number n -> "a number";
            case Boolean b -> "a boolean";
            case Map<?, ?> m -> "an object";
            case Iterable<?> i -> "an array";
            default -> "a " + value.getClass().getSimpleName();
        };
    }
}
