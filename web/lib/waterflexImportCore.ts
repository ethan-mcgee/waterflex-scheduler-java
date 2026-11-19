/**
 * Reading WaterFlex's exports: customer accounts with the coordinates WaterFlex geocoded them to, and the reason code
 * catalog. Pure parsing, so the import scripts and their tests share it. Nothing missing is filled in: an account
 * without coordinates keeps null ones, and a row that cannot be read is reported and skipped.
 */

export interface CsvTable { header: string[]; rows: { line: number; values: string[] }[] }

/** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. A blank line is skipped. */
export function readCsv(text: string): CsvTable {
  const records: { line: number; values: string[] }[] = [];
  let values: string[] = [], field = "", quoted = false, line = 1, recordLine = 1, i = 0;
  const source = text.startsWith("﻿") ? text.slice(1) : text;
  const endRecord = () => {
    values.push(field);
    if (!(values.length === 1 && values[0] === "")) records.push({ line: recordLine, values });
    values = []; field = "";
  };
  while (i < source.length) {
    const char = source[i];
    if (quoted) {
      if (char === '"' && source[i + 1] === '"') { field += '"'; i += 2; continue; }
      if (char === '"') { quoted = false; i++; continue; }
      if (char === "\n") line++;
      field += char; i++; continue;
    }
    if (char === '"' && field === "") { quoted = true; i++; continue; }
    if (char === ",") { values.push(field); field = ""; i++; continue; }
    if (char === "\r" && source[i + 1] === "\n") { i++; continue; }
    if (char === "\n" || char === "\r") { endRecord(); line++; recordLine = line; i++; continue; }
    field += char; i++;
  }
  if (quoted) throw new Error(`The CSV has an unclosed quote starting on line ${recordLine}`);
  if (field !== "" || values.length > 0) endRecord();
  const [first, ...rows] = records;
  if (first === undefined) throw new Error("The CSV is empty");
  return { header: first.values.map(name => name.trim().toLowerCase()), rows };
}

export interface ImportProblem { line: number; message: string }

/**
 * Finds a column by any of its spellings (the first being the usual one). A required column that is missing is added to
 * `missing`, so the caller can name every missing column at once.
 */
function locate(header: readonly string[], names: readonly string[], required: boolean, missing: string[]): number | null {
  const index = header.findIndex(name => names.includes(name));
  if (index >= 0) return index;
  if (required) missing.push(names.join(" or "));
  return null;
}

function refuseMissing(missing: readonly string[]) {
  if (missing.length > 0) throw new Error(`The CSV is missing the column${missing.length > 1 ? "s" : ""} ${missing.join(", ")}`);
}

const cell = (values: readonly string[], index: number | null): string | null => {
  if (index === null) return null;
  const value = values[index]?.trim() ?? "";
  return value === "" ? null : value;
};

export interface AccountRow {
  accountNumber: string;
  name: string | null;
  phone: string | null;
  email: string | null;
  line1: string;
  line2: string | null;
  city: string;
  state: string;
  postalCode: string;
  /** WaterFlex's coordinates; both null when the export has none. */
  lat: number | null;
  lng: number | null;
}

/**
 * Accounts from WaterFlex's account export. Blank coordinates (or WaterFlex's 0, 0 placeholder) import as missing; a
 * coordinate that is not a number, or only one of the two, is a problem and the row is skipped. A repeated account
 * number keeps its first row.
 */
