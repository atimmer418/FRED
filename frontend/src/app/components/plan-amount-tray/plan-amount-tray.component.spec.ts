/**
 * plan-amount-tray.component.spec.ts
 *
 * The tray emits per detent and per nudge; the host here plays the page: it writes each emitted
 * value back into the `value` input, the way the page's signals do. Karma's context document has
 * no layout (every rect is 0 x 0), so the band's rect is stubbed and finger positions are computed
 * against that stub.
 */
import { Component, computed, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  AmountUnit,
  HOLD_DELAY_MS,
  HOLD_INTERVAL_MS,
  PlanAmountTrayComponent,
  formatAmount,
  formatValue,
  stepLabel,
} from './plan-amount-tray.component';
import { PLAN_AMOUNT_SCALES, PiecewiseScale, PlanAmountScale, PlanField } from '../../utils/plan-amount-scale';

@Component({
  standalone: true,
  imports: [PlanAmountTrayComponent],
  template: `
    <div style="width: 328px">
      <app-plan-amount-tray
        [field]="field()"
        [value]="value()"
        [scale]="scale()"
        [unit]="unit()"
        [label]="label()"
        [valueSuffix]="valueSuffix()"
        (valueChange)="onChange($event)"
        (dragging)="drags.push($event)"
      />
    </div>
  `,
})
class HostComponent {
  readonly field = signal<string>('invest');
  readonly value = signal(1000);
  /** A scale of the host's own; otherwise the Freedom field's. */
  readonly customScale = signal<PlanAmountScale | null>(null);
  readonly scale = computed(() => this.customScale() ?? PLAN_AMOUNT_SCALES[this.field() as PlanField]);
  readonly unit = signal<AmountUnit>('dollars');
  readonly label = signal<string | undefined>(undefined);
  readonly valueSuffix = signal<string | undefined>(undefined);
  emitted: number[] = [];
  drags: boolean[] = [];

  onChange(v: number): void {
    this.emitted.push(v);
    this.value.set(v);
  }
}

/** The band as a 390px phone lays it out: 248px wide, so 220px of travel inside the thumb insets. */
const BAND_RECT = { left: 40, width: 248, top: 0, right: 288, bottom: 44, height: 44, x: 40, y: 0, toJSON: () => ({}) } as DOMRect;

