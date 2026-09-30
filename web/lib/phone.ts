const MIN_PHONE_DIGITS = 7;
const PHONE_RUN_CHARACTERS = new Set([" ", "(", ")", "-", ".", "+"]);

export function phoneDigitCount(value: string): number {
  return value.replace(/\D/g, "").length;
}

export function isValidPhone(value: string): boolean {
  return phoneDigitCount(value) >= MIN_PHONE_DIGITS;
}

// Formats the leading ten digits as (xxx) xxx-xxxx while typing. A leading +1 or 1 country code is dropped,
// and text after the number (for example an extension) is kept. Separators are only emitted once a digit
// follows them, so backspace can always delete the last digit. Input that does not start with a number is
// returned as typed so nothing is invented or discarded.
export function formatPhone(input: string): string {
  let text = input.trimStart();
  if (text.startsWith("+1")) text = text.slice(2);
  let runEnd = 0;
  while (runEnd < text.length && (/\d/.test(text.charAt(runEnd)) || PHONE_RUN_CHARACTERS.has(text.charAt(runEnd)))) runEnd++;
  const run = text.slice(0, runEnd);
  let digits = run.replace(/\D/g, "");
  if (!digits) return input;
  // Separators that trail the last digit belong to the number's own punctuation, not to trailing text.
  const lastDigit = run.search(/\d\D*$/);
  let rest = text.slice(lastDigit + 1);
  if ([...rest].every(character => PHONE_RUN_CHARACTERS.has(character))) rest = "";
  if (digits.length > 10 && digits.startsWith("1")) digits = digits.slice(1);
  if (digits.length > 10) {
    rest = `${digits.slice(10)}${rest}`;
    digits = digits.slice(0, 10);
  }
  let formatted = `(${digits.slice(0, 3)}`;
  if (digits.length > 3) formatted += `) ${digits.slice(3, 6)}`;
  if (digits.length > 6) formatted += `-${digits.slice(6)}`;
  return `${formatted}${rest}`;
}

// Stored numbers with exactly ten digits (or eleven with a leading 1) are shown formatted; anything
// else is shown exactly as stored.
export function formatStoredPhone(value: string): string {
  const digits = value.replace(/\D/g, "");
  return digits.length === 10 || (digits.length === 11 && digits.startsWith("1")) ? formatPhone(value) : value;
}
