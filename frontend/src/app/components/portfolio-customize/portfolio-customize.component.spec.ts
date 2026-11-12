/**
 * portfolio-customize.component.spec.ts
 *
 * Each row's allocation opens the slide tray under that row instead of the keyboard: one tray
 * for the whole list, whole percents 0 to 100, tapping another row switches it. The value lands
 * on the stock exactly as typing did, so the 100% sum check and the save payload are unchanged.
 * Karma's context has no layout, so the tray's motion is driven to its end by hand where a spec
 * needs the swap or the collapse to land.
 */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { NavController } from '@ionic/angular';
import { of } from 'rxjs';
import { PortfolioCustomizeComponent } from './portfolio-customize.component';
import { PlanAmountTrayComponent } from '../plan-amount-tray/plan-amount-tray.component';
import { KeyboardAvoidDirective } from '../../directives/keyboard-avoid.directive';
import { ToastService } from '../../services/toast.service';
import { PasskeyService } from '../../services/passkey.service';
import { PinService } from '../../services/pin.service';

const SAVED_PORTFOLIO = {
  id: 1,
  name: 'Mine',
  totalPercentage: 100,
  isDefault: false,
  createdAt: '',
  updatedAt: '',
  portfolioItems: [
    { id: 1, symbol: 'VTI', name: 'Vanguard Total Stock Market ETF', percentage: 75, assetType: 'ETF' },
    { id: 2, symbol: 'VXUS', name: 'Vanguard Total International Stock ETF', percentage: 20, assetType: 'ETF' },
    { id: 3, symbol: 'AAPL', name: 'Apple Inc.', percentage: 5, assetType: 'STOCK' },
  ],
};

