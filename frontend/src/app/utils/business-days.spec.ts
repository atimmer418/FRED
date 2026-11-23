import { addBusinessDays, lastBusinessDayOfMonth, nextBusinessDay, tradeWindow } from './business-days';

// November 2026: Fri 13, Sat 14, Sun 15, Mon 16, Fri 20, Sat 21, Sun 22, Mon 23, Wed 25, Fri 27, Mon 30.
describe('business-days', () => {
  describe('addBusinessDays', () => {
    it('skips the weekend: one business day after Friday is Monday', () => {
      expect(addBusinessDays('2026-11-20', 1)).toBe('2026-11-23');
      expect(addBusinessDays('2026-11-20', 3)).toBe('2026-11-25');
      expect(addBusinessDays('2026-11-16', 1)).toBe('2026-11-17');
      expect(addBusinessDays('2026-11-16', 5)).toBe('2026-11-23');
    });

    it('starts counting from a weekend date without counting it', () => {
      expect(addBusinessDays('2026-11-21', 1)).toBe('2026-11-23');
      expect(addBusinessDays('2026-11-22', 1)).toBe('2026-11-23');
    });

    it('zero gives the date back, weekend or not', () => {
      expect(addBusinessDays('2026-11-20', 0)).toBe('2026-11-20');
      expect(addBusinessDays('2026-11-21', 0)).toBe('2026-11-21');
    });

    it('goes backward', () => {
      expect(addBusinessDays('2026-11-23', -1)).toBe('2026-11-20');
      expect(addBusinessDays('2026-11-23', -3)).toBe('2026-11-18');
      expect(addBusinessDays('2026-11-22', -1)).toBe('2026-11-20');
    });

    it('crosses month and year ends with no holiday table', () => {
      expect(addBusinessDays('2026-11-27', 1)).toBe('2026-11-30');
      expect(addBusinessDays('2026-11-30', 1)).toBe('2026-12-01');
      expect(addBusinessDays('2026-12-31', 1)).toBe('2027-01-01');
      expect(addBusinessDays('2026-12-25', 1)).toBe('2026-12-28');
    });
  });

  describe('nextBusinessDay', () => {
    it('leaves a weekday alone and moves a weekend to the Monday', () => {
      expect(nextBusinessDay('2026-11-20')).toBe('2026-11-20');
      expect(nextBusinessDay('2026-11-16')).toBe('2026-11-16');
      expect(nextBusinessDay('2026-11-21')).toBe('2026-11-23');
      expect(nextBusinessDay('2026-11-22')).toBe('2026-11-23');
    });

    it('rolls into the next month or year when the weekend ends there', () => {
      expect(nextBusinessDay('2026-10-31')).toBe('2026-11-02');
      expect(nextBusinessDay('2026-08-01')).toBe('2026-08-03');
      expect(nextBusinessDay('2027-01-02')).toBe('2027-01-04');
    });
  });

  describe('lastBusinessDayOfMonth', () => {
    it('is the last day when that is a weekday, else the Friday before', () => {
      expect(lastBusinessDayOfMonth(2026, 11)).toBe('2026-11-30');
      expect(lastBusinessDayOfMonth(2026, 12)).toBe('2026-12-31');
      expect(lastBusinessDayOfMonth(2026, 10)).toBe('2026-10-30');
      expect(lastBusinessDayOfMonth(2026, 1)).toBe('2026-01-30');
      expect(lastBusinessDayOfMonth(2026, 5)).toBe('2026-05-29');
    });

    it('handles February in a leap year', () => {
      expect(lastBusinessDayOfMonth(2024, 2)).toBe('2024-02-29');
      expect(lastBusinessDayOfMonth(2026, 2)).toBe('2026-02-27');
    });
  });

  describe('tradeWindow', () => {
    it('is one to three business days after the transfer date', () => {
      expect(tradeWindow('2026-11-20')).toBe('Nov 23 to Nov 25');
      expect(tradeWindow('2026-11-16')).toBe('Nov 17 to Nov 19');
    });

    it('spans a month end and a year end', () => {
      expect(tradeWindow('2026-11-27')).toBe('Nov 30 to Dec 2');
      expect(tradeWindow('2026-12-30')).toBe('Dec 31 to Jan 4');
    });

    it('starts the Monday after a weekend transfer date', () => {
      expect(tradeWindow('2026-11-21')).toBe('Nov 23 to Nov 25');
    });
  });
});
