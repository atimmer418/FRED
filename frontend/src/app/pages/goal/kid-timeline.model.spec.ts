import { buildKidTimeline, TimelineDot } from './kid-timeline.model';

const NOW = new Date(2026, 10, 7); // 7 Nov 2026

const k = (label: string, birthYear: number | null, birthMonth: number | null = null) => ({ label, birthYear, birthMonth });

/** The last dot (the tsconfig lib predates Array.prototype.at). */
const lastDot = (t: { dots: TimelineDot[] }): TimelineDot => t.dots[t.dots.length - 1];

describe('buildKidTimeline', () => {
  it('born 2024 and 2025 with Now 2026: dots are evened out to 12 apart and the green reaches the Now dot', () => {
    const t = buildKidTimeline([k('A', 2024), k('B', 2025)], NOW);
    expect(t.dots.map(d => d.label)).toEqual(['A', 'B', 'Now', 'All 18']);
    expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 12, 24, 100]);
    expect(t.passedPct).toBeCloseTo(24, 0);
    expect(t.dots[0].passed).toBeTrue();
    expect(t.dots[1].passed).toBeTrue();
  });

  it('undated kids sit just before the end, do not move the end, and are never passed', () => {
    const t = buildKidTimeline([k('A', 2030), k('B', null), k('C', null)], NOW);
    expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'C', 'All 18']);
    expect(lastDot(t).year).toBe(2048);
    expect(t.dots.slice(2, 4).map(d => Math.round(d.position))).toEqual([76, 88]);
    expect(t.dots.slice(2, 4).every(d => d.undated && !d.passed)).toBeTrue();
  });

  it('every kid undated: the bar runs Now to this year + 18 with the kids parked before the end', () => {
    const t = buildKidTimeline([k('A', null), k('B', null)], NOW);
    expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'All 18']);
    expect(lastDot(t).year).toBe(2044);
    expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 76, 88, 100]);
  });

  it('six kids plus Now and the end never sit closer than 12 apart', () => {
    const t = buildKidTimeline([k('A', 2024), k('B', 2025), k('C', 2026), k('D', 2027), k('E', null), k('F', null)], NOW);
    expect(t.dots.length).toBe(8);
    for (let i = 1; i < t.dots.length; i++) {
      expect(t.dots[i].position - t.dots[i - 1].position).toBeGreaterThanOrEqual(12 - 1e-9);
    }
    expect(t.dots[0].position).toBe(0);
    expect(lastDot(t).position).toBe(100);
  });

  it('well-spread kids keep their scale positions; only a tie is pushed apart', () => {
    const t = buildKidTimeline([k('A', 2026), k('B', 2036)], NOW);
    // Now and A tie at 0: Now first, A pushed to 12. Span 28 years: 2036 is t = 10 on the first segment, so B stays at 65.
    expect(t.dots.map(d => d.label)).toEqual(['Now', 'A', 'B', 'All 18']);
    expect(t.dots.map(d => Math.round(d.position))).toEqual([0, 12, 65, 100]);
  });

  it('fills in a missing name and keeps the full dot shape', () => {
    const { dots } = buildKidTimeline([k('', 2024), k('  ', null)], NOW);
    expect(dots[0]).toEqual({ kind: 'kid', label: 'Kid 1', year: 2024, undated: false, expectedThisYear: false, passed: true, position: 0 });
    expect(dots[2]).toEqual(jasmine.objectContaining({ kind: 'kid', label: 'Kid 2', year: null, undated: true, passed: false }));
  });

  it('everyone born: passedPct equals Now position and no undated dots', () => {
    const { dots, passedPct } = buildKidTimeline([k('Ava', 2019), k('Ben', 2022)], NOW);
    const now = dots.find(d => d.kind === 'now')!;
    expect(passedPct).toBe(now.position);
    expect(dots.some(d => d.undated)).toBeFalse();
    expect(dots.filter(d => d.kind === 'kid').every(d => d.passed)).toBeTrue();
    // Names show once a kid has one.
    expect(dots.filter(d => d.kind === 'kid').map(d => d.label)).toEqual(['Ava', 'Ben']);
  });

  it('places a kid expected this year as a dated dot just after Now, not passed, and one kid ends at "18"', () => {
    const { dots } = buildKidTimeline([k('Ellie', 2026)], NOW);
    expect(dots.map(d => d.label)).toEqual(['Now', 'Ellie', '18']);
    const ellie = dots[1];
    expect(ellie.undated).toBeFalse();
    expect(ellie.passed).toBeFalse();
    expect(ellie.year).toBe(2026);
    expect(ellie.expectedThisYear).toBeTrue();
    expect(ellie.position).toBe(dots[0].position + 12);
    expect(dots[2].year).toBe(2044);
  });

  it('flags This year only for a kid dated this year with no month (Ruling R4)', () => {
    const { dots } = buildKidTimeline([
      k('Born', 2026, 3),
      k('Due', 2026, 12),
      k('Sometime', 2026, null),
      k('Later', 2027),
    ], NOW);
    const flag = (label: string) => dots.find(d => d.label === label)!.expectedThisYear;
    expect(flag('Born')).toBeFalse();
    expect(flag('Due')).toBeFalse();
    expect(flag('Sometime')).toBeTrue();
    expect(flag('Later')).toBeFalse();
    expect(dots.find(d => d.kind === 'now')!.expectedThisYear).toBeFalse();
  });

  it('ends at the last kid\'s 18th birthday, not 18 years from now, and keeps Now on the bar a gap before the end', () => {
    const { dots } = buildKidTimeline([k('Teen', 2010)], NOW);
    expect(dots[dots.length - 1]).toEqual(jasmine.objectContaining({ label: '18', year: 2028, position: 100 }));
    const adult = buildKidTimeline([k('Grown', 2006)], NOW);
    expect(adult.dots.map(d => d.label)).toEqual(['Grown', 'Now', '18']);
    expect(adult.passedPct).toBe(88);
  });

  it('starts the bar at Now when every kid is due later', () => {
    const { dots, passedPct } = buildKidTimeline([k('', 2028)], NOW);
    expect(passedPct).toBe(0);
    expect(dots[0].kind).toBe('now');
    // Two years into the first ten: 2 / 10 of 65, already more than 12 from Now.
    expect(dots[1].position).toBeCloseTo(13, 6);
    expect(dots[1].passed).toBeFalse();
    expect(dots[2]).toEqual(jasmine.objectContaining({ label: '18', year: 2046, position: 100 }));
  });
});
