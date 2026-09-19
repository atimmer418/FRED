/**
 * monthly-freedom-update.component.spec.ts — milestone card art selection.
 *
 * The pig image is reserved for milestones that unlock a NEW pig range ($1k/$10k/$100k/$1M end
 * equity tiers). The $50k/$250k/$500k equity milestones sit inside a range, so they keep the
 * milestone's default icon (military_tech), and the DEFAULT "no milestones" card keeps its smiley.
 * Investment-count-only milestones keep the pig (Andy's call, 2026-09-01: only the three
 * non-boundary equity milestones switch to the icon; a combined card follows its equity part).
 */
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { ModalController } from '@ionic/angular/standalone';
import { of } from 'rxjs';
import { MonthlyFreedomUpdateComponent } from './monthly-freedom-update.component';
import { MonthlyFreedomUpdateService, MonthlyFreedomUpdateData } from '../../services/monthly-freedom-update.service';
import { MfuStoreService } from '../../services/mfu-store.service';
import { ReviewService } from '../../services/review.service';

function payload(type: string, equityLevel: number): MonthlyFreedomUpdateData {
  return {
    shouldShow: true,
    periodStart: '2026-08-01',
    periodEnd: '2026-08-31',
    periodLabel: 'Aug 1 to Aug 31',
    milestones: [{ type, label: 'Milestone label', subtitle: 'Milestone subtitle' }],
    equityLevel,
    statusPercentile: 62,
    currentStreak: 0,
    reopen: true,
  } as unknown as MonthlyFreedomUpdateData;
}

