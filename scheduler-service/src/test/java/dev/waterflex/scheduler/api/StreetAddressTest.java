package dev.waterflex.scheduler.api;

import static dev.waterflex.scheduler.api.StreetAddress.normalizeStreet;
import static dev.waterflex.scheduler.api.StreetAddress.parseStreetLine;
import static org.junit.jupiter.api.Assertions.*;

import dev.waterflex.scheduler.Required;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The same cases as the portal's web/lib/geocode.test.ts, so the API and the portal compare streets alike. */
class StreetAddressTest {
    @Test void all206UspsPrimarySuffixPairsWorkOnlyInSuffixPosition() {
        assertEquals(206, StreetAddress.SUFFIX_PAIRS.size());
        for (List<String> pair : StreetAddress.SUFFIX_PAIRS) {
            String name = Required.value(pair.get(0)), abbreviation = Required.value(pair.get(1));
            assertEquals(normalizeStreet("Example " + name), normalizeStreet("Example " + abbreviation));
            assertEquals(normalizeStreet("N Example " + name + " SE"), normalizeStreet("North Example " + abbreviation + ". Southeast"));
        }
        for (String[] pair : new String[][] { {"St", "Street"}, {"Cir", "Circle"}, {"Plz", "Plaza"}, {"Ter", "Terrace"}, {"Trl", "Trail"}, {"Hwy", "Highway"} }) {
            String shortName = Required.value(pair[0]);
            assertEquals("main " + shortName.toLowerCase(java.util.Locale.ROOT), normalizeStreet("Main " + shortName));
            assertEquals(normalizeStreet("Main " + shortName), normalizeStreet("Main " + pair[1]));
        }
        for (String[] pair : new String[][] { {"N", "North"}, {"S", "South"}, {"E", "East"}, {"W", "West"}, {"NE", "Northeast"}, {"NW", "Northwest"},
                {"SE", "Southeast"}, {"SW", "Southwest"} }) {
            assertEquals(normalizeStreet(pair[0] + ". Main Rd."), normalizeStreet(pair[1] + " Main Road"));
            assertEquals(normalizeStreet("Main Rd. " + pair[0] + "."), normalizeStreet("Main Road " + pair[1]));
        }
        assertEquals("st charles rd", normalizeStreet("  St Charles Rd.  "));
        assertEquals("circle dr", normalizeStreet("Circle Dr"));
        assertEquals(normalizeStreet("St Charles Rd"), normalizeStreet("Saint Charles Rd"));
        assertNotEquals(normalizeStreet("Circle Dr"), normalizeStreet("Cir Dr"));
        assertNotEquals(normalizeStreet("Main St"), normalizeStreet("Main Cir"));
        assertNotEquals(normalizeStreet("St"), normalizeStreet("Street"));
        assertEquals("n", normalizeStreet("N"));
    }

    @Test void everyUspsCommonSpellingOfAStreetTypeMatchesItsStandardAbbreviation() {
        int spellings = 0;
        for (var entry : StreetAddress.SUFFIX_VARIANTS.entrySet())
            for (String variant : Required.value(entry.getValue())) {
                assertEquals("example " + entry.getKey(), normalizeStreet("Example " + variant), variant);
                spellings++;
            }
        assertEquals(161, spellings);
        assertEquals(normalizeStreet("Main Str"), normalizeStreet("Main Street"));
        assertEquals(normalizeStreet("Dodge Av"), normalizeStreet("Dodge Avenue"));
        assertEquals(normalizeStreet("Elm Mdw"), normalizeStreet("Elm Meadow"));
        assertNotEquals(normalizeStreet("Elm Mdw"), normalizeStreet("Elm Meadows"));
    }

    @Test void numberedStreetsHighwaysAndSaintCompareByMeaning() {
        for (String[] pair : new String[][] {
                {"N Seventy-Second St", "North 72nd Street"}, {"First Ave", "1st Avenue"}, {"Twenty First St", "21st St"},
                {"Eleventh St", "11th St"}, {"Ninety-Third Ave", "93rd Ave"},
                {"US-6", "US Highway 6"}, {"US 6", "U.S. Hwy 6"}, {"US Route 6", "US Hwy 6"}, {"Hwy 50", "Highway 50"},
                {"County Road 12", "CR 12"}, {"Co Rd 12", "County Rd 12"}, {"I-80", "Interstate 80"},
                {"Saint Marys Ave", "St. Marys Avenue"}, {"Sainte Genevieve Dr", "Ste Genevieve Dr"} })
            assertEquals(normalizeStreet(Required.value(pair[0])), normalizeStreet(Required.value(pair[1])), pair[0]);
        // Spelled numbers of one hundred and above are not converted, so such a street must be written the same way.
        assertNotEquals(normalizeStreet("One Hundredth St"), normalizeStreet("100th St"));
        assertNotEquals(normalizeStreet("72nd St"), normalizeStreet("72 St"));
        assertNotEquals(normalizeStreet("US 6"), normalizeStreet("US 60"));
        assertNotEquals(normalizeStreet("County Road 12"), normalizeStreet("State Road 12"));
        assertNotEquals(normalizeStreet("N-6"), normalizeStreet("US Highway 6"));
    }

    @Test void streetLinesLoseTheirUnitButKeepTheirHouseNumberHalvesAndRanges() {
        String[][] cases = {
            {"123 Main St Apt 4", "123", "Main St"}, {"123 Main St Apt. 4B", "123", "Main St"}, {"123 Main St #4", "123", "Main St"},
            {"123 Main St # 4", "123", "Main St"}, {"123 Main St Suite 200", "123", "Main St"}, {"123 Main St, Ste 200", "123", "Main St"},
            {"123 Main St Unit B-2", "123", "Main St"}, {"123 Main St Rear", "123", "Main St"}, {"123 1/2 Main St", "1231/2", "Main St"},
            {"123½ Main St", "1231/2", "Main St"}, {"123-125 Main St", "123-125", "Main St"}, {"12B Main St", "12b", "Main St"},
            {"100 Front St", "100", "Front St"}, {"5 Key West Dr", "5", "Key West Dr"}, {"9 Oak Trailer", "9", "Oak Trailer"},
            {"7 Broadway Apt 4", "7", "Broadway Apt 4"} };
        for (String[] item : cases)
            assertEquals(new StreetAddress.StreetLine(item[1], Required.value(item[2])), parseStreetLine(Required.value(item[0])), item[0]);
        assertEquals(new StreetAddress.StreetLine(null, "Main St"), parseStreetLine("Main St"));
    }
}
