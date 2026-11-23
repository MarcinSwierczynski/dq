# Capability: dq-rule-evaluation — Rule evaluation

Source: docs/specs/2026-11-20-dq-rule-engine.md
Status: implemented

## Requirement: One Result per applicable rule and record
The engine SHALL emit exactly one `Result` or one `EvaluationError` for each (selected rule × readable record) pair. Each Result carries the decision, the rule's severity, and provenance: rule id and label, record id and index, the fields read with their values, the computed value, how the decision was reached (`MAPPED` / `DEFAULT` / `OUT_OF_SCOPE`), and an optional plain-words explanation from the rule's explainer (the engine's own for `OUT_OF_SCOPE`).

### Scenario: §5 fixture
- WHEN the §5 records r1–r3 are validated against `countryBlocked`, `vatFormat`, and `ibanFormat`
- THEN 9 Results match the §5 expected table, and `r2/vatFormat` has `fieldsRead = {vatId: "FR22"}`, `value = "bad"`, `INVALID`

## Requirement: Decision through a declarative mapping
The engine SHALL derive every decision by looking up the rule's computed value in its `DecisionMapping`, falling back to `default` when the value is not mapped.

### Scenario: Default fallback
- WHEN `countryBlocked` computes `"ok"` and the mapping has only `blocked→INVALID, default→NOT_APPLICABLE`
- THEN the Result is `NOT_APPLICABLE` with provenance `DEFAULT`

### Scenario: Unmapped value without default
- WHEN a rule computes a value that is not in its mapping and the mapping has no default
- THEN an `EvaluationError(UNMAPPED_VALUE)` names the rule, the record, and the value, and no Result is emitted

## Requirement: Country scope applicability
The engine SHALL emit `NOT_APPLICABLE` with provenance `OUT_OF_SCOPE`, without running the logic, when a rule's scope is a country other than the record's country.

### Scenario: Scoped rule on a foreign record
- WHEN a rule scoped to `DE` meets record r2 (`FR`)
- THEN the Result is `NOT_APPLICABLE / OUT_OF_SCOPE`, and the logic is not called

## Requirement: Fault isolation
The engine SHALL record an `EvaluationError` (rule id, record id and index, kind, message, cause type, fields read so far) when a rule throws, reads a missing required field, hits a type mismatch, or returns null. It SHALL record an `UNREADABLE_RECORD` error when a record is not a JSON object. In both cases the run continues.

### Scenario: Throwing rule
- WHEN a fourth rule `faulty` (computes `"ok"`, mapping `ok→VALID`, throws for r2) is added to the fixture catalog
- THEN the run completes with 11 Results (the 9 fixture Results plus `faulty` VALID for r1 and r3) and 1 `RULE_EXCEPTION` error naming `faulty` and `r2`

### Scenario: Non-object element
- WHEN the input array contains `42` between r1 and r2
- THEN 1 `UNREADABLE_RECORD` error at index 1 is recorded, r1–r3 still yield the 9 fixture Results, and `recordsRead = 4` (unreadable elements count as read)

## Requirement: Host failures abort the run
The engine SHALL stop the run and throw `ValidationRunException` (carrying the partial `RunSummary`) when the listener throws or the input stream is syntactically broken. Results already emitted stay emitted.

### Scenario: Listener throws
- WHEN the listener throws on the 2nd Result
- THEN `validate` throws `ValidationRunException` whose partial summary has `resultCount = 1`, because an outcome is counted only after its listener call returns normally

## Requirement: Run summary
`validate` SHALL return a `RunSummary` with records read, rules selected, result count, error count, a count per `Decision`, a count of findings (`INVALID` and `REVIEW` results) per `Severity` (every enum key present, zeros included), the duration, and a completion status.

### Scenario: Fixture summary
- WHEN the §5 fixture runs, with severities `countryBlocked=ERROR`, `vatFormat=ERROR`, `ibanFormat=WARNING`
- THEN results=9, errors=0, `VALID=3, INVALID=4, REVIEW=0, NOT_APPLICABLE=2`, `ERROR=3, WARNING=1, INFO=0`

## Requirement: Bounded-memory streaming
The engine SHALL process a streamed JSON input of any length with heap use bounded by `batchSize`, not by the input size, delivering each outcome to the listener as it is produced. After each batch it SHALL call `onBatchComplete`.

### Scenario: One million records
- WHEN 1,000,000 generated records, streamed as a JSON array through `RecordSources.jsonArray`, run against 3 rules with `-Xmx64m` and a counting listener
- THEN the run completes with results + errors = 3,000,000 and no `OutOfMemoryError`

### Scenario: Batch size
- WHEN 2,500 records run with `batchSize = 1000`
- THEN `onBatchComplete` is called 3 times, with sizes 1000, 1000, 500

## Requirement: Cancellation
The engine SHALL stop between records when the calling thread is interrupted, and return a summary with status `CANCELLED`.

### Scenario: Interrupt mid-run
- WHEN the thread is interrupted after the first batch
- THEN `validate` returns with status `CANCELLED`, and the summary counts only what was emitted