describe('MonthlyFreedomUpdateComponent milestone card art', () => {
  function render(type: string, equityLevel = 3): HTMLElement {
    const data = payload(type, equityLevel);
    TestBed.configureTestingModule({
      imports: [MonthlyFreedomUpdateComponent],
      providers: [
        { provide: ModalController, useValue: {} },
        { provide: MonthlyFreedomUpdateService, useValue: {} },
        { provide: MfuStoreService, useValue: { loadReopen$: () => of(data), persistSnapshot: () => undefined } },
        { provide: ReviewService, useValue: {} },
        { provide: Router, useValue: {} },
      ],
    });
    const fixture = TestBed.createComponent(MonthlyFreedomUpdateComponent);
    fixture.componentInstance.isReopen = true; // quiet path: no dismiss timer, no self-load, no commit
    fixture.componentInstance.preloaded = data;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const pig = (el: HTMLElement) => el.querySelector<HTMLImageElement>('.mfu-milestone__pig-img');
  const icon = (el: HTMLElement) => el.querySelector<HTMLElement>('.mfu-milestone__icon-wrapper');

  afterEach(() => TestBed.resetTestingModule());

  it('shows the newly unlocked pig for a boundary equity milestone ($10k)', () => {
    const el = render('EQUITY_10K', 3);
    expect(pig(el)?.getAttribute('src')).toBe('assets/images/pig-range-3.svg');
    expect(icon(el)).toBeNull();
  });

  it('shows the pig for a combined card whose equity part is a boundary ($1k + 1st investment)', () => {
    const el = render('INVESTMENT_1,EQUITY_1K', 2);
    expect(pig(el)?.getAttribute('src')).toBe('assets/images/pig-range-2.svg');
    expect(icon(el)).toBeNull();
  });

  it('shows the default milestone icon for $50k (inside a pig range, nothing new unlocks)', () => {
    const el = render('EQUITY_50K', 3);
    expect(pig(el)).toBeNull();
    expect(icon(el)?.textContent?.trim()).toBe('military_tech');
  });

  it('shows the default milestone icon for a combined card whose equity part is $250k', () => {
    const el = render('INVESTMENT_10,EQUITY_250K', 4);
    expect(pig(el)).toBeNull();
    expect(icon(el)?.textContent?.trim()).toBe('military_tech');
  });

  it('shows the default milestone icon for $500k', () => {
    const el = render('EQUITY_500K', 4);
    expect(pig(el)).toBeNull();
    expect(icon(el)?.textContent?.trim()).toBe('military_tech');
  });

  it('keeps the pig for an investment-count-only milestone', () => {
    const el = render('INVESTMENT_10', 3);
    expect(pig(el)?.getAttribute('src')).toBe('assets/images/pig-range-3.svg');
    expect(icon(el)).toBeNull();
  });

  describe('branded hero (Concept 1)', () => {
    it('renders the FRED wordmark, the live equity pig avatar, and the month subtitle in place of the plain title', () => {
      const el = render('DEFAULT', 3);
      expect(el.querySelector('.mfu-hero')).not.toBeNull();
      expect(el.querySelector('.mfu-hero__wordmark')?.textContent?.trim()).toBe('FRED');
      expect(el.querySelector('.mfu-hero__avatar img')?.getAttribute('src')).toBe('assets/images/pig-range-3.svg');
      const sub = el.querySelector('.mfu-hero__title')?.textContent ?? '';
      expect(sub).toContain('Monthly Freedom Update');
      expect(sub).toContain('Aug'); // from periodLabel "Aug 1 to Aug 31"
      expect(el.querySelector('.mfu-header__title')).toBeNull(); // old centered title removed
      expect(el.querySelector('.mfu-hero__close')).not.toBeNull(); // close lives in the hero now
    });

    it('keeps the close button absolutely positioned (top-right), not pulled into the flex flow', () => {
      // Regression: a `.mfu-hero > *` layering rule out-specifies `.mfu-hero__close` under Angular
      // view encapsulation and silently demotes it to position:relative, dropping the X into the
      // flow at top-left and shoving the pig + wordmark right.
      const el = render('DEFAULT', 3);
      const close = el.querySelector('.mfu-hero__close') as HTMLElement;
      expect(getComputedStyle(close).position).toBe('absolute');
    });

    it('gives the white content sheet a rounded top that overlaps the gradient hero (tab3 pattern)', () => {
      const el = render('DEFAULT', 3);
      const content = el.querySelector('.mfu-content') as HTMLElement;
      const cs = getComputedStyle(content);
      expect(cs.borderTopLeftRadius).toBe('22px');
      expect(cs.borderTopRightRadius).toBe('22px');
      expect(parseFloat(cs.marginTop)).toBeLessThan(0); // sheet pulls up over the hero's gradient strip
    });
  });

  describe('heroSubtitle', () => {
    function subtitle(data: Partial<MonthlyFreedomUpdateData>): string {
      TestBed.configureTestingModule({
        imports: [MonthlyFreedomUpdateComponent],
        providers: [
          { provide: ModalController, useValue: {} },
          { provide: MonthlyFreedomUpdateService, useValue: {} },
          { provide: MfuStoreService, useValue: {} },
          { provide: ReviewService, useValue: {} },
          { provide: Router, useValue: {} },
        ],
      });
      const cmp = TestBed.createComponent(MonthlyFreedomUpdateComponent).componentInstance;
      cmp.data = data as MonthlyFreedomUpdateData; // getter only; ngOnInit never runs (no detectChanges)
      return cmp.heroSubtitle;
    }
    afterEach(() => TestBed.resetTestingModule());

    it('shows the single month for a one-month period', () => {
      expect(subtitle({ periodStart: '2026-08-01', periodEnd: '2026-08-31', periodLabel: 'Aug 1 to Aug 31' }))
        .toBe('Monthly Freedom Update \u00b7 Aug');
    });

    it('shows a month range for a multi-month period (returning after a gap), not just the start month', () => {
      expect(subtitle({ periodStart: '2026-06-01', periodEnd: '2026-08-31', periodLabel: 'Jun 1 to Aug 31' }))
        .toBe('Monthly Freedom Update \u00b7 Jun\u2013Aug');
    });

    it('falls back to no month when the period is missing', () => {
      expect(subtitle({})).toBe('Monthly Freedom Update');
    });
  });

  describe('close button (no 5-second lock)', () => {
    function renderAuto(): { el: HTMLElement; cmp: MonthlyFreedomUpdateComponent } {
      const data = payload('DEFAULT', 3);
      data.generatedForMonth = '2026-09';
      const commit = jasmine.createSpy('commitSeen').and.returnValue(of({}));
      TestBed.configureTestingModule({
        imports: [MonthlyFreedomUpdateComponent],
        providers: [
          { provide: ModalController, useValue: { dismiss: jasmine.createSpy('dismiss') } },
          { provide: MonthlyFreedomUpdateService, useValue: { commitSeen: commit } },
          { provide: MfuStoreService, useValue: { persistSnapshot: () => undefined } },
          { provide: ReviewService, useValue: { requestReviewIfFirstMFU: () => undefined } },
          { provide: Router, useValue: {} },
        ],
      });
      const fixture = TestBed.createComponent(MonthlyFreedomUpdateComponent);
      fixture.componentInstance.isReopen = false; // first-of-month auto popup (the path that used to lock 5s)
      fixture.componentInstance.preloaded = data;
      fixture.detectChanges();
      return { el: fixture.nativeElement as HTMLElement, cmp: fixture.componentInstance };
    }

    afterEach(() => TestBed.resetTestingModule());

    it('shows the working close button immediately on a first-of-month update (no wait, no locked placeholder)', () => {
      const { el, cmp } = renderAuto();
      expect(cmp.canDismiss).toBeTrue();
      expect(el.querySelector('.mfu-hero__close')).not.toBeNull(); // close lives in the branded hero
      expect(el.querySelector('.mfu-header__close--locked')).toBeNull(); // the 5s locked placeholder is gone
    });
  });

  it('keeps the smiley on the DEFAULT card', () => {
    const el = render('DEFAULT', 1);
    expect(pig(el)).toBeNull();
    expect(icon(el)?.textContent?.trim()).toBe('sentiment_satisfied');
  });
});

/**
 * FRED-217 — Retire "sky card". Once the member's projected freedom year has arrived
 * (projectedFreedomYear <= the current year), the "What If You Invested More?" CTA is replaced by
 * a celebratory Retire card that routes to the tab2 Education tab. Edge values (0/null/undefined)
 * must never trip the retire branch, and the quarterly-review gate still wins over both CTAs.
 */
describe('MonthlyFreedomUpdateComponent — Retire card (FRED-217)', () => {
  const THIS_YEAR = new Date().getFullYear();

  function retirePayload(overrides: Partial<MonthlyFreedomUpdateData>): MonthlyFreedomUpdateData {
    return {
      shouldShow: true,
      periodStart: '2026-08-01',
      periodEnd: '2026-08-31',
      periodLabel: 'Aug 1 to Aug 31',
      milestones: [],
      equityLevel: 3,
      statusPercentile: 99,
      currentStreak: 0,
      showQuarterlyCompare: false,
      reopen: true,
      ...overrides,
    } as unknown as MonthlyFreedomUpdateData;
  }

  function render(overrides: Partial<MonthlyFreedomUpdateData>): { el: HTMLElement; cmp: MonthlyFreedomUpdateComponent; dismiss: jasmine.Spy } {
    const data = retirePayload(overrides);
    const dismiss = jasmine.createSpy('dismiss');
    TestBed.configureTestingModule({
      imports: [MonthlyFreedomUpdateComponent],
      providers: [
        { provide: ModalController, useValue: { dismiss } },
        { provide: MonthlyFreedomUpdateService, useValue: {} },
        { provide: MfuStoreService, useValue: { loadReopen$: () => of(data), persistSnapshot: () => undefined } },
        { provide: ReviewService, useValue: {} },
        { provide: Router, useValue: {} },
      ],
    });
    const fixture = TestBed.createComponent(MonthlyFreedomUpdateComponent);
    fixture.componentInstance.isReopen = true;
    fixture.componentInstance.preloaded = data;
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, cmp: fixture.componentInstance, dismiss };
  }

  const retire = (el: HTMLElement) => el.querySelector('.mfu-retire');
  const investMore = (el: HTMLElement) => el.querySelector('.mfu-cta');

  afterEach(() => TestBed.resetTestingModule());

  it('renders the Retire card and hides the invest-more CTA when the freedom year is the current year', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR });
    expect(retire(el)).not.toBeNull();
    expect(investMore(el)).toBeNull();
  });

  it('shows the flying-pig hero, the exact copy, and the button in the Retire card', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR });
    expect(el.querySelector('.mfu-retire__sky img')?.getAttribute('src')).toBe('assets/images/whenPiggybanksFly.png');
    expect(el.querySelector('.mfu-retire__title')?.textContent?.trim()).toBe("You've reached your Freedom Date.");
    expect(el.querySelector('.mfu-retire__line')?.textContent?.trim())
      .toBe('Your portfolio is now ready to support the retirement income you set.');
    expect(el.querySelector('.mfu-retire__btn')?.textContent).toContain('Explore retirement strategies');
    expect(el.querySelector('.mfu-retire__confetti')).not.toBeNull();
  });

  it('shows the invest-more CTA and no Retire card for a future freedom year', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR + 5 });
    expect(retire(el)).toBeNull();
    expect(investMore(el)).not.toBeNull();
  });

  it('hides the "On track for Financial Freedom" tracker in the freedom year (the Retire card claims that space)', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR });
    expect(el.querySelector('.mfu-tracker')).toBeNull();
  });

  it('keeps the tracker for a future freedom year', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR + 5 });
    expect(el.querySelector('.mfu-tracker')).not.toBeNull();
  });

  it('hides the "Markets dip" bought-back section in the freedom year', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR });
    expect(el.querySelector('.mfu-bought-back')).toBeNull();
  });

  it('keeps the bought-back section for a future freedom year', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR + 5 });
    expect(el.querySelector('.mfu-bought-back')).not.toBeNull();
  });

  it('shows the Retire card for a past freedom year (over-target member)', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR - 2 });
    expect(retire(el)).not.toBeNull();
    expect(investMore(el)).toBeNull();
  });

  it('does not trip the Retire card on a zero/null/undefined freedom year (falls through to invest-more)', () => {
    for (const y of [0, null, undefined]) {
      const { el } = render({ projectedFreedomYear: y as unknown as number });
      expect(retire(el)).withContext(`projectedFreedomYear=${y}`).toBeNull();
      expect(investMore(el)).withContext(`projectedFreedomYear=${y}`).not.toBeNull();
      TestBed.resetTestingModule();
    }
  });

  it('lets the quarterly review win: neither CTA renders in the freedom year on a quarterly month', () => {
    const { el } = render({ projectedFreedomYear: THIS_YEAR, showQuarterlyCompare: true });
    expect(retire(el)).toBeNull();
    expect(investMore(el)).toBeNull();
  });

  it('dismisses with the exploreRetirement action when the Retire button is tapped', () => {
    const { el, dismiss } = render({ projectedFreedomYear: THIS_YEAR });
    (el.querySelector('.mfu-retire__btn') as HTMLButtonElement).click();
    expect(dismiss).toHaveBeenCalledWith(jasmine.objectContaining({ action: 'exploreRetirement' }));
  });
});

