package dq.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * Why a {@link Result} has its decision. Immutable, with value semantics.
 *
 * <p>A final class rather than a record, so that later minor versions can add information (for example a
 * rule version) as new accessors and constructor overloads without breaking hosts.
 */
public final class Provenance {

    /** Value recorded in {@link #fieldsRead()} for a field that is not present in the record. */
    public static final String ABSENT = "<absent>";

    private final String ruleId;
    private final String ruleLabel;
    private final String recordId;
    private final long recordIndex;
    private final SequencedMap<String, Object> fieldsRead;
    private final String computedValue;
    private final Basis basis;

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

    /** The rule that produced the result. */
    public String ruleId() {
        return ruleId;
    }

    /** The rule's label at evaluation time. */
    public String ruleLabel() {
        return ruleLabel;
    }

    /** The record's id, or {@code #<index>} when the record has none. */
    public String recordId() {
        return recordId;
    }

    /** 0-based position of the record in the input. */
    public long recordIndex() {
        return recordIndex;
    }

    /** Every field the rule read, in read order, with the value found or {@link #ABSENT}; unmodifiable. */
    public SequencedMap<String, Object> fieldsRead() {
        return fieldsRead;
    }

    /** The value the rule logic returned; {@code null} for {@link Basis#OUT_OF_SCOPE}. */
    public String computedValue() {
        return computedValue;
    }

    /** How the decision was reached. */
    public Basis basis() {
        return basis;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Provenance p
                && recordIndex == p.recordIndex
                && ruleId.equals(p.ruleId)
                && ruleLabel.equals(p.ruleLabel)
                && recordId.equals(p.recordId)
                && fieldsRead.equals(p.fieldsRead)
                && Objects.equals(computedValue, p.computedValue)
                && basis == p.basis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId, ruleLabel, recordId, recordIndex, fieldsRead, computedValue, basis);
    }

    @Override
    public String toString() {
        return "Provenance[ruleId=" + ruleId + ", ruleLabel=" + ruleLabel + ", recordId=" + recordId
                + ", recordIndex=" + recordIndex + ", fieldsRead=" + fieldsRead + ", computedValue="
                + computedValue + ", basis=" + basis + "]";
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
