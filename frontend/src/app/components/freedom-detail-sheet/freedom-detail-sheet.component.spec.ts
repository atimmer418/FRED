/**
 * freedom-detail-sheet.component.spec.ts
 *
 * The sheet owns the editable plan tiles (moved from the my-profile card,
 * 2026-08-15) — these specs carry the input-driven live-math coverage:
 * the hero date must move on every keystroke and every assumption step.
 * Math fixture matches the my-profile spec header: income $5,000/mo →
 * $1.5M target; $2,500/mo from $0 → 18 years at 10%.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ModalController } from '@ionic/angular/standalone';
import { FreedomDetailSheetComponent } from './freedom-detail-sheet.component';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { RateAssumptionsService } from '../../services/rate-assumptions.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { AnalyticsAdapter } from '../../services/analytics/analytics-adapter';

const CURRENT_YEAR = new Date().getFullYear();

describe('FreedomDetailSheetComponent', () => {
  let fixture: ComponentFixture<FreedomDetailSheetComponent>;
  let dismissSpy: jasmine.Spy;
  let trackSpy: jasmine.Spy;

  function setup(income = 5000, invest = 2500, equity = 0, storedReturn?: number): void {
    dismissSpy = jasmine.createSpy('dismiss');
    // The real service with a spied track(): the payload assertions then cover the exact event
    // object the service is handed, with no vendor and no second implementation of the helpers.
    const analytics = new AnalyticsService({} as unknown as AnalyticsAdapter);
    trackSpy = spyOn(analytics, 'track');
    TestBed.configureTestingModule({
      imports: [FreedomDetailSheetComponent],
      providers: [
        { provide: ModalController, useValue: { dismiss: dismissSpy } },
        { provide: FreedomStatsService, useValue: { birthYear: () => 0 } },
        { provide: AnalyticsService, useValue: analytics },
        RateAssumptionsService,
      ],
    });
    if (storedReturn !== undefined) {
      // A legacy free-form rate, set before the component reads it on init.
      TestBed.inject(RateAssumptionsService).annualReturn.set(storedReturn);
    }
    fixture = TestBed.createComponent(FreedomDetailSheetComponent);
    fixture.componentInstance.income = income;
    fixture.componentInstance.invest = invest;
    fixture.componentInstance.equity = equity;
    fixture.detectChanges();
  }

  function heroYear(): string {
    return (fixture.nativeElement.querySelector('.fd-hero-year')?.textContent ?? '').trim();
  }

  function typeInto(selector: string, value: string): void {
    const input = fixture.nativeElement.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }

  afterEach(() => localStorage.removeItem('userId'));

  it('renders the hero freedom year from the seeded plan', () => {
    setup();
    expect(heroYear()).toBe(String(CURRENT_YEAR + 18));
  });

  it('moves the hero date live as the invest tile is edited', () => {
    setup();
    const before = heroYear();
    typeInto('.fd-tiles .fd-tile:nth-child(2) .fd-tile-input', '5000');
    const after = heroYear();
    expect(Number(after)).toBeLessThan(Number(before));
  });

  it('holds the withdrawal rate at the fixed 4% rule (customization removed)', () => {
    setup();
    expect(fixture.componentInstance.withdrawalRate()).toBe(0.04);
    expect(fixture.componentInstance.portfolioGoal()).toBe(1_500_000);
    expect((fixture.componentInstance as any).bumpWithdrawal).toBeUndefined();
    expect(fixture.nativeElement.querySelector('.fd-fixed-val')?.textContent?.trim()).toBe('4%');
  });

  it('registers a single rate step in the months-granular hero label', () => {
    setup();
    const before = fixture.componentInstance.timeAwayLabel();
    fixture.componentInstance.selectReturn(0.12);  // Moderate → Optimistic: moves months, maybe not the year
    fixture.detectChanges();
    expect(fixture.componentInstance.timeAwayLabel()).not.toBe(before);
    expect(fixture.nativeElement.querySelector('.fd-hero-meta').textContent).toContain('away');
  });

  it('shows the +$100/mo insight and a valid months delta', () => {
    setup();
    expect(fixture.componentInstance.plus100Months()).toBeGreaterThan(0);
    const text = fixture.nativeElement.querySelector('.fd-insight')?.textContent ?? '';
    expect(text).toContain('+$100/mo');
  });

  it('save() dismisses with the edited plan and rates under role saved', () => {
    setup();
    typeInto('.fd-tiles .fd-tile:nth-child(2) .fd-tile-input', '3000');
    fixture.componentInstance.selectReturn(0.08);        // Moderate → Conservative
    fixture.componentInstance.save();
    expect(dismissSpy).toHaveBeenCalledWith(
      jasmine.objectContaining({ invest: 3000, annualReturn: 0.08, withdrawalRate: 0.04 }),
      'saved'
    );
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Analytics: freedom_plan_changed fires at the save point and nowhere else.
  // ───────────────────────────────────────────────────────────────────────────

  /** The sheet's real CTA — the save point the event is pinned to. */
  function tapSavePlan(): void {
    (fixture.nativeElement.querySelector('.fd-cta') as HTMLButtonElement).click();
  }

  it('fires freedom_plan_changed once, with banded amounts, when Save plan is tapped', () => {
    setup();                                              // seeded $5,000 / $2,500
    typeInto('.fd-tiles .fd-tile:nth-child(2) .fd-tile-input', '3000');
    fixture.componentInstance.selectReturn(0.08);         // Moderate → Conservative
    fixture.detectChanges();

    tapSavePlan();

    // $3,000/mo from $0 to a $1.5M target at 8% = 221 months → 19 years.
    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: false,
        invest_changed: true,
        preset_changed: true,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '2500_4999',
        return_preset: 'conservative',
        years_to_freedom: '11_20',
      },
    });
  });

  it('sends nothing while the tiles are being typed into', () => {
    setup();
    typeInto('.fd-tiles .fd-tile:nth-child(1) .fd-tile-input', '6');
    typeInto('.fd-tiles .fd-tile:nth-child(1) .fd-tile-input', '60');
    typeInto('.fd-tiles .fd-tile:nth-child(1) .fd-tile-input', '6000');
    fixture.componentInstance.selectReturn(0.12);

    expect(trackSpy).not.toHaveBeenCalled();
  });

  it('covers the rates-only save, where no amount moved and nothing is written to the server', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);         // only the preset moved
    fixture.detectChanges();

    tapSavePlan();

    // $2,500/mo from $0 to a $1.5M target at 12% = 196 months → 17 years.
    expect(trackSpy).toHaveBeenCalledTimes(1);
    expect(trackSpy).toHaveBeenCalledWith({
      name: 'freedom_plan_changed',
      props: {
        surface: 'freedom_sheet',
        income_changed: false,
        invest_changed: false,
        preset_changed: true,
        monthly_income_band: '5k_9999',
        monthly_invest_band: '2500_4999',
        return_preset: 'optimistic',
        years_to_freedom: '11_20',
      },
    });
  });

  it('tells a rates-only save apart from a save that moved nothing', () => {
    // Both carry identical bands, so without preset_changed these two are indistinguishable.
    setup();

    tapSavePlan();

    expect(trackSpy).toHaveBeenCalledTimes(1);
    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.income_changed).toBeFalse();
    expect(props.invest_changed).toBeFalse();
    expect(props.preset_changed).toBeFalse();
    expect(props.return_preset).toBe('moderate');
  });

  it('does not count the snap-to-nearest of a legacy free-form rate as a user change', () => {
    // A stored 9.5% snaps to the Moderate chip on open. The user did not touch anything.
    setup(5000, 2500, 0, 0.095);

    tapSavePlan();

    expect(trackSpy.calls.mostRecent().args[0].props.preset_changed).toBeFalse();
  });

  it('reads a non-default stored preset as unchanged when the user leaves it alone', () => {
    // Pins preset_changed against the rate the sheet OPENED with, not against the 0.10 default:
    // comparing to the default would call this a change and be wrong for every Conservative user.
    setup(5000, 2500, 0, 0.08);

    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.preset_changed).toBeFalse();
    expect(props.return_preset).toBe('conservative');
  });

  it('counts the reset-to-default chip as a preset change', () => {
    setup(5000, 2500, 0, 0.08);

    fixture.componentInstance.resetRates();                // the reset only renders off-default
    fixture.detectChanges();
    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.preset_changed).toBeTrue();
    expect(props.return_preset).toBe('moderate');
  });

  it('reports both amounts as changed when both tiles were edited', () => {
    setup();
    typeInto('.fd-tiles .fd-tile:nth-child(1) .fd-tile-input', '12000');
    typeInto('.fd-tiles .fd-tile:nth-child(2) .fd-tile-input', '400');
    fixture.detectChanges();

    tapSavePlan();

    const props = trackSpy.calls.mostRecent().args[0].props;
    expect(props.income_changed).toBeTrue();
    expect(props.invest_changed).toBeTrue();
    expect(props.monthly_income_band).toBe('10k_49k');
    expect(props.monthly_invest_band).toBe('250_499');
    expect(props.return_preset).toBe('moderate');
  });

  it('sends nothing when an incomplete plan cannot be saved', () => {
    setup(0, 2500);                                       // no income goal → canSave false
    fixture.componentInstance.save();

    expect(trackSpy).not.toHaveBeenCalled();
    expect(dismissSpy).not.toHaveBeenCalled();
  });

  it('reset restores standard rates and hides the reset affordance', () => {
    setup();
    fixture.componentInstance.selectReturn(0.12);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeTruthy();
    fixture.componentInstance.resetRates();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.fd-reset')).toBeFalsy();
    expect(fixture.componentInstance.isDefaultRates()).toBeTrue();
  });
});
