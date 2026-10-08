import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { geocodeAddress, GeocoderError, searchAddress } from "./geocode";
import { confirmedHomePin } from "./technicianHomePin";
import { NextRequest } from "next/server";
import { POST as lookupRoute } from "../app/api/technicians/geocode/route";
import { z } from "zod";
import { normalizeStreet, parseStreetLine, STREET_SUFFIX_PAIRS, STREET_SUFFIX_VARIANTS } from "./streetNormalization";

test("all 206 USPS primary suffix pairs work only in suffix position", () => {
  assert.equal(STREET_SUFFIX_PAIRS.length, 206);
  for (const [name, abbreviation] of STREET_SUFFIX_PAIRS) {
    assert.equal(normalizeStreet(`Example ${name}`), normalizeStreet(`Example ${abbreviation}`));
    assert.equal(normalizeStreet(`N Example ${name} SE`), normalizeStreet(`North Example ${abbreviation}. Southeast`));
  }
  for (const [short, full] of [["St", "Street"], ["Cir", "Circle"], ["Plz", "Plaza"], ["Ter", "Terrace"], ["Trl", "Trail"], ["Hwy", "Highway"]] as const) {
    assert.equal(normalizeStreet(`Main ${short}`), `main ${short.toLowerCase()}`);
    assert.equal(normalizeStreet(`Main ${short}`), normalizeStreet(`Main ${full}`));
  }
  for (const [short, full] of [["N", "North"], ["S", "South"], ["E", "East"], ["W", "West"], ["NE", "Northeast"], ["NW", "Northwest"], ["SE", "Southeast"], ["SW", "Southwest"]] as const) {
    assert.equal(normalizeStreet(`${short}. Main Rd.`), normalizeStreet(`${full} Main Road`));
    assert.equal(normalizeStreet(`Main Rd. ${short}.`), normalizeStreet(`Main Road ${full}`));
  }
  assert.equal(normalizeStreet("  St Charles Rd.  "), "st charles rd");
  assert.equal(normalizeStreet("Circle Dr"), "circle dr");
  assert.equal(normalizeStreet("St Charles Rd"), normalizeStreet("Saint Charles Rd"));
  assert.notEqual(normalizeStreet("Circle Dr"), normalizeStreet("Cir Dr"));
  assert.notEqual(normalizeStreet("Main St"), normalizeStreet("Main Cir"));
  assert.notEqual(normalizeStreet("St"), normalizeStreet("Street"));
  assert.equal(normalizeStreet("N"), "n");
});

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

test("Omaha direction and plaza spellings agree, with strict ZIP and road checks", async () => {
  globalThis.fetch = async () => Response.json([{ lat: "41.23", lon: "-96.18", address: {
    house_number: "2825", road: "South 170th Plaza", city: "Omaha", state: "Nebraska", postcode: "68130", country_code: "us",
  } }]);
  for (const line1 of ["2825 S 170th Plz", "2825 S 170th Plaza", "2825 South 170th Plaza", " 2825  s.  170TH  plz. "]) {
    assert.equal((await geocodeAddress({ line1, city: "Omaha", state: "NE", postalCode: "68130" }))?.precision, "ROOFTOP");
    assert.equal(await geocodeAddress({ line1, city: "Omaha", state: "NE", postalCode: "68103" }), null);
  }
  for (const line1 of ["2825 N 170th Plz", "2825 170th Plz", "2826 S 170th Plz", "2825 S 170th Cir", "2825 S 171st Plz"]) {
    assert.equal(await geocodeAddress({ line1, city: "Omaha", state: "NE", postalCode: "68130" }), null);
  }
});

test("invalid coordinates and null address data remain malformed", async () => {
  for (const changed of [{ address: null }, { lat: null }, { lat: "NaN" }, { lon: "181" }, { boundingbox: ["42", "41", "-97", "-96"] }]) {
    globalThis.fetch = async () => Response.json([{ ...street, ...changed }]);
    await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "malformed");
  }
});
const address = { line1: "2125 Crest Ridge Dr", city: "Papillion", state: "NE", postalCode: "68133" };
const street = { lat: "41.1637462", lon: "-96.0079032", address: {
  road: "Crest Ridge Drive", town: "Papillion", postcode: "68133", country_code: "us",
}, boundingbox: ["41.1623576", "41.1654397", "-96.0109311", "-96.0046578"] };

