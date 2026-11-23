package dq.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.fixtures.Fixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackingFieldReaderTest {

    private final TrackingFieldReader reader = new TrackingFieldReader(
            Fixture.record("name", "ACME", "employees", 12, "tags", List.of("a"), "note", null));

    @Test
    void recordsEveryReadInOrderWithAbsentMarker() {
        reader.text("name");
        reader.raw("employees");
        reader.has("missing");
        reader.text("note");
        reader.text("name");

        assertThat(reader.fieldsRead().sequencedKeySet()).containsExactly("name", "employees", "missing", "note");
        assertThat(reader.fieldsRead())
                .containsEntry("name", "ACME")
                .containsEntry("employees", 12)
                .containsEntry("missing", Provenance.ABSENT)
                .containsEntry("note", null);
    }

    @Test
    void textOfAbsentOrNullFieldIsEmpty() {
        assertThat(reader.text("missing")).isEmpty();
        assertThat(reader.text("note")).isEmpty();
        assertThat(reader.has("note")).isFalse();
        assertThat(reader.has("name")).isTrue();
    }

    @Test
    void textOfNonStringIsATypeMismatch() {
        assertThatThrownBy(() -> reader.text("employees"))
                .isInstanceOfSatisfying(FieldAccessException.class,
                        e -> assertThat(e.kind()).isEqualTo(ErrorKind.TYPE_MISMATCH))
                .hasMessageContaining("'employees' is a number");
        assertThatThrownBy(() -> reader.require("tags"))
                .isInstanceOfSatisfying(FieldAccessException.class,
                        e -> assertThat(e.kind()).isEqualTo(ErrorKind.TYPE_MISMATCH));
    }

    @Test
    void requireOfAbsentOrNullFieldIsMissingRequiredField() {
        for (String name : List.of("missing", "note")) {
            assertThatThrownBy(() -> reader.require(name))
                    .isInstanceOfSatisfying(FieldAccessException.class,
                            e -> assertThat(e.kind()).isEqualTo(ErrorKind.MISSING_REQUIRED_FIELD))
                    .hasMessageContaining("'" + name + "'");
        }
        assertThat(reader.require("name")).isEqualTo("ACME");
    }
}
