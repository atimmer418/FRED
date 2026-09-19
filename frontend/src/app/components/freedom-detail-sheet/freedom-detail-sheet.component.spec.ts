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

const CURRENT_YEAR = new Date().getFullYear();

describe('FreedomDetailSheetComponent', () => {
  let fixture: ComponentFixture<FreedomDetailSheetComponent>;
  let dismissSpy: jasmine.Spy;

  function setup(income = 5000, invest = 2500, equity = 0): void {
    dismissSpy = jasmine.createSpy('dismiss');
    TestBed.configureTestingModule({
      imports: [FreedomDetailSheetComponent],
      providers: [
        { provide: ModalController, useValue: { dismiss: dismissSpy } },
        { provide: FreedomStatsService, useValue: { birthYear: () => 0 } },
        RateAssumptionsService,
      ],
    });
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
