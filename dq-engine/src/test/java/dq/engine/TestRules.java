package dq.engine;

import dq.engine.api.Decision;
import dq.engine.api.DecisionMapping;
import dq.engine.api.Rule;
import dq.engine.api.RuleLogic;
import dq.engine.api.RuleStatus;
import dq.engine.api.Severity;

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
