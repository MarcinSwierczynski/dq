package dq.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dq.engine.catalog.CountryScope;
import org.junit.jupiter.api.Test;

class CountryScopeTest {

    @Test
    void worldAppliesEverywhereIncludingRecordsWithoutCountry() {
        assertThat(CountryScope.world().appliesTo("DE")).isTrue();
        assertThat(CountryScope.world().appliesTo(null)).isTrue();
    }

    @Test
    void countryAppliesOnlyToItsOwnCountryCaseInsensitively() {
        CountryScope de = CountryScope.country("de");
        assertThat(de).isEqualTo(CountryScope.country("DE"));
        assertThat(de.appliesTo("DE")).isTrue();
        assertThat(de.appliesTo("de")).isTrue();
        assertThat(de.appliesTo("FR")).isFalse();
        assertThat(de.appliesTo(null)).isFalse();
    }

    @Test
    void rejectsCodesThatAreNotTwoLetters() {
        assertThatThrownBy(() -> CountryScope.country("DEU")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CountryScope.country("")).isInstanceOf(IllegalArgumentException.class);
    }
}
