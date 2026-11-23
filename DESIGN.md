# DESIGN — dq-engine

`dq-engine` is a framework-free Java library. It runs a catalog of data-quality rules over a stream
of JSON-like records and pushes every outcome to a listener the host supplies. A Spring Boot app,
`dq-http-example`, shows how a host embeds it. The full build plan and its decisions are in
[`docs/specs/2026-11-20-dq-rule-engine.md`](docs/specs/2026-11-20-dq-rule-engine.md).

```
RecordSource ──► DqEngine.validate ──► per record × selected rule:
                                         scope check ─► RuleLogic(FieldReader) ─► DecisionMapping.resolve
                                       ──► Result | EvaluationError ──► ValidationListener ──► RunSummary
```

## 1. Public API, and why it has this shape

The exported package is `dq.engine.api`, and `module-info.java` exports only that package.
`dq.engine.internal` holds the loop, the JSON parsing, and the field tracking.

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
- **Immutable records and sealed types.** `DecisionMapping.Resolution`, `CountryScope`, and
  `SourceElement` are sealed, so the engine uses exhaustive switches. `Rule`, `DecisionMapping`,
  `RunSummary`, and `Provenance` copy their collections defensively. The copies are shallow: a nested
  list or map read with `FieldReader.raw` is shared with the record, so logic and listeners must treat
  it as read-only.
- **Configuration errors fail fast; data errors are isolated.** An invalid catalog throws
  `InvalidCatalogException` from `Rule.builder().build()`, `DecisionMapping.build()`,
  `RuleCatalog.of`, or `DqEngine.Builder.build()`. This happens before any record runs. Invalid means
  a blank id or label, a missing component, an empty mapping, or duplicate ids. Because the engine
  re-validates host-supplied catalogs, a DB-backed catalog cannot get around these checks
  (`RuleCatalogTest`).
- **No hidden state.** There are no statics with behavior, no service loaders, and no framework.
  Logging goes through `System.Logger`. Jackson is an `implementation` dependency and never appears in
  the API: records cross the boundary as `Map<String, ?>`. Lombok is compile-time only (`requires
  static lombok`; `@Accessors(fluent = true)` keeps record-style accessors): it generates the accessors,
  `equals`/`hashCode`/`toString` and builders of `Provenance`, `RunOptions` and `CountingListener`, and
  nothing from it ships at runtime. Javadoc is built from the delomboked sources.
- **Deterministic order.** Results come in input order, and within a record in catalog order
  (`FixtureScenarioTest.emitsInInputOrderThenCatalogOrder`). Callbacks run on the calling thread, so
  listeners do not need to be thread-safe.

### Semver policy

The API is `dq.engine.api` only. Breaking changes need a major version. These count as additive, so a
minor version may:

- add a constant to `ErrorKind` (documented as open: switch over it with a `default` branch);
- add a variant to `CountryScope`;
- add overloads, factories, or default methods.

Record components are never added to existing records: that changes the canonical constructor and
breaks record patterns (`case Provenance(var id, …)`). So the types expected to grow are final
classes with accessors: `RunOptions` (built through its builder; new settings get a builder method
and a default) and `Provenance` (a rule version can become a new accessor). `RunSummary` stays a
record and shipped in its full shape from the start. `dq.engine.internal` may change in any release,
and the module system enforces that boundary.

## 2. Fault isolation (F3)

Each (rule × record) evaluation is its own isolation boundary (`RunExecution.evaluate(Rule, …)`). It
always produces exactly one `Result` or one `EvaluationError`, and the run continues.

| What happens in a rule | Outcome |
|---|---|
| Throws any `Exception` | `RULE_EXCEPTION` with the message, the cause type, the `cause`, and the fields read so far |
| `StackOverflowError`, `LinkageError` | `RULE_EXCEPTION`. Recovery is safe here because the stack has unwound or the failure is local to the rule. |
| Other `Error`s (`OutOfMemoryError`, …) | Propagate. The JVM is not in a state where isolating them makes sense. |
| `fields.require(x)` on an absent or null field | `MISSING_REQUIRED_FIELD` |
| `fields.text(x)` on a non-string | `TYPE_MISMATCH` |
| Returns `null` | `NULL_VALUE` |
| A value that is neither mapped nor covered by a default | `UNMAPPED_VALUE`, naming the value. The engine never picks a decision itself. |
| The input element is not an object | `UNREADABLE_RECORD` (no rule), reported once for that element |

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

**Missing versus failing.** r3 has no `vatId` and is expected to be `INVALID`, not an error. So an
*absent* field is normal input, handled through `Optional` (`fields.text`). A rule makes absence an
error only when it calls `fields.require`. The fixture's `ibanFormat` does this for `country`, which
is why the fault demo's r4 yields `MISSING_REQUIRED_FIELD`.

## 3. Bounded memory (F5)

- `RecordSources.jsonArray` streams the input through Jackson's `JsonParser`. It holds **one** element
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
- **Proof** (`ScaleTest`, run by `scaleTest` with `maxHeapSize = "64m"`; `check` depends on it): 1,000,000
  generated records are streamed through the real `jsonArray` path against the 3 fixture rules. The
  run produces 3,000,000 outcomes, and on a laptop it takes about 0.7 s (~1.4M records/s). As a control,
  the same test with a `CollectingListener` fails under that cap with `OutOfMemoryError: Java heap
  space`, so the cap really separates streaming from accumulation.
- The batch is the host's commit point (flush a DB batch, commit an offset). It is also the unit a
  future parallel executor would split on. `BatchingTest` checks the 1000/1000/500 split.
