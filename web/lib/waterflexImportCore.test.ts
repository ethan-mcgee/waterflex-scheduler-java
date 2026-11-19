import { test } from "node:test";
import assert from "node:assert/strict";
import { parseAccounts, parseReasonCodes, parseRecurringSeries, readCsv, readDate } from "./waterflexImportCore";

test("CSV fields may be quoted, hold commas, quotes and line breaks, and blank lines are skipped", () => {
  const table = readCsv('﻿A,B\r\n"1, 2","say ""hi"""\r\n\r\n"two\nlines",x\n');
  assert.deepEqual(table.header, ["a", "b"]);
  assert.deepEqual(table.rows, [{ line: 2, values: ["1, 2", 'say "hi"'] }, { line: 4, values: ["two\nlines", "x"] }]);
  assert.throws(() => readCsv('a\n"open'), /unclosed quote/);
  assert.throws(() => readCsv(""), /empty/);
});

const ACCOUNT_HEADER = "account_number,house_number,street,city,state,postal_code,lattitude,longitude,name,phone";

test("accounts take WaterFlex's coordinates, and an account without them imports with none rather than made-up ones", () => {
  const parsed = parseAccounts([ACCOUNT_HEADER,
    "1001,12,Main St,Paso Robles,ca,93446,35.6266,-120.6910,Ana Ruiz,805-555-0100",
    "1002,,Rural Route 4,Templeton,CA,93465,,,,",
    "1003,7,Oak Ave,Atascadero,CA,93422,0,0,,"].join("\n"));
  assert.deepEqual(parsed.problems, []);
  assert.equal(parsed.missingCoordinates, 2);
  assert.deepEqual(parsed.accounts[0], { accountNumber: "1001", name: "Ana Ruiz", phone: "805-555-0100", email: null, line1: "12 Main St",
    line2: null, city: "Paso Robles", state: "CA", postalCode: "93446", lat: 35.6266, lng: -120.691 });
  assert.equal(parsed.accounts[1]?.line1, "Rural Route 4");
  assert.deepEqual([parsed.accounts[1]?.lat, parsed.accounts[1]?.lng], [null, null]);
  // WaterFlex's 0, 0 placeholder is not a place.
  assert.deepEqual([parsed.accounts[2]?.lat, parsed.accounts[2]?.lng], [null, null]);
});

test("unreadable, half-missing or out-of-range coordinates, missing address parts and repeats are reported and skipped", () => {
  const parsed = parseAccounts([ACCOUNT_HEADER,
    "2001,1,A St,X,CA,93446,abc,-120,,",
    "2002,1,A St,X,CA,93446,35.1,,,",
    "2003,1,A St,X,CA,93446,95,-120,,",
    "2004,1,,X,CA,93446,35,-120,,",
    ",1,A St,X,CA,93446,35,-120,,",
    "2005,1,A St,X,CA,93446,35,-120,,",
    "2005,2,B St,X,CA,93446,35,-120,,"].join("\n"));
  assert.deepEqual(parsed.accounts.map(account => account.accountNumber), ["2005"]);
  assert.deepEqual(parsed.problems.map(problem => problem.line), [2, 3, 4, 5, 6, 8]);
  assert.match(parsed.problems[5]?.message ?? "", /more than once/);
});

test("an account export without a required column is refused as a whole", () => {
  assert.throws(() => parseAccounts("account_number,street,city,state,lattitude,longitude\n1,A,B,C,1,2"), /house_number.*postal_code or zip/);
});

test("reason codes need a code, a label and whole minutes; a buffer may be a fraction or a percentage", () => {
  const parsed = parseReasonCodes(["code,label,minutes,buffer_pct", "LEAK,Leaking pipe at the tank,45,0.1", "SALT,Salt bridge,30,",
    "PRES,Low pressure,20,15%", "BAD,Bad minutes,0,", "HALF,Half minutes,12.5,", "BUF,Bad buffer,30,2", "LEAK,Again,10,"].join("\n"));
  assert.deepEqual(parsed.codes, [
    { code: "LEAK", name: "Leaking pipe at the tank", estDurationMin: 45, bufferPct: 0.1 },
    { code: "SALT", name: "Salt bridge", estDurationMin: 30, bufferPct: null },
    { code: "PRES", name: "Low pressure", estDurationMin: 20, bufferPct: 0.15 },
  ]);
  assert.deepEqual(parsed.problems.map(problem => problem.line), [5, 6, 7, 8]);
});

test("recurring series are read with their codes, interval and dates, and unreadable rows are reported", () => {
  const { series, problems } = parseRecurringSeries([
    "account_number,complaint_1_c,complaint_2_c,work_order_type,every,unit,start_date,last_visit_date",
    "100482,FILTER,SALT,Maintenance,3,months,2026-01-05,9/15/2026",
    "100483,FILTER,,,6,Weeks,1/5/2026,",
    "100484,FILTER,FILTER,,3,months,2026-01-05,",
    "100485,FILTER,,,0,months,2026-01-05,",
    "100486,FILTER,,,3,years,2026-01-05,",
    "100487,FILTER,,,3,months,2026-02-30,",
    "100488,FILTER,,,3,months,2026-01-05,yesterday",
    "100482,FILTER,,,1,month,2026-01-05,",
  ].join("\n"));
  assert.deepEqual(series, [
    { accountNumber: "100482", reasonCodes: ["FILTER", "SALT"], workOrderType: "Maintenance", intervalUnit: "MONTH", intervalCount: 3, startDate: "2026-01-05", lastVisitDate: "2026-09-15" },
    { accountNumber: "100483", reasonCodes: ["FILTER"], workOrderType: null, intervalUnit: "WEEK", intervalCount: 6, startDate: "2026-01-05", lastVisitDate: null },
  ]);
  assert.deepEqual(problems.map(problem => problem.line), [4, 5, 6, 7, 8, 9]);
  assert.throws(() => parseRecurringSeries("account_number,reason_code\n1,A"), /every/);
});

test("dates are read as ISO or US dates and refused when the day does not exist", () => {
  assert.equal(readDate("2026-11-04"), "2026-11-04");
  assert.equal(readDate("11/4/2026"), "2026-11-04");
  assert.equal(readDate("2026-02-29"), null);
  assert.equal(readDate("2028-02-29"), "2028-02-29");
  assert.equal(readDate("Nov 4"), null);
});
