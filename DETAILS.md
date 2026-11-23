# DETAILS — dq-engine

Trade-offs and designs for work not built yet. The overview is in [`DESIGN.md`](DESIGN.md).

## 1. Trade-offs under the time-box

- **Sequential evaluation.** It keeps callbacks single-threaded and output deterministic. The batch
  loop is the place to parallelize later (§2).
- **No per-rule timeout.** A rule that loops forever stalls the run. Timeouts need a worker thread per
  evaluation (see [next steps](DESIGN.md#7-what-id-do-next)).
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

## 2. Adding parallel evaluation

Parallelism can be added inside `RunExecution.loop` without changing the public contract. Only
evaluation goes parallel. The source, the listener and the counters stay on the calling thread, so
callbacks stay single-threaded and the output matches a sequential run exactly. Rule code is already
required to be thread-safe (`RuleLogic`, `RuleExplainer`), and each evaluation has its own
`TrackingFieldReader`.

1. **Split computing from emitting.** `evaluate` returns a record's outcomes instead of calling
   `emit`. This is a pure refactor, the existing tests cover it, and the isolation `try`/`catch` moves
   with the computation, so F3 holds on any thread.
2. **One task per record, delivered in order.** Submit records into a bounded window
   (`ArrayDeque<Future<List<Outcome>>>`). When the window is full, `join()` the oldest future and
   deliver its outcomes. This is the reorder buffer: tasks finish in any order, but outcomes are
   delivered in input order. Drain the window before `onBatchComplete`, so a batch is still a commit
   point.
3. **Options (additive).** `RunOptions.executor(Executor)` defaults to running each task inline,
   which is today's behavior. Hosts pass virtual threads for blocking rules or a fixed pool for
   CPU-bound ones. The engine never owns threads, and it avoids the common `ForkJoinPool`.
   `maxInFlight(int)` sets the window size. Memory becomes `batchSize` records plus `maxInFlight`
   records' outcomes, which is still independent of the input size.
4. **Failures.** On a listener failure, a source failure or an interrupt of the calling thread, cancel
   the window, then abort or cancel as today. The delivered output stays an in-order prefix. An `Error`
   comes out of `join()` wrapped in a `CompletionException` and is rethrown unwrapped. A rule's
   `InterruptedException` interrupts only its worker thread, so it must not cancel the run, and the
   task wrapper clears the flag.
5. **Tests.** Run the existing suites in both modes. Add rules that sleep for random times and assert
   that the parallel output equals the sequential output. Assert that every callback runs on the
   calling thread. Add a parallel `ScaleTest` under the 64 MB cap.

Per-rule timeouts then come almost for free: `future.get(timeout)` emits a new `ErrorKind.TIMEOUT`
(`ErrorKind` is open). Cancelling the task interrupts it, but a rule that ignores interrupts keeps its
thread, so the run must not wait for it.

## 3. Adding a per-rule circuit breaker

The breaker turns off a rule that keeps failing for the rest of the run, so it stops wasting time and
flooding the listener with errors.

- **State:** per run, in `RunExecution`, as a `Map<String, Integer>` of consecutive failures keyed by
  rule id. The engine stays immutable, and the breaker never carries state across runs. To disable a
  rule across runs, the host remembers what tripped and excludes it with a `RuleFilter`.
- **What counts:** only failures of the rule's own code: `RULE_EXCEPTION`, `NULL_VALUE`,
  `UNMAPPED_VALUE` and, with timeouts, `TIMEOUT`. Data problems (`MISSING_REQUIRED_FIELD`,
  `TYPE_MISMATCH`) and `OUT_OF_SCOPE` results do not count. Any `Result` resets the counter.
- **Tripping:** after `RunOptions.circuitBreaker(n)` consecutive failures, which is off by default,
  the rule's logic no longer runs. Each later cell still yields exactly one outcome, an
  `EvaluationError` with a new `ErrorKind.RULE_DISABLED` whose message names the threshold and the
  failure that tripped it. So F3's one outcome per cell still holds, and per-record output stays
  complete. Log the trip once at `WARNING`, giving the rule id and kind and no field values. There is
  no half-open state: a run is a bounded batch job, and the next run starts closed.
- **Parallel runs:** update the counters and decide at *delivery* time, on the calling thread, in
  input order. A cell that was already computed after the rule tripped is replaced by
  `RULE_DISABLED`. The trip point is then the same in both modes. Workers can check a shared flag to
  skip the work early, but delivery decides the outcome.
- **Tests:** a rule that fails from record k onwards trips after n failures. Later cells are
  `RULE_DISABLED`, and the other rules are unaffected. A success resets the count. Data errors never
  trip the breaker. The sequential and parallel outputs are identical.
