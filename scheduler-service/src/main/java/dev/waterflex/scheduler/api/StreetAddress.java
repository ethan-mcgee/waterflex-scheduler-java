package dev.waterflex.scheduler.api;

import dev.waterflex.scheduler.Required;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Street line normalization for address matching, ported from the portal's web/lib/streetNormalization.ts so both
 * compare addresses the same way. USPS Publication 28: Appendix C1 street suffixes, Appendix C2 secondary units.
 */
public final class StreetAddress {
    private StreetAddress() { }

    /**
     * USPS Publication 28, Appendix C1, primary names and standard abbreviations.
     * https://pe.usps.com/text/pub28/28apc_002.htm (retrieved 2026-09-24). Common variants are in {@link #SUFFIX_VARIANTS}.
     */
    static final List<List<String>> SUFFIX_PAIRS = Required.value(List.of(
            List.of("alley", "aly"), List.of("anex", "anx"), List.of("arcade", "arc"), List.of("avenue", "ave"),
            List.of("bayou", "byu"), List.of("beach", "bch"), List.of("bend", "bnd"), List.of("bluff", "blf"),
            List.of("bluffs", "blfs"), List.of("bottom", "btm"), List.of("boulevard", "blvd"), List.of("branch", "br"),
            List.of("bridge", "brg"), List.of("brook", "brk"), List.of("brooks", "brks"), List.of("burg", "bg"),
            List.of("burgs", "bgs"), List.of("bypass", "byp"), List.of("camp", "cp"), List.of("canyon", "cyn"),
            List.of("cape", "cpe"), List.of("causeway", "cswy"), List.of("center", "ctr"), List.of("centers", "ctrs"),
            List.of("circle", "cir"), List.of("circles", "cirs"), List.of("cliff", "clf"), List.of("cliffs", "clfs"),
            List.of("club", "clb"), List.of("common", "cmn"), List.of("commons", "cmns"), List.of("corner", "cor"),
            List.of("corners", "cors"), List.of("course", "crse"), List.of("court", "ct"), List.of("courts", "cts"),
            List.of("cove", "cv"), List.of("coves", "cvs"), List.of("creek", "crk"), List.of("crescent", "cres"),
            List.of("crest", "crst"), List.of("crossing", "xing"), List.of("crossroad", "xrd"), List.of("crossroads", "xrds"),
            List.of("curve", "curv"), List.of("dale", "dl"), List.of("dam", "dm"), List.of("divide", "dv"),
            List.of("drive", "dr"), List.of("drives", "drs"), List.of("estate", "est"), List.of("estates", "ests"),
            List.of("expressway", "expy"), List.of("extension", "ext"), List.of("extensions", "exts"), List.of("fall", "fall"),
            List.of("falls", "fls"), List.of("ferry", "fry"), List.of("field", "fld"), List.of("fields", "flds"),
            List.of("flat", "flt"), List.of("flats", "flts"), List.of("ford", "frd"), List.of("fords", "frds"),
            List.of("forest", "frst"), List.of("forge", "frg"), List.of("forges", "frgs"), List.of("fork", "frk"),
            List.of("forks", "frks"), List.of("fort", "ft"), List.of("freeway", "fwy"), List.of("garden", "gdn"),
            List.of("gardens", "gdns"), List.of("gateway", "gtwy"), List.of("glen", "gln"), List.of("glens", "glns"),
            List.of("green", "grn"), List.of("greens", "grns"), List.of("grove", "grv"), List.of("groves", "grvs"),
            List.of("harbor", "hbr"), List.of("harbors", "hbrs"), List.of("haven", "hvn"), List.of("heights", "hts"),
            List.of("highway", "hwy"), List.of("hill", "hl"), List.of("hills", "hls"), List.of("hollow", "holw"),
            List.of("inlet", "inlt"), List.of("island", "is"), List.of("islands", "iss"), List.of("isle", "isle"),
            List.of("junction", "jct"), List.of("junctions", "jcts"), List.of("key", "ky"), List.of("keys", "kys"),
            List.of("knoll", "knl"), List.of("knolls", "knls"), List.of("lake", "lk"), List.of("lakes", "lks"),
            List.of("land", "land"), List.of("landing", "lndg"), List.of("lane", "ln"), List.of("light", "lgt"),
            List.of("lights", "lgts"), List.of("loaf", "lf"), List.of("lock", "lck"), List.of("locks", "lcks"),
            List.of("lodge", "ldg"), List.of("loop", "loop"), List.of("mall", "mall"), List.of("manor", "mnr"),
            List.of("manors", "mnrs"), List.of("meadow", "mdw"), List.of("meadows", "mdws"), List.of("mews", "mews"),
            List.of("mill", "ml"), List.of("mills", "mls"), List.of("mission", "msn"), List.of("motorway", "mtwy"),
            List.of("mount", "mt"), List.of("mountain", "mtn"), List.of("mountains", "mtns"), List.of("neck", "nck"),
            List.of("orchard", "orch"), List.of("oval", "oval"), List.of("overpass", "opas"), List.of("park", "park"),
            List.of("parks", "park"), List.of("parkway", "pkwy"), List.of("parkways", "pkwy"), List.of("pass", "pass"),
            List.of("passage", "psge"), List.of("path", "path"), List.of("pike", "pike"), List.of("pine", "pne"),
            List.of("pines", "pnes"), List.of("place", "pl"), List.of("plain", "pln"), List.of("plains", "plns"),
            List.of("plaza", "plz"), List.of("point", "pt"), List.of("points", "pts"), List.of("port", "prt"),
            List.of("ports", "prts"), List.of("prairie", "pr"), List.of("radial", "radl"), List.of("ramp", "ramp"),
            List.of("ranch", "rnch"), List.of("rapid", "rpd"), List.of("rapids", "rpds"), List.of("rest", "rst"),
            List.of("ridge", "rdg"), List.of("ridges", "rdgs"), List.of("river", "riv"), List.of("road", "rd"),
            List.of("roads", "rds"), List.of("route", "rte"), List.of("row", "row"), List.of("rue", "rue"),
            List.of("run", "run"), List.of("shoal", "shl"), List.of("shoals", "shls"), List.of("shore", "shr"),
            List.of("shores", "shrs"), List.of("skyway", "skwy"), List.of("spring", "spg"), List.of("springs", "spgs"),
            List.of("spur", "spur"), List.of("spurs", "spur"), List.of("square", "sq"), List.of("squares", "sqs"),
            List.of("station", "sta"), List.of("stravenue", "stra"), List.of("stream", "strm"), List.of("street", "st"),
            List.of("streets", "sts"), List.of("summit", "smt"), List.of("terrace", "ter"), List.of("throughway", "trwy"),
            List.of("trace", "trce"), List.of("track", "trak"), List.of("trafficway", "trfy"), List.of("trail", "trl"),
            List.of("trailer", "trlr"), List.of("tunnel", "tunl"), List.of("turnpike", "tpke"), List.of("underpass", "upas"),
            List.of("union", "un"), List.of("unions", "uns"), List.of("valley", "vly"), List.of("valleys", "vlys"),
            List.of("viaduct", "via"), List.of("view", "vw"), List.of("views", "vws"), List.of("village", "vlg"),
            List.of("villages", "vlgs"), List.of("ville", "vl"), List.of("vista", "vis"), List.of("walk", "walk"),
            List.of("walks", "walk"), List.of("wall", "wall"), List.of("way", "way"), List.of("ways", "ways"),
            List.of("well", "wl"), List.of("wells", "wls")));

