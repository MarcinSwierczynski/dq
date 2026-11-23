package dq.http;

import static dq.engine.catalog.Decision.INVALID;
import static dq.engine.catalog.Decision.NOT_APPLICABLE;
import static dq.engine.catalog.Decision.VALID;

import dq.engine.catalog.CountryScope;
import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import dq.engine.run.DqEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three §5 rules plus {@code deVatPrefix}, which is scoped to Germany so the example shows the
 * per-record scope check ({@code NOT_APPLICABLE} / {@code OUT_OF_SCOPE} on non-DE records), wired into one engine bean. A real host would load definitions from its own
 * store by implementing {@link RuleCatalog}.
 */
@Configuration(proxyBeanMethods = false)
class ExampleCatalog {

    static RuleCatalog catalog() {
        return RuleCatalog.of(
                Rule.builder().id("countryBlocked").label("Country is not blocked")
                        .logic(f -> f.text("country").filter("ZZ"::equals).map(c -> "blocked").orElse("ok"))
                        .explain(e -> e.text("country")
                                .map(c -> "country " + c + (e.value().equals("blocked") ? " is blocked" : " is not blocked"))
                                .orElse("no country"))
                        .mapping(DecisionMapping.builder().when("blocked", INVALID).otherwise(NOT_APPLICABLE).build())
                        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("compliance").build(),
                Rule.builder().id("vatFormat").label("VAT id has a plausible length")
                        .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
                        .explain(e -> e.text("vatId")
                                .map(v -> v.length() >= 9 ? "len " + v.length() : "len " + v.length() + " instead of 9")
                                .orElse("no vatId"))
                        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
                        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax").build(),
                Rule.builder().id("ibanFormat").label("IBAN starts with the record's country")
                        .logic(f -> {
                            String country = f.require("country");
                            return f.text("iban").filter(iban -> iban.startsWith(country)).map(i -> "ok").orElse("bad");
                        })
                        .explain(e -> {
                            String country = e.text("country").orElseThrow();
                            return e.text("iban").map(iban -> iban.isEmpty() ? "empty iban"
                                    : iban.startsWith(country) ? "iban starts with " + country
                                    : "iban does not start with " + country).orElse("no iban");
                        })
                        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
                        .status(RuleStatus.RELEASED).severity(Severity.WARNING).category("banking").build(),
                Rule.builder().id("deVatPrefix").label("German VAT id starts with DE")
                        .scope(CountryScope.country("DE"))
                        .logic(f -> f.text("vatId").filter(v -> v.startsWith("DE")).map(v -> "ok").orElse("bad"))
                        .explain(e -> e.text("vatId")
                                .map(v -> v.startsWith("DE") ? "starts with DE" : "starts with " + v.substring(0, Math.min(2, v.length())) + " instead of DE")
                                .orElse("no vatId"))
                        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
                        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax").build());
    }

    @Bean
    DqEngine dqEngine() {
        return DqEngine.builder().catalog(catalog()).build();
    }
}
