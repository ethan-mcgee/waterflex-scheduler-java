import { type Page } from "@playwright/test";

export const locationFixture = { status: "MATCHED", candidates: [{ lat: 41.23, lng: -96.18, precision: "ROOFTOP" }],
  serviceArea: { circles: [{ lat: 41.23, lng: -96.18, radiusMi: 65 }], bounds: { south: 40, north: 43, west: -98, east: -94 } } };
export async function mockBookingLocation(page: Page) {
  await page.route("**/api/book/location", route => route.fulfill({ json: locationFixture }));
  await page.route("http://localhost:8083/omaha.json", route => route.fulfill({ json: { bounds: [-98, 40, -94, 43] } }));
  await page.route("http://localhost:8083/omaha/**/*.mvt", route => route.fulfill({ status: 200, body: "" }));
}
