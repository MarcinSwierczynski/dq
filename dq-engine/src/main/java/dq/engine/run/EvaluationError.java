package dq.engine.run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * A (rule, record) evaluation — or a whole record — that could not produce a {@link Result}. The run
 * continues after it.
 *
 * <p>Its value is plain data (strings, numbers, the error kind and the fields read), so it compares and
 * serializes like {@link Result}. The underlying exception of an engine-created error is kept on the
 * side, for logging: {@link #cause()} is not part of {@code equals}, {@code hashCode} or
 * {@code toString}.
 *
 * <p>A final class rather than a record, so that later minor versions can add information without
 * breaking hosts.
 */
@Value
@Accessors(fluent = true) // record-style accessors: recordId(), not getRecordId()
public class EvaluationError {

    /** Null for record-level errors; exposed as {@link #ruleId()}. */
    String ruleId;
    /** The record's id, or {@code #<index>} when the record has none. */
    String recordId;
    /** 0-based position of the record in the input. */
    long recordIndex;
    /** The error category. */
    ErrorKind kind;
    /** A human-readable description. */
    String message;
    /** Null when there is no underlying exception; exposed as {@link #causeType()}. */
    String causeType;
    /** Fields the rule read before failing, in read order, with the value found; unmodifiable. */
    SequencedMap<String, Object> fieldsRead;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Throwable cause;

    /**
     * Normally created by the engine; public for host test doubles and for hosts that rebuild errors
     * from storage.
     *
     * @param ruleId      the failing rule, or {@code null} for a record-level error
     *                    ({@link ErrorKind#UNREADABLE_RECORD})
     * @param recordId    the record's id, or {@code #<index>}
     * @param recordIndex 0-based position of the record in the input
     * @param kind        the error category
     * @param message     a human-readable description
     * @param causeType   class name of the underlying exception, or {@code null} when there is none
     * @param fieldsRead  fields the rule read before failing, in read order
     */
    public EvaluationError(String ruleId, String recordId, long recordIndex, ErrorKind kind, String message,
            String causeType, SequencedMap<String, Object> fieldsRead) {
        this(ruleId, recordId, recordIndex, kind, message, causeType, fieldsRead, null);
    }

    /** Engine-created: {@code causeType} is the class name of {@code cause}, when there is one. */
    EvaluationError(String ruleId, String recordId, long recordIndex, ErrorKind kind, String message,
            SequencedMap<String, Object> fieldsRead, Throwable cause) {
        this(ruleId, recordId, recordIndex, kind, message, cause == null ? null : cause.getClass().getName(),
                fieldsRead, cause);
    }

    private EvaluationError(String ruleId, String recordId, long recordIndex, ErrorKind kind, String message,
            String causeType, SequencedMap<String, Object> fieldsRead, Throwable cause) {
        this.ruleId = ruleId;
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.recordIndex = recordIndex;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.message = Objects.requireNonNull(message, "message");
        this.causeType = causeType;
        this.fieldsRead = Collections.unmodifiableSequencedMap(
                new LinkedHashMap<>(Objects.requireNonNull(fieldsRead, "fieldsRead")));
        this.cause = cause;
    }

    /** The failing rule; empty for record-level errors ({@link ErrorKind#UNREADABLE_RECORD}). */
    public Optional<String> ruleId() {
        return Optional.ofNullable(ruleId);
    }

    /** Class name of the underlying exception; empty when there is none. */
    public Optional<String> causeType() {
        return Optional.ofNullable(causeType);
    }

    /**
     * The underlying exception, for logging and debugging. Present only on errors the engine created in
     * this JVM (never on ones rebuilt from {@link #causeType()}), and not part of this error's value.
     * Do not send it to clients: its message and stack trace may expose internals.
     */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }
}