describe('PlanAmountTrayComponent', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;

  function setup(field: string = 'invest', value = 1000, scale: PlanAmountScale | null = null): void {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    host.field.set(field);
    host.value.set(value);
    host.customScale.set(scale);
    fixture.detectChanges();
    spyOn(band(), 'getBoundingClientRect').and.returnValue(BAND_RECT);
  }

  /** The recurring-investment scale from the rollout spec. */
  const RECURRING: PiecewiseScale = {
    min: 5, max: 10_000, fine: 5,
    segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
  };
  const PERCENT: PlanAmountScale = { min: 0, max: 100, step: 1 };

  const tray = () => fixture.debugElement.children[0].children[0].componentInstance as PlanAmountTrayComponent;
  const band = () => fixture.nativeElement.querySelector('.ps') as HTMLElement;
  const minus = () => fixture.nativeElement.querySelector('.nudge[aria-label="Less"]') as HTMLButtonElement;
  const plus = () => fixture.nativeElement.querySelector('.nudge[aria-label="More"]') as HTMLButtonElement;

  /** The viewport x of a place along the track (0 to 1), inside the 14px thumb insets. */
  function xAt(pos: number): number {
    const r = band().getBoundingClientRect();
    return r.left + 14 + (r.width - 28) * pos;
  }

  function pointer(type: string, pos: number, pointerId = 7, clientY = 0): void {
    band().dispatchEvent(new PointerEvent(type, { clientX: xAt(pos), clientY, pointerId, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  it('reads the field: name, ends and ARIA from the scale', () => {
    setup('income', 5000);
    expect(band().getAttribute('role')).toBe('slider');
    expect(band().getAttribute('aria-label')).toBe('Income goal');
    expect(band().getAttribute('aria-valuemin')).toBe('500');
    expect(band().getAttribute('aria-valuemax')).toBe('30000');
    expect(band().getAttribute('aria-valuenow')).toBe('5000');
    expect(band().getAttribute('aria-valuetext')).toBe('$5,000/mo');
    const ends = Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')) as HTMLElement[];
    expect(ends.map(e => e.textContent)).toEqual(['$500', '$30,000']);
    expect(getComputedStyle(band()).height).toBe('44px');
    expect(getComputedStyle(band()).touchAction).toBe('pan-y');
  });

  it('draws an off-grid seed exactly, at its interpolated position, and a value over the cap at the right end', () => {
    setup('invest', 4333);
    expect(band().getAttribute('aria-valuenow')).toBe('4333');
    expect(tray().fillPct()).toBeCloseTo(43.33, 6);
    expect(tray().thumbLeft()).toContain('* 0.4333)');
    expect(host.emitted).toEqual([]);

    host.field.set('income');
    host.value.set(99_999);
    fixture.detectChanges();
    expect(band().getAttribute('aria-valuenow')).toBe('30000');
    expect(tray().thumbLeft()).toContain('* 1)');
  });

  it('draws unset income at the left end without touching the value', () => {
    setup('income', 0);
    expect(tray().thumbLeft()).toContain('* 0)');
    expect(host.emitted).toEqual([]);
    expect(minus().disabled).toBeTrue();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Drag
  // ───────────────────────────────────────────────────────────────────────────

  it('a tap lands on the detent under the finger on release; the touch itself commits nothing', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.5);
    expect(host.drags).toEqual([true]);
    expect(host.emitted).toEqual([]);
    expect(tray().isDragging()).toBeTrue();
    expect(band().classList.contains('dragging')).toBeTrue();
    pointer('pointerup', 0.5);
    expect(host.emitted).toEqual([5000]);
    expect(host.drags).toEqual([true, false]);
  });

  it('a vertical scroll that starts on the band moves nothing', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.5);
    pointer('pointermove', 0.5, 7, 12);           // straight down: the page's, not the slider's
    pointer('pointermove', 0.502, 7, 30);
    expect(host.emitted).toEqual([]);
    expect(tray().thumbLeft()).toContain('* 0.1)');
    pointer('pointercancel', 0.502, 7, 40);       // the browser hands the pointer to the scroll
    expect(host.emitted).toEqual([]);
    expect(host.drags).toEqual([true, false]);
  });

  describe('the swipe-back edge', () => {
    /** Whether a touchstart at x on the band reaches the page above it (where swipe-back listens). */
    function touchReachesPage(clientX: number): boolean {
      const page = fixture.nativeElement as HTMLElement;
      let reached = false;
      const listener = () => { reached = true; };
      page.addEventListener('touchstart', listener);
      const touch = new Touch({ identifier: 1, target: band(), clientX, clientY: 10 });
      band().dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, changedTouches: [touch], touches: [touch], targetTouches: [touch] }));
      page.removeEventListener('touchstart', listener);
      return reached;
    }

    it('a touch on bare track inside the edge is left to the page', () => {
      setup('invest', 1000);                     // the thumb sits at 10%, 26px right of x=50
      band().dispatchEvent(new PointerEvent('pointerdown', { clientX: 50, pointerId: 7, bubbles: true, cancelable: true }));
      fixture.detectChanges();
      expect(host.drags).toEqual([]);
      expect(tray().isDragging()).toBeFalse();
      band().dispatchEvent(new PointerEvent('pointerup', { clientX: 50, pointerId: 7, bubbles: true }));
      expect(host.emitted).toEqual([]);
      expect(touchReachesPage(50)).toBeTrue();
    });

    it('a touch on the thumb inside the edge is the slider\'s, and the page never sees it start', () => {
      setup('income', 0);                        // unset: the thumb is drawn at the floor, centred 4px right of x=50
      band().dispatchEvent(new PointerEvent('pointerdown', { clientX: 50, pointerId: 7, bubbles: true, cancelable: true }));
      fixture.detectChanges();
      expect(host.drags).toEqual([true]);
      expect(tray().isDragging()).toBeTrue();
      expect(touchReachesPage(50)).toBeFalse();
      pointer('pointermove', 0.2);                // raw detent 23.6: lands on 23 (65% commit)
      pointer('pointerup', 0.2);
      expect(host.value()).toBe(500 + 250 * 23);
      expect(host.drags).toEqual([true, false]);
    });
  });

  it('commits a detent only once the finger is 65% of the way to it, and the thumb follows the finger meanwhile', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.5);                 // 100 detents of track: one detent is 1%
    pointer('pointermove', 0.505);
    expect(host.emitted).toEqual([5000]);        // half-way: still $5,000
    expect(tray().thumbLeft()).toContain('* 0.505');
    pointer('pointermove', 0.5064);
    expect(host.emitted).toEqual([5000]);
    pointer('pointermove', 0.5066);
    expect(host.emitted).toEqual([5000, 5100]);
    pointer('pointermove', 0.5036);              // back down: the same hysteresis the other way
    expect(host.emitted).toEqual([5000, 5100]);
    pointer('pointermove', 0.4934);
    expect(host.emitted).toEqual([5000, 5100, 4900]);
  });

  it('release settles the thumb onto the detent and reports the end of the drag', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.5);
    pointer('pointermove', 0.503);
    pointer('pointerup', 0.503);
    expect(host.drags).toEqual([true, false]);
    expect(tray().isDragging()).toBeFalse();
    expect(tray().thumbLeft()).toContain('* 0.5)');
    expect(host.value()).toBe(5000);
  });

  it('a cancelled pointer ends the drag the same way, and a second pointer is ignored', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.2, 1);
    pointer('pointermove', 0.201, 1);            // the first horizontal move arms the drag
    pointer('pointerdown', 0.9, 2);              // a second finger: nothing
    expect(host.emitted).toEqual([2000]);
    pointer('pointermove', 0.9, 2);
    expect(host.emitted).toEqual([2000]);
    pointer('pointercancel', 0.2, 1);
    expect(host.drags).toEqual([true, false]);
  });

  it('a field switch mid-drag ends the drag: the finger does not carry on into the other field', () => {
    setup('invest', 1000);
    pointer('pointerdown', 0.2, 1);
    pointer('pointermove', 0.201, 1);            // armed: $2,000 on investing
    expect(host.emitted).toEqual([2000]);
    host.field.set('income');
    host.value.set(5000);
    fixture.detectChanges();
    expect(host.drags).toEqual([true, false]);
    expect(tray().isDragging()).toBeFalse();
    pointer('pointermove', 0.9, 1);              // the same finger: nobody's now
    expect(host.emitted).toEqual([2000]);
    expect(host.value()).toBe(5000);
  });

  it('a field switch mid-hold ends the hold: the held button does not step the other field', fakeAsync(() => {
    setup('invest', 1000);
    plus().dispatchEvent(new PointerEvent('pointerdown', { pointerId: 3, bubbles: true, cancelable: true }));
    tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
    expect(host.emitted).toEqual([1100]);
    host.field.set('income');
    host.value.set(5000);
    fixture.detectChanges();
    tick(HOLD_INTERVAL_MS * 4);
    expect(host.emitted).toEqual([1100]);
    expect(host.value()).toBe(5000);
    plus().dispatchEvent(new PointerEvent('pointerup', { pointerId: 3, bubbles: true }));
    plus().click();
    fixture.detectChanges();
    // The hold ended without a release behind it, so the click that follows is an ordinary tap.
    expect(host.value()).toBe(5250);
  }));

  it('a seed off the grid snaps onto it on the first touch; a seed over the cap clamps', () => {
    setup('invest', 4333);
    pointer('pointerdown', 0.4333);              // the finger lands where the thumb is drawn
    pointer('pointerup', 0.4333);
    expect(host.emitted).toEqual([4300]);

    host.field.set('income');
    host.value.set(99_999);
    host.emitted = [];
    fixture.detectChanges();
    pointer('pointerdown', 1);
    pointer('pointerup', 1);
    expect(host.emitted).toEqual([30_000]);
  });

  it('clamps a finger dragged past either end of the band', () => {
    setup('invest', 5000);
    pointer('pointerdown', 0.5);
    band().dispatchEvent(new PointerEvent('pointermove', { clientX: xAt(0) - 500, pointerId: 7, bubbles: true }));
    fixture.detectChanges();
    expect(host.value()).toBe(0);
    band().dispatchEvent(new PointerEvent('pointermove', { clientX: xAt(1) + 500, pointerId: 7, bubbles: true }));
    fixture.detectChanges();
    expect(host.value()).toBe(10_000);
  });

  it('the arrow keys step the value', () => {
    setup('income', 5000);
    band().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true }));
    band().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true }));
    band().dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', cancelable: true }));
    expect(host.emitted).toEqual([5250, 5500, 5250]);
    const other = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    band().dispatchEvent(other);
    expect(other.defaultPrevented).toBeFalse();
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Minus and plus
  // ───────────────────────────────────────────────────────────────────────────

  it('a tap moves one step; an off-grid value rounds in the tap direction', () => {
    setup('income', 5000);
    plus().click();
    minus().click();
    minus().click();
    expect(host.emitted).toEqual([5250, 5000, 4750]);

    host.value.set(4333);
    host.emitted = [];
    fixture.detectChanges();
    plus().click();
    expect(host.emitted).toEqual([4500]);
    host.value.set(4333);
    fixture.detectChanges();
    minus().click();
    expect(host.emitted).toEqual([4500, 4250]);
  });

  it('what it emitted for one field is never reused for the other', () => {
    setup('income', 1000);
    // The host does not render the new value back yet: the control reasons from its own emit.
    minus().click();
    expect(host.emitted).toEqual([750]);
    // The other field, whose value happens to equal the one the emit was made against.
    host.field.set('invest');
    host.value.set(1000);
    fixture.detectChanges();
    plus().click();
    expect(host.emitted).toEqual([750, 1100]);
  });

  it('dims and disables minus at the floor and plus at the cap', () => {
    setup('invest', 0);
    expect(minus().disabled).toBeTrue();
    expect(plus().disabled).toBeFalse();
    expect(getComputedStyle(minus()).opacity).toBe('0.35');
    host.value.set(10_000);
    fixture.detectChanges();
    expect(minus().disabled).toBeFalse();
    expect(plus().disabled).toBeTrue();
  });

  it('a touch on minus or plus never reaches the page above: a sheet\'s drag gesture cannot start on a held button', () => {
    setup('invest', 1000);
    const page = fixture.nativeElement as HTMLElement;
    for (const button of [minus(), plus()]) {
      let reached = false;
      const listener = () => { reached = true; };
      page.addEventListener('touchstart', listener);
      const touch = new Touch({ identifier: 1, target: button, clientX: 300, clientY: 10 });
      button.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, changedTouches: [touch], touches: [touch], targetTouches: [touch] }));
      page.removeEventListener('touchstart', listener);
      expect(reached).withContext(button.getAttribute('aria-label')!).toBeFalse();
    }
  });

  it('suppresses the long-press callout on the buttons', () => {
    setup();
    const menu = new Event('contextmenu', { cancelable: true, bubbles: true });
    plus().dispatchEvent(menu);
    expect(menu.defaultPrevented).toBeTrue();
  });

  describe('hold to repeat', () => {
    function press(btn: HTMLButtonElement): void {
      btn.dispatchEvent(new PointerEvent('pointerdown', { pointerId: 3, bubbles: true, cancelable: true }));
    }

    /** The release: pointerup, then the click the browser sends for the same press. */
    function release(btn: HTMLButtonElement): void {
      btn.dispatchEvent(new PointerEvent('pointerup', { pointerId: 3, bubbles: true }));
      btn.click();
      fixture.detectChanges();
    }

    it('starts repeating after 400ms at 8 per second, in single steps', fakeAsync(() => {
      setup('invest', 1000);
      press(plus());
      tick(HOLD_DELAY_MS);
      expect(host.emitted).toEqual([]);
      tick(HOLD_INTERVAL_MS);
      expect(host.emitted).toEqual([1100]);
      tick(HOLD_INTERVAL_MS * 3);
      expect(host.emitted).toEqual([1100, 1200, 1300, 1400]);
      release(plus());
      // The click after a hold is not a tap: no extra step.
      expect(host.emitted).toEqual([1100, 1200, 1300, 1400]);
      tick(1000);
      expect(host.emitted.length).toBe(4);
    }));

    it('shifts to x5 after 1.6s and x25 after 3s of holding', fakeAsync(() => {
      setup('income', 500);
      press(plus());
      // Ticks at 525, 650, ... each reads the time held when it fires.
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS * 9);    // 1525: nine single steps so far
      expect(host.value()).toBe(500 + 250 * 9);
      tick(HOLD_INTERVAL_MS);                        // held 1650 when it fires: x5
      expect(host.value()).toBe(500 + 250 * 14);
      tick(HOLD_INTERVAL_MS * 10);                   // through 2900, all x5
      const beforeTop = host.value();
      expect(beforeTop).toBe(500 + 250 * 64);
      tick(HOLD_INTERVAL_MS);                        // held 3025 when it fires: x25
      expect(host.value()).toBe(beforeTop + 250 * 25);
      release(plus());
    }));

    it('a short press is a tap: one step on release, nothing before', fakeAsync(() => {
      setup('invest', 1000);
      press(minus());
      tick(200);
      expect(host.emitted).toEqual([]);
      release(minus());
      expect(host.emitted).toEqual([900]);
    }));

    it('stops itself at the end of the scale, where the button disables under the finger', fakeAsync(() => {
      setup('invest', 9900);
      press(plus());
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
      fixture.detectChanges();
      expect(host.value()).toBe(10_000);
      expect(plus().disabled).toBeTrue();
      tick(HOLD_INTERVAL_MS * 20);
      expect(host.emitted).toEqual([10_000]);
    }));

    it('pointerleave cancels the hold', fakeAsync(() => {
      setup('invest', 1000);
      press(plus());
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
      expect(host.emitted).toEqual([1100]);
      plus().dispatchEvent(new PointerEvent('pointerleave', { pointerId: 3, bubbles: false }));
      tick(HOLD_INTERVAL_MS * 5);
      expect(host.emitted).toEqual([1100]);
      // No click follows a hold that ended this way, so the next keyboard tap is a tap.
      minus().click();
      expect(host.emitted).toEqual([1100, 1000]);
    }));

    it('a hold that stopped itself at the cap leaves the next keyboard tap alone', fakeAsync(() => {
      setup('invest', 9900);
      press(plus());
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
      fixture.detectChanges();
      expect(host.value()).toBe(10_000);
      // Disabled under the finger: neither the release nor a click reaches the button.
      minus().click();
      expect(host.emitted).toEqual([10_000, 9900]);
    }));

    it('the release after a touch hold still swallows its click, even with pointerleave in between', fakeAsync(() => {
      setup('invest', 1000);
      press(plus());
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
      plus().dispatchEvent(new PointerEvent('pointerup', { pointerId: 3, bubbles: true }));
      // A touch pointer leaves after it lifts, before the click the browser sends for the press.
      plus().dispatchEvent(new PointerEvent('pointerleave', { pointerId: 3, bubbles: false }));
      plus().click();
      expect(host.emitted).toEqual([1100]);
    }));

    it('the component going away ends a hold still in flight', fakeAsync(() => {
      setup('invest', 1000);
      press(plus());
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS);
      expect(host.emitted).toEqual([1100]);
      fixture.destroy();
      // A hold left running would emit into a destroyed output here, which throws.
      tick(HOLD_INTERVAL_MS * 5);
      expect(host.emitted).toEqual([1100]);
    }));
  });

  it('formats amounts and the step label the way the tile shows them', () => {
    expect(formatAmount(30_000)).toBe('$30,000');
    expect(formatAmount(0)).toBe('$0');
    expect(stepLabel(PLAN_AMOUNT_SCALES.income)).toBe('± $250');
    expect(stepLabel(PLAN_AMOUNT_SCALES.invest)).toBe('± $100');
  });

  it('formats cents when there are any, percentages, and the step in force at a value', () => {
    expect(formatAmount(1234.56)).toBe('$1,234.56');
    expect(formatAmount(1234.5)).toBe('$1,234.50');
    expect(formatValue(35, 'percent')).toBe('35%');
    expect(formatValue(12.5, 'percent')).toBe('12.5%');
    expect(formatValue(2500)).toBe('$2,500');
    expect(stepLabel(RECURRING)).toBe('± $5');
    expect(stepLabel(RECURRING, 3000)).toBe('± $250');
    expect(stepLabel(PERCENT, 40, 'percent')).toBe('± 1%');
  });

  // ───────────────────────────────────────────────────────────────────────────
  // Piecewise scales, units, naming, theming
  // ───────────────────────────────────────────────────────────────────────────

  describe('a piecewise scale', () => {
    it('reads its ends from min and max and draws a hairline tick at each breakpoint', () => {
      setup('amount', 100, RECURRING);
      const ends = Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')) as HTMLElement[];
      expect(ends.map(e => e.textContent)).toEqual(['$5', '$10,000']);
      const marks = Array.from(fixture.nativeElement.querySelectorAll('.ps-mark')) as HTMLElement[];
      expect(marks.length).toBe(3);
      // 152 detents: $500 is detent 99, $2,000 is 129, $5,000 is 141. (The browser normalises the
      // calc on the element, so the places are read off the signal, as the thumb's is.)
      const lefts = tray().markLefts();
      expect(lefts[0]).toContain(`* ${99 / 151})`);
      expect(lefts[1]).toContain(`* ${129 / 151})`);
      expect(lefts[2]).toContain(`* ${141 / 151})`);
      expect(marks[0].style.left).not.toBe('');
      expect(getComputedStyle(marks[0]).width).toBe('1px');
    });

    it('draws no ticks on a uniform scale', () => {
      setup('income', 5000);
      expect(fixture.nativeElement.querySelectorAll('.ps-mark').length).toBe(0);
    });

    it('drags in index space: the same finger distance per detent, whatever the dollars', () => {
      setup('amount', 5, RECURRING);
      pointer('pointerdown', 99 / 151);
      pointer('pointerup', 99 / 151);
      expect(host.emitted).toEqual([500]);
      pointer('pointerdown', 130 / 151);
      pointer('pointerup', 130 / 151);
      expect(host.emitted).toEqual([500, 2250]);
      expect(tray().fillPct()).toBeCloseTo((130 / 151) * 100, 6);
    });

    it('nudges by the fine step, not the slider step, and reaches the cap', () => {
      setup('amount', 2000, RECURRING);
      plus().click();
      minus().click();
      minus().click();
      expect(host.emitted).toEqual([2005, 2000, 1995]);
      host.value.set(9995);
      fixture.detectChanges();
      plus().click();
      fixture.detectChanges();
      expect(host.value()).toBe(10_000);
      expect(plus().disabled).toBeTrue();
    });

    it('a held button repeats in fine steps and shifts gears the same way', fakeAsync(() => {
      setup('amount', 1000, RECURRING);
      plus().dispatchEvent(new PointerEvent('pointerdown', { pointerId: 3, bubbles: true, cancelable: true }));
      tick(HOLD_DELAY_MS + HOLD_INTERVAL_MS * 3);
      expect(host.emitted).toEqual([1005, 1010, 1015]);
      plus().dispatchEvent(new PointerEvent('pointerup', { pointerId: 3, bubbles: true }));
      plus().click();
      expect(host.emitted.length).toBe(3);
    }));

    it('a cap that is not round is the last detent, and its label shows the cents', () => {
      const cash: PiecewiseScale = { min: 5, max: 1234.56, fine: 5, segments: [{ to: 500, step: 5 }, { to: 1234.56, step: 25 }] };
      setup('withdraw', 100, cash);
      const ends = Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')) as HTMLElement[];
      expect(ends[1].textContent).toBe('$1,234.56');
      pointer('pointerdown', 1);
      pointer('pointerup', 1);
      expect(host.value()).toBe(1234.56);
      expect(band().getAttribute('aria-valuenow')).toBe('1234.56');
    });
  });

  it('a percent field speaks percentages, with no dollar sign anywhere', () => {
    setup('share', 35, PERCENT);
    host.unit.set('percent');
    fixture.detectChanges();
    const ends = Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')) as HTMLElement[];
    expect(ends.map(e => e.textContent)).toEqual(['0%', '100%']);
    expect(band().getAttribute('aria-valuetext')).toBe('35%');
    expect(fixture.nativeElement.querySelector('.tray-in').textContent).not.toContain('$');
  });

  it('takes its spoken name and value suffix from the host; the Freedom fields keep theirs without asking', () => {
    setup('amount', 100, RECURRING);
    expect(band().getAttribute('aria-label')).toBe('amount');
    expect(band().getAttribute('aria-valuetext')).toBe('$100');
    host.label.set('Investment amount');
    host.valueSuffix.set(' per paycheck');
    fixture.detectChanges();
    expect(band().getAttribute('aria-label')).toBe('Investment amount');
    expect(band().getAttribute('aria-valuetext')).toBe('$100 per paycheck');

    host.field.set('invest');
    host.customScale.set(null);
    host.label.set(undefined);
    host.valueSuffix.set(undefined);
    fixture.detectChanges();
    expect(band().getAttribute('aria-label')).toBe('Investing');
    expect(band().getAttribute('aria-valuetext')).toBe('$100/mo');
  });

  it('wears the white-page colours by default and takes --tray-* custom properties from any ancestor', () => {
    setup('invest', 1000);
    const fill = fixture.nativeElement.querySelector('.ps-fill') as HTMLElement;
    const card = fixture.nativeElement.querySelector('.tray-in') as HTMLElement;
    const thumbDot = getComputedStyle(fixture.nativeElement.querySelector('.ps-thumb'), '::after').backgroundColor;
    expect(getComputedStyle(fill).backgroundColor).toBe('rgb(37, 99, 235)');
    expect(getComputedStyle(card).backgroundColor).toBe('rgb(248, 250, 252)');
    expect(thumbDot).toBe('rgb(37, 99, 235)');
    expect(getComputedStyle(minus()).color).toBe('rgb(37, 99, 235)');

    const wrapper = fixture.nativeElement.firstElementChild as HTMLElement;
    wrapper.style.setProperty('--tray-fill', 'rgb(17, 24, 39)');
    wrapper.style.setProperty('--tray-surface', 'rgb(1, 2, 3)');
    wrapper.style.setProperty('--tray-nudge-ink', 'rgb(4, 5, 6)');
    wrapper.style.setProperty('--tray-label', 'rgb(7, 8, 9)');
    expect(getComputedStyle(fill).backgroundColor).toBe('rgb(17, 24, 39)');
    expect(getComputedStyle(card).backgroundColor).toBe('rgb(1, 2, 3)');
    expect(getComputedStyle(minus()).color).toBe('rgb(4, 5, 6)');
    expect(getComputedStyle(fixture.nativeElement.querySelector('.ps-ends')).color).toBe('rgb(7, 8, 9)');
  });
});
