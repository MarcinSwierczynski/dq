package dq.engine.internal;

import dq.engine.api.ErrorKind;
import dq.engine.api.FieldReader;
import dq.engine.api.Provenance;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/** A {@link FieldReader} that records every field read, in order, for provenance. */
final class TrackingFieldReader implements FieldReader {
    private final Map<String, ?> fields;
    private final SequencedMap<String, Object> read = new LinkedHashMap<>();

    TrackingFieldReader(Map<String, ?> fields) {
        this.fields = fields;
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

    /** Fields read so far, with their values or {@link Provenance#ABSENT}. */
    SequencedMap<String, Object> fieldsRead() {
        return read;
    }

    private Object track(String name) {
        Objects.requireNonNull(name, "field name");
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
