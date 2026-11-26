package dq.engine.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.CountryScope;
import dq.engine.catalog.InvalidCatalogException;
import dq.engine.catalog.LogicRegistry;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.fixtures.Fixture;
import dq.engine.run.CollectingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.Result;
import dq.engine.source.RecordSources;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JsonRuleCatalogTest {

    /** The §5 rules' code, registered under the names the JSON document uses. */
    private static final LogicRegistry REGISTRY = registry(Fixture.RULES);

    private static LogicRegistry registry(List<Rule> rules) {
        var builder = LogicRegistry.builder();
        for (Rule rule : rules) {
            builder.logic(rule.id(), rule.logic()).explainer(rule.id(), rule.explainer());
        }
        return builder.build();
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static RuleCatalog fixtureCatalog() {
        return JsonRuleCatalog.read(JsonRuleCatalogTest.class.getResourceAsStream("fixture-catalog.json"), REGISTRY);
    }

    @Test
    void theFixtureCatalogAsDataProducesTheSameNineCellsAsTheCodeBuiltOne() {
        List<Result> fromJson = run(fixtureCatalog());
        List<Result> fromCode = run(Fixture.catalog());

        assertThat(fromJson.stream().collect(Collectors.toMap(r -> r.recordId() + "/" + r.ruleId(), Result::decision)))
                .isEqualTo(Fixture.EXPECTED);
        assertThat(fromJson).isEqualTo(fromCode); // decisions, severities, provenance and explanations
    }

    @Test
    void readsMetadataAndDefaults() {
        var rules = fixtureCatalog().rules().stream().collect(Collectors.toMap(Rule::id, Function.identity()));
        Rule fromCode = Fixture.COUNTRY_BLOCKED;
        Rule fromJson = rules.get("countryBlocked");
        assertThat(fromJson.label()).isEqualTo(fromCode.label());
        assertThat(fromJson.mapping()).isEqualTo(fromCode.mapping());
        assertThat(fromJson.status()).isEqualTo(fromCode.status());
        assertThat(fromJson.severity()).isEqualTo(fromCode.severity());
        assertThat(fromJson.categories()).containsExactly("compliance");
        assertThat(fromJson.scope()).isEqualTo(CountryScope.world()); // scope omitted
    }

    @Test
    void readsACountryScopeAndOptionalParts() {
        RuleCatalog catalog = JsonRuleCatalog.read(json("""
                {"rules": [{"id": "de", "label": "DE only", "logic": "vatFormat",
                  "mapping": {"default": "REVIEW"}, "status": "DRAFT", "severity": "INFO", "scope": "de"}]}
                """), REGISTRY);
        Rule rule = catalog.rules().getFirst();
        assertThat(rule.scope()).isEqualTo(CountryScope.country("DE"));
        assertThat(rule.categories()).isEmpty();
        assertThat(rule.mapping().cases()).isEmpty();
        assertThat(rule.mapping().defaultDecision()).contains(dq.engine.catalog.Decision.REVIEW);
        assertThat(rule.explainer()).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '~', textBlock = """
            not JSON            | {"rules": [                                             | catalog is not valid JSON
            not an object       | []                                                      | must be a JSON object with a "rules" array
            no rules            | {}                                                      | must be a JSON object with a "rules" array
            unknown top key     | {"rules": [], "version": 2}                             | catalog: unknown property 'version'
            rule not an object  | {"rules": [42]}                                         | rules[0] must be a JSON object
            unknown rule key    | {"rules": [{"id": "a", "lable": "x"}]}                  | rule 'a': unknown property 'lable'
            missing logic       | {"rules": [{"id": "a", "label": "A"}]}                  | rule 'a': "logic" is required
            unknown logic       | {"rules": [{"id": "a", "label": "A", "logic": "nope"}]} | rule 'a': unknown logic 'nope'; registered: [countryBlocked, vatFormat, ibanFormat]
            unknown explainer   | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "explainer": "nope", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO"}]} | rule 'a': unknown explainer 'nope'
            missing mapping     | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat"}]}               | rule 'a': mapping must be an object
            empty mapping       | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {}}]} | rule 'a': decision mapping must have at least one case or a default
            bad decision        | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"cases": {"ok": "FINE"}}}]} | rule 'a': mapping case 'ok' must be one of [VALID, INVALID, REVIEW, NOT_APPLICABLE], was 'FINE'
            duplicate case      | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"cases": {"ok": "VALID", "ok": "INVALID"}}}]} | catalog is not valid JSON: Duplicate Object property "ok"
            bad status          | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "LIVE"}]} | rule 'a': status must be one of [DRAFT, RELEASED, DEPRECATED], was 'LIVE'
            bad scope           | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO", "scope": "Germany"}]} | rule 'a': scope must be WORLD or a two-letter ISO code, was 'Germany'
            bad categories      | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO", "categories": "tax"}]} | rule 'a': categories must be an array of strings
            non-string id       | {"rules": [{"id": 7}]}                                  | rules[0]: "id" must be a string
            blank label         | {"rules": [{"id": "a", "label": " ", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO"}]} | rule 'a': label must not be blank
            duplicate rule id   | {"rules": [{"id": "a", "label": "A", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO"}, {"id": "a", "label": "B", "logic": "vatFormat", "mapping": {"default": "VALID"}, "status": "RELEASED", "severity": "INFO"}]} | duplicate rule id 'a'
            """)
    void anInvalidDocumentFailsAtLoadTimeNamingTheProblem(String caseName, String document, String message) {
        assertThatThrownBy(() -> JsonRuleCatalog.read(json(document), REGISTRY))
                .isInstanceOf(InvalidCatalogException.class)
                .hasMessageContaining(message);
    }

    @Test
    void theCatalogIsReadOnceAndTheStreamIsClosed() {
        var closed = new boolean[1];
        InputStream in = new ByteArrayInputStream("{\"rules\": []}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        assertThat(JsonRuleCatalog.read(in, REGISTRY).rules()).isEmpty();
        assertThat(closed[0]).isTrue();
    }

    @Test
    void aRegistryRejectsDuplicateAndBlankNames() {
        assertThatThrownBy(() -> LogicRegistry.builder().logic("a", f -> "ok").logic("a", f -> "ok"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("logic 'a' is registered twice");
        assertThatThrownBy(() -> LogicRegistry.builder().explainer(" ", e -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("explainer name must not be blank");
        assertThat(REGISTRY.logic("missing")).isEqualTo(Optional.empty());
    }

    private static List<Result> run(RuleCatalog catalog) {
        var listener = new CollectingListener();
        DqEngine.builder().catalog(catalog).build().validate(RecordSources.of(Fixture.RECORDS), listener);
        return listener.results();
    }
}
