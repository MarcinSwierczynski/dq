package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.fixtures.Fixture;
import dq.engine.run.CollectingListener;
import dq.engine.run.DqEngine;
import dq.engine.run.ErrorKind;
import dq.engine.run.EvaluationError;
import dq.engine.run.Result;
import dq.engine.run.RunStatus;
import dq.engine.run.RunSummary;
import dq.engine.run.ValidationRunException;
import dq.engine.source.RecordSource;
import dq.engine.source.RecordSourceException;
import dq.engine.source.RecordSources;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JsonArraySourceTest {

    private final DqEngine engine = DqEngine.builder().catalog(Fixture.catalog()).build();

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Result> byCell(CollectingListener listener) {
        return listener.results().stream()
                .collect(Collectors.toMap(r -> r.recordId() + "/" + r.ruleId(), Function.identity()));
    }

    @Test
    void validatesTheFixtureFromJson() throws IOException {
        var listener = new CollectingListener();
        try (InputStream in = getClass().getResourceAsStream("fixture.json")) {
            RunSummary summary = engine.validate(RecordSources.jsonArray(in), listener);
            assertThat(summary.resultCount()).isEqualTo(9);
        }
        assertThat(byCell(listener)).hasSize(9);
        Fixture.EXPECTED.forEach((cell, decision) ->
                assertThat(byCell(listener).get(cell).decision()).as(cell).isEqualTo(decision));
    }

    @Test
    void aNonObjectElementIsAnUnreadableRecordAndTheRestStillRuns() throws IOException {
        String text;
        try (InputStream in = getClass().getResourceAsStream("fixture.json")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String withNumber = text.replaceFirst("},\\s*\\{\\s*\"id\": \"r2\"", "}, 42, {\"id\": \"r2\"");
        var listener = new CollectingListener();

        RunSummary summary = engine.validate(RecordSources.jsonArray(json(withNumber)), listener);

        assertThat(summary.recordsRead()).isEqualTo(4);
        assertThat(summary.resultCount()).isEqualTo(9);
        assertThat(summary.errorCount()).isEqualTo(1);
        EvaluationError error = listener.errors().getFirst();
        assertThat(error.kind()).isEqualTo(ErrorKind.UNREADABLE_RECORD);
        assertThat(error.ruleId()).isEmpty();
        assertThat(error.recordIndex()).isEqualTo(1);
        assertThat(error.recordId()).isEqualTo("#1");
        assertThat(error.message()).contains("number");
        Fixture.EXPECTED.forEach((cell, decision) ->
                assertThat(byCell(listener).get(cell).decision()).as(cell).isEqualTo(decision));
        assertThat(byCell(listener).get("r2/vatFormat").provenance().recordIndex()).isEqualTo(2);
    }

    @Test
    void nestedArraysStringsAndNullsAreUnreadable() {
        var listener = new CollectingListener();
        RunSummary summary = engine.validate(
                RecordSources.jsonArray(json("[[1,[2]], \"x\", null, {\"id\":\"ok\",\"country\":\"DE\"}]")), listener);

        assertThat(summary.recordsRead()).isEqualTo(4);
        assertThat(listener.errors()).extracting(EvaluationError::recordIndex).containsExactly(0L, 1L, 2L);
        assertThat(listener.results()).hasSize(3).allSatisfy(r -> assertThat(r.recordId()).isEqualTo("ok"));
    }

    @Test
    void malformedJsonAbortsTheRunAfterDeliveringWhatWasRead() {
        var listener = new CollectingListener();
        String truncated = "[{\"id\":\"r1\",\"country\":\"DE\",\"vatId\":\"DE111111111\",\"iban\":\"DE1\"}, {\"id\":\"r2\",";

        assertThatThrownBy(() -> engine.validate(RecordSources.jsonArray(json(truncated)), listener))
                .isInstanceOfSatisfying(ValidationRunException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(RecordSourceException.class);
                    assertThat(e.partialSummary().status()).isEqualTo(RunStatus.ABORTED);
                    assertThat(e.partialSummary().recordsRead()).isEqualTo(1);
                    assertThat(e.partialSummary().resultCount()).isEqualTo(3);
                });
        assertThat(listener.results()).hasSize(3);
    }

    @Test
    void anIoFailureIsReportedAsUnreadableInputNotAsMalformedJson() {
        InputStream head = json("[{\"id\":\"r1\",\"country\":\"DE\"}, ");
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                int b = head.read();
                if (b < 0) {
                    throw new IOException("connection reset");
                }
                return b;
            }
        };

        assertThatThrownBy(() -> engine.validate(RecordSources.jsonArray(failing), new CollectingListener()))
                .isInstanceOfSatisfying(ValidationRunException.class, e -> assertThat(e.getCause())
                        .isInstanceOf(RecordSourceException.class)
                        .hasMessageStartingWith("could not read input")
                        .hasMessageContaining("connection reset"));
    }

    @Test
    void inputThatIsNotAnArrayAborts() {
        for (String input : new String[] {"{\"id\":\"r1\"}", "", "   ", "[1,2] trailing", "[1,"}) {
            assertThatThrownBy(() -> engine.validate(RecordSources.jsonArray(json(input)), new CollectingListener()))
                    .as(input)
                    .isInstanceOf(ValidationRunException.class)
                    .hasCauseInstanceOf(RecordSourceException.class);
        }
    }

    @Test
    void parsingStartsInsideValidateNotWhenTheSourceIsCreated() {
        var stream = new TrackingStream("not json");
        RecordSource source = RecordSources.jsonArray(stream);
        assertThat(stream.reads).isZero();
        assertThatThrownBy(() -> engine.validate(source, new CollectingListener()))
                .isInstanceOf(ValidationRunException.class);
        assertThat(stream.reads).isPositive();
    }

    @Test
    void theSourceClosesItsStreamOnSuccessAndOnFailure() {
        var ok = new TrackingStream("[]");
        engine.validate(RecordSources.jsonArray(ok), new CollectingListener());
        assertThat(ok.closed).isTrue();

        var broken = new TrackingStream("[{");
        assertThatThrownBy(() -> engine.validate(RecordSources.jsonArray(broken), new CollectingListener()))
                .isInstanceOf(ValidationRunException.class);
        assertThat(broken.closed).isTrue();
    }

    @Test
    void anyHostSourceIsClosedAndItsFailureAborts() {
        var closed = new boolean[1];
        RecordSource failing = new RecordSource() {
            @Override
            public dq.engine.source.SourceElement next() {
                throw new IllegalStateException("cursor lost");
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        assertThatThrownBy(() -> engine.validate(failing, new CollectingListener()))
                .isInstanceOf(ValidationRunException.class)
                .hasMessageContaining("cursor lost");
        assertThat(closed[0]).isTrue();
    }

    @Test
    void aFailingCloseDoesNotHideTheMisuse() {
        dq.engine.source.RecordSource failingClose = new dq.engine.source.RecordSource() {
            @Override
            public dq.engine.source.SourceElement next() {
                return null;
            }

            @Override
            public void close() {
                throw new IllegalStateException("close failed");
            }
        };
        assertThatThrownBy(() -> engine.validate(failingClose, null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("listener")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                        .extracting(Throwable::getMessage).isEqualTo("close failed"));
    }

    @Test
    void theSourceIsClosedEvenWhenValidateIsMisused() {
        var stream = new TrackingStream("[]");
        assertThatThrownBy(() -> engine.validate(RecordSources.jsonArray(stream), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void aFailingCloseDoesNotDiscardTheSummary() {
        RecordSource source = new RecordSource() {
            private final RecordSource delegate = RecordSources.of(Fixture.RECORDS);

            @Override
            public dq.engine.source.SourceElement next() {
                return delegate.next();
            }

            @Override
            public void close() {
                throw new IllegalStateException("close failed");
            }
        };

        RunSummary summary = engine.validate(source, new CollectingListener());

        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.resultCount()).isEqualTo(9);
    }

    private static final class TrackingStream extends ByteArrayInputStream {
        int reads;
        boolean closed;

        TrackingStream(String text) {
            super(text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            reads++;
            return super.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
