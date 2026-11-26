# DETAILS — dq-engine

The design of planned work that is not built yet. Everything about what *is* built, including its
trade-offs, is in [`DESIGN.md`](DESIGN.md).

## Adding a per-rule circuit breaker

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