/**
 * Anti-stale freedom descriptions. The down-month body paragraph rotates through a pool so a member
 * who opens the update every month does not reread the exact same sentence. Selection is
 * deterministic per MFU (seeded off mfuCount), never random-per-render: it must be stable across the
 * many getter calls a change-detection pass makes, and identical when the same month is reopened.
 */
describe('MonthlyFreedomUpdateComponent — rotating freedom descriptions', () => {
  // Getter-only; ngOnInit never runs, so the injected deps are never touched. Constructing directly
  // (no TestBed) lets a single test build several instances without re-configuring the test module.
  function make(data: Partial<MonthlyFreedomUpdateData>): MonthlyFreedomUpdateComponent {
    const cmp = new MonthlyFreedomUpdateComponent({} as any, {} as any, {} as any, {} as any, {} as any);
    cmp.data = data as MonthlyFreedomUpdateData;
    return cmp;
  }
  const DOWN = MonthlyFreedomUpdateComponent.DOWN_DESCRIPTIONS;
  const UP = MonthlyFreedomUpdateComponent.UP_DESCRIPTIONS;

  it('down month: renders the mfuCount-indexed variant and stays stable across repeated reads', () => {
    const cmp = make({ daysBoughtBack: 0, periodLabel: 'Aug 1 to Aug 31', mfuCount: 1 });
    const expected = DOWN[1]('Aug 1 to Aug 31');
    expect(cmp.freedomDescriptionHtml).toBe(expected);
    expect(cmp.freedomDescriptionHtml).toBe(expected);
  });

  it('down month: consecutive months rotate to a different sentence', () => {
    const a = make({ daysBoughtBack: 0, periodLabel: 'Aug', mfuCount: 0 }).freedomDescriptionHtml;
    const b = make({ daysBoughtBack: 0, periodLabel: 'Aug', mfuCount: 1 }).freedomDescriptionHtml;
    expect(a).not.toBe(b);
  });

  it('down month: the index wraps around the pool', () => {
    const first = make({ daysBoughtBack: 0, periodLabel: 'Aug', mfuCount: 0 }).freedomDescriptionHtml;
    const wrapped = make({ daysBoughtBack: 0, periodLabel: 'Aug', mfuCount: DOWN.length }).freedomDescriptionHtml;
    expect(wrapped).toBe(first);
  });

  it('up month: rotates but always keeps the day count and the period', () => {
    const cmp = make({ daysBoughtBack: 12, periodLabel: 'Aug 1 to Aug 31', mfuCount: 2 });
    expect(cmp.freedomDescriptionHtml).toBe(UP[2 % UP.length]('Aug 1 to Aug 31', 12));
    expect(cmp.freedomDescriptionHtml).toContain('12 days');
    expect(cmp.freedomDescriptionHtml).toContain('Aug 1 to Aug 31');
  });

  it('no variant uses an em dash (house style)', () => {
    for (const fn of DOWN) expect(fn('Aug')).not.toContain('—');
    for (const fn of UP) expect(fn('Aug', 5)).not.toContain('—');
  });

  it('falls back to a stable pick when mfuCount is absent', () => {
    const cmp = make({ daysBoughtBack: 0, periodLabel: 'Aug', generatedForMonth: '2026-09' });
    const once = cmp.freedomDescriptionHtml;
    expect(DOWN.some(fn => fn('Aug') === once)).toBeTrue();
    expect(cmp.freedomDescriptionHtml).toBe(once); // stable
  });
});