describe('PortfolioCustomizeComponent', () => {
  let fixture: ComponentFixture<PortfolioCustomizeComponent>;
  let component: PortfolioCustomizeComponent;
  let http: HttpTestingController;
  let showToast: jasmine.Spy;
  let show: jasmine.Spy;

  beforeEach(async () => {
    showToast = jasmine.createSpy('showToast');
    show = jasmine.createSpy('show');
    const pin = jasmine.createSpyObj<PinService>('PinService', ['hasPin', 'promptPin']);
    pin.hasPin.and.resolveTo(false);
    TestBed.configureTestingModule({
      imports: [PortfolioCustomizeComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: ToastService, useValue: { showToast, show } },
        { provide: PasskeyService, useValue: {} },
        { provide: PinService, useValue: pin },
        { provide: NavController, useValue: jasmine.createSpyObj<NavController>('NavController', ['navigateBack']) },
      ],
    })
      // The Capacitor Keyboard plugin rejects on web, and the directive's listener would surface
      // that as an uncaught error in whichever spec is running when ion-content hydrates.
      .overrideComponent(PortfolioCustomizeComponent, { remove: { imports: [KeyboardAvoidDirective] } });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PortfolioCustomizeComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    http.expectOne(req => req.url.endsWith('/portfolio/current')).flush(SAVED_PORTFOLIO);
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const root = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(root().querySelectorAll('ion-item-sliding')) as HTMLElement[];
  const buttons = () => Array.from(root().querySelectorAll('.alloc-btn')) as HTMLButtonElement[];
  const slots = () => Array.from(root().querySelectorAll('.field-tray')) as HTMLElement[];
  const done = () => root().querySelector('.field-tray-done') as HTMLButtonElement | null;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
  const stock = (symbol: string) => component.portfolio.find(s => s.symbol === symbol)!;

  function tapRow(index: number): void {
    buttons()[index].click();
    fixture.detectChanges();
  }

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

  /** Lands the open slot's collapse: on a close the slot leaves; on a row switch the other row's slot takes its place. */
  async function settleClose(): Promise<void> {
    for (const anim of slots()[0]?.getAnimations() ?? []) await finish(anim);
  }

  function tapOutside(target: Element): void {
    target.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 7 }));
    target.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 7 }));
    fixture.detectChanges();
  }

  it('renders the saved rows with a value button and % each, and no number inputs', () => {
    expect(component).toBeTruthy();
    expect(rows().length).toBe(3);
    expect(root().querySelector('input[type="number"]')).toBeNull();
    expect(buttons().map(text)).toEqual(['75 %', '20 %', '5 %']);
    expect(buttons()[2].getAttribute('aria-label')).toBe('AAPL allocation');
    expect(slots().length).toBe(0);
    expect(done()).toBeNull();
    expect(component.getTotalAllocation()).toBe(100);
  });

  it('a row tap opens the tray under that row on a 0 to 100 percent scale, with Done in the card header', () => {
    tapRow(1);
    expect(component.tray.open()).toBe('VXUS');
    expect(buttons()[1].classList.contains('active')).toBeTrue();
    expect(buttons()[1].getAttribute('aria-expanded')).toBe('true');
    expect(rows()[1].querySelector('ion-item')!.classList.contains('active')).toBeTrue();
    expect(slots().length).toBe(1);
    expect(rows()[1].nextElementSibling).toBe(slots()[0]);
    expect(done()).not.toBeNull();
    expect(done()!.closest('.card-header-row')).not.toBeNull();

    const t = tray()!;
    expect(t.field()).toBe('VXUS');
    expect(t.value()).toBe(20);
    expect(t.scale()).toEqual({ min: 0, max: 100, step: 1, fine: 1 });
    expect(t.unit()).toBe('percent');
    const slider = root().querySelector('.ps')!;
    expect(slider.getAttribute('aria-label')).toBe('VXUS allocation');
    expect(slider.getAttribute('aria-valuemin')).toBe('0');
    expect(slider.getAttribute('aria-valuemax')).toBe('100');
    expect(slider.getAttribute('aria-valuetext')).toBe('20%');
    expect(Array.from(root().querySelectorAll('.ps-ends span')).map(e => e.textContent)).toEqual(['0%', '100%']);
  });

  it('a detent lands on that stock alone; the total and the sum check follow', () => {
    tapRow(0);
    slide(70);
    expect(stock('VTI').percentage).toBe(70);
    expect(stock('VXUS').percentage).toBe(20);
    expect(text(buttons()[0])).toBe('70 %');
    expect(tray()!.value()).toBe(70);
    expect(root().querySelector('.ps')!.getAttribute('aria-valuetext')).toBe('70%');
    expect(component.getTotalAllocation()).toBe(95);
    expect(component.isValidAllocation()).toBeFalse();
    expect(component.hasPortfolioChanged()).toBeTrue();
    slide(75);
    expect(component.isValidAllocation()).toBeTrue();
    expect(component.hasPortfolioChanged()).toBeFalse();
  });

  it('tapping another row switches the tray to it: lit at once, the open slot collapses and the slot under the new row grows when it lands', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    tapRow(0);
    const first = slots()[0];
    tapRow(2);
    expect(component.tray.open()).toBe('AAPL');
    expect(component.tray.shown()).toBe('VTI');
    expect(buttons()[0].classList.contains('active')).toBeFalse();
    expect(buttons()[2].classList.contains('active')).toBeTrue();
    // The slot under VTI is still there, collapsing; the rows between never snap.
    expect(slots().length).toBe(1);
    expect(rows()[0].nextElementSibling).toBe(first);
    const collapse = animate.calls.all().filter(c => c.object === first).pop()!;
    expect((collapse.args[0] as Keyframe[])[1]).toEqual({ height: '0px' });
    expect((collapse.args[1] as KeyframeAnimationOptions).duration).toBe(200);
    await settleClose();
    expect(component.tray.shown()).toBe('AAPL');
    expect(slots().length).toBe(1);
    expect(slots()[0]).not.toBe(first);
    expect(rows()[2].nextElementSibling).toBe(slots()[0]);
    const grow = animate.calls.all().filter(c => c.object === slots()[0])[0];
    expect((grow.args[0] as Keyframe[])[0]).toEqual({ height: '0px' });
    expect((grow.args[1] as KeyframeAnimationOptions).duration).toBe(240);
    expect(tray()!.field()).toBe('AAPL');
    expect(tray()!.value()).toBe(5);
    slide(10);
    expect(stock('AAPL').percentage).toBe(10);
    expect(stock('VTI').percentage).toBe(75);
  });

  it('the one Done in the card header survives a row switch visible: its fade-out\'s held frame is dropped before it fades back in', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    tapRow(0);
    const shared = done()!;
    tapRow(2);
    const onDone = () => animate.calls.all().filter(c => c.object === shared);
    const fadeOut = onDone().pop()!;
    expect((fadeOut.args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });
    expect((fadeOut.args[1] as KeyframeAnimationOptions).fill).toBe('forwards');
    await settleClose();
    expect(done()).toBe(shared);
    // The held fade-out is cancelled, not left under the fade-in to win back once that ends.
    expect((fadeOut.returnValue as Animation).playState).toBe('idle');
    const fadeIn = onDone().pop()!;
    expect(fadeIn).not.toBe(fadeOut);
    expect((fadeIn.args[0] as Keyframe[])[1]).toEqual({ opacity: 1 });
    expect(shared.getAnimations()).toEqual([fadeIn.returnValue as Animation]);
  });

  it('Done closes the tray; the slot and Done leave when the collapse lands', async () => {
    tapRow(0);
    done()!.click();
    fixture.detectChanges();
    expect(component.tray.open()).toBeNull();
    expect(buttons()[0].classList.contains('active')).toBeFalse();
    await settleClose();
    expect(component.tray.shown()).toBeNull();
    expect(slots().length).toBe(0);
    expect(done()).toBeNull();
  });

  it('tapping the open row again closes it; a tap elsewhere closes it; a tap on the slider does not', () => {
    tapRow(0);
    tapRow(0);
    expect(component.tray.open()).toBeNull();
    tapRow(1);
    tapOutside(root().querySelector('.ps')!);
    expect(component.tray.open()).toBe('VXUS');
    tapOutside(root().querySelector('.card-title')!);
    expect(component.tray.open()).toBeNull();
  });

  it('removing the open row snaps the tray shut with it', async () => {
    tapRow(2);
    component.removeStock(stock('AAPL'));
    fixture.detectChanges();
    expect(component.tray.open()).toBeNull();
    expect(component.tray.shown()).toBeNull();
    expect(slots().length).toBe(0);
    // The row's leave animation lets go of it a microtask later.
    await fixture.whenStable();
    fixture.detectChanges();
    expect(rows().length).toBe(2);
    expect(component.portfolio.map(s => s.symbol)).toEqual(['VTI', 'VXUS']);
  });

  it('resetting to the default rows closes the tray first', () => {
    tapRow(0);
    component.resetToDefault();
    fixture.detectChanges();
    expect(component.tray.shown()).toBeNull();
    expect(slots().length).toBe(0);
    expect(component.portfolio.map(s => s.symbol)).toEqual(['VTI', 'VXUS', 'VBR']);
  });

  it('Save closes the tray before the sum check runs', async () => {
    tapRow(0);
    slide(70);
    await component.savePortfolio();
    expect(component.tray.open()).toBeNull();
    expect(showToast).toHaveBeenCalledWith('Portfolio allocations must add up to 100%', 'danger');
  });

  it('leaving the page snaps the tray shut', () => {
    tapRow(1);
    component.ionViewWillLeave();
    fixture.detectChanges();
    expect(component.tray.open()).toBeNull();
    expect(component.tray.shown()).toBeNull();
    expect(slots().length).toBe(0);
  });

  it('adding a symbol already in the list warns with a toast, not a browser alert, and leaves the list alone', () => {
    const alert = spyOn(window, 'alert');
    component.newStock = { symbol: 'aapl', percentage: 10 };
    component.addCustomStock();
    expect(show).toHaveBeenCalledWith({ severity: 'warning', message: 'This stock is already in your portfolio' });
    expect(alert).not.toHaveBeenCalled();
    expect(component.portfolio.length).toBe(3);
    expect(component.newStock.symbol).toBe('aapl');
  });
});
