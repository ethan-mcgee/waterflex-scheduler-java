// USPS Publication 28, Appendix C1, primary names and standard abbreviations.
// https://pe.usps.com/text/pub28/28apc_002.htm (retrieved 2026-09-24).
// Common variants in the middle column are intentionally excluded.
export const STREET_SUFFIX_PAIRS = [
  ["alley", "aly"],
  ["anex", "anx"],
  ["arcade", "arc"],
  ["avenue", "ave"],
  ["bayou", "byu"],
  ["beach", "bch"],
  ["bend", "bnd"],
  ["bluff", "blf"],
  ["bluffs", "blfs"],
  ["bottom", "btm"],
  ["boulevard", "blvd"],
  ["branch", "br"],
  ["bridge", "brg"],
  ["brook", "brk"],
  ["brooks", "brks"],
  ["burg", "bg"],
  ["burgs", "bgs"],
  ["bypass", "byp"],
  ["camp", "cp"],
  ["canyon", "cyn"],
  ["cape", "cpe"],
  ["causeway", "cswy"],
  ["center", "ctr"],
  ["centers", "ctrs"],
  ["circle", "cir"],
  ["circles", "cirs"],
  ["cliff", "clf"],
  ["cliffs", "clfs"],
  ["club", "clb"],
  ["common", "cmn"],
  ["commons", "cmns"],
  ["corner", "cor"],
  ["corners", "cors"],
  ["course", "crse"],
  ["court", "ct"],
  ["courts", "cts"],
  ["cove", "cv"],
  ["coves", "cvs"],
  ["creek", "crk"],
  ["crescent", "cres"],
  ["crest", "crst"],
  ["crossing", "xing"],
  ["crossroad", "xrd"],
  ["crossroads", "xrds"],
  ["curve", "curv"],
  ["dale", "dl"],
  ["dam", "dm"],
  ["divide", "dv"],
  ["drive", "dr"],
  ["drives", "drs"],
  ["estate", "est"],
  ["estates", "ests"],
  ["expressway", "expy"],
  ["extension", "ext"],
  ["extensions", "exts"],
  ["fall", "fall"],
  ["falls", "fls"],
  ["ferry", "fry"],
  ["field", "fld"],
  ["fields", "flds"],
  ["flat", "flt"],
  ["flats", "flts"],
  ["ford", "frd"],
  ["fords", "frds"],
  ["forest", "frst"],
  ["forge", "frg"],
  ["forges", "frgs"],
  ["fork", "frk"],
  ["forks", "frks"],
  ["fort", "ft"],
  ["freeway", "fwy"],
  ["garden", "gdn"],
  ["gardens", "gdns"],
  ["gateway", "gtwy"],
  ["glen", "gln"],
  ["glens", "glns"],
  ["green", "grn"],
  ["greens", "grns"],
  ["grove", "grv"],
  ["groves", "grvs"],
  ["harbor", "hbr"],
  ["harbors", "hbrs"],
  ["haven", "hvn"],
  ["heights", "hts"],
  ["highway", "hwy"],
  ["hill", "hl"],
  ["hills", "hls"],
  ["hollow", "holw"],
  ["inlet", "inlt"],
  ["island", "is"],
  ["islands", "iss"],
  ["isle", "isle"],
  ["junction", "jct"],
  ["junctions", "jcts"],
  ["key", "ky"],
  ["keys", "kys"],
  ["knoll", "knl"],
  ["knolls", "knls"],
  ["lake", "lk"],
  ["lakes", "lks"],
  ["land", "land"],
  ["landing", "lndg"],
  ["lane", "ln"],
  ["light", "lgt"],
  ["lights", "lgts"],
  ["loaf", "lf"],
  ["lock", "lck"],
  ["locks", "lcks"],
  ["lodge", "ldg"],
  ["loop", "loop"],
  ["mall", "mall"],
  ["manor", "mnr"],
  ["manors", "mnrs"],
  ["meadow", "mdw"],
  ["meadows", "mdws"],
  ["mews", "mews"],
  ["mill", "ml"],
  ["mills", "mls"],
  ["mission", "msn"],
  ["motorway", "mtwy"],
  ["mount", "mt"],
  ["mountain", "mtn"],
  ["mountains", "mtns"],
  ["neck", "nck"],
  ["orchard", "orch"],
  ["oval", "oval"],
  ["overpass", "opas"],
  ["park", "park"],
  ["parks", "park"],
  ["parkway", "pkwy"],
  ["parkways", "pkwy"],
  ["pass", "pass"],
  ["passage", "psge"],
  ["path", "path"],
  ["pike", "pike"],
  ["pine", "pne"],
  ["pines", "pnes"],
  ["place", "pl"],
  ["plain", "pln"],
  ["plains", "plns"],
  ["plaza", "plz"],
  ["point", "pt"],
  ["points", "pts"],
  ["port", "prt"],
  ["ports", "prts"],
  ["prairie", "pr"],
  ["radial", "radl"],
  ["ramp", "ramp"],
  ["ranch", "rnch"],
  ["rapid", "rpd"],
  ["rapids", "rpds"],
  ["rest", "rst"],
  ["ridge", "rdg"],
  ["ridges", "rdgs"],
  ["river", "riv"],
  ["road", "rd"],
  ["roads", "rds"],
  ["route", "rte"],
  ["row", "row"],
  ["rue", "rue"],
  ["run", "run"],
  ["shoal", "shl"],
  ["shoals", "shls"],
  ["shore", "shr"],
  ["shores", "shrs"],
  ["skyway", "skwy"],
  ["spring", "spg"],
  ["springs", "spgs"],
  ["spur", "spur"],
  ["spurs", "spur"],
  ["square", "sq"],
  ["squares", "sqs"],
  ["station", "sta"],
  ["stravenue", "stra"],
  ["stream", "strm"],
  ["street", "st"],
  ["streets", "sts"],
  ["summit", "smt"],
  ["terrace", "ter"],
  ["throughway", "trwy"],
  ["trace", "trce"],
  ["track", "trak"],
  ["trafficway", "trfy"],
  ["trail", "trl"],
  ["trailer", "trlr"],
  ["tunnel", "tunl"],
  ["turnpike", "tpke"],
  ["underpass", "upas"],
  ["union", "un"],
  ["unions", "uns"],
  ["valley", "vly"],
  ["valleys", "vlys"],
  ["viaduct", "via"],
  ["view", "vw"],
  ["views", "vws"],
  ["village", "vlg"],
  ["villages", "vlgs"],
  ["ville", "vl"],
  ["vista", "vis"],
  ["walk", "walk"],
  ["walks", "walk"],
  ["wall", "wall"],
  ["way", "way"],
  ["ways", "ways"],
  ["well", "wl"],
  ["wells", "wls"],
] as const;

