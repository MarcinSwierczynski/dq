package dq.engine.fixtures;

import static dq.engine.catalog.Decision.INVALID;
import static dq.engine.catalog.Decision.NOT_APPLICABLE;
import static dq.engine.catalog.Decision.VALID;

import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import dq.engine.run.RunSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The §5 fixture of the task brief: three records and three RELEASED rules. */
public final class Fixture {
    private Fixture() {}

    public static final Rule COUNTRY_BLOCKED = Rule.builder()
            .id("countryBlocked").label("Country is not blocked")
            .logic(f -> f.text("country").filter("ZZ"::equals).map(c -> "blocked").orElse("ok"))
            .explain(e -> e.text("country")
                    .map(c -> "country " + c + (e.value().equals("blocked") ? " is blocked" : " is not blocked"))
                    .orElse("no country"))
            .mapping(DecisionMapping.builder().when("blocked", INVALID).otherwise(NOT_APPLICABLE).build())
            .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("compliance")
            .build();

    public static final Rule VAT_FORMAT = Rule.builder()
            .id("vatFormat").label("VAT id has a plausible length")
            .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
            .explain(e -> e.text("vatId")
                    .map(v -> v.length() >= 9 ? "len " + v.length() : "len " + v.length() + " instead of 9")
                    .orElse("no vatId"))
            .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
            .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax")
            .build();

    public static final Rule IBAN_FORMAT = Rule.builder()
            .id("ibanFormat").label("IBAN starts with the record's country")
            .logic(f -> {
                String country = f.require("country");
                return f.text("iban").filter(iban -> iban.startsWith(country)).map(iban -> "ok").orElse("bad");
            })
            .explain(e -> {
                String country = e.text("country").orElseThrow();
                return e.text("iban").map(iban -> iban.isEmpty() ? "empty iban"
                        : iban.startsWith(country) ? "iban starts with " + country
                        : "iban does not start with " + country).orElse("no iban");
            })
            .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
            .status(RuleStatus.RELEASED).severity(Severity.WARNING).category("banking")
            .build();

    public static final List<Rule> RULES = List.of(COUNTRY_BLOCKED, VAT_FORMAT, IBAN_FORMAT);

    public static RuleCatalog catalog() {
        return RuleCatalog.of(RULES);
    }

    public static final List<Map<String, Object>> RECORDS = List.of(
            record("id", "r1", "vatId", "DE111111111", "country", "DE", "legalName", "ACME GmbH", "iban", "DE123456789"),
            record("id", "r2", "vatId", "FR22", "country", "FR", "legalName", "Bricolage SARL", "iban", "FR123456789"),
            record("id", "r3", "country", "ZZ", "legalName", "Nowhere Ltd", "iban", ""));

    /** Expected decisions, keyed "record/rule". */
    public static final Map<String, dq.engine.catalog.Decision> EXPECTED = Map.of(
            "r1/countryBlocked", NOT_APPLICABLE, "r1/vatFormat", VALID, "r1/ibanFormat", VALID,
            "r2/countryBlocked", NOT_APPLICABLE, "r2/vatFormat", INVALID, "r2/ibanFormat", VALID,
            "r3/countryBlocked", INVALID, "r3/vatFormat", INVALID, "r3/ibanFormat", INVALID);

    /** An ordered record from alternating key/value pairs. */
    public static Map<String, Object> record(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** Results a run delivered: the sum of {@link RunSummary#byDecision()}. */
    public static long resultCount(RunSummary summary) {
        return summary.byDecision().values().stream().mapToLong(Long::longValue).sum();
    }
}
