package dq.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * Why a {@link Result} has its decision.
 *
 * @param ruleId        the rule that produced the result
 * @param ruleLabel     its label at evaluation time
 * @param recordId      the record's id, or {@code #<index>} when the record has none
 * @param recordIndex   0-based position of the record in the input
 * @param fieldsRead    every field the rule read, in read order, with the value found or {@link #ABSENT}
 * @param computedValue the value the rule logic returned; {@code null} for {@link Basis#OUT_OF_SCOPE}
 * @param basis         how the decision was reached
 */
public record Provenance(
        String ruleId,
        String ruleLabel,
        String recordId,
        long recordIndex,
        SequencedMap<String, Object> fieldsRead,
        String computedValue,
        Basis basis) {

    /** Value recorded in {@link #fieldsRead()} for a field that is not present in the record. */
    public static final String ABSENT = "<absent>";

    public Provenance {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(ruleLabel, "ruleLabel");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(basis, "basis");
        fieldsRead = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(fieldsRead));
    }

    /** How the decision was reached. */
    public enum Basis {
        /** The computed value matched a mapping case. */
        MAPPED,
        /** The computed value matched no case; the mapping default applied. */
        DEFAULT,
        /** The rule's country scope excludes the record; the logic did not run. */
        OUT_OF_SCOPE
    }
}