// USPS Publication 28, Appendix C1, commonly used spellings (middle column), keyed by the standard abbreviation.
// https://pe.usps.com/text/pub28/28apc_002.htm (retrieved 2026-10-08). Spellings that are themselves a primary
// name or standard abbreviation are covered by STREET_SUFFIX_PAIRS. USPS lists "mdw" under both Meadow and Meadows;
// it stays with Meadow, whose standard abbreviation it is.
export const STREET_SUFFIX_VARIANTS: Readonly<Record<string, readonly string[]>> = {
  aly: ["allee", "ally"],
  anx: ["annex", "annx"],
  ave: ["av", "aven", "avenu", "avn", "avnue"],
  byu: ["bayoo"],
  blf: ["bluf"],
  btm: ["bot", "bottm"],
  blvd: ["boul", "boulv"],
  br: ["brnch"],
  brg: ["brdge"],
  byp: ["bypa", "bypas", "byps"],
  cp: ["cmp"],
  cyn: ["canyn", "cnyn"],
  cswy: ["causwa"],
  ctr: ["cen", "cent", "centr", "centre", "cnter", "cntr"],
  cir: ["circ", "circl", "crcl", "crcle"],
  cres: ["crsent", "crsnt"],
  xing: ["crssng"],
  dv: ["div", "dvd"],
  dr: ["driv", "drv"],
  expy: ["exp", "expr", "express", "expw"],
  ext: ["extn", "extnsn"],
  fry: ["frry"],
  frst: ["forests"],
  frg: ["forg"],
  ft: ["frt"],
  fwy: ["freewy", "frway", "frwy"],
  gdn: ["gardn", "grden", "grdn"],
  gdns: ["grdns"],
  gtwy: ["gatewy", "gatway", "gtway"],
  grv: ["grov"],
  hbr: ["harb", "harbr", "hrbor"],
  hts: ["ht"],
  hwy: ["highwy", "hiway", "hiwy", "hway"],
  holw: ["hllw", "hollows", "holws"],
  is: ["islnd"],
  iss: ["islnds"],
  isle: ["isles"],
  jct: ["jction", "jctn", "junctn", "juncton"],
  jcts: ["jctns"],
  knl: ["knol"],
  lndg: ["lndng"],
  ldg: ["ldge", "lodg"],
  loop: ["loops"],
  mdws: ["medows"],
  msn: ["missn", "mssn"],
  mt: ["mnt"],
  mtn: ["mntain", "mntn", "mountin", "mtin"],
  mtns: ["mntns"],
  orch: ["orchrd"],
  oval: ["ovl"],
  park: ["prk"],
  pkwy: ["parkwy", "pkway", "pkwys", "pky"],
  path: ["paths"],
  pike: ["pikes"],
  plz: ["plza"],
  pr: ["prr"],
  radl: ["rad", "radiel"],
  rnch: ["ranches", "rnchs"],
  rdg: ["rdge"],
  riv: ["rivr", "rvr"],
  shr: ["shoar"],
  shrs: ["shoars"],
  spg: ["spng", "sprng"],
  spgs: ["spngs", "sprngs"],
  sq: ["sqr", "sqre", "squ"],
  sqs: ["sqrs"],
  sta: ["statn", "stn"],
  stra: ["strav", "straven", "stravn", "strvn", "strvnue"],
  strm: ["streme"],
  st: ["str", "strt"],
  smt: ["sumit", "sumitt"],
  ter: ["terr"],
  trce: ["traces"],
  trak: ["tracks", "trk", "trks"],
  trl: ["trails", "trls"],
  trlr: ["trlrs"],
  tunl: ["tunel", "tunls", "tunnels", "tunnl"],
  tpke: ["trnpk", "turnpk"],
  vly: ["vally", "vlly"],
  via: ["vdct", "viadct"],
  vlg: ["vill", "villag", "villg", "villiage"],
  vis: ["vist", "vst", "vsta"],
  way: ["wy"],
};

