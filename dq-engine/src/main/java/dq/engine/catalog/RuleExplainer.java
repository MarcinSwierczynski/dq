package dq.engine.catalog;

import java.util.Optional;

/**
 * The explaining half of a rule: turns what the rule read and computed into a short, human-readable
 * reason such as {@code "len 4 instead of 9"} or {@code "no vatId"}. It runs after the decision is made,
 * so it cannot influence it; the result becomes {@link dq.engine.run.Provenance#explanation()}.
 *
 * <p>An exception thrown here is part of the rule failing: it is reported as
 * {@link dq.engine.run.ErrorKind#RULE_EXCEPTION} for this (rule, record) pair, like one thrown by the logic.
 * Implementations must be side-effect free and thread-safe.
 */
@FunctionalInterface
public interface RuleExplainer {

    /** @return the reason, or {@code null} for none */
    String explain(Evidence evidence) throws Exception;

    /** An explainer that gives no reason; the default for rules that do not declare one. */
    static RuleExplainer none() {
        return evidence -> null;
    }

    /**
     * What a rule's logic saw and returned. Only fields the logic read are visible, so an explanation
     * can only cite the evidence the decision was based on.
     */
    interface Evidence {

        /** A field the logic read, as text; empty when absent, null, not text, or not read by the logic. */
        Optional<String> text(String name);

        /** A field the logic read, as its raw value; empty when absent, null, or not read by the logic. */
        Optional<Object> raw(String name);

        /** The value the logic computed. */
        String value();
    }
}
