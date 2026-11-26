# DESIGN — dq-engine

`dq-engine` is a framework-free Java library with no runtime dependencies. It runs a catalog of
data-quality rules over a stream of JSON-like records and pushes every outcome to a listener the host
supplies. `dq-engine-json` is a separate adapter module that reads records from JSON with Jackson. A
Spring Boot app, `dq-http-example`, shows how a host embeds both.

This file is meant to be read on its own: the API and why it has this shape (§1), fault isolation
(§2), bounded memory and parallelism (§3), the seams (§4), the example host (§5), assumptions (§6),
trade-offs (§7) and next steps (§8). Two documents add detail but are not needed to review the
design: [`docs/specs/2026-11-20-dq-rule-engine.md`](docs/specs/2026-11-20-dq-rule-engine.md) is the
original build plan (where the code differs from it, §7 says how), and [`DETAILS.md`](DETAILS.md)
designs the one planned feature not built yet, a per-rule circuit breaker.

```
RecordSource ──► DqEngine.validate ──► per record × selected rule:
                                         scope check ─► RuleLogic(FieldReader) ─► DecisionMapping.resolve
                                       ──► Result | EvaluationError ──► ValidationListener ──► RunSummary
```

## 1. Public API, and why it has this shape

Code is grouped by feature, and `module-info.java` exports the three feature packages:
`dq.engine.catalog` (rules, mappings, scopes, filters, and the `FieldReader` rule logic reads
through), `dq.engine.source` (record input) and `dq.engine.run` (the engine, listeners, results and
errors). Dependencies point one way: `run` uses `catalog` and `source`, which use nothing else in the
library, so a rule can be written against `catalog` alone. `ArchitectureTest` fails the build on a
package cycle. The loop and the field tracking sit package-private next to the API they implement
(`RunExecution` and `TrackingFieldReader` in `run`), so they are not part of the API.

The JSON adapter is its own module, `dq-engine-json` (Java module `dq.engine.json`), exporting
`JsonRecordSources`; its parser, `JsonArrayRecordSource`, is package-private. The core module
requires nothing but the JDK, so embedding it cannot bring a library-version conflict into the host.
A host that already has its own JSON stack can skip the adapter and implement `RecordSource` over it.
`ArchitectureTest` also fails the build if a core class depends on anything outside `java..` and
`dq.engine..`.

| Type | Role |
|---|---|
| `DqEngine` (+ `Builder`) | Entry point. It is immutable and thread-safe; `validate(source, listener[, options]) → RunSummary`. |
| `Rule`, `RuleLogic`, `DecisionMapping`, `CountryScope` | A rule definition. Logic returns a `String` value, and the mapping (data) turns that value into a `Decision`. |
| `FieldReader` | The only view of a record that logic gets. It records each field it reads, for provenance. |
| `Result`, `Provenance`, `EvaluationError`, `ErrorKind` | Outcomes. |
| `RunSummary`, `RunStatus`, `BatchProgress` | Totals and progress. |
| `RuleCatalog`, `RecordSource`/`SourceElement`, `ValidationListener`, `RuleFilter` | The four seams (see §4). |
| `RecordSources`, `CollectingListener`, `CountingListener` | Built-in implementations. |
| `InvalidCatalogException`, `ValidationRunException`, `RecordSourceException` | Failures, split by who owns them. |

Why this shape:

- **Push API (listener + summary) rather than `List<Result>`.** At 1M records × dozens of rules a run
  has tens of millions of outcomes, so a returned list would make the most obvious call the wrong one
  at scale. The listener gets each outcome as soon as it exists. Small callers use
  `CollectingListener`. A `Stream<Outcome>` pull API would need a side channel for the summary, and
  its close and exception semantics would be awkward, so it is left as a possible future adapter.
- **Rule code cannot decide the outcome itself (F2).** `RuleLogic.compute(FieldReader) → String` has
  no access to `Decision` or the mapping. The lookup, with its `default` fallback, happens in the
  engine (`RunExecution.evaluate`). The type signature is what keeps the mapping declarative.
  `Provenance.basis` records whether the decision came from `MAPPED`, `DEFAULT`, or `OUT_OF_SCOPE`.