const suffixes = new Map<string, string>([
  ...STREET_SUFFIX_PAIRS.flatMap(([name, abbreviation]) => [[name, abbreviation], [abbreviation, abbreviation]] as const),
  ...Object.entries(STREET_SUFFIX_VARIANTS).flatMap(([abbreviation, spellings]) => spellings.map(spelling => [spelling, abbreviation] as const)),
]);
const DIRECTION_PAIRS = [
  ["north", "n"], ["south", "s"], ["east", "e"], ["west", "w"],
  ["northeast", "ne"], ["northwest", "nw"], ["southeast", "se"], ["southwest", "sw"],
] as const;
const directions = new Map<string, string>(DIRECTION_PAIRS.flatMap(([name, abbreviation]) => [[name, abbreviation], [abbreviation, abbreviation]]));

const ORDINAL_WORDS = new Map<string, number>([
  ["first", 1], ["second", 2], ["third", 3], ["fourth", 4], ["fifth", 5], ["sixth", 6], ["seventh", 7], ["eighth", 8], ["ninth", 9],
  ["tenth", 10], ["eleventh", 11], ["twelfth", 12], ["thirteenth", 13], ["fourteenth", 14], ["fifteenth", 15], ["sixteenth", 16],
  ["seventeenth", 17], ["eighteenth", 18], ["nineteenth", 19], ["twentieth", 20], ["thirtieth", 30], ["fortieth", 40],
  ["fiftieth", 50], ["sixtieth", 60], ["seventieth", 70], ["eightieth", 80], ["ninetieth", 90],
]);
const TENS = new Map<string, number>([["twenty", 20], ["thirty", 30], ["forty", 40], ["fifty", 50], ["sixty", 60], ["seventy", 70], ["eighty", 80], ["ninety", 90]]);
function ordinal(value: number): string {
  const teen = value % 100 >= 11 && value % 100 <= 13;
  return `${value}${teen ? "th" : (["th", "st", "nd", "rd"][value % 10] ?? "th")}`;
}

/** Numbered streets compare as digits: "Twenty-First" and "21st" are the same street. */
function ordinals(words: string[]): string[] {
  const result: string[] = [];
  for (let i = 0; i < words.length; i++) {
    const word = words[i] ?? "", next = words[i + 1] ?? "";
    const tens = TENS.get(word), ones = ORDINAL_WORDS.get(next);
    if (tens !== undefined && ones !== undefined && ones < 10) { result.push(ordinal(tens + ones)); i++; continue; }
    const single = ORDINAL_WORDS.get(word);
    result.push(single === undefined ? word : ordinal(single));
  }
  return result;
}

const HIGHWAY_WORDS = new Set(["highway", "highwy", "hiway", "hiwy", "hway", "hwy"]);
const NUMBER = /^\d+[a-z]?$/;
/**
 * Route names compare by their standard form: "US-6", "US 6", "US Route 6" and "US Highway 6" are all "us hwy 6";
 * "County Road 12", "Co Rd 12" and "CR 12" are "co rd 12"; "Interstate 80" and "I-80" are "i 80". State route
 * prefixes such as "N-6" are left alone, because "N" is also a direction; such an address does not match.
 */
