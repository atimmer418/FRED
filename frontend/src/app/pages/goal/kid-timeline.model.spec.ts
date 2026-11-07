import { buildKidTimeline } from './kid-timeline.model';

const NOW = new Date(2026, 10, 15); // 15 Nov 2026

describe('buildKidTimeline', () => {
  it('born 2024, 2028, undated at 2026-11: dots are Kid 1 (0, passed), Now, Kid 3 (undated, this year), Kid 2, All 18 2046 (100)', () => {
    const { dots, passedPct } = buildKidTimeline([
      { label: '', birthYear: 2024 },
      { label: '  ', birthYear: 2028 },
      { label: '', birthYear: null },
    ], NOW);

    expect(dots.map(d => d.label)).toEqual(['Kid 1', 'Now', 'Kid 3', 'Kid 2', 'All 18']);
    expect(dots.map(d => d.kind)).toEqual(['kid', 'now', 'kid', 'kid', 'end']);

    const [kid1, now, kid3, kid2, end] = dots;
    expect(kid1).toEqual({ kind: 'kid', label: 'Kid 1', year: 2024, undated: false, expectedThisYear: false, passed: true, position: 0 });
    // Two years into the first ten: 2 / 10 of 65.
    expect(now.position).toBeCloseTo(13, 6);
    expect(now.year).toBe(2026);
    expect(now.passed).toBeFalse();
    expect(kid3).toEqual({ kind: 'kid', label: 'Kid 3', year: null, undated: true, expectedThisYear: false, passed: false, position: now.position });
    expect(kid2.position).toBeCloseTo(26, 6);
    expect(kid2.passed).toBeFalse();
    expect(kid2.undated).toBeFalse();
    expect(end).toEqual({ kind: 'end', label: 'All 18', year: 2046, undated: false, expectedThisYear: false, passed: false, position: 100 });
    expect(passedPct).toBe(now.position);
  });

  it('the first ten years take 65% of the width', () => {
    const { dots } = buildKidTimeline([
      { label: 'Ellie', birthYear: 2016 },
      { label: 'Theo', birthYear: 2026 },
    ], NOW);
    const at = (label: string) => dots.find(d => d.label === label)!;
    expect(at('Ellie').position).toBe(0);
    expect(at('Theo').position).toBeCloseTo(65, 6);
    // The last 18 years (2026 to 2044) share the remaining 35%.
    expect(at('All 18').year).toBe(2044);
    expect(at('All 18').position).toBe(100);
  });

  it('everyone born: passedPct equals Now position and no undated dots', () => {
    const { dots, passedPct } = buildKidTimeline([
      { label: 'Ava', birthYear: 2019 },
      { label: 'Ben', birthYear: 2022 },
    ], NOW);
    const now = dots.find(d => d.kind === 'now')!;
    expect(passedPct).toBe(now.position);
    expect(dots.some(d => d.undated)).toBeFalse();
    expect(dots.filter(d => d.kind === 'kid').every(d => d.passed)).toBeTrue();
    // Names show once a kid has one.
    expect(dots.filter(d => d.kind === 'kid').map(d => d.label)).toEqual(['Ava', 'Ben']);
  });

  it('places a kid expected this year as a dated dot at Now, not passed, and one kid ends at "18"', () => {
    const { dots } = buildKidTimeline([{ label: 'Ellie', birthYear: 2026 }], NOW);
    expect(dots.map(d => d.label)).toEqual(['Now', 'Ellie', '18']);
    const ellie = dots[1];
    expect(ellie.undated).toBeFalse();
    expect(ellie.passed).toBeFalse();
    expect(ellie.year).toBe(2026);
    expect(ellie.expectedThisYear).toBeTrue();
    expect(ellie.position).toBe(dots[0].position);
    expect(dots[2].year).toBe(2044);
  });

  it('flags This year only for a kid dated this year with no month (Ruling R4)', () => {
    const { dots } = buildKidTimeline([
      { label: 'Born', birthYear: 2026, birthMonth: 3 },
      { label: 'Due', birthYear: 2026, birthMonth: 12 },
      { label: 'Sometime', birthYear: 2026, birthMonth: null },
      { label: 'Later', birthYear: 2027 },
    ], NOW);
    const flag = (label: string) => dots.find(d => d.label === label)!.expectedThisYear;
    expect(flag('Born')).toBeFalse();
    expect(flag('Due')).toBeFalse();
    expect(flag('Sometime')).toBeTrue();
    expect(flag('Later')).toBeFalse();
    expect(dots.find(d => d.kind === 'now')!.expectedThisYear).toBeFalse();
  });

  it('ends at the last kid\'s 18th birthday, not 18 years from now, and keeps Now on the bar', () => {
    const { dots } = buildKidTimeline([{ label: 'Teen', birthYear: 2010 }], NOW);
    expect(dots[dots.length - 1]).toEqual(jasmine.objectContaining({ label: '18', year: 2028, position: 100 }));
    const adult = buildKidTimeline([{ label: 'Grown', birthYear: 2006 }], NOW);
    expect(adult.passedPct).toBe(100);
  });

  it('starts the bar at Now when every kid is due later', () => {
    const { dots, passedPct } = buildKidTimeline([{ label: '', birthYear: 2028 }], NOW);
    expect(passedPct).toBe(0);
    expect(dots[0].kind).toBe('now');
    expect(dots[1].position).toBeCloseTo(13, 6);
    expect(dots[1].passed).toBeFalse();
    expect(dots[2]).toEqual(jasmine.objectContaining({ label: '18', year: 2046, position: 100 }));
  });
});
