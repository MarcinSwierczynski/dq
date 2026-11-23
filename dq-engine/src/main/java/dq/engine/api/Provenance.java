package dq.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Why a {@link Result} has its decision. Immutable, with value semantics.
 *
 * <p>A final class rather than a record, so that later minor versions can add information (for example a
 * rule version) as new accessors and constructor overloads without breaking hosts.
 */
@Value
@Accessors(fluent = true) // record-style accessors: ruleId(), not getRuleId()
public class Provenance {

    /** Value recorded in {@link #fieldsRead()} for a field that is not present in the record. */
    public static final String ABSENT = "<absent>";

    /** The rule that produced the result. */
    String ruleId;
    /** The rule's label at evaluation time. */
    String ruleLabel;
    /** The record's id, or {@code #<index>} when the record has none. */
    String recordId;
    /** 0-based position of the record in the input. */
    long recordIndex;
    /** Every field the rule read, in read order, with the value found or {@link #ABSENT}; unmodifiable. */
    SequencedMap<String, Object> fieldsRead;
    /** The value the rule logic returned; {@code null} for {@link Basis#OUT_OF_SCOPE}. */
    String computedValue;
    /** How the decision was reached. */
    Basis basis;

    /**
     * Normally created by the engine; public for host test doubles.
     *
     * @param ruleId        the rule that produced the result
     * @param ruleLabel     its label at evaluation time
     * @param recordId      the record's id, or {@code #<index>} when the record has none
     * @param recordIndex   0-based position of the record in the input
     * @param fieldsRead    every field the rule read, in read order, with the value found or {@link #ABSENT}
     * @param computedValue the value the rule logic returned; {@code null} for {@link Basis#OUT_OF_SCOPE}
     * @param basis         how the decision was reached
     */
    public Provenance(String ruleId, String ruleLabel, String recordId, long recordIndex,
            SequencedMap<String, Object> fieldsRead, String computedValue, Basis basis) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.ruleLabel = Objects.requireNonNull(ruleLabel, "ruleLabel");
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.recordIndex = recordIndex;
        this.fieldsRead = Collections.unmodifiableSequencedMap(
                new LinkedHashMap<>(Objects.requireNonNull(fieldsRead, "fieldsRead")));
        this.computedValue = computedValue;
        this.basis = Objects.requireNonNull(basis, "basis");
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
