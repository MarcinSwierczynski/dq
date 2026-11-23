package dq.engine.api;

/**
 * The computational half of a rule: turns a record's fields into a plain value such as {@code "ok"} or
 * {@code "blocked"}. It returns a value, never a {@link Decision} — the rule's {@link DecisionMapping}
 * decides. Implementations must be side-effect free and thread-safe.
 *
 * <p>Any exception thrown here is isolated to this (rule, record) pair and reported as an
 * {@link EvaluationError}.
 */
@FunctionalInterface
public interface RuleLogic {
    /** @return the computed value; {@code null} is reported as {@link ErrorKind#NULL_VALUE} */
    String compute(FieldReader fields) throws Exception;
}
