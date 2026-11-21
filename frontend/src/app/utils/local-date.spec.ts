import {
  addDays,
  ageOn,
  dayOfWeek,
  daysInMonth,
  formatFull,
  formatLong,
  formatMonthDay,
  formatShort,
  fromLocalDate,
  isWeekend,
  parseYmd,
  toLocalDate,
  todayYmd,
  ymd,
} from './local-date';

describe('local-date', () => {
  describe('ymd and parseYmd', () => {
    it('pads the month and day', () => {
      expect(ymd(2026, 3, 5)).toBe('2026-03-05');
      expect(ymd(2026, 11, 20)).toBe('2026-11-20');
    });

    it('reads the parts back', () => {
      expect(parseYmd('2026-11-20')).toEqual({ y: 2026, m: 11, d: 20 });
      expect(parseYmd('1991-03-14')).toEqual({ y: 1991, m: 3, d: 14 });
      expect(parseYmd('2024-02-29')).toEqual({ y: 2024, m: 2, d: 29 });
    });

    it('refuses anything that is not a real calendar day written as YYYY-MM-DD', () => {
      for (const bad of ['2026-3-5', '2026-02-30', '2026-13-01', '2026-00-10', '2023-02-29', 'nope', '', '2026-11-20T00:00:00.000Z']) {
        expect(() => parseYmd(bad)).withContext(bad).toThrowError(RangeError);
      }
    });
  });

  describe('local constructors only', () => {
    it('toLocalDate is local midnight on that day, not UTC midnight', () => {
      const date = toLocalDate('2026-11-20');
      expect(date.getFullYear()).toBe(2026);
      expect(date.getMonth()).toBe(10);
      expect(date.getDate()).toBe(20);
      expect(date.getHours()).toBe(0);
    });

    it('fromLocalDate and todayYmd read the local day, so a late evening stays today', () => {
      // After about 7pm Eastern toISOString() already prints tomorrow; the local getters do not.
      expect(fromLocalDate(new Date(2026, 10, 20, 19, 30))).toBe('2026-11-20');
      expect(fromLocalDate(new Date(2026, 0, 1, 23, 59))).toBe('2026-01-01');
      expect(fromLocalDate(new Date(2026, 11, 31, 23, 59, 59))).toBe('2026-12-31');
      expect(todayYmd(new Date(2026, 10, 15, 22, 0))).toBe('2026-11-15');
      expect(todayYmd()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    });
  });

  describe('addDays', () => {
    it('rolls over month ends, February in a leap year and the year', () => {
      expect(addDays('2026-01-31', 1)).toBe('2026-02-01');
      expect(addDays('2026-02-28', 1)).toBe('2026-03-01');
      expect(addDays('2024-02-28', 1)).toBe('2024-02-29');
      expect(addDays('2024-02-29', 1)).toBe('2024-03-01');
      expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
      expect(addDays('2026-11-20', 11)).toBe('2026-12-01');
    });

    it('goes backward too', () => {
      expect(addDays('2027-01-01', -1)).toBe('2026-12-31');
      expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
      expect(addDays('2024-03-01', -1)).toBe('2024-02-29');
      expect(addDays('2026-11-20', 0)).toBe('2026-11-20');
    });

    it('crosses the US daylight-saving switches as whole days', () => {
      expect(addDays('2026-03-07', 1)).toBe('2026-03-08');
      expect(addDays('2026-03-08', 1)).toBe('2026-03-09');
      expect(addDays('2026-11-01', -1)).toBe('2026-10-31');
      expect(addDays('2026-10-31', 1)).toBe('2026-11-01');
    });
  });

  describe('daysInMonth', () => {
    it('knows the month lengths and the leap rule', () => {
      expect(daysInMonth(2026, 1)).toBe(31);
      expect(daysInMonth(2026, 2)).toBe(28);
      expect(daysInMonth(2024, 2)).toBe(29);
      expect(daysInMonth(2000, 2)).toBe(29);
      expect(daysInMonth(1900, 2)).toBe(28);
      expect(daysInMonth(2026, 4)).toBe(30);
      expect(daysInMonth(2026, 11)).toBe(30);
      expect(daysInMonth(2026, 12)).toBe(31);
    });
  });

  describe('dayOfWeek and isWeekend', () => {
    it('counts Sunday as 0', () => {
      expect(dayOfWeek('2026-11-15')).toBe(0);
      expect(dayOfWeek('2026-11-16')).toBe(1);
      expect(dayOfWeek('2026-11-20')).toBe(5);
      expect(dayOfWeek('2026-11-21')).toBe(6);
    });

    it('calls Saturday and Sunday the weekend', () => {
      expect(isWeekend('2026-11-14')).toBeTrue();
      expect(isWeekend('2026-11-15')).toBeTrue();
      expect(isWeekend('2026-11-16')).toBeFalse();
      expect(isWeekend('2026-11-20')).toBeFalse();
    });
  });

  describe('formats', () => {
    it('formatMonthDay: "Nov 20", no leading zero on the day', () => {
      expect(formatMonthDay('2026-11-20')).toBe('Nov 20');
      expect(formatMonthDay('2026-03-05')).toBe('Mar 5');
    });

    it('formatShort: weekday and month-day, with the year only when it is not this year', () => {
      expect(formatShort('2026-11-20', '2026-11-15')).toBe('Fri, Nov 20');
      expect(formatShort('2026-01-02', '2026-11-15')).toBe('Fri, Jan 2');
      expect(formatShort('2027-01-01', '2026-11-15')).toBe('Fri, Jan 1, 2027');
      expect(formatShort('2025-12-31', '2026-11-15')).toBe('Wed, Dec 31, 2025');
    });

    it('formatShort defaults to the real today', () => {
      const thisYear = parseYmd(todayYmd()).y;
      expect(formatShort(ymd(thisYear, 6, 1))).not.toContain(String(thisYear));
      expect(formatShort(ymd(thisYear + 1, 6, 1))).toContain(String(thisYear + 1));
    });

    it('formatLong: "Mar 14, 1991"', () => {
      expect(formatLong('1991-03-14')).toBe('Mar 14, 1991');
      expect(formatLong('2026-11-20')).toBe('Nov 20, 2026');
    });

    it('formatFull: the whole date spelled out', () => {
      expect(formatFull('2026-11-20')).toBe('Friday, November 20, 2026');
      expect(formatFull('1991-03-14')).toBe('Thursday, March 14, 1991');
      expect(formatFull('2026-11-15')).toBe('Sunday, November 15, 2026');
    });
  });

  describe('ageOn', () => {
    it('turns a year older on the birthday itself, not the day before', () => {
      expect(ageOn('1991-03-14', '2026-03-13')).toBe(34);
      expect(ageOn('1991-03-14', '2026-03-14')).toBe(35);
      expect(ageOn('1991-03-14', '2026-11-15')).toBe(35);
      expect(ageOn('1991-12-31', '2026-01-01')).toBe(34);
    });

    it('a Feb 29 birthday counts on Mar 1 in a common year', () => {
      expect(ageOn('1992-02-29', '2026-02-28')).toBe(33);
      expect(ageOn('1992-02-29', '2026-03-01')).toBe(34);
      expect(ageOn('1992-02-29', '2028-02-29')).toBe(36);
    });

    it('is 0 on the day of birth and negative before it', () => {
      expect(ageOn('2026-11-15', '2026-11-15')).toBe(0);
      expect(ageOn('2026-12-01', '2026-11-15')).toBe(-1);
    });
  });
});
