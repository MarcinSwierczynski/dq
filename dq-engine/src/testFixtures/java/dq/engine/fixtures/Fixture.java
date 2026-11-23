package dq.engine.fixtures;

import static dq.engine.api.Decision.INVALID;
import static dq.engine.api.Decision.NOT_APPLICABLE;
import static dq.engine.api.Decision.VALID;

import dq.engine.api.DecisionMapping;
import dq.engine.api.Rule;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RuleStatus;
import dq.engine.api.Severity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The §5 fixture of the task brief: three records and three RELEASED rules. */
public final class Fixture {
    private Fixture() {}

    public static final Rule COUNTRY_BLOCKED = Rule.builder()
            .id("countryBlocked").label("Country is not blocked")
            .logic(f -> f.text("country").filter("ZZ"::equals).map(c -> "blocked").orElse("ok"))
            .mapping(DecisionMapping.builder().when("blocked", INVALID).otherwise(NOT_APPLICABLE).build())
            .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("compliance")
            .build();

    public static final Rule VAT_FORMAT = Rule.builder()
            .id("vatFormat").label("VAT id has a plausible length")
            .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
            .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
            .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax")
            .build();

    public static final Rule IBAN_FORMAT = Rule.builder()
            .id("ibanFormat").label("IBAN starts with the record's country")
            .logic(f -> {
                String country = f.require("country");
                return f.text("iban").filter(iban -> iban.startsWith(country)).map(iban -> "ok").orElse("bad");
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
    public static final Map<String, dq.engine.api.Decision> EXPECTED = Map.of(
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
}