test("retries without state and labels the Papillion street result approximate", async () => {
  const queries: URL[] = [];
  globalThis.fetch = async input => {
    const url = new URL(input.toString()); queries.push(url);
    return Response.json(url.searchParams.has("state") ? [] : [street]);
  };
  const results = await searchAddress(address);
  assert.equal(queries.length, 4);
  assert.equal(queries[0]?.searchParams.get("state"), "NE");
  assert.equal(queries[1]?.searchParams.has("state"), false);
  assert.equal(queries[2]?.searchParams.has("city"), false);
  assert.equal(queries[2]?.searchParams.get("street"), "2125 Crest Ridge Dr");
  assert.equal(queries[3]?.searchParams.get("street"), "Crest Ridge Dr");
  assert.deepEqual(results, [{ lat: 41.1637462, lng: -96.0079032, precision: "APPROXIMATE",
    bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 } }]);
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, results, false), null);
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, results, true)?.precision, "APPROXIMATE");
  assert.equal(confirmedHomePin({ lat: 41.17, lng: -96.008 }, results, true), null);
});

test("rejects conflicting address details", async () => {
  for (const changed of [
    { address: { ...street.address, postcode: "68130" } },
    { address: { ...street.address, postcode: undefined, town: "Omaha" } },
    { address: { ...street.address, state: "Iowa" } },
    { address: { ...street.address, country_code: "ca" } },
    { address: { ...street.address, house_number: "2126" } },
    { address: { ...street.address, road: "Other Drive" } },
  ]) {
    globalThis.fetch = async () => Response.json([{ ...street, ...changed }]);
    assert.deepEqual(await searchAddress(address), []);
  }
});

test("rejects a street response without usable bounds as malformed", async () => {
  globalThis.fetch = async () => Response.json([{ ...street, boundingbox: undefined }]);
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "malformed");
});

test("accepts an exact matching house without manual confirmation", async () => {
  globalThis.fetch = async () => Response.json([{ ...street, address: { ...street.address, house_number: "2125", state: "Nebraska" } }]);
  const result = await geocodeAddress(address);
  assert.deepEqual(result, { lat: 41.1637462, lng: -96.0079032, precision: "ROOFTOP",
    bounds: { south: 41.1623576, north: 41.1654397, west: -96.0109311, east: -96.0046578 } });
  assert.equal(confirmedHomePin({ lat: 41.1637462, lng: -96.0079032 }, result ? [result] : [], false)?.precision, "ROOFTOP");
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, result ? [result] : [], false), null);
  assert.equal(confirmedHomePin({ lat: 41.164, lng: -96.008 }, result ? [result] : [], true)?.precision, "ROOFTOP");
  assert.equal(confirmedHomePin({ lat: 41.17, lng: -96.008 }, result ? [result] : [], true), null);
});

test("separates transport, timeout, malformed response, and genuine miss", async () => {
  globalThis.fetch = async () => { throw new Error("offline"); };
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "unavailable");
  globalThis.fetch = async () => { throw new DOMException("timeout", "TimeoutError"); };
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "timeout");
  globalThis.fetch = async () => Response.json({ results: null });
  await assert.rejects(searchAddress(address), error => error instanceof GeocoderError && error.kind === "malformed");
  globalThis.fetch = async () => Response.json([]);
  assert.deepEqual(await searchAddress(address), []);
});

test("technician lookup API reports misses and geocoder failures separately", async () => {
  const request = () => new NextRequest("http://localhost/api/technicians/geocode", { method: "POST", body: JSON.stringify(address) });
  for (const [reply, expectedStatus] of [
    [async () => Response.json([]), 404],
    [async () => { throw new DOMException("timeout", "TimeoutError"); }, 504],
    [async () => { throw new Error("offline"); }, 503],
    [async () => Response.json({ candidates: null }), 502],
  ] as const) {
    globalThis.fetch = reply;
    const response = await lookupRoute(request());
    assert.equal(response.status, expectedStatus);
    const body: unknown = await response.json();
    assert.equal(z.object({ error: z.string() }).safeParse(body).success, true);
  }
});


test("street abbreviations never normalize locality names", async () => {
  globalThis.fetch = async () => Response.json([{ ...street, address: { ...street.address, postcode: undefined, town: "Circle" } }]);
  assert.deepEqual(await searchAddress({ ...address, city: "Cir" }), []);
});

