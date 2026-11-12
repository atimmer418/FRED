/**
 * monte-carlo-flow.component.spec.ts
 *
 * The outside-accounts step on the slide tray: the two balances are value buttons that open the
 * tray inside their glass field, in place of the keyboard, and the tray wears the blue flow's
 * colours. The flow's analytics have their own spec (monte-carlo-flow.analytics.spec.ts); the
 * other steps are untouched. Karma's context document has no layout, so measured heights are
 * whatever scrollHeight says.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { ModalController } from '@ionic/angular/standalone';
import { MonteCarloFlowComponent, OUTSIDE_BALANCE_SCALE } from './monte-carlo-flow.component';
import { MonteCarloService } from '../../services/monte-carlo.service';
import { SimulationHistoryService } from '../../services/simulation-history.service';
import { PortfolioService } from '../../services/portfolio.service';
import { AuthService } from '../../services/auth.service';
import { AlpacaService } from '../../services/alpaca.service';
import { ToastService } from '../../services/toast.service';
import { FreedomStatsService } from '../../services/freedom-stats.service';
import { AnalyticsService } from '../../services/analytics/analytics.service';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';
import { HOUSE_EASING } from '../plan-amount-tray/amount-tray-host';
import { breakpoints, detents, nudge, stepAt } from '../../utils/plan-amount-scale';

describe('MonteCarloFlowComponent: outside balances on the slide tray', () => {
  let fixture: ComponentFixture<MonteCarloFlowComponent>;
  let cmp: MonteCarloFlowComponent;
  let analytics: jasmine.SpyObj<AnalyticsService>;

  const OUTSIDE_KEY = 'fred.mcOutside.anon';
  const LAST_USED_KEY = 'fred.mcLastUsed.anon';

  /** Under reduced motion the host snaps, so open, switch and close settle synchronously. */
  function setup(reducedMotion = true): void {
    if (reducedMotion) {
      const real = window.matchMedia.bind(window);
      spyOn(window, 'matchMedia').and.callFake((query: string) =>
        query.includes('prefers-reduced-motion') ? ({ matches: true } as MediaQueryList) : real(query));
    }
    analytics = jasmine.createSpyObj<AnalyticsService>('AnalyticsService', ['track', 'trackErrorShown']);
    TestBed.configureTestingModule({
      imports: [MonteCarloFlowComponent],
      providers: [
        { provide: MonteCarloService, useValue: jasmine.createSpyObj<MonteCarloService>('MonteCarloService', ['runShowdown', 'solveMaxMonthlySpend']) },
        { provide: SimulationHistoryService, useValue: jasmine.createSpyObj<SimulationHistoryService>('SimulationHistoryService', ['saveEntry']) },
        { provide: PortfolioService, useValue: jasmine.createSpyObj<PortfolioService>('PortfolioService', ['getPortfolioDashboard']) },
        { provide: AuthService, useValue: { userProgress$: of(null) } },
        { provide: AlpacaService, useValue: { getKycData: () => of({}) } },
        { provide: ToastService, useValue: jasmine.createSpyObj<ToastService>('ToastService', ['showToast']) },
        { provide: ModalController, useValue: jasmine.createSpyObj<ModalController>('ModalController', ['create', 'dismiss']) },
        { provide: FreedomStatsService, useValue: { monthlyActualInvestment: () => 0, stats: () => ({ freedomYear: '2050' }) } },
        { provide: AnalyticsService, useValue: analytics },
      ],
    });
    fixture = TestBed.createComponent(MonteCarloFlowComponent);
    cmp = fixture.componentInstance;
    cmp.includeOutside = true;
    fixture.detectChanges();
    cmp.showStep('outside');
    fixture.detectChanges();
  }

  afterEach(() => {
    localStorage.removeItem(OUTSIDE_KEY);
    localStorage.removeItem(LAST_USED_KEY);
  });

  const host = () => fixture.nativeElement as HTMLElement;
  const outsideStep = () => host().querySelectorAll('.fscreen')[1] as HTMLElement;
  const card = (i: 0 | 1) => outsideStep().querySelectorAll('.glass-field')[i] as HTMLElement;
  const button = (i: 0 | 1) => card(i).querySelector('.field-value-btn') as HTMLButtonElement;
  const valueOf = (i: 0 | 1) => (button(i).querySelector('.gf-val') as HTMLElement).textContent!.trim();
  const label = (i: 0 | 1) => card(i).querySelector('.field-label') as HTMLElement;
  const done = (i: 0 | 1) => card(i).querySelector('.field-tray-done') as HTMLButtonElement | null;
  const slot = (i: 0 | 1) => card(i).querySelector('app-amount-tray-slot') as HTMLElement | null;
  const slots = () => host().querySelectorAll('app-amount-tray-slot');
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;
  const combined = () => (outsideStep().querySelector('.combined-line strong') as HTMLElement).textContent;

  function tap(i: 0 | 1): void {
    button(i).click();
    fixture.detectChanges();
  }

  /** A detent or a nudge from the tray, as the slider would emit it. */
  function slide(value: number): void {
    tray()!.valueChange.emit(value);
    fixture.detectChanges();
  }

  async function finish(anim: Animation): Promise<void> {
    const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
    anim.finish();
    await landed;
    fixture.detectChanges();
  }

  it('the balance scale: breakpoints at $100k, $500k and $2M, 121 detents ending exactly at $5M, minus and plus by $1,000', () => {
    expect(breakpoints(OUTSIDE_BALANCE_SCALE)).toEqual([100_000, 500_000, 2_000_000]);
    const list = detents(OUTSIDE_BALANCE_SCALE);
    expect(list.length).toBe(121);
    expect(list[0]).toBe(0);
    expect(list[list.length - 1]).toBe(5_000_000);
    expect(list).toContain(95_000);
    expect(list).toContain(110_000);
    expect(list).toContain(1_950_000);
    expect(list).toContain(2_100_000);
    expect(stepAt(OUTSIDE_BALANCE_SCALE, 0)).toBe(5_000);
    expect(stepAt(OUTSIDE_BALANCE_SCALE, 100_000)).toBe(10_000);
    expect(stepAt(OUTSIDE_BALANCE_SCALE, 4_999_999)).toBe(100_000);
    expect(nudge(OUTSIDE_BALANCE_SCALE, 0, 1)).toBe(1_000);
    expect(nudge(OUTSIDE_BALANCE_SCALE, 250_000, -1)).toBe(249_000);
    expect(nudge(OUTSIDE_BALANCE_SCALE, 5_000_000, 1)).toBe(5_000_000);
  });

  it('renders each balance as a value button in its glass field, the placeholder while it is $0, and nothing to type into', () => {
    setup();
    expect(outsideStep().querySelectorAll('input').length).toBe(0);
    expect(outsideStep().querySelectorAll('.field-value-btn').length).toBe(2);
    expect(button(0).querySelector('.field-affix')!.textContent).toBe('$');
    expect(valueOf(0)).toBe('250,000');
    expect(valueOf(1)).toBe('85,000');
    expect(button(0).querySelector('.field-placeholder')).not.toBeNull();
    expect(button(1).querySelector('.field-placeholder')).not.toBeNull();
    expect(label(0).textContent).toBe('401(k) balance');
    expect(label(1).textContent).toBe('Roth IRA balance');
    expect(slots().length).toBe(0);
    expect(done(0)).toBeNull();
    expect(done(1)).toBeNull();
  });

  it('a tap on the 401(k) field opens the tray inside its card: label and card lit, Done in the label row, slot under the row, the $0 to $5M scale', () => {
    setup();
    tap(0);
    expect(cmp.tray.open()).toBe('k401');
    expect(cmp.tray.shown()).toBe('k401');
    expect(button(0).classList.contains('active')).toBeTrue();
    expect(button(0).getAttribute('aria-expanded')).toBe('true');
    expect(label(0).classList.contains('active')).toBeTrue();
    expect(card(0).classList.contains('active')).toBeTrue();
    expect(card(1).classList.contains('active')).toBeFalse();
    expect(done(0)).not.toBeNull();
    expect(done(0)!.parentElement).toBe(label(0).parentElement);
    expect(done(1)).toBeNull();
    expect(slot(0)).not.toBeNull();
    expect(button(0).nextElementSibling).toBe(slot(0));
    expect(slot(1)).toBeNull();
    expect(slots().length).toBe(1);
    const t = tray()!;
    expect(t.field()).toBe('k401');
    expect(t.scale()).toBe(OUTSIDE_BALANCE_SCALE);
    expect(t.value()).toBe(0);
    expect(t.unit()).toBe('dollars');
    expect(host().querySelector('.ps')!.getAttribute('aria-label')).toBe('401(k) balance');
    expect(host().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('$0');
    const ends = Array.from(host().querySelectorAll('.ps-ends span')).map(e => e.textContent);
    expect(ends).toEqual(['$0', '$5,000,000']);
    expect(host().querySelectorAll('.ps-mark').length).toBe(3);
  });

  it('a detent lands on the balance: the button shows it, the combined line follows, and Continue reports a slider', () => {
    setup();
    cmp.portfolioValue.set(1_000_000);
    tap(0);
    slide(250_000);
    expect(cmp.k401Balance()).toBe(250_000);
    expect(valueOf(0)).toBe('250,000');
    expect(button(0).querySelector('.field-placeholder')).toBeNull();
    expect(combined()).toBe('$1,250,000');
    expect(cmp.rothBalance()).toBe(0);
    expect(analytics.track).not.toHaveBeenCalled();

    cmp.onContinueClick();

    expect(analytics.track).toHaveBeenCalledWith({
      name: 'monte_carlo_step_completed',
      props: { step: 'outside', input_method: 'slider' },
    });
  });

  it('tapping the Roth field moves the tray into its card: one slot, Done moves with it, the slider serves the Roth balance', () => {
    setup();
    cmp.rothBalance.set(85_000);
    fixture.detectChanges();
    tap(0);
    tap(1);
    expect(cmp.tray.open()).toBe('roth');
    expect(cmp.tray.shown()).toBe('roth');
    expect(slot(0)).toBeNull();
    expect(slot(1)).not.toBeNull();
    expect(button(1).nextElementSibling).toBe(slot(1));
    expect(slots().length).toBe(1);
    expect(done(0)).toBeNull();
    expect(done(1)).not.toBeNull();
    expect(button(0).classList.contains('active')).toBeFalse();
    expect(button(1).classList.contains('active')).toBeTrue();
    expect(label(1).classList.contains('active')).toBeTrue();
    const t = tray()!;
    expect(t.field()).toBe('roth');
    expect(t.value()).toBe(85_000);
    expect(host().querySelector('.ps')!.getAttribute('aria-label')).toBe('Roth IRA balance');

    slide(90_000);
    expect(cmp.rothBalance()).toBe(90_000);
    expect(cmp.k401Balance()).toBe(0);
    expect(valueOf(1)).toBe('90,000');
  });

  it('with motion on, a switch collapses the 401(k) slot as a close does, then the tray grows afresh inside the Roth card (it cannot cross-fade in place: the cards are stacked)', async () => {
    setup(false);
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    tap(0);
    const first = slot(0)!;
    tap(1);
    // Lit at once; the open slot is still there, collapsing, and the slider moves only when that lands.
    expect(cmp.tray.open()).toBe('roth');
    expect(cmp.tray.shown()).toBe('k401');
    expect(slot(0)).toBe(first);
    const collapse = animate.calls.all().filter(c => c.object === first).pop()!;
    expect((collapse.args[0] as Keyframe[])[1]).toEqual({ height: '0px' });
    expect((collapse.args[1] as KeyframeAnimationOptions).duration).toBe(200);
    expect((collapse.args[1] as KeyframeAnimationOptions).easing).toBe(HOUSE_EASING);
    const inner = first.querySelector('.field-tray-in') as HTMLElement;
    // The card's fade lands first, then the wrapper's collapse: that is when the slot leaves.
    for (const anim of inner.getAnimations()) await finish(anim);
    expect(cmp.tray.shown()).toBe('k401');
    for (const anim of first.getAnimations()) await finish(anim);
    expect(cmp.tray.shown()).toBe('roth');
    expect(slot(0)).toBeNull();
    const second = slot(1)!;
    expect(second).not.toBe(first);
    const grows = animate.calls.all().filter(c => c.object === second);
    expect(grows.length).toBe(1);
    const [frames, options] = grows[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[0]).toEqual({ height: '0px' });
    expect(options.duration).toBe(240);
    expect(options.easing).toBe(HOUSE_EASING);
  });

  it('the label reads the step in force while the finger is down: $5,000 near the floor, $50,000 past $500k, the name again on release', () => {
    setup();
    tap(0);
    expect(cmp.tray.label('k401')).toBe('401(k) balance');
    cmp.tray.onDragging(true);
    fixture.detectChanges();
    expect(label(0).textContent).toBe('± $5,000');
    expect(label(1).textContent).toBe('Roth IRA balance');
    slide(1_000_000);
    expect(label(0).textContent).toBe('± $50,000');
    cmp.tray.onDragging(false);
    fixture.detectChanges();
    expect(label(0).textContent).toBe('401(k) balance');
  });

  it('Done closes the tray; so does a tap elsewhere on the step, on release', () => {
    setup();
    tap(0);
    done(0)!.click();
    fixture.detectChanges();
    expect(cmp.tray.open()).toBeNull();
    expect(slots().length).toBe(0);
    expect(done(0)).toBeNull();
    expect(button(0).classList.contains('active')).toBeFalse();

    tap(1);
    const elsewhere = outsideStep().querySelector('.fs-sub') as HTMLElement;
    elsewhere.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    fixture.detectChanges();
    expect(slots().length).toBe(1);
    elsewhere.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
    fixture.detectChanges();
    expect(cmp.tray.open()).toBeNull();
    expect(slots().length).toBe(0);
  });

  it('while the tray is shown the step column is held where it sat (top-aligned, its centred offset as padding), through a switch, and let go with the slot', () => {
    setup();
    const column = outsideStep().querySelector('.fs-center') as HTMLElement;
    const first = column.firstElementChild as HTMLElement;
    expect(column.classList.contains('tray-pinned')).toBeFalse();
    expect(column.style.paddingTop).toBe('');
    // The margin box: the first glass field's own margin-top applies again once the column is top-aligned.
    const offset = first.getBoundingClientRect().top - column.getBoundingClientRect().top
      - (parseFloat(getComputedStyle(first).marginTop) || 0);

    tap(0);
    expect(column.classList.contains('tray-pinned')).toBeTrue();
    expect(column.style.paddingTop).toBe(`${Math.max(0, offset)}px`);
    expect(cmp.outsidePinPx()).toBe(Math.max(0, offset));

    cmp.outsidePinPx.set(37);                      // a switch keeps the hold: the slot is already in the column
    tap(1);
    expect(cmp.tray.shown()).toBe('roth');
    expect(cmp.outsidePinPx()).toBe(37);
    expect(column.style.paddingTop).toBe('37px');

    done(1)!.click();
    fixture.detectChanges();
    expect(slots().length).toBe(0);
    expect(column.classList.contains('tray-pinned')).toBeFalse();
    expect(column.style.paddingTop).toBe('');
  });

  it('Continue snaps the tray shut as the step moves on, and the balance it set is saved', () => {
    setup();
    tap(0);
    slide(250_000);
    cmp.onContinueClick();
    fixture.detectChanges();
    expect(cmp.tray.open()).toBeNull();
    expect(cmp.tray.shown()).toBeNull();
    expect(slots().length).toBe(0);
    expect(cmp.currentStep()).toBe('spend');
    expect(JSON.parse(localStorage.getItem(OUTSIDE_KEY)!)).toEqual({ k401: 250_000, roth: 0 });
  });

  it('a saved balance comes back onto the button; one past the cap is shown exactly and drawn at the top end', () => {
    localStorage.setItem(OUTSIDE_KEY, JSON.stringify({ k401: 6_000_000, roth: 85_000 }));
    setup();
    expect(valueOf(0)).toBe('6,000,000');
    expect(valueOf(1)).toBe('85,000');
    tap(0);
    const t = tray()!;
    expect(t.value()).toBe(6_000_000);
    expect(t.fillPct()).toBe(100);
    expect(host().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('$6,000,000');
  });

  it('the blue-flow tokens reach the tray: white fill, the sim-range track, white minus and plus ink', () => {
    setup();
    tap(0);
    const fill = host().querySelector('.ps-fill') as HTMLElement;
    const track = host().querySelector('.ps-track') as HTMLElement;
    const nudgeBtn = host().querySelector('.nudge') as HTMLElement;
    expect(getComputedStyle(fill).backgroundColor).toBe('rgb(255, 255, 255)');
    expect(getComputedStyle(track).backgroundColor).toBe('rgba(255, 255, 255, 0.22)');
    expect(getComputedStyle(nudgeBtn).color).toBe('rgb(255, 255, 255)');
    expect(getComputedStyle(nudgeBtn).backgroundColor).toBe('rgba(255, 255, 255, 0.12)');
  });

  it('hands the tray\'s document listener back when the flow is destroyed', () => {
    setup();
    tap(0);
    const remove = spyOn(document, 'removeEventListener').and.callThrough();
    fixture.destroy();
    const types = remove.calls.all().filter(c => c.args[2] === true).map(c => c.args[0]);
    expect(types).toEqual(jasmine.arrayContaining(['pointerdown', 'pointerup', 'pointercancel']));
  });
});
