# DETAILS — dq-engine

Trade-offs and designs for work not built yet. The overview is in [`DESIGN.md`](DESIGN.md).

## 1. Trade-offs under the time-box

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
- **Deviations from the spec:** there is no public `DataRecord`; the engine reads the id and country
  from the field map, and the field-access exception is internal (a smaller API). `RecordSource` is
  `SourceElement next()`, where the sealed `SourceElement` is `Fields | Unreadable`, so a host source
  can report a non-record without throwing.

## 2. Adding a per-rule circuit breaker

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
