package dq.engine.run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * A (rule, record) evaluation — or a whole record — that could not produce a {@link Result}. The run
 * continues after it.
 *
 * @param ruleId      the failing rule; empty for record-level errors ({@link ErrorKind#UNREADABLE_RECORD})
 * @param recordId    the record's id, or {@code #<index>}
 * @param recordIndex 0-based position of the record in the input
 * @param kind        the error category
 * @param message     a human-readable description
 * @param causeType   class name of the underlying exception, or {@code null} when there is none
 * @param fieldsRead  fields the rule read before failing, in read order
 * @param cause       the underlying exception, or {@code null}; not meant for serialization
 */
public record EvaluationError(
        Optional<String> ruleId,
        String recordId,
        long recordIndex,
        ErrorKind kind,
        String message,
        String causeType,
        SequencedMap<String, Object> fieldsRead,
        Throwable cause) {

    public EvaluationError {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        fieldsRead = Collections.unmodifiableSequencedMap(
                new LinkedHashMap<>(Objects.requireNonNull(fieldsRead, "fieldsRead")));
    }
}