    /**
     * USPS Publication 28, Appendix C1, commonly used spellings, keyed by the standard abbreviation (retrieved
     * 2026-10-08). USPS lists "mdw" under both Meadow and Meadows; it stays with Meadow, whose standard abbreviation it is.
     */
    static final Map<String, List<String>> SUFFIX_VARIANTS = Required.value(Map.ofEntries(
            Map.entry("aly", List.of("allee", "ally")), Map.entry("anx", List.of("annex", "annx")),
            Map.entry("ave", List.of("av", "aven", "avenu", "avn", "avnue")), Map.entry("byu", List.of("bayoo")),
            Map.entry("blf", List.of("bluf")), Map.entry("btm", List.of("bot", "bottm")),
            Map.entry("blvd", List.of("boul", "boulv")), Map.entry("br", List.of("brnch")),
            Map.entry("brg", List.of("brdge")), Map.entry("byp", List.of("bypa", "bypas", "byps")),
            Map.entry("cp", List.of("cmp")), Map.entry("cyn", List.of("canyn", "cnyn")),
            Map.entry("cswy", List.of("causwa")), Map.entry("ctr", List.of("cen", "cent", "centr", "centre", "cnter", "cntr")),
            Map.entry("cir", List.of("circ", "circl", "crcl", "crcle")), Map.entry("cres", List.of("crsent", "crsnt")),
            Map.entry("xing", List.of("crssng")), Map.entry("dv", List.of("div", "dvd")),
            Map.entry("dr", List.of("driv", "drv")), Map.entry("expy", List.of("exp", "expr", "express", "expw")),
            Map.entry("ext", List.of("extn", "extnsn")), Map.entry("fry", List.of("frry")),
            Map.entry("frst", List.of("forests")), Map.entry("frg", List.of("forg")),
            Map.entry("ft", List.of("frt")), Map.entry("fwy", List.of("freewy", "frway", "frwy")),
            Map.entry("gdn", List.of("gardn", "grden", "grdn")), Map.entry("gdns", List.of("grdns")),
            Map.entry("gtwy", List.of("gatewy", "gatway", "gtway")), Map.entry("grv", List.of("grov")),
            Map.entry("hbr", List.of("harb", "harbr", "hrbor")), Map.entry("hts", List.of("ht")),
            Map.entry("hwy", List.of("highwy", "hiway", "hiwy", "hway")), Map.entry("holw", List.of("hllw", "hollows", "holws")),
            Map.entry("is", List.of("islnd")), Map.entry("iss", List.of("islnds")),
            Map.entry("isle", List.of("isles")), Map.entry("jct", List.of("jction", "jctn", "junctn", "juncton")),
            Map.entry("jcts", List.of("jctns")), Map.entry("knl", List.of("knol")),
            Map.entry("lndg", List.of("lndng")), Map.entry("ldg", List.of("ldge", "lodg")),
            Map.entry("loop", List.of("loops")), Map.entry("mdws", List.of("medows")),
            Map.entry("msn", List.of("missn", "mssn")), Map.entry("mt", List.of("mnt")),
            Map.entry("mtn", List.of("mntain", "mntn", "mountin", "mtin")), Map.entry("mtns", List.of("mntns")),
            Map.entry("orch", List.of("orchrd")), Map.entry("oval", List.of("ovl")),
            Map.entry("park", List.of("prk")), Map.entry("pkwy", List.of("parkwy", "pkway", "pkwys", "pky")),
            Map.entry("path", List.of("paths")), Map.entry("pike", List.of("pikes")),
            Map.entry("plz", List.of("plza")), Map.entry("pr", List.of("prr")),
            Map.entry("radl", List.of("rad", "radiel")), Map.entry("rnch", List.of("ranches", "rnchs")),
            Map.entry("rdg", List.of("rdge")), Map.entry("riv", List.of("rivr", "rvr")),
            Map.entry("shr", List.of("shoar")), Map.entry("shrs", List.of("shoars")),
            Map.entry("spg", List.of("spng", "sprng")), Map.entry("spgs", List.of("spngs", "sprngs")),
            Map.entry("sq", List.of("sqr", "sqre", "squ")), Map.entry("sqs", List.of("sqrs")),
            Map.entry("sta", List.of("statn", "stn")), Map.entry("stra", List.of("strav", "straven", "stravn", "strvn", "strvnue")),
            Map.entry("strm", List.of("streme")), Map.entry("st", List.of("str", "strt")),
            Map.entry("smt", List.of("sumit", "sumitt")), Map.entry("ter", List.of("terr")),
            Map.entry("trce", List.of("traces")), Map.entry("trak", List.of("tracks", "trk", "trks")),
            Map.entry("trl", List.of("trails", "trls")), Map.entry("trlr", List.of("trlrs")),
            Map.entry("tunl", List.of("tunel", "tunls", "tunnels", "tunnl")), Map.entry("tpke", List.of("trnpk", "turnpk")),
            Map.entry("vly", List.of("vally", "vlly")), Map.entry("via", List.of("vdct", "viadct")),
            Map.entry("vlg", List.of("vill", "villag", "villg", "villiage")), Map.entry("vis", List.of("vist", "vst", "vsta")),
            Map.entry("way", List.of("wy"))));