export function parseAccounts(text: string): { accounts: AccountRow[]; problems: ImportProblem[]; missingCoordinates: number } {
  const table = readCsv(text);
  const missing: string[] = [];
  const column = (names: readonly string[], required = true) => locate(table.header, names, required, missing);
  // WaterFlex's export spells latitude "lattitude".
  const at = { accountNumber: column(["account_number"]), houseNumber: column(["house_number"]), street: column(["street"]),
    line2: column(["line2", "unit"], false), city: column(["city"]), state: column(["state"]), postalCode: column(["postal_code", "zip"]),
    lat: column(["lattitude", "latitude"]), lng: column(["longitude"]), name: column(["name", "customer_name"], false),
    phone: column(["phone"], false), email: column(["email"], false) };
  refuseMissing(missing);
  const accounts: AccountRow[] = [], problems: ImportProblem[] = [], seen = new Set<string>();
  let missingCoordinates = 0;
  for (const { line, values } of table.rows) {
    const get = (key: keyof typeof at) => cell(values, at[key]);
    const accountNumber = get("accountNumber"), street = get("street"), city = get("city"), state = get("state"), postalCode = get("postalCode");
    if (accountNumber === null) { problems.push({ line, message: "No account number" }); continue; }
    if (seen.has(accountNumber)) { problems.push({ line, message: `Account ${accountNumber} appears more than once; the first row is kept` }); continue; }
    if (street === null || city === null || state === null || postalCode === null) {
      problems.push({ line, message: `Account ${accountNumber} needs a street, city, state and postal code` }); continue;
    }
    const latText = get("lat"), lngText = get("lng");
    let lat: number | null = null, lng: number | null = null;
    if (latText !== null || lngText !== null) {
      const parsedLat = latText === null ? Number.NaN : Number(latText), parsedLng = lngText === null ? Number.NaN : Number(lngText);
      if (!Number.isFinite(parsedLat) || !Number.isFinite(parsedLng) || Math.abs(parsedLat) > 90 || Math.abs(parsedLng) > 180) {
        problems.push({ line, message: `Account ${accountNumber} has coordinates that cannot be read` }); continue;
      }
      if (!(parsedLat === 0 && parsedLng === 0)) { lat = parsedLat; lng = parsedLng; }
    }
    if (lat === null) missingCoordinates++;
    seen.add(accountNumber);
    const houseNumber = get("houseNumber");
    accounts.push({ accountNumber, name: get("name"), phone: get("phone"), email: get("email"),
      line1: houseNumber === null ? street : `${houseNumber} ${street}`, line2: get("line2"), city, state: state.toUpperCase(), postalCode, lat, lng });
  }
  return { accounts, problems, missingCoordinates };
}

export interface ReasonCodeRow {
  code: string;
  name: string;
  estDurationMin: number;
  /** Null when the file does not say: a new code takes the catalog's default and an existing one keeps its own. */
  bufferPct: number | null;
}

/** Reason codes: each code with its label, its minutes (a whole number above zero) and optionally its buffer. */
export function parseReasonCodes(text: string): { codes: ReasonCodeRow[]; problems: ImportProblem[] } {
  const table = readCsv(text);
  const missing: string[] = [];
  const column = (names: readonly string[], required = true) => locate(table.header, names, required, missing);
  const at = { code: column(["code", "complaint_c"]), name: column(["label", "name", "description"]),
    minutes: column(["minutes", "est_duration_min"]), buffer: column(["buffer_pct", "buffer"], false) };
  refuseMissing(missing);
  const codes: ReasonCodeRow[] = [], problems: ImportProblem[] = [], seen = new Set<string>();
  for (const { line, values } of table.rows) {
    const code = cell(values, at.code), name = cell(values, at.name), minutesText = cell(values, at.minutes), bufferText = cell(values, at.buffer);
    if (code === null || name === null) { problems.push({ line, message: "A reason code needs its code and label" }); continue; }
    if (seen.has(code)) { problems.push({ line, message: `Reason code ${code} appears more than once; the first row is kept` }); continue; }
    const minutes = minutesText === null ? Number.NaN : Number(minutesText);
    if (!Number.isSafeInteger(minutes) || minutes <= 0) { problems.push({ line, message: `Reason code ${code} needs its minutes as a whole number above zero` }); continue; }
    let bufferPct: number | null = null;
    if (bufferText !== null) {
      const buffer = Number(bufferText.replace(/%$/, ""));
      const fraction = bufferText.endsWith("%") ? buffer / 100 : buffer;
      if (!Number.isFinite(fraction) || fraction < 0 || fraction > 1) { problems.push({ line, message: `Reason code ${code} has a buffer that cannot be read` }); continue; }
      bufferPct = fraction;
    }
    seen.add(code);
    codes.push({ code, name, estDurationMin: minutes, bufferPct });
  }
  return { codes, problems };
}

