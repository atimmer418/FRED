/**
 * business-days.ts
 *
 * Weekday arithmetic on local `YYYY-MM-DD` strings. A business day is Monday to Friday: there is
 * no bank-holiday table on purpose (the backend has none either, and the two must agree on when a
 * transfer lands). Shared by the date sheet's subline and the page echo under each date field.
 */
import { addDays, daysInMonth, formatMonthDay, isWeekend, ymd } from './local-date';

/**
 * `n` business days after the date (before it when negative). Weekends are skipped, not counted:
 * one business day after a Friday is the Monday. Zero gives the date back as it is, weekend or not.
 */
export function addBusinessDays(value: string, n: number): string {
  const direction = n < 0 ? -1 : 1;
  let remaining = Math.abs(Math.trunc(n));
  let current = value;
  while (remaining > 0) {
    current = addDays(current, direction);
    if (!isWeekend(current)) remaining--;
  }
  return current;
}

/** The date itself when it is a business day, else the Monday after it. */
export function nextBusinessDay(value: string): string {
  let current = value;
  while (isWeekend(current)) current = addDays(current, 1);
  return current;
}

/** The last weekday of the month. */
export function lastBusinessDayOfMonth(y: number, m: number): string {
  let current = ymd(y, m, daysInMonth(y, m));
  while (isWeekend(current)) current = addDays(current, -1);
  return current;
}

/**
 * "Nov 23 to Nov 25": when the trades for a transfer on that date land, one to three business
 * days after it.
 */
export function tradeWindow(value: string): string {
  return `${formatMonthDay(addBusinessDays(value, 1))} to ${formatMonthDay(addBusinessDays(value, 3))}`;
}