    private static final Map<String, String> SUFFIXES = new HashMap<>();
    private static final Map<String, String> DIRECTIONS = new HashMap<>();
    private static final Map<String, Integer> ORDINAL_WORDS = new HashMap<>();
    private static final Map<String, Integer> TENS = new HashMap<>();
    static {
        for (List<String> pair : SUFFIX_PAIRS) {
            SUFFIXES.put(Required.value(pair.get(0)), Required.value(pair.get(1)));
            SUFFIXES.put(Required.value(pair.get(1)), Required.value(pair.get(1)));
        }
        SUFFIX_VARIANTS.forEach((abbreviation, spellings) -> {
            for (String spelling : Required.value(spellings)) SUFFIXES.put(spelling, Required.value(abbreviation));
        });
        String[][] directions = { {"north", "n"}, {"south", "s"}, {"east", "e"}, {"west", "w"},
            {"northeast", "ne"}, {"northwest", "nw"}, {"southeast", "se"}, {"southwest", "sw"} };
        for (String[] direction : directions) {
            DIRECTIONS.put(Required.value(direction[0]), Required.value(direction[1]));
            DIRECTIONS.put(Required.value(direction[1]), Required.value(direction[1]));
        }
        String[] ordinals = { "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh",
            "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth" };
        for (int i = 0; i < ordinals.length; i++) ORDINAL_WORDS.put(Required.value(ordinals[i]), i + 1);
        String[] tens = { "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };
        String[] tenths = { "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth" };
        for (int i = 0; i < tens.length; i++) {
            TENS.put(Required.value(tens[i]), 20 + 10 * i);
            ORDINAL_WORDS.put(Required.value(tenths[i]), 20 + 10 * i);
        }
    }

    private static String ordinal(int value) {
        boolean teen = value % 100 >= 11 && value % 100 <= 13;
        String[] endings = { "th", "st", "nd", "rd" };
        return value + (teen || value % 10 >= endings.length ? "th" : endings[value % 10]);
    }

    /** Numbered streets compare as digits: "Twenty-First" and "21st" are the same street. */
    private static List<String> ordinals(List<String> words) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = Required.value(words.get(i)), next = i + 1 < words.size() ? Required.value(words.get(i + 1)) : "";
            Integer tens = TENS.get(word), ones = ORDINAL_WORDS.get(next);
            if (tens != null && ones != null && ones < 10) { result.add(ordinal(tens + ones)); i++; continue; }
            Integer single = ORDINAL_WORDS.get(word);
            result.add(single == null ? word : ordinal(single));
        }
        return result;
    }

    private static final Set<String> HIGHWAY_WORDS = Required.value(Set.of("highway", "highwy", "hiway", "hiwy", "hway", "hwy"));
    private static final Pattern NUMBER = Required.value(Pattern.compile("\\d+[a-z]?"));

    private static boolean number(String word) { return NUMBER.matcher(word).matches(); }

    /**
     * Route names compare by their standard form: "US-6", "US 6", "US Route 6" and "US Highway 6" are all "us hwy 6";
     * "County Road 12", "Co Rd 12" and "CR 12" are "co rd 12"; "Interstate 80" and "I-80" are "i 80". State route
     * prefixes such as "N-6" are left alone, because "N" is also a direction; such an address does not match.
     */
    private static List<String> routes(List<String> words) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String word = Required.value(words.get(i));
            String next = i + 1 < words.size() ? Required.value(words.get(i + 1)) : "", after = i + 2 < words.size() ? Required.value(words.get(i + 2)) : "";
            if (word.equals("us") && number(next)) { result.addAll(List.of("us", "hwy", next)); i++; continue; }
            if (word.equals("us") && (HIGHWAY_WORDS.contains(next) || next.equals("route") || next.equals("rte")) && number(after)) {
                result.addAll(List.of("us", "hwy", after)); i += 2; continue;
            }
            if ((word.equals("interstate") || word.equals("i")) && number(next)) { result.addAll(List.of("i", next)); i++; continue; }
            if (word.equals("cr") && number(next)) { result.addAll(List.of("co", "rd", next)); i++; continue; }
            if ((word.equals("county") || word.equals("co")) && (next.equals("road") || next.equals("rd")) && number(after)) {
                result.addAll(List.of("co", "rd", after)); i += 2; continue;
            }
            result.add(HIGHWAY_WORDS.contains(word) ? "hwy" : word);
        }
        return result;
    }

    private static List<String> words(String value) {
        return Required.value(Arrays.asList(value.trim().split("\\s+")));
    }

    public static String normalizeStreet(String value) {
        List<String> words = new ArrayList<>(routes(ordinals(words(Required.value(value.toLowerCase(Locale.ROOT).replace(".", "").replace("-", " "))))));
        int start = 0, end = words.size() - 1;
        String first = Required.value(words.get(start));
        String direction = DIRECTIONS.get(first);
        if (start < end && direction != null) { words.set(start, direction); start++; }
        direction = DIRECTIONS.get(Required.value(words.get(end)));
        if (start < end && direction != null) { words.set(end, direction); end--; }
        // "Saint" and "St" open a name; "St" never ends one there, so the two spellings are the same name.
        String opening = Required.value(words.get(start));
        if (start < end && (opening.equals("saint") || opening.equals("st"))) words.set(start, "st");
        if (start < end && opening.equals("sainte")) words.set(start, "ste");
        String suffix = SUFFIXES.get(Required.value(words.get(end)));
        if (start < end && suffix != null) words.set(end, suffix);
        return Required.value(String.join(" ", words));
    }

    // USPS Publication 28, Appendix C2, secondary unit designators.
    // https://pe.usps.com/text/pub28/28apc_003.htm (retrieved 2026-10-08).
    private static final Set<String> RANGED_UNITS = Required.value(Set.of("apartment", "apt", "building", "bldg", "department", "dept", "floor", "fl",
            "hangar", "hanger", "hngr", "key", "lot", "pier", "room", "rm", "slip", "space", "spc", "stop", "suite", "ste", "trailer", "trlr", "unit"));
    private static final Set<String> UNRANGED_UNITS = Required.value(Set.of("basement", "bsmt", "front", "frnt", "lobby", "lbby", "lower", "lowr",
            "office", "ofc", "penthouse", "ph", "rear", "side", "upper", "uppr"));
    private static final Pattern UNIT_IDENTIFIER = Required.value(Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)?", Pattern.CASE_INSENSITIVE));
    private static final Pattern HOUSE = Required.value(Pattern.compile("^\\s*(\\d+(?:-\\d+)?[a-z]?)(?:\\s*(1/2|\u00bd))?\\s+(.+)$",
            Pattern.CASE_INSENSITIVE));
    private static final Pattern TRAILING_COMMA = Required.value(Pattern.compile(",\\s*$"));

    /** House numbers compare without spaces or case, with "\u00bd" written as "1/2". */
    public static String normalizeHouseNumber(String value) {
        return Required.value(value.toLowerCase(Locale.ROOT).replace("\u00bd", "1/2").replaceAll("\\s+", ""));
    }

    /** A street line split into its house number, if any, and its street. */
    public record StreetLine(@Nullable String houseNumber, String street) { }

    /**
     * Splits a street line into its house number and street, dropping an apartment or suite written on the same line
     * ("Apt 4", "#4", "Suite 200", "Rear"). A unit is only recognized after at least two street words, so a street
     * named "Front St" or "Key West Dr" keeps its name.
     */
    public static StreetLine parseStreetLine(String line1) {
        Matcher house = HOUSE.matcher(line1);
        boolean numbered = house.matches();
        String number = numbered ? house.group(1) : null, half = numbered ? house.group(2) : null, rest = numbered ? house.group(3) : null;
        List<String> words = words(rest == null ? line1 : rest);
        int end = words.size();
        int pound = -1;
        for (int i = 2; i < words.size() && pound < 0; i++) if (Required.value(words.get(i)).startsWith("#")) pound = i;
        if (pound >= 0) end = pound;
        else {
            String last = Required.value(words.get(words.size() - 1)).toLowerCase(Locale.ROOT).replace(".", "");
            String designator = words.size() >= 2 ? Required.value(words.get(words.size() - 2)).toLowerCase(Locale.ROOT).replace(".", "") : "";
            if (words.size() >= 4 && RANGED_UNITS.contains(designator) && UNIT_IDENTIFIER.matcher(last).matches()) end = words.size() - 2;
            else if (words.size() >= 3 && UNRANGED_UNITS.contains(last)) end = words.size() - 1;
        }
        String street = Required.value(TRAILING_COMMA.matcher(String.join(" ", words.subList(0, end))).replaceAll(""));
        return new StreetLine(number == null ? null : normalizeHouseNumber(number + (half == null ? "" : half)), street);
    }
}
