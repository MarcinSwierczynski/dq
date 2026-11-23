# Capability: dq-rule-selection — Rule selection

Source: docs/specs/2026-11-20-dq-rule-engine.md
Status: implemented

## Requirement: Composable rule filters
The engine SHALL select rules with a `RuleFilter` built from `status(..)`, `countryScope(..)`, and `category(..)`, combined with `and`/`or`/`not`. It SHALL always intersect the result with `status == RELEASED`.

### Scenario: Category and country
- WHEN the filter is `category("tax").and(countryScope("DE"))`
- THEN only RELEASED rules that are in category `tax` and scoped to `WORLD` or `DE` run

### Scenario: Non-released rules never run
- WHEN the filter is `status(DRAFT)`
- THEN `rulesSelected = 0`, no Results are emitted, and the run completes normally
