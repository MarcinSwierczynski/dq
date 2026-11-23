# dq — data-quality rule engine

An embeddable Java 25 library (`dq-engine`) that validates business-partner records against a catalog
of rules, plus a Spring Boot example host (`dq-http-example`). Design and trade-offs: [`DESIGN.md`](DESIGN.md).
Spec: [`docs/specs/2026-11-20-dq-rule-engine.md`](docs/specs/2026-11-20-dq-rule-engine.md).

## Build and test

Requires a JDK 25 (Gradle toolchains pick it up); the wrapper downloads Gradle.

```sh
./gradlew build                      # compile, unit tests, javadoc, boot jar
./gradlew :dq-engine:test            # library unit tests only
./gradlew :dq-http-example:test      # HTTP tests (MockMvc)
```

## Use the library

```java
DqEngine engine = DqEngine.builder()
        .catalog(RuleCatalog.of(rules))            // validated here: InvalidCatalogException
        .build();

RunSummary summary = engine.validate(
        RecordSources.jsonArray(inputStream),      // the source closes the stream
        listener);                                 // your ValidationListener: onResult / onError
```

A rule is logic that computes a value plus a decision mapping held as data:

```java
Rule vatFormat = Rule.builder()
        .id("vatFormat").label("VAT id has a plausible length")
        .logic(f -> f.text("vatId").filter(v -> v.length() >= 9).map(v -> "ok").orElse("bad"))
        .mapping(DecisionMapping.builder().when("ok", VALID).when("bad", INVALID).build())
        .status(RuleStatus.RELEASED).severity(Severity.ERROR).category("tax")
        .build();
```

## Run the HTTP example

```sh
./gradlew :dq-http-example:bootRun          # listens on :8080
```

The §5 fixture (9 results):

```sh
curl -s -X POST localhost:8080/validations -H 'Content-Type: application/json' \
  --data-binary @dq-engine/src/test/resources/dq/engine/fixture.json
```

The fault demo: a record without `country`/`vatId`, plus a non-object element. It returns 11 results and
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

The buffered JSON response accepts at most `dq.http.max-records` (default 10,000) records and returns
`413` beyond that. Malformed JSON returns a `400` problem detail.
