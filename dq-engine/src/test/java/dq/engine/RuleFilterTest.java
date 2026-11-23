package dq.engine;

import static dq.engine.TestRules.rule;
import static dq.engine.api.RuleFilter.category;
import static dq.engine.api.RuleFilter.countryScope;
import static dq.engine.api.RuleFilter.not;
import static dq.engine.api.RuleFilter.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.api.CollectingListener;
import dq.engine.api.CountryScope;
import dq.engine.api.DqEngine;
import dq.engine.api.RecordSources;
import dq.engine.api.Result;
import dq.engine.api.Rule;
import dq.engine.api.RuleCatalog;
import dq.engine.api.RuleFilter;
import dq.engine.api.RuleStatus;
import dq.engine.api.RunOptions;
import dq.engine.api.RunStatus;
import dq.engine.api.RunSummary;
import dq.engine.fixtures.Fixture;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleFilterTest {

    private static final Rule WORLD_TAX = rule("worldTax", f -> "ok").category("tax").build();
    private static final Rule DE_TAX = rule("deTax", f -> "ok").category("tax").scope(CountryScope.country("DE")).build();
    private static final Rule FR_TAX = rule("frTax", f -> "ok").category("tax").scope(CountryScope.country("FR")).build();
    private static final Rule DE_BANK = rule("deBank", f -> "ok").category("banking").category("kyc")
            .scope(CountryScope.country("DE")).build();
    private static final Rule DRAFT_TAX = rule("draftTax", f -> "ok").category("tax").status(RuleStatus.DRAFT).build();
    private static final List<Rule> ALL = List.of(WORLD_TAX, DE_TAX, FR_TAX, DE_BANK, DRAFT_TAX);

    private static List<String> select(RuleFilter filter) {
        return ALL.stream().filter(filter::test).map(Rule::id).toList();
    }

    private static RunSummary run(RuleFilter filter, CollectingListener listener) {
        return DqEngine.builder().catalog(RuleCatalog.of(ALL)).build().validate(
                RecordSources.of(Fixture.RECORDS), listener, RunOptions.builder().filter(filter).build());
    }

    @Test
    void factoriesSelectByStatusCountryScopeAndCategory() {
        assertThat(select(RuleFilter.all())).hasSize(5);
        assertThat(select(status(RuleStatus.DRAFT))).containsExactly("draftTax");
        assertThat(select(status(RuleStatus.RELEASED, RuleStatus.DRAFT))).hasSize(5);
        assertThat(select(countryScope("DE"))).containsExactly("worldTax", "deTax", "deBank", "draftTax");
        assertThat(select(countryScope("de", "FR"))).hasSize(5);
        assertThat(select(category("kyc"))).containsExactly("deBank");
        assertThat(select(category("banking", "tax"))).hasSize(5);
        assertThat(select(category("tax", "tax"))).hasSize(4);
    }

    @Test
    void filtersCombine() {
        assertThat(select(category("tax").and(countryScope("DE")))).containsExactly("worldTax", "deTax", "draftTax");
        assertThat(select(category("kyc").or(countryScope("FR")))).containsExactly("worldTax", "frTax", "deBank", "draftTax");
        assertThat(select(not(category("tax")))).containsExactly("deBank");
        assertThat(select(category("tax").and(not(countryScope("FR"))))).containsExactly("deTax");
    }

    @Test
    void theEngineAlwaysIntersectsWithReleased() {
        var listener = new CollectingListener();
        RunSummary summary = run(category("tax").and(countryScope("DE")), listener);

        assertThat(summary.rulesSelected()).isEqualTo(2);
        assertThat(listener.results()).extracting(Result::ruleId).containsOnly("worldTax", "deTax");
    }

    @Test
    void selectingOnlyNonReleasedRulesIsAnEmptyButCompletedRun() {
        var listener = new CollectingListener();
        RunSummary summary = run(status(RuleStatus.DRAFT), listener);

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.rulesSelected()).isZero();
        assertThat(summary.resultCount()).isZero();
        assertThat(summary.recordsRead()).isEqualTo(3);
        assertThat(listener.results()).isEmpty();
    }

    @Test
    void aThrowingFilterPropagatesUnchangedBeforeAnyRecordAndTheSourceIsClosed() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        dq.engine.api.RecordSource source = new dq.engine.api.RecordSource() {
            @Override
            public dq.engine.api.SourceElement next() {
                throw new AssertionError("no record may be pulled when rule selection fails");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        RuleFilter broken = rule -> { throw new IllegalStateException("tenant lookup failed"); };

        assertThatThrownBy(() -> DqEngine.builder().catalog(RuleCatalog.of(ALL)).build()
                        .validate(source, new CollectingListener(), RunOptions.builder().filter(broken).build()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("tenant lookup failed");
        assertThat(closed).isTrue();
    }

    @Test
    void rejectsEmptyArguments() {
        assertThatThrownBy(RuleFilter::countryScope).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(RuleFilter::category).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> countryScope("Germany")).isInstanceOf(IllegalArgumentException.class);
    }
}
