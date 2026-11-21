import { QUICK_PICKS_MAX, quickPicks } from './quick-picks';

// November 2026: Tue 10, Fri 13, Sat 14, Sun 15, Mon 16, Fri 20, Mon 30; Tue Dec 1, Tue Dec 15, Thu Dec 31.
describe('quick-picks', () => {
  it('WEEKLY leads with Next Mon and Next Fri, padded to four with the 1st and 15th, sorted ascending', () => {
    expect(quickPicks('WEEKLY', '2026-11-15', '2026-11-15')).toEqual([
      { label: 'Next Mon', date: '2026-11-16' },
      { label: 'Next Fri', date: '2026-11-20' },
      { label: '1st', date: '2026-12-01' },
      { label: '15th', date: '2026-12-15' },
    ]);
  });

  it('BIWEEKLY is the same set; Tomorrow pads it when it is its own day, and the list sorts by date', () => {
    const picks = quickPicks('BIWEEKLY', '2026-11-11', '2026-11-10');
    expect(picks).toEqual([
      { label: 'Tomorrow', date: '2026-11-11' },
      { label: 'Next Fri', date: '2026-11-13' },
      { label: 'Next Mon', date: '2026-11-16' },
      { label: '1st', date: '2026-12-01' },
    ]);
    expect(picks.length).toBe(QUICK_PICKS_MAX);
  });

  it('MONTHLY leads with the 1st, the 15th and the month end (its last business day)', () => {
    expect(quickPicks('MONTHLY', '2026-11-15', '2026-11-15')).toEqual([
      { label: 'Tomorrow', date: '2026-11-16' },
      { label: 'Month end', date: '2026-11-30' },
      { label: '1st', date: '2026-12-01' },
      { label: '15th', date: '2026-12-15' },
    ]);
  });

  it('SEMI_MONTHLY has none: the field is locked', () => {
    expect(quickPicks('SEMI_MONTHLY', '2026-11-15', '2026-11-15')).toEqual([]);
    expect(quickPicks('SEMI_MONTHLY', '2026-11-15', '2026-11-15', true)).toEqual([]);
  });

  it('a 15th still ahead this month is this month\'s; the month end moves on once this month\'s has passed', () => {
    // Fri May 29 2026 is May's last business day; June's is Tue June 30.
    const picks = quickPicks('MONTHLY', '2026-05-30', '2026-05-29');
    expect(picks).toEqual([
      { label: 'Tomorrow', date: '2026-05-30' },
      { label: '1st', date: '2026-06-01' },
      { label: '15th', date: '2026-06-15' },
      { label: 'Month end', date: '2026-06-30' },
    ]);
    expect(quickPicks('MONTHLY', '2026-11-11', '2026-11-10').find(p => p.label === '15th')!.date).toBe('2026-11-15');
  });

  it('crosses the year end', () => {
    expect(quickPicks('MONTHLY', '2026-12-29', '2026-12-28')).toEqual([
      { label: 'Tomorrow', date: '2026-12-29' },
      { label: 'Month end', date: '2026-12-31' },
      { label: '1st', date: '2027-01-01' },
      { label: '15th', date: '2027-01-15' },
    ]);
  });

  it('drops picks before min, so a later min leaves fewer than four', () => {
    expect(quickPicks('WEEKLY', '2026-11-25', '2026-11-15')).toEqual([
      { label: '1st', date: '2026-12-01' },
      { label: '15th', date: '2026-12-15' },
    ]);
  });

  it('de-duplicates by date, the leading label winning', () => {
    // Sunday Nov 15: Tomorrow is Next Mon, so Tomorrow never appears.
    const labels = quickPicks('WEEKLY', '2026-11-15', '2026-11-15').map(p => p.label);
    expect(labels).not.toContain('Tomorrow');
    expect(new Set(quickPicks('WEEKLY', '2026-11-15', '2026-11-15').map(p => p.date)).size).toBe(4);
  });

  describe('businessDaysOnly', () => {
    it('moves a weekend 1st and 15th forward to the Monday, and de-duplicates after the move', () => {
      // Thu July 30 2026: Aug 1 and Aug 15 are Saturdays; Fri July 31 is the month end, tomorrow and next Friday at once.
      expect(quickPicks('MONTHLY', '2026-07-31', '2026-07-30', true)).toEqual([
        { label: 'Month end', date: '2026-07-31' },
        { label: '1st', date: '2026-08-03' },
        { label: '15th', date: '2026-08-17' },
      ]);
    });

    it('leaves weekday picks where they are', () => {
      expect(quickPicks('BIWEEKLY', '2026-11-11', '2026-11-10', true)).toEqual(quickPicks('BIWEEKLY', '2026-11-11', '2026-11-10'));
    });

    it('drops a Tomorrow that would have to move, instead of labelling a Monday "Tomorrow"', () => {
      // Fri Nov 13: tomorrow is Saturday; the 15th is Sunday and lands on Next Mon.
      expect(quickPicks('WEEKLY', '2026-11-14', '2026-11-13', true)).toEqual([
        { label: 'Next Mon', date: '2026-11-16' },
        { label: 'Next Fri', date: '2026-11-20' },
        { label: '1st', date: '2026-12-01' },
      ]);
      expect(quickPicks('WEEKLY', '2026-11-14', '2026-11-13')).toEqual([
        { label: 'Tomorrow', date: '2026-11-14' },
        { label: 'Next Mon', date: '2026-11-16' },
        { label: 'Next Fri', date: '2026-11-20' },
        { label: '1st', date: '2026-12-01' },
      ]);
    });

    it('never returns a weekend', () => {
      const todays = ['2026-11-10', '2026-11-13', '2026-11-14', '2026-11-15', '2026-07-30', '2026-12-28', '2026-01-30'];
      for (const today of todays) {
        for (const frequency of ['WEEKLY', 'BIWEEKLY', 'MONTHLY'] as const) {
          for (const pick of quickPicks(frequency, today, today, true)) {
            const [y, m, d] = pick.date.split('-').map(Number);
            const day = new Date(y, m - 1, d).getDay();
            expect(day).withContext(`${frequency} from ${today}: ${pick.label} ${pick.date}`).not.toBe(0);
            expect(day).withContext(`${frequency} from ${today}: ${pick.label} ${pick.date}`).not.toBe(6);
          }
        }
      }
    });
  });
});
