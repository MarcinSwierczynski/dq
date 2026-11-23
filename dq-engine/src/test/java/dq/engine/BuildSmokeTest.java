package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void runsOnTheJava25Toolchain() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
    }
}
