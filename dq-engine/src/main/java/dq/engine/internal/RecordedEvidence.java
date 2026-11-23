package dq.engine.internal;

import dq.engine.api.Provenance;
import dq.engine.api.RuleExplainer;
import java.util.Map;
import java.util.Optional;

/** {@link RuleExplainer.Evidence} over the fields a rule's logic read and the value it computed. */
record RecordedEvidence(Map<String, Object> fieldsRead, String value) implements RuleExplainer.Evidence {

    @Override
    public Optional<String> text(String name) {
        return raw(name).filter(String.class::isInstance).map(String.class::cast);
    }

    @Override
    public Optional<Object> raw(String name) {
        Object found = fieldsRead.get(name);
        // Identity, not equals: only the engine's marker means "absent", not a record holding that text.
        return found == Provenance.ABSENT ? Optional.empty() : Optional.ofNullable(found);
    }
}