function routes(words: string[]): string[] {
  const result: string[] = [];
  for (let i = 0; i < words.length; i++) {
    const word = words[i] ?? "", next = words[i + 1] ?? "", after = words[i + 2] ?? "";
    if (word === "us" && NUMBER.test(next)) { result.push("us", "hwy", next); i++; continue; }
    if (word === "us" && (HIGHWAY_WORDS.has(next) || next === "route" || next === "rte") && NUMBER.test(after)) { result.push("us", "hwy", after); i += 2; continue; }
    if ((word === "interstate" || word === "i") && NUMBER.test(next)) { result.push("i", next); i++; continue; }
    if (word === "cr" && NUMBER.test(next)) { result.push("co", "rd", next); i++; continue; }
    if ((word === "county" || word === "co") && (next === "road" || next === "rd") && NUMBER.test(after)) { result.push("co", "rd", after); i += 2; continue; }
    result.push(HIGHWAY_WORDS.has(word) ? "hwy" : word);
  }
  return result;
}

export function normalizeStreet(value: string): string {
  const words = routes(ordinals(value.toLowerCase().replace(/\./g, "").replace(/-/g, " ").trim().split(/\s+/)));
  let start = 0;
  let end = words.length - 1;
  const first = words[start];
  if (start < end && first && directions.has(first)) { words[start] = directions.get(first) ?? first; start++; }
  const last = words[end];
  if (start < end && last && directions.has(last)) { words[end] = directions.get(last) ?? last; end--; }
  // "Saint" and "St" open a name; "St" never ends one there, so the two spellings are the same name.
  const opening = words[start];
  if (start < end && (opening === "saint" || opening === "st")) words[start] = "st";
  if (start < end && opening === "sainte") words[start] = "ste";
  const suffix = words[end];
  if (start < end && suffix) words[end] = suffixes.get(suffix) ?? suffix;
  return words.join(" ");
}

// USPS Publication 28, Appendix C2, secondary unit designators.
// https://pe.usps.com/text/pub28/28apc_003.htm (retrieved 2026-10-08).
const RANGED_UNITS = new Set(["apartment", "apt", "building", "bldg", "department", "dept", "floor", "fl", "hangar", "hanger", "hngr",
  "key", "lot", "pier", "room", "rm", "slip", "space", "spc", "stop", "suite", "ste", "trailer", "trlr", "unit"]);
const UNRANGED_UNITS = new Set(["basement", "bsmt", "front", "frnt", "lobby", "lbby", "lower", "lowr", "office", "ofc", "penthouse", "ph",
  "rear", "side", "upper", "uppr"]);
const UNIT_IDENTIFIER = /^[a-z0-9]+(?:-[a-z0-9]+)?$/i;
const HOUSE = /^\s*(\d+(?:-\d+)?[a-z]?)(?:\s*(1\/2|½))?\s+(.+)$/i;

/** House numbers compare without spaces or case, with "½" written as "1/2". */
export function normalizeHouseNumber(value: string): string {
  return value.toLowerCase().replace(/½/g, "1/2").replace(/\s+/g, "");
}

/**
 * Splits a street line into its house number and street, dropping an apartment or suite written on the same line
 * ("Apt 4", "#4", "Suite 200", "Rear"). A unit is only recognized after at least two street words, so a street
 * named "Front St" or "Key West Dr" keeps its name.
 */
export function parseStreetLine(line1: string): { houseNumber: string | null; street: string } {
  const house = HOUSE.exec(line1);
  const number = house?.[1], half = house?.[2], rest = house?.[3];
  const words = (rest ?? line1).trim().split(/\s+/);
  let end = words.length;
  const pound = words.findIndex((word, index) => index >= 2 && word.startsWith("#"));
  if (pound >= 0) end = pound;
  else {
    const last = (words[words.length - 1] ?? "").toLowerCase().replace(/\./g, "");
    const designator = (words[words.length - 2] ?? "").toLowerCase().replace(/\./g, "");
    if (words.length >= 4 && RANGED_UNITS.has(designator) && UNIT_IDENTIFIER.test(last)) end = words.length - 2;
    else if (words.length >= 3 && UNRANGED_UNITS.has(last)) end = words.length - 1;
  }
  const street = words.slice(0, end).join(" ").replace(/,\s*$/, "");
  return { houseNumber: number ? normalizeHouseNumber(`${number}${half ?? ""}`) : null, street };
}
