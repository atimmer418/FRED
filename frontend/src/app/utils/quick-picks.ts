/**
 * quick-picks.ts
 *
 * The chips above the date sheet's grid in upcoming mode: up to four dates a person is likely to
 * want for a transfer, from the pay frequency and today. Pure, so the field pages compute them and
 * pass them into the sheet.
 */
import { InvestmentFrequencyKey } from './investment-frequency.utils';
import { lastBusinessDayOfMonth, nextBusinessDay } from './business-days';
import { addDays, dayOfWeek, parseYmd, ymd } from './local-date';

export interface QuickPick {
  /** The chip's first line: "Next Fri", "1st", "Month end". */
  label: string;
  /** YYYY-MM-DD. */
  date: string;
}

export const QUICK_PICKS_MAX = 4;

const MONDAY = 1;
const FRIDAY = 5;

/** The first `weekday` strictly after the date. */
function nextWeekday(from: string, weekday: number): string {
  let current = addDays(from, 1);
  while (dayOfWeek(current) !== weekday) current = addDays(current, 1);
  return current;
}

/** The 1st of the month after the one the date is in. */
function firstOfNextMonth(from: string): string {
  const { y, m } = parseYmd(from);
  return m === 12 ? ymd(y + 1, 1, 1) : ymd(y, m + 1, 1);
}

/** The next 15th strictly after the date. */
function nextFifteenth(from: string): string {
  const { y, m, d } = parseYmd(from);
  if (d < 15) return ymd(y, m, 15);
  return m === 12 ? ymd(y + 1, 1, 15) : ymd(y, m + 1, 15);
}

/** The last business day of this month, or of next month once this month's has passed. */
function nextMonthEnd(from: string): string {
  const { y, m } = parseYmd(from);
  const thisMonth = lastBusinessDayOfMonth(y, m);
  if (thisMonth > from) return thisMonth;
  return m === 12 ? lastBusinessDayOfMonth(y + 1, 1) : lastBusinessDayOfMonth(y, m + 1);
}

/**
 * The quick picks for a frequency: WEEKLY and BIWEEKLY lead with Next Mon and Next Fri, MONTHLY
 * with the 1st, the 15th and the month end (its last business day), and SEMI_MONTHLY has none
 * (the backend owns the 1st and 15th, so the field is locked). The list is padded to four from
 * Tomorrow, Next Fri, the 1st and the 15th, kept to dates on or after `min`, de-duplicated by date
 * (the first label for a date wins) and sorted ascending.
 *
 * With `businessDaysOnly` every pick moves forward onto the next business day, as the sheet will
 * not let a weekend be chosen. "Tomorrow" is the one label that would then lie, so it is dropped
 * when it would have to move; the chip that replaces it says the weekday it lands on.
 */
export function quickPicks(frequency: InvestmentFrequencyKey, min: string, today: string, businessDaysOnly = false): QuickPick[] {
  if (frequency === 'SEMI_MONTHLY') return [];

  const nextMon = nextWeekday(today, MONDAY);
  const nextFri = nextWeekday(today, FRIDAY);
  const first = firstOfNextMonth(today);
  const fifteenth = nextFifteenth(today);
  const tomorrow = addDays(today, 1);

  const leading: QuickPick[] = frequency === 'MONTHLY'
    ? [{ label: '1st', date: first }, { label: '15th', date: fifteenth }, { label: 'Month end', date: nextMonthEnd(today) }]
    : [{ label: 'Next Mon', date: nextMon }, { label: 'Next Fri', date: nextFri }];
  const padding: QuickPick[] = [
    { label: 'Tomorrow', date: tomorrow },
    { label: 'Next Fri', date: nextFri },
    { label: '1st', date: first },
    { label: '15th', date: fifteenth },
  ];

  const seen = new Set<string>();
  const out: QuickPick[] = [];
  for (const pick of [...leading, ...padding]) {
    if (out.length >= QUICK_PICKS_MAX) break;
    const date = businessDaysOnly ? nextBusinessDay(pick.date) : pick.date;
    if (pick.label === 'Tomorrow' && date !== pick.date) continue;
    if (date < min || seen.has(date)) continue;
    seen.add(date);
    out.push({ label: pick.label, date });
  }
  return out.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0));
}
