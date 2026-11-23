package dq.engine.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a rule applies: everywhere ({@link World}) or to records of one country ({@link Country}).
 *
 * <p>This sealed hierarchy may gain variants (for example a set of countries); pattern matches over
 * it should keep a {@code default} branch.
 */
public sealed interface CountryScope permits CountryScope.World, CountryScope.Country {

    /** The scope that applies to every record, including records without a country. */
    static CountryScope world() {
        return World.INSTANCE;
    }

    /** A scope limited to one ISO 3166-1 alpha-2 country code (case-insensitive, stored upper-case). */
    static CountryScope country(String iso2) {
        return new Country(iso2);
    }

    /**
     * Whether a rule with this scope applies to a record whose country is {@code recordCountry}.
     *
     * @param recordCountry the record's country, or {@code null} when the record has none
     */
    boolean appliesTo(String recordCountry);

    /** Applies to every record. */
    record World() implements CountryScope {
        static final World INSTANCE = new World();

        @Override
        public boolean appliesTo(String recordCountry) {
            return true;
        }

        @Override
        public String toString() {
            return "WORLD";
        }
    }

    /** Applies only to records whose country equals {@code iso2} (case-insensitively). */
    record Country(String iso2) implements CountryScope {
        private static final Pattern ISO2 = Pattern.compile("[A-Za-z]{2}");

        public Country {
            Objects.requireNonNull(iso2, "iso2");
            if (!ISO2.matcher(iso2).matches()) {
                throw new IllegalArgumentException("country scope must be a two-letter ISO code: " + iso2);
            }
            iso2 = iso2.toUpperCase(Locale.ROOT);
        }

        @Override
        public boolean appliesTo(String recordCountry) {
            return recordCountry != null && iso2.equalsIgnoreCase(recordCountry.strip());
        }

        @Override
        public String toString() {
            return iso2;
        }
    }
}