- **Results explain themselves in plain words.** A rule may declare a `RuleExplainer`
  (`Rule.builder().explain(…)`), which turns the evidence into a short reason such as
  `len 4 instead of 9`, `no vatId`, or `empty iban` (`Provenance.explanation()`,
  `FixtureScenarioTest.explainsEveryCellInPlainWords`). It runs after the decision is fixed and sees
  only `RuleExplainer.Evidence`, the fields the logic read and the value it computed, so it can
  neither change the outcome nor cite data the decision did not use. For `OUT_OF_SCOPE` the engine
  writes the reason itself (`applies to DE only; record country is FR`), because the logic never ran.
- **Immutable records and sealed types.** `DecisionMapping.Resolution`, `CountryScope`, and
  `SourceElement` are sealed, so the engine uses exhaustive switches. `Rule`, `DecisionMapping`,
  `RunSummary`, and `Provenance` copy their collections defensively. The copies are shallow: a nested
  list or map read with `FieldReader.raw` is shared with the record, so logic and listeners must treat
  it as read-only.
- **Outcomes are plain values.** `Result`, `Provenance` and `EvaluationError` hold strings, numbers,
  enums and the fields read, so they compare, store and serialize without special cases. Optional
  parts (an error's rule id and cause type, a result's explanation) are nullable fields exposed as
  `Optional` accessors, not `Optional` components. The exception behind an engine-created error is
  kept on the side, as `EvaluationError.cause()`, for logging: it is not part of `equals`, `hashCode`
  or `toString`, and an error rebuilt from storage has none.
- **Configuration errors fail fast; data errors are isolated.** An invalid catalog throws
  `InvalidCatalogException` from `Rule.builder().build()`, `DecisionMapping.build()`,
  `RuleCatalog.of`, or `DqEngine.Builder.build()`. This happens before any record runs. Invalid means
  a blank id or label, a missing component, an empty mapping, or duplicate ids. Because the engine
  re-validates host-supplied catalogs, a DB-backed catalog cannot get around these checks
  (`RuleCatalogTest`).
- **No hidden state.** There are no statics with behavior, no service loaders, and no framework.
  Logging goes through `System.Logger`. Records cross the boundary as `Map<String, ?>`, so the core
  needs no JSON library; in `dq-engine-json`, Jackson is an `implementation` dependency and never
  appears in its API. Lombok is compile-time only (`requires
  static lombok`; `@Accessors(fluent = true)` keeps record-style accessors): it generates the accessors,
  `equals`/`hashCode`/`toString` and builders of `Provenance`, `EvaluationError`, `RunOptions` and
  `CountingListener`, and
  nothing from it ships at runtime. Javadoc is built from the delomboked sources.
- **Deterministic order.** Results come in input order, and within a record in catalog order
  (`FixtureScenarioTest.emitsInInputOrderThenCatalogOrder`). Callbacks run on the calling thread, so
  listeners do not need to be thread-safe. Both hold when records are evaluated in parallel (§3).

## 2. Fault isolation (F3)

Each (rule × record) evaluation is its own isolation boundary (`RunExecution.evaluate(Rule, …)`). It
always produces exactly one `Result` or one `EvaluationError`, and the run continues.

