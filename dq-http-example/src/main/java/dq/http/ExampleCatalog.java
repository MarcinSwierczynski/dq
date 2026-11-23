package dq.http;

import static dq.engine.api.Decision.INVALID;
import static dq.engine.api.Decision.NOT_APPLICABLE;
import static dq.engine.api.Decision.VALID;

import dq.engine.api.DecisionMapping;
import dq.engine.api.DqEngine;
import dq.engine.api.Rule;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RuleStatus;
import dq.engine.api.Severity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three §5 rules, wired into one engine bean. A real host would load definitions from its own
 * store by implementing {@link RuleCatalog}.
 */
@Configuration(proxyBeanMethods = false)
class ExampleCatalog {

    static RuleCatalog catalog() {
        return RuleCatalog.of(
                Rule.builder().id("countryBlocked").label("Country is not blocked")
                        .logic(f -> f.text("country").filter("ZZ"::equals).map(c -> "blocked").orElse("ok"))
                        .mapping(DecisionMapping.builder().when("blocked", INVALID).otherwise(NOT_APPLICABLE).build())
                        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("compliance").build(),
                Rule.builder().id("vatFormat").label("VAT id has a plausible length")
                        .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
                        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
                        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax").build(),
                Rule.builder().id("ibanFormat").label("IBAN starts with the record's country")
                        .logic(f -> {
                            String country = f.require("country");
                            return f.text("iban").filter(iban -> iban.startsWith(country)).map(i -> "ok").orElse("bad");
                        })
                        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
                        .status(RuleStatus.RELEASED).severity(Severity.WARNING).category("banking").build());
    }

    @Bean
    DqEngine dqEngine() {
        return DqEngine.builder().catalog(catalog()).build();
    }
}
