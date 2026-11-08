import { ComponentFixture, TestBed } from '@angular/core/testing';

import { KidTimelineComponent } from './kid-timeline.component';
import { buildKidTimeline } from '../../pages/goal/kid-timeline.model';

const NOW = new Date(2026, 10, 15);

describe('KidTimelineComponent', () => {
  function create(kids: Array<{ label: string; birthYear: number | null; birthMonth?: number | null }>): ComponentFixture<KidTimelineComponent> {
    TestBed.configureTestingModule({ imports: [KidTimelineComponent] });
    const fixture = TestBed.createComponent(KidTimelineComponent);
    fixture.componentInstance.timeline = buildKidTimeline(kids, NOW);
    fixture.detectChanges();
    return fixture;
  }

  /** "Name sub", the name on its own line in the DOM (a block <b>). */
  function labels(fixture: ComponentFixture<KidTimelineComponent>): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.ktl-lb')).map(el =>
      Array.from((el as HTMLElement).childNodes).map(n => n.textContent!.trim()).filter(Boolean).join(' '));
  }

  it('draws a dot per kid, Now and the end, with the green fill to Now and labels alternating above and below', () => {
    const fixture = create([{ label: 'Ellie', birthYear: 2024 }, { label: 'Theo', birthYear: 2028 }, { label: '', birthYear: null }]);
    const dots = Array.from(fixture.nativeElement.querySelectorAll('.ktl-dot')) as HTMLElement[];
    expect(dots.length).toBe(5);
    expect(dots[0].classList).toContain('past');
    expect(dots[1].classList).toContain('now');
    // The undated kid is parked just before the end.
    expect(dots[3].classList).toContain('unsure');
    expect(dots[4].style.left).toBe('100%');
    const fill = fixture.nativeElement.querySelector('.ktl-done') as HTMLElement;
    expect(fill.style.width).toBe(`${fixture.componentInstance.timeline!.passedPct}%`);

    expect(labels(fixture)).toEqual(['Ellie 2024', 'Now 2026', 'Theo 2028', 'Kid 3 not yet', 'All 18 2046']);
    const lbs = Array.from(fixture.nativeElement.querySelectorAll('.ktl-lb')) as HTMLElement[];
    expect(lbs.map(l => l.classList.contains('up'))).toEqual([true, false, true, false, true]);
    expect(lbs[0].classList).toContain('l');
    expect(lbs[4].classList).toContain('r');
  });

  it('reads This year for a kid expected this year and 18 for a single kid', () => {
    const fixture = create([{ label: 'Bea', birthYear: 2026 }]);
    expect(labels(fixture)).toEqual(['Now 2026', 'Bea This year', '18 2044']);
    const dots = Array.from(fixture.nativeElement.querySelectorAll('.ktl-dot')) as HTMLElement[];
    expect(dots[1].classList).not.toContain('past');
    expect(dots[1].classList).not.toContain('unsure');
  });

  it('reads the year, not This year, for a kid born earlier this year with a known month (Ruling R4, as the card does)', () => {
    const fixture = create([{ label: 'Bea', birthYear: 2026, birthMonth: 3 }]);
    expect(labels(fixture)).toEqual(['Now 2026', 'Bea 2026', '18 2044']);
  });

  it('renders nothing without a timeline', () => {
    TestBed.configureTestingModule({ imports: [KidTimelineComponent] });
    const fixture = TestBed.createComponent(KidTimelineComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ktl')).toBeNull();
  });
});
