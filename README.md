# dq — data-quality rule engine

An embeddable Java 25 library (`dq-engine`, no runtime dependencies) that validates business-partner
records against a catalog of rules, a JSON adapter for it (`dq-engine-json`, on Jackson), and a Spring
Boot example host (`dq-http-example`). Design and trade-offs: [`DESIGN.md`](DESIGN.md).
Spec: [`docs/specs/2026-11-20-dq-rule-engine.md`](docs/specs/2026-11-20-dq-rule-engine.md).

## Build and test

Requires a JDK 25 (Gradle toolchains pick it up); the wrapper downloads Gradle.

```sh
./gradlew build                      # compile, unit tests, scale test, javadoc, boot jar
./gradlew :dq-engine:test            # library unit tests only
./gradlew :dq-engine-json:test       # JSON adapter tests
./gradlew :dq-engine-json:scaleTest  # 1,000,000 streamed records under -Xmx64m
./gradlew :dq-http-example:test      # HTTP tests (MockMvc)
```

## Use the library

```java
DqEngine engine = DqEngine.builder()
        .catalog(RuleCatalog.of(rules))            // validated here: InvalidCatalogException
        .build();

RunSummary summary = engine.validate(
        JsonRecordSources.jsonArray(inputStream),  // dq-engine-json; streamed, closes the stream
        listener,                                  // your ValidationListener: onResult / onError / onBatchComplete
        RunOptions.builder()
                .batchSize(5_000)
                .filter(RuleFilter.category("tax").and(RuleFilter.countryScope("DE")))
                .build());
```

A rule is logic that computes a value plus a decision mapping held as data:

```java
Rule vatFormat = Rule.builder()
        .id("vatFormat").label("VAT id has a plausible length")
        .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
        .explain(e -> e.text("vatId")                      // optional: the reason, in plain words
                .map(v -> v.length() >= 9 ? "len " + v.length() : "len " + v.length() + " instead of 9")
                .orElse("no vatId"))
        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax")
        .build();
```

## Run the HTTP example

```sh
./gradlew :dq-http-example:bootRun          # listens on :8080
```

The example catalog is the three §5 rules plus `deVatPrefix`, a tax rule scoped to `DE`.

The §5 fixture (12 results). The response has one entry per record, with results keyed by rule id:
`records[1].results.vatFormat` is `{"decision": "INVALID", "severity": "ERROR", "provenance": "len 4 instead of 9"}`:

| record | countryBlocked | vatFormat | ibanFormat | deVatPrefix |
|---|---|---|---|---|
| r1 | NOT_APPLICABLE (country DE is not blocked) | VALID (len 11) | VALID (iban starts with DE) | VALID (starts with DE) |
| r2 | NOT_APPLICABLE (country FR is not blocked) | INVALID (len 4 instead of 9) | VALID (iban starts with FR) | NOT_APPLICABLE (applies to DE only; record country is FR) |
| r3 | INVALID (country ZZ is blocked) | INVALID (no vatId) | INVALID (empty iban) | NOT_APPLICABLE (applies to DE only; record country is ZZ) |

```sh
curl -s -X POST localhost:8080/validations -H 'Content-Type: application/json' \
  --data-binary @dq-engine-json/src/test/resources/dq/engine/json/fixture.json
```

The fault demo: a record without `country`/`vatId`, plus a non-object element. It returns 15 results and
2 errors (`ibanFormat` `MISSING_REQUIRED_FIELD` for r4 and `UNREADABLE_RECORD` at index 4). The rest of
the batch is unaffected.

```sh
curl -s -X POST localhost:8080/validations -H 'Content-Type: application/json' -d '[
  {"id":"r1","vatId":"DE111111111","country":"DE","legalName":"ACME GmbH","iban":"DE123456789"},
  {"id":"r2","vatId":"FR22","country":"FR","legalName":"Bricolage SARL","iban":"FR123456789"},
  {"id":"r3","country":"ZZ","legalName":"Nowhere Ltd","iban":""},
  {"id":"r4","iban":"DE1"},
  42]'
```

Country scope: each record's `country` decides whether a country-scoped rule applies to it. Here
`category=tax` selects `vatFormat` (WORLD) and `deVatPrefix` (DE). `deVatPrefix` runs only on DE records,
matching the country case-insensitively, so `c2` (`de`) is checked and fails. An FR record or one with no
`country` gets `NOT_APPLICABLE`, with the reason in its provenance, and the rule's logic is not called.

```sh
curl -s -X POST 'localhost:8080/validations?category=tax' -H 'Content-Type: application/json' -d '[
  {"id":"c1","vatId":"DE123456789","country":"DE"},
  {"id":"c2","vatId":"AT123456789","country":"de"},
  {"id":"c3","vatId":"FR123456789","country":"FR"},
  {"id":"c4","vatId":"DE123456789"}]'
```

| record | vatFormat | deVatPrefix |
|---|---|---|
| c1 | VALID (len 11) | VALID (starts with DE) |
| c2 | VALID (len 11) | INVALID, ERROR (starts with AT instead of DE) |
| c3 | VALID (len 11) | NOT_APPLICABLE (applies to DE only; record country is FR) |
| c4 | VALID (len 11) | NOT_APPLICABLE (applies to DE only; record has no country) |

The `country` query parameter is a different, run-level filter: `?category=tax&country=FR` drops
`deVatPrefix` from the run entirely (`rulesSelected` is 1), so no record gets a result for it.

Streaming (no record limit, one `{"type":"record",…}` line per record, then a `summary` line), with
rule selection:

```sh
curl -s -N -X POST 'localhost:8080/validations?category=tax&country=DE&batchSize=500' \
  -H 'Content-Type: application/json' -H 'Accept: application/x-ndjson' \
  --data-binary @dq-engine-json/src/test/resources/dq/engine/json/fixture.json
```

| Parameter | Meaning |
|---|---|
| `status`, `country`, `category` | Repeatable. Values of one parameter are ORed; different parameters are ANDed. RELEASED is always enforced. |
| `batchSize` | Records per batch (1 to `dq.http.max-batch-size`, default 1000; the cap defaults to 10,000). |

The buffered JSON response accepts at most `dq.http.max-records` (default 10,000) records and returns
`413` beyond that. Malformed JSON returns a `400` problem detail. On the streaming endpoint the status
is already sent, so a failure mid-stream ends with an `{"type":"aborted",…}` line.
