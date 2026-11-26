package dq.http;

import dq.engine.catalog.LogicRegistry;
import dq.engine.catalog.RuleCatalog;
import dq.engine.json.JsonRuleCatalog;
import dq.engine.run.DqEngine;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Wires one engine bean from a JSON rule catalog ({@code dq.http.catalog}, default {@code rules.json} on
 * the classpath): the three §5 rules plus {@code deVatPrefix}, which is scoped to Germany so the example
 * shows the per-record scope check ({@code NOT_APPLICABLE} / {@code OUT_OF_SCOPE} on non-DE records).
 *
 * <p>The definitions — ids, labels, mappings, status, severity, scope, categories — are data in that file.
 * Only the code a rule runs is Java, registered here by name; the file refers to it by that name. A host
 * with a rule store would implement {@link RuleCatalog} over it the same way.
 */
@Configuration(proxyBeanMethods = false)
class ExampleCatalog {

    /** The rule code the catalog file can name. */
    static LogicRegistry registry() {
        return LogicRegistry.builder()
                .logic("countryBlocked", f -> f.text("country").filter("ZZ"::equals).map(c -> "blocked").orElse("ok"))
                .explainer("countryBlocked", e -> e.text("country")
                        .map(c -> "country " + c + (e.value().equals("blocked") ? " is blocked" : " is not blocked"))
                        .orElse("no country"))
                .logic("vatMinLength9", f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
                .explainer("vatMinLength9", e -> e.text("vatId")
                        .map(v -> v.length() >= 9 ? "len " + v.length() : "len " + v.length() + " instead of 9")
                        .orElse("no vatId"))
                .logic("ibanCountryPrefix", f -> {
                    String country = f.require("country");
                    return f.text("iban").filter(iban -> iban.startsWith(country)).map(i -> "ok").orElse("bad");
                })
                .explainer("ibanCountryPrefix", e -> {
                    String country = e.text("country").orElseThrow();
                    return e.text("iban").map(iban -> iban.isEmpty() ? "empty iban"
                            : iban.startsWith(country) ? "iban starts with " + country
                            : "iban does not start with " + country).orElse("no iban");
                })
                .logic("vatPrefixDE", f -> f.text("vatId").filter(v -> v.startsWith("DE")).map(v -> "ok").orElse("bad"))
                .explainer("vatPrefixDE", e -> e.text("vatId")
                        .map(v -> v.startsWith("DE") ? "starts with DE"
                                : "starts with " + v.substring(0, Math.min(2, v.length())) + " instead of DE")
                        .orElse("no vatId"))
                .build();
    }

    @Bean
    DqEngine dqEngine(@Value("${dq.http.catalog:classpath:rules.json}") Resource catalog) throws IOException {
        return DqEngine.builder().catalog(JsonRuleCatalog.read(catalog.getInputStream(), registry())).build();
    }
}
