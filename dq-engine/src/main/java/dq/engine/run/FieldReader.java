package dq.engine.run;

import dq.engine.catalog.RuleLogic;
import java.util.Optional;

/**
 * Read access to one record's fields, handed to {@link RuleLogic}. Every field name read through it is
 * recorded, in read order, with the value found (or {@link Provenance#ABSENT}); that list becomes the
 * {@link Provenance#fieldsRead()} of the outcome.
 *
 * <p>A field holding JSON {@code null} counts as absent for {@link #text}, {@link #require} and
 * {@link #has}. Instances are not thread-safe and are valid only during the {@code compute} call.
 */
public interface FieldReader {

    /**
     * The field as text, or empty when absent or null.
     * A non-string value fails the evaluation with {@link ErrorKind#TYPE_MISMATCH}.
     */
    Optional<String> text(String name);

    /**
     * The field as text; an absent or null field fails the evaluation with
     * {@link ErrorKind#MISSING_REQUIRED_FIELD}, a non-string value with {@link ErrorKind#TYPE_MISMATCH}.
     */
    String require(String name);

    /**
     * The raw field value (String, Number, Boolean, List or Map), or empty when absent or null. Nested
     * lists and maps are shared with the record and with provenance; logic must treat them as read-only.
     */
    Optional<Object> raw(String name);

    /** Whether the field is present with a non-null value. */
    boolean has(String name);
}
