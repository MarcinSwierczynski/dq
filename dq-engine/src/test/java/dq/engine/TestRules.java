package dq.engine;

import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleLogic;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;

/** Small rule factories for tests. */
final class TestRules {
    private TestRules() {}

    /** A RELEASED INFO rule whose mapping is {@code ok→VALID} (no default). */
    static Rule.Builder rule(String id, RuleLogic logic) {
        return Rule.builder().id(id).label(id).logic(logic)
                .mapping(DecisionMapping.builder().when("ok", Decision.VALID).build())
                .status(RuleStatus.RELEASED).severity(Severity.INFO);
    }

    static Rule okRule(String id) {
        return rule(id, f -> "ok").build();
    }
}
