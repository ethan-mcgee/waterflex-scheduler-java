import { test } from "node:test";
import assert from "node:assert/strict";
import { colorOf, serviceColors } from "./serviceColors";

test("services get colors in code order, so a service keeps its color whatever order it is loaded in", () => {
  const colors = serviceColors(["WATER_TEST", "SOFTENER_SERVICE", "RO_INSTALL"]);
  const again = serviceColors(["RO_INSTALL", "WATER_TEST", "SOFTENER_SERVICE"]);
  for (const code of ["WATER_TEST", "SOFTENER_SERVICE", "RO_INSTALL"]) assert.deepEqual(colors.get(code), again.get(code));
  assert.equal(colorOf(colors, "RO_INSTALL").color, "#2563eb", "The first code takes the first color");
  assert.notEqual(colorOf(colors, "RO_INSTALL").color, colorOf(colors, "SOFTENER_SERVICE").color);
  assert.equal(colorOf(colors, "RO_INSTALL").drive, "rgba(37, 99, 235, 0.3)", "The drive band is the service color, lighter");
});

test("past the palette the colors repeat, and duplicate or unknown services are refused", () => {
  const codes = Array.from({ length: 11 }, (_, index) => `S${String(index).padStart(2, "0")}`);
  const colors = serviceColors(codes);
  assert.equal(colorOf(colors, "S10").color, colorOf(colors, "S00").color);
  assert.throws(() => serviceColors(["A", "A"]), /unique/);
  assert.throws(() => colorOf(colors, "MISSING"), /has no schedule color/);
});