- Cancellation: the engine checks the thread's interrupt flag between records, returns `CANCELLED`
  with the counts so far, and leaves the flag set (`BatchingTest.interruptStopsTheRunBetweenRecordsAsCancelled`).
  Because the check also runs inside a batch, a cancelled (or aborted) run can deliver outcomes after
  its last `onBatchComplete`; that partial batch gets no callback, so a host that commits per batch
  also flushes when `validate` returns or throws
  (`BatchingTest.aRunCancelledInsideABatchGetsNoCallbackForThatPartialBatch`).

## 4. Seams for host-supplied inputs

| Seam | Built in | A host would plug in |
|---|---|---|
| `RuleCatalog` (`List<Rule> rules()`) | `RuleCatalog.of(…)` | DB-backed or versioned catalog service. It is read once at `build()`, so a run never sees a catalog change mid-way. Rebuild the engine (cheap) to pick up a new version. |
| `RecordSource` (`SourceElement next()`, `close()`) | `RecordSources.of(List<Map>)`, `RecordSources.jsonArray(InputStream)` | Kafka consumer, JDBC cursor, S3 object. A non-record is reported with `SourceElement.unreadable(reason)`, and the run continues. Throwing means the stream is broken, and the run aborts. |
| `ValidationListener` | `CollectingListener`, `CountingListener` | DB batch writer, NDJSON writer (`dq-http-example/NdjsonListener`), metrics bridge |
| `RuleFilter` | `all`, `status`, `countryScope`, `category`, `and`/`or`/`not` | Tenant- or job-specific selection (any lambda) |
| Field names | `recordIdField` (default `id`), `countryField` (default `country`) | Other record schemas |

**Country scope, twice (F6).** (1) Every rule is checked against each record's own country. A rule
scoped to `DE` on a `FR` record yields `NOT_APPLICABLE` with basis `OUT_OF_SCOPE`, and the logic is
not called (`ScopeAndSelectionTest`). Its provenance shows the country value it compared. (2) At run
level, `RuleFilter.countryScope("DE")` keeps WORLD and DE rules, for example for a per-country
worker. Whatever the filter selects, the engine **always** intersects it with `status == RELEASED`
(`RuleFilterTest`). A filter that selects nothing is a normal, completed run with `rulesSelected = 0`.

## 5. Example host (`dq-http-example`)

`POST /validations` (`ValidationController`):

- **JSON (`Accept: application/json`):** returns `{summary, results, errors}`. Results are buffered,
  so the request is capped at `dq.http.max-records` with a `RecordLimit` source decorator, giving
  `413` and a hint to use NDJSON. The cap is enforced while streaming the input, so the body is never
  fully buffered first. Malformed JSON gives a `400` problem detail.
- **NDJSON (`Accept: application/x-ndjson`):** a `StreamingResponseBody`. It writes one line per
  outcome, flushes after each batch, and ends with a `summary` line. If the run aborts after the 200
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
  `recordIndex` tells them apart.
- **A4:** a value with no case and no default is an `UNMAPPED_VALUE` error. The engine never picks a
  decision itself.
- **A5:** catalog problems are configuration errors and fail at build time.
- **A6:** RELEASED is always enforced on top of the caller's filter.
- **A7:** country codes are two-letter ISO codes, compared case-insensitively.
- JSON `null` counts as absent for `text`, `require`, and `has`, but provenance records it as
  `null`, so it can be told apart from `<absent>`.

## 7. Trade-offs under the time-box

- **Sequential evaluation.** It keeps callbacks single-threaded and output deterministic. The batch
  loop is the place to parallelize later.
- **No per-rule timeout.** A rule that loops forever stalls the run. Timeouts need a worker thread per
  evaluation (see next steps).
- **Logic is Java, definitions are data.** An expression language (CEL/JEXL) would make logic data
  too, but it brings a sandbox, a type system, and error reporting that did not fit. `RuleLogic` is
  the seam a `CelRuleLogic` would plug into.
- **String values.** Mapping keys are simple and serializable. Numeric or enum values have to be
  converted to strings in the logic.
- **One `NOT_APPLICABLE` result per (record × foreign-country rule).** This is explicit, but it
  inflates output for heavily country-scoped catalogs. The mitigation today is `RuleFilter.countryScope`.
- **JSON syntax errors abort the run.** An array parser cannot resynchronize after one. NDJSON input
  could skip a bad line instead.
- **Jackson inside the library.** It stays internal, but a host on a conflicting Jackson 3.x could
  still clash at runtime. Shading is the fix if that happens.
- **Not built (it was optional):** `JsonRuleCatalog` + `LogicRegistry`, which would load rule
  definitions from JSON and resolve logic by name.
- **Deviations from the spec:** there is no public `DataRecord`; the engine reads the id and country
  from the field map, and the field-access exception is internal (a smaller API). `RecordSource` is
  `SourceElement next()`, where the sealed `SourceElement` is `Fields | Unreadable`, so a host source
  can report a non-record without throwing.

## 8. What I'd do next

1. Parallel batches on virtual threads: evaluate a batch concurrently and deliver outcomes in order
   through a reorder buffer, so the listener contract does not change.
2. Per-rule timeouts and a circuit breaker that disables a rule after N consecutive failures in a run.
3. `JsonRuleCatalog` + `LogicRegistry`, then CEL-backed logic so rules can be authored without a
   deployment.
4. NDJSON input with line-level recovery from malformed records.
5. Rule version or hash in `Provenance` so stored results stay explainable after the catalog changes.
6. A run option to suppress `OUT_OF_SCOPE` results, and a multi-country `CountryScope` variant.
7. Metrics hooks (per-rule latency and error rate) through a listener decorator, and an
   `onRunComplete` callback.
8. Publish to Maven with `-sources`/`-javadoc` jars (already built) and add API-compatibility checks
   (e.g. japicmp) to CI.
