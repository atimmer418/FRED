/**
 * local-date.ts
 *
 * Calendar dates as local `YYYY-MM-DD` strings, built and read with the local-time Date
 * constructors only. `new Date('2026-11-20')` parses as UTC midnight (the evening before in every
 * US timezone) and `toISOString()` prints the UTC day (tomorrow after about 7pm Eastern); neither
 * is used here, so a date is the same day on the screen as it is in the string. The strings sort
 * and compare lexically, so `a < b` is "a is before b".
 */

export interface LocalDate {
  y: number;
  /** 1 to 12. */
  m: number;
  /** 1 to 31. */
  d: number;
}

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MONTHS_SHORT = MONTHS.map(name => name.slice(0, 3));
const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const WEEKDAYS_SHORT = WEEKDAYS.map(name => name.slice(0, 3));

const YMD_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

const pad2 = (n: number): string => (n < 10 ? '0' : '') + n;

/** `2026-11-20` from its parts. The parts are taken as given: use `addDays` to roll over a month. */
export function ymd(y: number, m: number, d: number): string {
  return `${y}-${pad2(m)}-${pad2(d)}`;
}

/** The parts of a `YYYY-MM-DD` string. Throws on anything else: a bad date here is a programming error, not user input. */
export function parseYmd(value: string): LocalDate {
  const match = YMD_PATTERN.exec(value);
  if (!match) throw new RangeError(`Not a YYYY-MM-DD date: "${value}"`);
  const y = Number(match[1]);
  const m = Number(match[2]);
  const d = Number(match[3]);
  if (m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m)) throw new RangeError(`Not a calendar date: "${value}"`);
  return { y, m, d };
}

/** A Date at local midnight on that day. */
export function toLocalDate(value: string): Date {
  const { y, m, d } = parseYmd(value);
  return new Date(y, m - 1, d);
}

/** The local calendar day a Date falls on. */
export function fromLocalDate(date: Date): string {
  return ymd(date.getFullYear(), date.getMonth() + 1, date.getDate());
}

/** Today, where the device is. `now` is for specs. */
export function todayYmd(now: Date = new Date()): string {
  return fromLocalDate(now);
}

/** `n` days later (or earlier when negative), rolling over months and years. */
export function addDays(value: string, n: number): string {
  const { y, m, d } = parseYmd(value);
  return fromLocalDate(new Date(y, m - 1, d + n));
}

/** How many days the month has; leap years included. */
export function daysInMonth(y: number, m: number): number {
  return new Date(y, m, 0).getDate();
}

/** 0 is Sunday, 6 is Saturday. */
export function dayOfWeek(value: string): number {
  return toLocalDate(value).getDay();
}

export function isWeekend(value: string): boolean {
  const day = dayOfWeek(value);
  return day === 0 || day === 6;
}

/** "Nov 20". */
export function formatMonthDay(value: string): string {
  const { m, d } = parseYmd(value);
  return `${MONTHS_SHORT[m - 1]} ${d}`;
}

/** "Fri, Nov 20", with the year appended ("Fri, Nov 20, 2027") when it is not this year. */
export function formatShort(value: string, today: string = todayYmd()): string {
  const { y } = parseYmd(value);
  const thisYear = parseYmd(today).y;
  return `${WEEKDAYS_SHORT[dayOfWeek(value)]}, ${formatMonthDay(value)}${y === thisYear ? '' : `, ${y}`}`;
}

/** "Mar 14, 1991". */
export function formatLong(value: string): string {
  return `${formatMonthDay(value)}, ${parseYmd(value).y}`;
}

/** "Friday, November 20, 2026": what a screen reader says for a cell. */
export function formatFull(value: string): string {
  const { y, m, d } = parseYmd(value);
  return `${WEEKDAYS[dayOfWeek(value)]}, ${MONTHS[m - 1]} ${d}, ${y}`;
}

/** Whole years lived as of `today`; the birthday itself counts. Negative for a date of birth in the future. */
export function ageOn(dob: string, today: string): number {
  const birth = parseYmd(dob);
  const now = parseYmd(today);
  const beforeBirthday = now.m < birth.m || (now.m === birth.m && now.d < birth.d);
  return now.y - birth.y - (beforeBirthday ? 1 : 0);
}