test("every USPS common spelling of a street type matches its standard abbreviation", () => {
  let spellings = 0;
  for (const [abbreviation, variants] of Object.entries(STREET_SUFFIX_VARIANTS)) for (const variant of variants) {
    assert.equal(normalizeStreet(`Example ${variant}`), `example ${abbreviation}`, variant);
    spellings++;
  }
  assert.equal(spellings, 161);
  assert.equal(normalizeStreet("Main Str"), normalizeStreet("Main Street"));
  assert.equal(normalizeStreet("Dodge Av"), normalizeStreet("Dodge Avenue"));
  assert.equal(normalizeStreet("Elm Mdw"), normalizeStreet("Elm Meadow"));
  assert.notEqual(normalizeStreet("Elm Mdw"), normalizeStreet("Elm Meadows"));
});

test("numbered streets, highways and Saint compare by meaning", () => {
  for (const [written, standard] of [
    ["N Seventy-Second St", "North 72nd Street"], ["First Ave", "1st Avenue"], ["Twenty First St", "21st St"],
    ["Eleventh St", "11th St"], ["Ninety-Third Ave", "93rd Ave"],
    ["US-6", "US Highway 6"], ["US 6", "U.S. Hwy 6"], ["US Route 6", "US Hwy 6"], ["Hwy 50", "Highway 50"],
    ["County Road 12", "CR 12"], ["Co Rd 12", "County Rd 12"], ["I-80", "Interstate 80"],
    ["Saint Marys Ave", "St. Marys Avenue"], ["Sainte Genevieve Dr", "Ste Genevieve Dr"],
  ] as const) assert.equal(normalizeStreet(written), normalizeStreet(standard), written);
  // Spelled numbers of one hundred and above are not converted, so such a street must be written the same way.
  assert.notEqual(normalizeStreet("One Hundredth St"), normalizeStreet("100th St"));
  assert.notEqual(normalizeStreet("72nd St"), normalizeStreet("72 St"));
  assert.notEqual(normalizeStreet("US 6"), normalizeStreet("US 60"));
  assert.notEqual(normalizeStreet("County Road 12"), normalizeStreet("State Road 12"));
  assert.notEqual(normalizeStreet("N-6"), normalizeStreet("US Highway 6"));
});

test("street lines lose their unit but keep their house number, halves and ranges", () => {
  for (const [line1, houseNumber, street] of [
    ["123 Main St Apt 4", "123", "Main St"], ["123 Main St Apt. 4B", "123", "Main St"], ["123 Main St #4", "123", "Main St"],
    ["123 Main St # 4", "123", "Main St"], ["123 Main St Suite 200", "123", "Main St"], ["123 Main St, Ste 200", "123", "Main St"],
    ["123 Main St Unit B-2", "123", "Main St"], ["123 Main St Rear", "123", "Main St"], ["123 1/2 Main St", "1231/2", "Main St"],
    ["123½ Main St", "1231/2", "Main St"], ["123-125 Main St", "123-125", "Main St"], ["12B Main St", "12b", "Main St"],
    ["100 Front St", "100", "Front St"], ["5 Key West Dr", "5", "Key West Dr"], ["9 Oak Trailer", "9", "Oak Trailer"],
    ["7 Broadway Apt 4", "7", "Broadway Apt 4"], ["Main St", null, "Main St"],
  ] as const) assert.deepEqual(parseStreetLine(line1), { houseNumber, street }, line1);
});

test("ZIP, house number and street decide; a mailing city is accepted when the ZIP agrees", async () => {
  const ralston = { ...street, address: { ...street.address, house_number: "2125", town: "Ralston", postcode: "68133-4410" } };
  globalThis.fetch = async () => Response.json([ralston]);
  for (const line1 of ["2125 Crest Ridge Dr", "2125 Crest Ridge Drive Apt 4", "2125 Crest Ridge Dr #4"])
    assert.equal((await geocodeAddress({ ...address, line1, city: "Papillion", postalCode: "68133" }))?.precision, "ROOFTOP", line1);
  assert.equal((await geocodeAddress({ ...address, postalCode: "68133-1234" }))?.precision, "ROOFTOP");
  assert.equal(await geocodeAddress({ ...address, line1: "2127 Crest Ridge Dr" }), null);
  assert.equal(await geocodeAddress({ ...address, postalCode: "68046" }), null);
  globalThis.fetch = async () => Response.json([{ ...ralston, address: { ...ralston.address, postcode: undefined } }]);
  assert.equal(await geocodeAddress(address), null);
  globalThis.fetch = async () => Response.json([{ ...ralston, address: { ...ralston.address, house_number: "123 1/2", road: "Main Street" } }]);
  assert.equal((await geocodeAddress({ ...address, line1: "123½ Main St" }))?.precision, "ROOFTOP");
  assert.equal(await geocodeAddress({ ...address, line1: "123 Main St" }), null);
});