/** A customer's recurring service, as the series import reads it. Codes are WaterFlex reason codes, first one first. */
export interface RecurringSeriesRow {
  accountNumber: string;
  reasonCodes: string[];
  workOrderType: string | null;
  intervalUnit: "WEEK" | "MONTH";
  intervalCount: number;
  startDate: string;
  lastVisitDate: string | null;
}

/** "2026-11-04" or "11/4/2026" as "2026-11-04"; null for anything else, including dates that do not exist. */
export function readDate(value: string): string | null {
  const iso = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value), us = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(value);
  const parts = iso ? [iso[1], iso[2], iso[3]] : us ? [us[3], us[1], us[2]] : null;
  if (parts === null) return null;
  const [year, month, day] = parts.map(Number);
  if (year === undefined || month === undefined || day === undefined) return null;
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return date.toISOString().slice(0, 10);
}

/**
 * Reads recurring series: `account_number`, `reason_code` (or `complaint_1_c`), optionally `reason_code_2` and
 * `reason_code_3` (or `complaint_2_c`, `complaint_3_c`), `work_order_type`, `every` with `unit` (weeks or months),
 * `start_date` (the first due date) and optionally `last_visit_date`. Rows that cannot be read are reported and
 * skipped; one account and first code may appear once.
 */
export function parseRecurringSeries(text: string): { series: RecurringSeriesRow[]; problems: ImportProblem[] } {
  const table = readCsv(text);
  const missing: string[] = [];
  const column = (names: readonly string[], required = true) => locate(table.header, names, required, missing);
  const at = { account: column(["account_number"]), first: column(["reason_code", "complaint_1_c"]),
    second: column(["reason_code_2", "complaint_2_c"], false), third: column(["reason_code_3", "complaint_3_c"], false),
    type: column(["work_order_type"], false), every: column(["every", "interval"]), unit: column(["unit", "interval_unit"]),
    start: column(["start_date"]), last: column(["last_visit_date", "last_visit"], false) };
  refuseMissing(missing);
  const series: RecurringSeriesRow[] = [], problems: ImportProblem[] = [], seen = new Set<string>();
  for (const { line, values } of table.rows) {
    const accountNumber = cell(values, at.account), first = cell(values, at.first);
    if (accountNumber === null || first === null) { problems.push({ line, message: "A series needs its account number and reason code" }); continue; }
    const reasonCodes = [first, cell(values, at.second), cell(values, at.third)].filter((code): code is string => code !== null);
    if (new Set(reasonCodes).size !== reasonCodes.length) { problems.push({ line, message: `Account ${accountNumber} names a reason code twice` }); continue; }
    const key = `${accountNumber}|${first}`;
    if (seen.has(key)) { problems.push({ line, message: `Account ${accountNumber} has more than one ${first} series; the first row is kept` }); continue; }
    const unitText = cell(values, at.unit)?.toLowerCase() ?? "";
    const intervalUnit = /^weeks?$/.test(unitText) ? "WEEK" : /^months?$/.test(unitText) ? "MONTH" : null;
    const intervalCount = Number(cell(values, at.every) ?? Number.NaN);
    const limit = intervalUnit === "WEEK" ? 52 : 24;
    if (intervalUnit === null || !Number.isSafeInteger(intervalCount) || intervalCount < 1 || intervalCount > limit) {
      problems.push({ line, message: `Account ${accountNumber} needs an interval of 1 to 52 weeks or 1 to 24 months` }); continue;
    }
    const startText = cell(values, at.start), lastText = cell(values, at.last);
    const startDate = startText === null ? null : readDate(startText);
    const lastVisitDate = lastText === null ? null : readDate(lastText);
    if (startDate === null) { problems.push({ line, message: `Account ${accountNumber} needs a start date` }); continue; }
    if (lastText !== null && lastVisitDate === null) { problems.push({ line, message: `Account ${accountNumber} has a last visit date that cannot be read` }); continue; }
    seen.add(key);
    series.push({ accountNumber, reasonCodes, workOrderType: cell(values, at.type), intervalUnit, intervalCount, startDate, lastVisitDate });
  }
  return { series, problems };
}
