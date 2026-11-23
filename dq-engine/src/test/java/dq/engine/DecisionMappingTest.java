package dq.engine;

import static dq.engine.catalog.Decision.INVALID;
import static dq.engine.catalog.Decision.NOT_APPLICABLE;
import static dq.engine.catalog.Decision.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.DecisionMapping.Resolution;
import dq.engine.catalog.InvalidCatalogException;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DecisionMappingTest {

    private static final DecisionMapping WITH_DEFAULT =
            DecisionMapping.builder().when("blocked", INVALID).otherwise(NOT_APPLICABLE).build();
    private static final DecisionMapping WITHOUT_DEFAULT =
            DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build();

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of(WITH_DEFAULT, "blocked", new Resolution.Mapped(INVALID)),
                Arguments.of(WITH_DEFAULT, "ok", new Resolution.Defaulted(NOT_APPLICABLE)),
                Arguments.of(WITHOUT_DEFAULT, "ok", new Resolution.Mapped(VALID)),
                Arguments.of(WITHOUT_DEFAULT, "bad", new Resolution.Mapped(INVALID)),
                Arguments.of(WITHOUT_DEFAULT, "OK", new Resolution.Unmapped()),
                Arguments.of(WITHOUT_DEFAULT, "", new Resolution.Unmapped()));
    }

    @ParameterizedTest(name = "{1} -> {2}")
    @MethodSource("table")
    void resolvesMappedDefaultedAndUnmappedValues(DecisionMapping mapping, String value, Resolution expected) {
        assertThat(mapping.resolve(value)).isEqualTo(expected);
    }

    @Test
    void rejectsAMappingWithNeitherCasesNorDefault() {
        assertThatThrownBy(() -> DecisionMapping.builder().build())
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining("at least one case or a default");
        assertThatThrownBy(() -> new DecisionMapping(Map.of(), Optional.empty()))
                .isInstanceOf(InvalidCatalogException.class);
    }

    @Test
    void acceptsADefaultOnlyMapping() {
        DecisionMapping mapping = DecisionMapping.builder().otherwise(VALID).build();
        assertThat(mapping.resolve("anything")).isEqualTo(new Resolution.Defaulted(VALID));
    }

    @Test
    void rejectsDuplicateValues() {
        assertThatThrownBy(() -> DecisionMapping.builder().when("ok", VALID).when("ok", INVALID))
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining("'ok' twice");
    }

    @Test
    void isImmutableAndKeepsDeclarationOrder() {
        var source = new java.util.LinkedHashMap<String, dq.engine.catalog.Decision>();
        source.put("b", INVALID);
        source.put("a", VALID);
        DecisionMapping mapping = new DecisionMapping(source, Optional.empty());
        source.put("c", VALID);

        assertThat(mapping.cases()).containsExactly(Map.entry("b", INVALID), Map.entry("a", VALID));
        assertThatThrownBy(() -> mapping.cases().put("x", VALID)).isInstanceOf(UnsupportedOperationException.class);
    }
}
