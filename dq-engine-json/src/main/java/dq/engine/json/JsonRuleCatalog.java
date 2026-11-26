package dq.engine.json;

import dq.engine.catalog.CountryScope;
import dq.engine.catalog.Decision;
import dq.engine.catalog.DecisionMapping;
import dq.engine.catalog.InvalidCatalogException;
import dq.engine.catalog.LogicRegistry;
import dq.engine.catalog.Rule;
import dq.engine.catalog.RuleCatalog;
import dq.engine.catalog.RuleStatus;
import dq.engine.catalog.Severity;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A {@link RuleCatalog} read from a JSON document. Rule definitions are data; the code they run is
 * named, and resolved in a {@link LogicRegistry}:
 *
 * <pre>{@code
 * {"rules": [{
 *   "id": "vatFormat",
 *   "label": "VAT id has a plausible length",
 *   "logic": "vatLength",                      // a LogicRegistry logic name
 *   "explainer": "vatLength",                  // optional, a LogicRegistry explainer name
 *   "mapping": {"cases": {"ok": "VALID", "bad": "INVALID"}, "default": "REVIEW"},
 *   "status": "RELEASED",
 *   "severity": "ERROR",
 *   "scope": "WORLD",                          // optional: WORLD (default) or an ISO 3166-1 alpha-2 code
 *   "categories": ["tax"]                      // optional
 * }]}
 * }</pre>
 *
 * The document is read and validated once, when {@link #read} is called; the catalog it returns is an
 * immutable snapshot. Unknown properties, duplicate keys and unknown logic names are errors, so a typo
 * in a definition fails at load time rather than silently changing what a rule does.
 */
public final class JsonRuleCatalog {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final Set<String> DOCUMENT_KEYS = Set.of("rules");
    private static final Set<String> RULE_KEYS = Set.of(
            "id", "label", "logic", "explainer", "mapping", "status", "severity", "scope", "categories");
    private static final Set<String> MAPPING_KEYS = Set.of("cases", "default");

    private JsonRuleCatalog() {}

    /**
     * Reads a catalog document and resolves each rule's logic and explainer in {@code registry}. Reads
     * {@code json} to the end and closes it.
     *
     * @throws InvalidCatalogException if the document is not valid JSON, does not have the shape above,
     *     names logic or an explainer the registry does not have, or defines an invalid rule (blank id,
     *     duplicate id, empty mapping, …)
     */
    public static RuleCatalog read(InputStream json, LogicRegistry registry) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(registry, "registry");
        JsonNode document;
        try (json) {
            document = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidCatalogException("catalog is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (java.io.IOException e) {
            throw new InvalidCatalogException("could not read catalog: " + e.getMessage(), e);
        }
        if (document == null || !document.isObject()) {
            throw new InvalidCatalogException("catalog must be a JSON object with a \"rules\" array");
        }
        onlyKeys(document, DOCUMENT_KEYS, "catalog");
        JsonNode rules = document.get("rules");
        if (rules == null || !rules.isArray()) {
            throw new InvalidCatalogException("catalog must be a JSON object with a \"rules\" array");
        }
        var parsed = new ArrayList<Rule>(rules.size());
        int index = 0;
        for (JsonNode rule : rules.values()) {
            parsed.add(rule(rule, index++, registry));
        }
        return RuleCatalog.of(parsed); // duplicate ids are rejected here
    }

    private static Rule rule(JsonNode node, int index, LogicRegistry registry) {
        if (!node.isObject()) {
            throw new InvalidCatalogException("rules[" + index + "] must be a JSON object");
        }
        String where = node.get("id") != null && node.get("id").isString()
                ? "rule '" + node.get("id").stringValue() + "'" : "rules[" + index + "]";
        onlyKeys(node, RULE_KEYS, where);

        String id = requiredText(node, "id", where);
        String label = requiredText(node, "label", where);
        String logicName = requiredText(node, "logic", where);
        var builder = Rule.builder()
                .id(id)
                .label(label)
                .logic(registry.logic(logicName).orElseThrow(() -> new InvalidCatalogException(
                        where + ": unknown logic '" + logicName + "'; registered: " + registry.logicNames())))
                .mapping(mapping(node.get("mapping"), where))
                .status(constant(RuleStatus.class, requiredText(node, "status", where), where, "status"))
                .severity(constant(Severity.class, requiredText(node, "severity", where), where, "severity"));
        String explainerName = optionalText(node, "explainer", where);
        if (explainerName != null) {
            builder.explain(registry.explainer(explainerName).orElseThrow(() -> new InvalidCatalogException(
                    where + ": unknown explainer '" + explainerName + "'; registered: "
                            + registry.explainerNames())));
        }
        String scope = optionalText(node, "scope", where);
        if (scope != null && !scope.equals("WORLD")) {
            try {
                builder.scope(CountryScope.country(scope));
            } catch (IllegalArgumentException e) {
                throw new InvalidCatalogException(where + ": scope must be WORLD or a two-letter ISO code, was '"
                        + scope + "'");
            }
        }
        JsonNode categories = node.get("categories");
        if (categories != null) {
            if (!categories.isArray()) {
                throw new InvalidCatalogException(where + ": categories must be an array of strings");
            }
            for (JsonNode category : categories.values()) {
                if (!category.isString()) {
                    throw new InvalidCatalogException(where + ": categories must be an array of strings");
                }
                builder.category(category.stringValue());
            }
        }
        return builder.build(); // Rule's own checks (blank label, blank category, …) apply too
    }

    private static DecisionMapping mapping(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new InvalidCatalogException(where + ": mapping must be an object with \"cases\" and/or \"default\"");
        }
        onlyKeys(node, MAPPING_KEYS, where + " mapping");
        var builder = DecisionMapping.builder();
        JsonNode cases = node.get("cases");
        if (cases != null) {
            if (!cases.isObject()) {
                throw new InvalidCatalogException(where + ": mapping cases must be an object of value → decision");
            }
            for (Map.Entry<String, JsonNode> entry : cases.properties()) {
                if (!entry.getValue().isString()) {
                    throw new InvalidCatalogException(where + ": mapping case '" + entry.getKey()
                            + "' must name a decision");
                }
                builder.when(entry.getKey(), constant(Decision.class, entry.getValue().stringValue(), where,
                        "mapping case '" + entry.getKey() + "'"));
            }
        }
        String fallback = optionalText(node, "default", where + " mapping");
        if (fallback != null) {
            builder.otherwise(constant(Decision.class, fallback, where, "mapping default"));
        }
        try {
            return builder.build();
        } catch (InvalidCatalogException e) {
            throw new InvalidCatalogException(where + ": " + e.getMessage());
        }
    }

    private static void onlyKeys(JsonNode node, Set<String> allowed, String where) {
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw new InvalidCatalogException(where + ": unknown property '" + key + "'; allowed: "
                        + allowed.stream().sorted().toList());
            }
        }
    }

    private static String requiredText(JsonNode node, String key, String where) {
        String value = optionalText(node, key, where);
        if (value == null) {
            throw new InvalidCatalogException(where + ": \"" + key + "\" is required");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InvalidCatalogException(where + ": \"" + key + "\" must be a string");
        }
        return value.stringValue();
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String where, String what) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            List<String> allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
            throw new InvalidCatalogException(where + ": " + what + " must be one of " + allowed + ", was '"
                    + name + "'");
        }
    }
}