| What happens in a rule | Outcome |
|---|---|
| Throws any `Exception` | `RULE_EXCEPTION` with the message, the cause type, the `cause`, and the fields read so far |
| Its `RuleExplainer` throws | `RULE_EXCEPTION`, message `rule explanation threw: …`. The explainer is rule code too, so a broken one fails its cell rather than shipping a result without its reason (`FaultIsolationTest.aThrowingExplainerFailsOnlyItsOwnCellAsARuleException`). |
| `StackOverflowError`, `LinkageError` | `RULE_EXCEPTION`. Recovery is safe here because the stack has unwound or the failure is local to the rule. |
| Other `Error`s (`OutOfMemoryError`, …) | Propagate. The JVM is not in a state where isolating them makes sense. |
| `fields.require(x)` on an absent or null field | `MISSING_REQUIRED_FIELD` |
| `fields.text(x)` on a non-string | `TYPE_MISMATCH` |
| Returns `null` | `NULL_VALUE` |
| A value that is neither mapped nor covered by a default | `UNMAPPED_VALUE`, naming the value. The engine never picks a decision itself. |
| The input element is not an object | `UNREADABLE_RECORD` (no rule), reported once for that element |
| Runs longer than `RunOptions.ruleTimeout` (opt-in) | `TIMEOUT`, with the fields read until then. The rule is interrupted and abandoned, and the run moves on. |

Every error says where the problem is: the rule id, the record id **and** its 0-based index (ids may
be missing or duplicated), the kind, the message, the cause type, and the fields read before the
failure. Proof: `FaultIsolationTest`. The `faulty` rule throws on r2, and the run still completes with
11 results and 1 error. The same class covers null values, unmapped values, a missing required field,
a type mismatch, `StackOverflowError`, checked exceptions, and `LinkageError`. In
`JsonArraySourceTest`, a `42` element yields 1 error and the 9 fixture results. The HTTP fault demo
does the same end to end (`ValidationControllerTest.faultDemoReportsErrorsAndStillValidatesTheRest`).

**What may stop a run.** Only failures the host owns: the listener throws, or the source throws (a
broken stream or malformed JSON). The engine then throws `ValidationRunException`, which carries a
partial `RunSummary` with status `ABORTED`. Outcomes already delivered stay delivered, and an outcome
is counted only after its listener call returns. If the listener failed silently and we continued,
the promise that the caller receives every outcome would be broken. Records buffered before a source
failure are still evaluated. A failing `RecordSource.close()` is logged and does not discard the
summary. An exception from a host `RuleFilter` is a programming error: it is thrown from `validate`
unchanged, before any record is read, and the source is still closed
(`RuleFilterTest.aThrowingFilterPropagatesUnchangedBeforeAnyRecordAndTheSourceIsClosed`). An I/O failure on the input is
reported as `could not read input`, separately from malformed JSON
(`JsonArraySourceTest.anIoFailureIsReportedAsUnreadableInputNotAsMalformedJson`).

**Per-rule timeouts.** `RunOptions.builder().ruleTimeout(Duration)` bounds one (rule, record)
evaluation, logic and explainer together. Without it (the default) rule code runs inline on the
calling thread, as before. With it, each evaluation runs on a fresh virtual thread and the calling
thread waits at most the timeout (`Evaluator.timed`). An overrun becomes a `TIMEOUT` error for that
cell, and the worker is interrupted and abandoned: the run never waits for it. The worker's
`TrackingFieldReader` is then read from the calling thread, so in this mode it takes a lock, and the
error can still list what the rule read before it hung. Everything else is unchanged: listener
callbacks stay on the calling thread, `Error`s still propagate, and every other outcome equals the
inline one (`RuleTimeoutTest.withATimeoutEveryOtherOutcomeIsTheSameAsWithout`). The costs are a
virtual thread per evaluation, rule code that cannot rely on thread-locals of the calling thread, and
a rule that ignores interrupts keeping its thread (and, if CPU-bound, a carrier thread) until it
returns (`RuleTimeoutTest.aRuleThatIgnoresInterruptsIsAbandonedAndTheRunDoesNotWaitForIt`). The JVM
cannot stop a thread safely, so that is the limit of any in-process timeout. Interrupting the
calling thread while it waits still cancels the run (`CANCELLED`); a partly evaluated record then
delivers nothing. The HTTP example sets `dq.http.rule-timeout=1s`, so one hung rule cannot hold a
request.

