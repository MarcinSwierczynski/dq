package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.InvalidCatalogException;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import dq.engine.fixtures.Fixture;
import dq.engine.run.DqEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleCatalogTest {

    private static Rule.Builder valid() {
        return Rule.builder().id("r").label("Rule").logic(f -> "ok")
                .mapping(DecisionMapping.builder().when("ok", Decision.VALID).build())
                .status(RuleStatus.RELEASED).severity(Severity.INFO);
    }

    @Test
    void rejectsDuplicateIds() {
        Rule a = valid().id("same").build();
        Rule b = valid().id("same").label("Other").build();
        assertThatThrownBy(() -> RuleCatalog.of(a, b))
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining("duplicate rule id 'same'");
    }

    @Test
    void engineRevalidatesHostSuppliedCatalogs() {
        Rule a = valid().id("same").build();
        RuleCatalog hostCatalog = () -> List.of(a, a);
        assertThatThrownBy(() -> DqEngine.builder().catalog(hostCatalog).build())
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> valid().id(" ").build())
                .isInstanceOf(InvalidCatalogException.class).hasMessageContaining("id must not be blank");
        assertThatThrownBy(() -> valid().id(null).build()).isInstanceOf(InvalidCatalogException.class);
    }

    @Test
    void rejectsBlankLabel() {
        assertThatThrownBy(() -> valid().label("").build())
                .isInstanceOf(InvalidCatalogException.class).hasMessageContaining("'r': label");
    }

    @Test
    void rejectsEmptyMapping() {
        assertThatThrownBy(() -> valid().mapping(DecisionMapping.builder().build()).build())
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining("at least one case or a default");
    }

    @Test
    void rejectsMissingRequiredComponents() {
        assertThatThrownBy(() -> valid().logic(null).build()).hasMessageContaining("logic is required");
        assertThatThrownBy(() -> valid().mapping(null).build()).hasMessageContaining("mapping is required");
        assertThatThrownBy(() -> valid().status(null).build()).hasMessageContaining("status is required");
        assertThatThrownBy(() -> valid().severity(null).build()).hasMessageContaining("severity is required");
        assertThatThrownBy(() -> valid().category(" ").build()).hasMessageContaining("categories must not be blank");
    }

    @Test
    void catalogIsAnImmutableSnapshot() {
        var rules = new ArrayList<>(Fixture.RULES);
        RuleCatalog catalog = RuleCatalog.of(rules);
        rules.clear();

        assertThat(catalog.rules()).containsExactlyElementsOf(Fixture.RULES);
        assertThatThrownBy(() -> catalog.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultsScopeToWorldAndCategoriesToNone() {
        Rule rule = valid().build();
        assertThat(rule.scope()).isEqualTo(dq.engine.catalog.CountryScope.world());
        assertThat(rule.categories()).isEmpty();
    }
}