**Missing versus failing.** r3 has no `vatId` and is expected to be `INVALID`, not an error. So an
*absent* field is normal input, handled through `Optional` (`fields.text`). A rule makes absence an
error only when it calls `fields.require`. The fixture's `ibanFormat` does this for `country`, which
is why the fault demo's r4 yields `MISSING_REQUIRED_FIELD`.

## 3. Bounded memory (F5)

- `JsonRecordSources.jsonArray` (in `dq-engine-json`) streams the input through Jackson's `JsonParser`. It holds **one** element
  at a time (`JsonArrayRecordSource`), and parsing begins inside `validate`, not when the source is
  created.
- The engine pulls at most `batchSize` elements into a buffer and evaluates them. It pushes each
  outcome straight to the listener, calls `onBatchComplete(BatchProgress)`, and drops the buffer
  (`RunExecution.loop`). Live heap is O(`batchSize` records + one evaluation's outcome), whatever the
  input size. Counters are plain `long`s.
- Results refer to rules and records **by id**, never by object, so a listener that keeps results does
  not keep records alive.
- The bound assumes records of bounded size: one element is materialised whole. Jackson's default
  `StreamReadConstraints` limit string length and nesting depth, but by default not document length
  or token count, so a host that accepts untrusted input should also cap the request or message size.
- **Proof** (`ScaleTest` in `dq-engine-json`, run by its `scaleTest` task with `maxHeapSize = "64m"`;
  `check` depends on it): 1,000,000 generated records are streamed through the real `jsonArray` path
  against the 3 fixture rules, sequentially and on a thread pool. Each run produces 3,000,000
  outcomes, and on a laptop it takes about 0.8 s (~1.3M records/s).
- **Measured, not just survived.** Finishing under 64 MB shows the run does not accumulate hundreds of
  MB, but not how much it keeps. So the test subscribes to the JVM's GC notifications and records the
  heap left after every collection during the run, which bounds the live set from above (raw used
  heap is no measure: it climbs to the cap before each collection whatever is live). It asserts that
  this peak stays within 16 MB of the heap before the run. Measured over ~130 collections: peak
  **8.6 MB after GC against a 7.1 MB baseline**, so the run keeps about 1.5 MB for a million records,
  in both modes. A control in the same class (`theMeasurementDetectsALeakingListener`) keeps every
  outcome of just 20,000 records in a `CollectingListener` and grows by about 25 MB, so the bound
  does catch accumulation; with all 1,000,000 records that listener fails with `OutOfMemoryError`.
- The batch is the host's commit point (flush a DB batch, commit an offset). `BatchingTest` checks
  the 1000/1000/500 split.
- Cancellation: the engine checks the thread's interrupt flag between records, returns `CANCELLED`
  with the counts so far, and leaves the flag set (`BatchingTest.interruptStopsTheRunBetweenRecordsAsCancelled`).
  Because the check also runs inside a batch, a cancelled (or aborted) run can deliver outcomes after
  its last `onBatchComplete`; that partial batch gets no callback, so a host that commits per batch
  also flushes when `validate` returns or throws
  (`BatchingTest.aRunCancelledInsideABatchGetsNoCallbackForThatPartialBatch`).

**Parallel evaluation.** `RunOptions.builder().executor(executor).maxInFlight(n)` evaluates records
on the host's executor. Only evaluation moves: the source, the listener and the counters stay on the
calling thread, so the output is exactly that of a sequential run (`RunExecution.evaluateInWindow`).

- **One task per record, delivered in order.** The calling thread submits each record of the batch as
  a `FutureTask` into a window of at most `maxInFlight` (default 64). When the window is full, it waits
  for the *oldest* task and delivers its outcomes. Tasks finish in any order, but outcomes leave in
  input order, so the window is a reorder buffer and the backpressure on the source in one. It is
  drained before `onBatchComplete`, so a batch is still a commit point. `Evaluator` holds no per-run
  state and each evaluation has its own `TrackingFieldReader`, so it is safe on any thread; rule code
  is already required to be thread-safe.
- **The engine never owns threads.** Without an executor (the default), nothing changes. Hosts pass
  virtual threads for blocking rules or a fixed pool for CPU-bound ones; the engine never shuts the
  executor down and avoids the common `ForkJoinPool`. Memory becomes `batchSize` records plus
  `maxInFlight` records' outcomes, which is still independent of the input size.
- **Failures.** A listener failure, a source failure, or an interrupt of the calling thread cancels
  (interrupts) the tasks still in the window; the run then aborts or is cancelled as before, and the
  delivered output is an in-order prefix. An `Error` from a task is rethrown unwrapped. A task the
  executor rejects aborts the run with the `RejectedExecutionException` as cause. A rule that
  interrupts itself only interrupts its worker, so it does not cancel the run, and the task clears the
  flag so it does not leak into the host's pool thread.
- **Timeouts compose.** With `ruleTimeout` set, a record task waits for each rule's virtual thread as
  the calling thread does in a sequential run.
- **Proof** (`ParallelEvaluationTest`). 500 records through rules that sleep for random times and
  rules that fail give *equal* outcomes, batches and summaries to a sequential run, and every callback
  runs on the calling thread. A rule that waits on a 4-party barrier completes only because four
  records run at once; `maxInFlight(3)` keeps at most three running. Aborts, cancellation, rejection,
  `Error`s and self-interrupting rules each have a test. `ScaleTest` also streams the 1,000,000
  records through a fixed pool under the 64 MB cap.
- **When it helps.** The fixture's rules take well under a microsecond, so for them JSON parsing on
  the calling thread is the bottleneck, and a parallel run is slightly *slower* (≈0.9 s instead of
  ≈0.75 s for 1M records), the cost of a task per record. Parallelism pays off when rules are
  expensive or block (a lookup, a remote check), which is when a host would turn it on.

## 4. Seams for host-supplied inputs

| Seam | Built in | A host would plug in |
|---|---|---|
| `RuleCatalog` (`List<Rule> rules()`) | `RuleCatalog.of(…)`; `JsonRuleCatalog.read(json, LogicRegistry)` in `dq-engine-json` | DB-backed or versioned catalog service. It is read once at `build()`, so a run never sees a catalog change mid-way. Rebuild the engine (cheap) to pick up a new version. |
| `RecordSource` (`SourceElement next()`, `close()`) | `RecordSources.of(List<Map>)`; `JsonRecordSources.jsonArray(InputStream)` in `dq-engine-json` | Kafka consumer, JDBC cursor, S3 object. A non-record is reported with `SourceElement.unreadable(reason)`, and the run continues. Throwing means the stream is broken, and the run aborts. |
| `ValidationListener` | `CollectingListener`, `CountingListener` | DB batch writer, NDJSON writer (`dq-http-example/NdjsonListener`), metrics bridge |
| `RuleFilter` | `all`, `status`, `countryScope`, `category`, `and`/`or`/`not` | Tenant- or job-specific selection (any lambda) |
| Field names | `recordIdField` (default `id`), `countryField` (default `country`) | Other record schemas |

**Country scope, twice (F6).** (1) Every rule is checked against each record's own country. A rule
scoped to `DE` on a `FR` record yields `NOT_APPLICABLE` with basis `OUT_OF_SCOPE`, and the logic is
not called (`ScopeAndSelectionTest`). Its provenance shows the country value it compared. (2) At run
level, `RuleFilter.countryScope("DE")` keeps WORLD and DE rules, for example for a per-country
worker. Whatever the filter selects, the engine **always** intersects it with `status == RELEASED`
(`RuleFilterTest`). A filter that selects nothing is a normal, completed run with `rulesSelected = 0`.

**Rules as data.** `JsonRuleCatalog` (in `dq-engine-json`) reads rule definitions from a JSON
document: id, label, mapping, status, severity, scope and categories are data, and each rule names
its logic and explainer. The names resolve in a `LogicRegistry` (in the core, so any stored catalog
can use it), which maps a name to `RuleLogic` / `RuleExplainer` code. The document is read and
checked once: unknown properties, duplicate keys, unknown names and bad enum values are
`InvalidCatalogException`s naming the rule, and the result goes through the same `RuleCatalog.of`
checks as a code-built catalog. `JsonRuleCatalogTest` loads the §5 rules from JSON and gets results
equal to the code-built catalog's, provenance and explanations included. The HTTP example loads its
four rules from `rules.json` this way (`ExampleCatalog`), so the catalog seam runs end to end.

## 5. Example host (`dq-http-example`)

`POST /validations` (`ValidationController`):

- **Grouped by record.** Both responses have one entry per input record, in input order:
  `{recordId, results, errors}`. `results` is keyed by rule id in catalog order, so the
  §5 fixture reads as its table (one row per record, one column per rule). Each entry holds the
  decision, `provenance` (the rule's explanation, such as `len 4 instead of 9`, or its label when it
  gives none; the engine's fields read, computed value and basis are not sent) and, for `INVALID` and `REVIEW` only, the rule's severity: a passing or not-applicable check
  is not a finding. The summary's `bySeverity` counts the same findings, by rule severity
  (`RunSummary`): for the fixture `ERROR=3, WARNING=1`. Neither the reply's summary nor
  `RunSummary` has a result count: it is the sum of `byDecision`. `errors` holds that record's rule errors, or the one
  `UNREADABLE_RECORD` error of a non-object element. The engine stays per-outcome; the host's
  `RecordGrouping` listener builds the groups. Outcomes arrive in input order and a record never
  spans batches, so it holds only the current record. Records are grouped by the engine's record
  index, since ids may repeat, but the index is not sent: entries are in input order, and an
  unreadable element's id (`#4`) already names its position. A run that selects no rules has no outcomes, and so no record entries.
- **JSON (`Accept: application/json`):** returns `{summary, records}`. Results are buffered,
  so the request is capped at `dq.http.max-records` with a `RecordLimit` source decorator, giving
  `413` and a hint to use NDJSON. The cap is enforced while streaming the input, so the body is never
  fully buffered first. Malformed JSON gives a `400` problem detail.
- **NDJSON (`Accept: application/x-ndjson`):** a `StreamingResponseBody`. It writes one
  `{"type":"record",…}` line per record, flushes after each batch, and ends with a `summary` line.
  On an abort, the record in progress is still written before the `aborted` line. If the run aborts after the 200
  is on the wire, the last line is `{"type":"aborted",…}`. A real-server check streamed 50,000 records
  (150,000 outcomes) in about 0.7 s.
- Query parameters `status`, `country`, `category`, and `batchSize` map onto `RuleFilter` and
  `RunOptions`. The engine buffers one whole batch, so `batchSize` is capped at
  `dq.http.max-batch-size` (default 10,000; `400` above it). Without the cap a client could request one
  batch the size of the whole body and defeat the streaming endpoint's memory bound
  (`ValidationControllerTest.aBatchSizeAboveTheCapIs400OnBothEndpoints`).
- `ErrorView` drops the `Throwable` so stack traces never reach clients. Rule exception messages are
  passed through as they are; a production host would map `RULE_EXCEPTION` to a generic message.
- It is an example: it has no authentication, and its only input limits are a record-count cap on the
  buffered endpoint and the `batchSize` cap. Input I/O error messages also reach clients in the `aborted` line.

**Sensitive data.** VAT ids and IBANs appear in `fieldsRead`. The library never logs field values:
`System.Logger` gets only ids, the error kind, and the cause type. The host decides whether
provenance is stored or returned.

## 6. Assumptions

- **A1:** an absent field is input, not an error. Errors are defined in §2.
- **A2:** no scope means WORLD. A record without a (string) `country` is out of scope for every rule
  scoped to a country.
- **A3:** the record id comes from `recordIdField`. If that field is missing or blank, the id is
  `#<index>`. A numeric id is used as its string value. Duplicate ids are allowed, and
  `recordIndex` (in the library's `Result` and `EvaluationError`) tells them apart.
- **A4:** a value with no case and no default is an `UNMAPPED_VALUE` error. The engine never picks a
  decision itself.
- **A5:** catalog problems are configuration errors and fail at build time.
- **A6:** RELEASED is always enforced on top of the caller's filter.
- **A7:** country codes are two-letter ISO codes, compared case-insensitively.
- JSON `null` counts as absent for `text`, `require`, and `has`, but provenance records it as
  `null`, so it can be told apart from `<absent>`.

## 7. Trade-offs

- **Parallel evaluation is opt-in, per record.** Sequential evaluation stays the default: it needs no
  threads, and for cheap rules it is faster. The parallel unit is a record, not a (rule, record)
  cell, which keeps one task's outcomes together for in-order delivery; a record with one slow rule
  holds its slot in the window.
- **Timeouts are opt-in.** Without `ruleTimeout` a rule that loops forever stalls the run; with it,
  every evaluation pays for a virtual thread, and an abandoned rule that ignores interrupts keeps
  running in the background.
- **Logic is Java, definitions are data.** An expression language (CEL/JEXL) would make logic data
  too, but it brings a sandbox, a type system, and error reporting that did not fit. `RuleLogic` is
  the seam a `CelRuleLogic` would plug into.
- **String values.** Mapping keys are simple and serializable. Numeric or enum values have to be
  converted to strings in the logic.
- **One `NOT_APPLICABLE` result per (record × foreign-country rule).** This is explicit, but it
  inflates output for heavily country-scoped catalogs. The mitigation today is `RuleFilter.countryScope`.
- **JSON syntax errors abort the run.** An array parser cannot resynchronize after one. NDJSON input
  could skip a bad line instead.
- **Jackson in the JSON adapter.** The core has no dependencies; `dq-engine-json` uses Jackson 3
  internally. A host on a conflicting Jackson 3.x could still clash at runtime if it uses the adapter.
  It can then shade the adapter, or implement `RecordSource` over its own parser.
- **Deviations from the spec** (the original plan in `docs/specs`; the code and this file win):
  - There is no public `DataRecord`; the engine reads the id and country from the field map, and the
    field-access exception is internal (a smaller API). `RecordSource` is `SourceElement next()`,
    where the sealed `SourceElement` is `Fields | Unreadable`, so a host source can report a
    non-record without throwing.
  - Packages are by feature (`catalog`, `source`, `run`) rather than `api`/`internal`, with
    package-private implementation classes, and without a package cycle (§1).
  - Jackson is not a dependency of `dq-engine`: JSON input and the JSON catalog live in
    `dq-engine-json` (§1).
  - The spec's Q4 chose sequential evaluation and listed per-rule timeouts as not handled. Both are
    now built, opt-in, with sequential inline evaluation still the default (§2, §3).
  - Step 16, the optional `JsonRuleCatalog` + `LogicRegistry`, is built (§4).
  - `EvaluationError` is a final class rather than a record (§1).

## 8. What I'd do next

1. The per-rule circuit breaker ([DETAILS](DETAILS.md#adding-a-per-rule-circuit-breaker)), so a
   rule that keeps failing or timing out stops costing a thread and a timeout per record.
2. Parse the input in parallel too (for example NDJSON split by line ranges), since the JSON parser on
   the calling thread is the bottleneck for cheap rules.
3. CEL-backed logic registered in the `LogicRegistry`, so new rule code can be authored without a
   deployment (today only definitions can).
4. NDJSON input with line-level recovery from malformed records.
5. Rule version or hash in `Provenance` so stored results stay explainable after the catalog changes.
6. A run option to suppress `OUT_OF_SCOPE` results, and a multi-country `CountryScope` variant.
7. Metrics hooks (per-rule latency and error rate) through a listener decorator, and an
   `onRunComplete` callback.
8. Publish to Maven with `-sources`/`-javadoc` jars (already built) and add API-compatibility checks
   (e.g. japicmp) to CI.
