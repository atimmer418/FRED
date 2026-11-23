/**
 * amount-tray-host.spec.ts
 *
 * The page side of a tray on a form screen, driven through a small page that hosts two fields
 * the way the screens will: a label row with Done, a value button per field, and the slot under
 * the row. Karma's context document has no layout, so measured heights are whatever scrollHeight
 * says and rects are 0 x 0.
 */
import { Component, DestroyRef, ElementRef, inject, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { AmountTrayHost, HOUSE_EASING } from './amount-tray-host';
import { AmountTraySlotComponent } from './amount-tray-slot.component';
import { PlanAmountTrayComponent } from './plan-amount-tray.component';
import { PiecewiseScale, PlanAmountScale } from '../../utils/plan-amount-scale';

type Field = 'amount' | 'share' | 'plain';

const RECURRING: PiecewiseScale = {
  min: 5, max: 10_000, fine: 5,
  segments: [{ to: 500, step: 5 }, { to: 2000, step: 50 }, { to: 5000, step: 250 }, { to: 10_000, step: 500 }],
};
const PERCENT: PlanAmountScale = { min: 0, max: 100, step: 1 };

let reduced = false;

@Component({
  standalone: true,
  imports: [AmountTraySlotComponent],
  template: `
    <section class="card">
      <div class="field-label-row">
        <label class="field-label" [class.active]="tray.isOpen('amount')">{{ tray.label('amount') }}</label>
        @if (tray.shown()) {
          <button type="button" class="field-tray-done" (click)="tray.close()">Done</button>
        }
      </div>
      <button type="button" class="field-value-btn" id="amount" [class.active]="tray.isOpen('amount')" (click)="tray.toggle('amount')">
        <span class="field-affix">$</span>{{ amount() }}
      </button>
      @if (tray.shown()) {
        <app-amount-tray-slot [tray]="tray" />
      }
      <label class="field-label" [class.active]="tray.isOpen('share')">{{ tray.label('share') }}</label>
      <button type="button" class="field-value-btn" id="share" [class.active]="tray.isOpen('share')" (click)="tray.toggle('share')">{{ share() }}%</button>
      <button type="button" class="field-value-btn" id="plain" (click)="tray.toggle('plain')">{{ model.amount }}</button>
    </section>
    <p class="elsewhere">Something else on the page</p>
  `,
})
class HostPage {
  readonly amount = signal(100);
  readonly share = signal(35);
  readonly liveScale = signal<PlanAmountScale>(PERCENT);
  /** A field over a plain object, as a page with a form model registers one. */
  readonly model = { amount: 10 };
  opened: Field[] = [];

  readonly tray = new AmountTrayHost<Field>({
    root: inject(ElementRef),
    destroyRef: inject(DestroyRef),
    fields: {
      amount: { label: 'Investment amount', scale: RECURRING, value: this.amount, set: v => this.amount.set(v) },
      share: { label: 'Allocation', unit: 'percent', scale: this.liveScale, value: this.share, set: v => this.share.set(v) },
      plain: { label: 'Plain', scale: PERCENT, value: () => this.model.amount, set: v => { this.model.amount = v; } },
    },
    onOpen: field => this.opened.push(field),
    reducedMotion: () => reduced,
  });
}

describe('AmountTrayHost', () => {
  let fixture: ComponentFixture<HostPage>;
  let page: HostPage;

  function setup(reducedMotion = false): void {
    reduced = reducedMotion;
    TestBed.configureTestingModule({ imports: [HostPage] });
    fixture = TestBed.createComponent(HostPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
  }

  const field = (id: Field) => fixture.nativeElement.querySelector(`#${id}`) as HTMLButtonElement;
  const labelOf = (id: Field) => fixture.nativeElement.querySelectorAll('.field-label')[id === 'amount' ? 0 : 1] as HTMLElement;
  const slot = () => fixture.nativeElement.querySelector('.field-tray') as HTMLElement | null;
  const inner = () => fixture.nativeElement.querySelector('.field-tray-in') as HTMLElement;
  const done = () => fixture.nativeElement.querySelector('.field-tray-done') as HTMLButtonElement | null;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;

  function tap(id: Field): void {
    field(id).click();
    fixture.detectChanges();
  }

  async function finish(anim: Animation): Promise<void> {
    const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
    anim.finish();
    await landed;
    fixture.detectChanges();
  }

  async function settleSwap(): Promise<void> {
    for (const anim of inner().getAnimations()) await finish(anim);
  }

  it('starts closed: no slot, no Done, nothing lit', () => {
    setup();
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
    expect(labelOf('amount').textContent).toBe('Investment amount');
  });

  it('a field tap opens the tray on it: lit field and label, slot under the row, Done in the label row, tray bound to the field', () => {
    setup();
    tap('amount');
    expect(page.tray.open()).toBe('amount');
    expect(page.tray.shown()).toBe('amount');
    expect(page.tray.isOpen('amount')).toBeTrue();
    expect(field('amount').classList.contains('active')).toBeTrue();
    expect(labelOf('amount').classList.contains('active')).toBeTrue();
    expect(field('share').classList.contains('active')).toBeFalse();
    expect(slot()).not.toBeNull();
    expect(field('amount').nextElementSibling).toBe(slot());
    expect(done()).not.toBeNull();
    expect(page.opened).toEqual(['amount']);
    const t = tray()!;
    expect(t.field()).toBe('amount');
    expect(t.value()).toBe(100);
    expect(t.scale()).toBe(RECURRING);
    expect(t.unit()).toBe('dollars');
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-label')).toBe('Investment amount');
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-valuetext')).toBe('$100');
  });

  it('grows the slot with WAAPI: the wrapper from 0 to its measured height, the card fading in with a lift, Done fading in', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tap('amount');
    const box = slot()!;
    const boxCalls = animate.calls.all().filter(c => c.object === box);
    expect(boxCalls.length).toBe(1);
    const [frames, options] = boxCalls[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[0]).toEqual({ height: '0px' });
    expect(frames[1]).toEqual({ height: `${box.scrollHeight}px` });
    expect(options.duration).toBe(240);
    expect(options.easing).toBe(HOUSE_EASING);

    const innerCalls = animate.calls.all().filter(c => c.object === inner());
    expect(innerCalls.length).toBe(1);
    const [innerFrames, innerOptions] = innerCalls[0].args as [Keyframe[], KeyframeAnimationOptions];
    expect(innerFrames[0]).toEqual({ opacity: 0, transform: 'translateY(-6px)' });
    expect(innerFrames[1]).toEqual({ opacity: 1, transform: 'translateY(0px)' });
    expect(innerOptions.duration).toBe(240);
    expect(innerOptions.easing).toBe(HOUSE_EASING);

    const doneCalls = animate.calls.all().filter(c => c.object === done());
    expect(doneCalls.length).toBe(1);
    expect((doneCalls[0].args[1] as KeyframeAnimationOptions).duration).toBe(160);
  });

  it('a value from the tray lands on the shown field', () => {
    setup();
    tap('amount');
    tray()!.valueChange.emit(2005);
    fixture.detectChanges();
    expect(page.amount()).toBe(2005);
    expect(tray()!.value()).toBe(2005);
    expect(page.share()).toBe(35);
  });

  it('a value behind a plain getter is read fresh on every render: after a detent, and after the model is replaced under an open tray', () => {
    setup(true);
    tap('plain');
    expect(tray()!.value()).toBe(10);
    tray()!.valueChange.emit(40);
    fixture.detectChanges();
    expect(page.model.amount).toBe(40);
    expect(tray()!.value()).toBe(40);
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-valuenow')).toBe('40');
    // A load landing while the tray is open: the page writes the model with no signal in sight.
    page.model.amount = 70;
    fixture.detectChanges();
    expect(tray()!.value()).toBe(70);
    expect(page.tray.value()).toBe(70);
  });

  it('tapping the other field switches the tray: lit at once, the slider swapped when the fade-out lands, no second slot', async () => {
    setup();
    tap('amount');
    tap('share');
    expect(page.tray.open()).toBe('share');
    expect(page.tray.shown()).toBe('amount');
    expect(field('share').classList.contains('active')).toBeTrue();
    expect(field('amount').classList.contains('active')).toBeFalse();
    await settleSwap();
    expect(page.tray.shown()).toBe('share');
    expect(fixture.debugElement.queryAll(By.directive(PlanAmountTrayComponent)).length).toBe(1);
    expect(fixture.nativeElement.querySelectorAll('.field-tray').length).toBe(1);
    const t = tray()!;
    expect(t.field()).toBe('share');
    expect(t.value()).toBe(35);
    expect(t.unit()).toBe('percent');
    const ends = Array.from(fixture.nativeElement.querySelectorAll('.ps-ends span')) as HTMLElement[];
    expect(ends.map(e => e.textContent)).toEqual(['0%', '100%']);
    expect(fixture.nativeElement.querySelector('.ps').getAttribute('aria-valuetext')).toBe('35%');
    expect(page.opened).toEqual(['amount', 'share']);
  });

  it('a live scale getter is followed: a new scale object reaches the tray', async () => {
    setup(true);
    tap('share');
    expect(tray()!.scale()).toBe(PERCENT);
    const wider: PlanAmountScale = { min: 0, max: 200, step: 2 };
    page.liveScale.set(wider);
    fixture.detectChanges();
    expect(tray()!.scale()).toBe(wider);
  });

  it('a switch waits while a finger is down on the slider', () => {
    setup(true);
    tap('amount');
    page.tray.onDragging(true);
    fixture.detectChanges();
    tap('share');
    expect(page.tray.open()).toBe('amount');
    page.tray.onDragging(false);
    fixture.detectChanges();
    tap('share');
    expect(page.tray.open()).toBe('share');
  });

  it('tapping the open field again collapses the tray and removes the slot only when the collapse lands', async () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tap('amount');
    const box = slot()!;
    const card = inner();
    const doneBtn = done()!;
    tap('amount');
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBe('amount');
    expect(field('amount').classList.contains('active')).toBeFalse();
    expect(slot()).not.toBeNull();

    const collapse = animate.calls.all().filter(c => c.object === box)[1];
    const [frames, options] = collapse.args as [Keyframe[], KeyframeAnimationOptions];
    expect(frames[1]).toEqual({ height: '0px' });
    expect(options.duration).toBe(200);
    expect(options.easing).toBe(HOUSE_EASING);
    const fadeOut = animate.calls.all().filter(c => c.object === card)[1];
    expect((fadeOut.args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });
    expect((fadeOut.args[1] as KeyframeAnimationOptions).duration).toBe(140);
    const doneOut = animate.calls.all().filter(c => c.object === doneBtn)[1];
    expect((doneOut.args[1] as KeyframeAnimationOptions).duration).toBe(120);

    await finish(collapse.returnValue as Animation);
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a second close mid-collapse changes nothing; a re-open mid-collapse grows the tray back and keeps the one slot', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tap('amount');
    const box = slot()!;
    tap('amount');
    const before = animate.calls.count();
    done()!.click();
    fixture.detectChanges();
    expect(animate.calls.count()).toBe(before);
    expect(page.tray.shown()).toBe('amount');

    tap('amount');
    expect(page.tray.open()).toBe('amount');
    expect(page.tray.shown()).toBe('amount');
    expect(fixture.nativeElement.querySelectorAll('.field-tray').length).toBe(1);
    expect(slot()).toBe(box);
    const grows = animate.calls.all().filter(c => c.object === box);
    expect(grows.length).toBe(3);
    expect((grows[2].args[1] as KeyframeAnimationOptions).duration).toBe(240);
    // Done came back from its fade-out instead of leaving.
    const doneCalls = animate.calls.all().filter(c => c.object === done());
    expect((doneCalls[doneCalls.length - 1].args[0] as Keyframe[])[1]).toEqual({ opacity: 1 });
  });

  it('Done closes the tray', () => {
    setup(true);
    tap('amount');
    done()!.click();
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
    expect(done()).toBeNull();
  });

  it('a tap outside the fields, the tray and Done closes it on release; taps on them do not', () => {
    setup(true);
    const pointer = (type: string, target: Element, pointerId = 1) => {
      target.dispatchEvent(new PointerEvent(type, { bubbles: true, pointerId }));
      fixture.detectChanges();
    };
    const press = (target: Element) => { pointer('pointerdown', target); pointer('pointerup', target); };
    const elsewhere = () => fixture.nativeElement.querySelector('.elsewhere') as HTMLElement;
    tap('amount');

    press(fixture.nativeElement.querySelector('.ps'));
    expect(slot()).not.toBeNull();
    press(field('share'));
    expect(slot()).not.toBeNull();
    press(done()!);
    expect(slot()).not.toBeNull();
    // A touch outside that turns into a scroll is not a tap; nor is a release that did not start outside.
    pointer('pointerdown', elsewhere());
    pointer('pointercancel', elsewhere());
    expect(slot()).not.toBeNull();
    pointer('pointerdown', fixture.nativeElement.querySelector('.ps'), 2);
    pointer('pointerup', elsewhere(), 2);
    expect(slot()).not.toBeNull();
    // A release that travelled is a drag that ended (a sheet pulled and let go), not a tap.
    pointer('pointerdown', elsewhere());
    elsewhere().dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1, clientX: 0, clientY: 40 }));
    fixture.detectChanges();
    expect(slot()).not.toBeNull();
    expect(page.tray.open()).toBe('amount');

    pointer('pointerdown', elsewhere());
    expect(slot()).not.toBeNull();
    pointer('pointerup', elsewhere());
    expect(page.tray.open()).toBeNull();
    expect(slot()).toBeNull();
  });

  it('the label reads the step in force while the finger is down, and the name again on release, with a fade each way', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup();
    tap('amount');
    expect(page.tray.label('amount')).toBe('Investment amount');
    const before = animate.calls.count();
    page.tray.onDragging(true);
    fixture.detectChanges();
    expect(page.tray.showsStep('amount')).toBeTrue();
    expect(page.tray.showsStep('share')).toBeFalse();
    expect(labelOf('amount').textContent).toBe('± $5');          // $100 sits in the $5 segment
    expect(page.tray.label('share')).toBe('Allocation');
    const swaps = animate.calls.all().slice(before).filter(c => c.object === labelOf('amount'));
    expect(swaps.length).toBe(1);
    expect((swaps[0].args[1] as KeyframeAnimationOptions).duration).toBe(160);

    page.amount.set(3000);
    expect(page.tray.label('amount')).toBe('± $250');           // and $3,000 in the $250 one
    page.tray.onDragging(false);
    fixture.detectChanges();
    expect(labelOf('amount').textContent).toBe('Investment amount');
    expect(animate.calls.all().slice(before).filter(c => c.object === labelOf('amount')).length).toBe(2);
  });

  it('speaks a percent step for a percent field', () => {
    setup(true);
    tap('share');
    page.tray.onDragging(true);
    expect(page.tray.label('share')).toBe('± 1%');
  });

  it('close(true) snaps shut at once, for leaving the page', () => {
    setup();
    tap('amount');
    page.tray.close(true);
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
  });

  it('under reduced motion the tray snaps open and shut with no animation', () => {
    const animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    setup(true);
    tap('amount');
    expect(slot()).not.toBeNull();
    page.tray.onDragging(true);
    page.tray.onDragging(false);
    tap('share');
    expect(page.tray.shown()).toBe('share');
    tap('share');
    expect(page.tray.shown()).toBeNull();
    expect(slot()).toBeNull();
    expect(animate).not.toHaveBeenCalled();
  });

  it('hands the document listener back when the page is destroyed', () => {
    setup(true);
    const remove = spyOn(document, 'removeEventListener').and.callThrough();
    tap('amount');
    fixture.destroy();
    const types = remove.calls.all().filter(c => c.args[1] === (page.tray as unknown as { onOutsidePointer: EventListener }).onOutsidePointer).map(c => c.args[0]);
    expect(types).toEqual(jasmine.arrayContaining(['pointerdown', 'pointerup', 'pointercancel']));
  });
});

/** Two fields in separate cards, each with its own slot and Done, the way the Monte Carlo balances sit. */
@Component({
  standalone: true,
  imports: [AmountTraySlotComponent],
  template: `
    <section class="card" id="card-amount">
      <div class="field-label-row">
        <label class="field-label" [class.active]="tray.isOpen('amount')">{{ tray.label('amount') }}</label>
        @if (tray.shown() === 'amount') {
          <button type="button" class="field-tray-done" (click)="tray.close()">Done</button>
        }
      </div>
      <button type="button" class="field-value-btn" id="amount" [class.active]="tray.isOpen('amount')" (click)="tray.toggle('amount')">{{ amount() }}</button>
      @if (tray.shown() === 'amount') {
        <app-amount-tray-slot [tray]="tray" field="amount" />
      }
    </section>
    <section class="card" id="card-share">
      <div class="field-label-row">
        <label class="field-label" [class.active]="tray.isOpen('share')">{{ tray.label('share') }}</label>
        @if (tray.shown() === 'share') {
          <button type="button" class="field-tray-done" (click)="tray.close()">Done</button>
        }
      </div>
      <button type="button" class="field-value-btn" id="share" [class.active]="tray.isOpen('share')" (click)="tray.toggle('share')">{{ share() }}%</button>
      @if (tray.shown() === 'share') {
        <app-amount-tray-slot [tray]="tray" field="share" />
      }
    </section>
    <p class="below">Simulating with something</p>
  `,
})
class PerFieldPage {
  readonly amount = signal(100);
  readonly share = signal(35);
  readonly tray = new AmountTrayHost<Field>({
    root: inject(ElementRef),
    destroyRef: inject(DestroyRef),
    fields: {
      amount: { label: 'Investment amount', scale: RECURRING, value: this.amount, set: v => this.amount.set(v) },
      share: { label: 'Allocation', unit: 'percent', scale: PERCENT, value: this.share, set: v => this.share.set(v) },
      plain: { label: 'Plain', scale: PERCENT, value: () => 0, set: () => undefined },
    },
    reducedMotion: () => reduced,
  });
}

describe('AmountTrayHost with a slot per field', () => {
  let fixture: ComponentFixture<PerFieldPage>;
  let page: PerFieldPage;
  let animate: jasmine.Spy;

  function setup(reducedMotion = false): void {
    reduced = reducedMotion;
    animate = spyOn(HTMLElement.prototype, 'animate').and.callThrough();
    TestBed.configureTestingModule({ imports: [PerFieldPage] });
    fixture = TestBed.createComponent(PerFieldPage);
    page = fixture.componentInstance;
    fixture.detectChanges();
  }

  const field = (id: Field) => fixture.nativeElement.querySelector(`#${id}`) as HTMLButtonElement;
  const card = (id: Field) => fixture.nativeElement.querySelector(`#card-${id}`) as HTMLElement;
  const slots = () => Array.from(fixture.nativeElement.querySelectorAll('.field-tray')) as HTMLElement[];
  const done = () => fixture.nativeElement.querySelector('.field-tray-done') as HTMLButtonElement | null;
  const tray = () => fixture.debugElement.query(By.directive(PlanAmountTrayComponent))?.componentInstance as PlanAmountTrayComponent | undefined;
  const lastOn = (el: Element) => animate.calls.all().filter(c => c.object === el).pop()!;

  function tap(id: Field): void {
    field(id).click();
    fixture.detectChanges();
  }

  async function finish(anim: Animation): Promise<void> {
    const landed = new Promise<void>(resolve => anim.addEventListener('finish', () => resolve()));
    anim.finish();
    await landed;
    fixture.detectChanges();
  }

  it('a switch collapses the open slot as a close does, and the other field\'s slot grows under its own card when the collapse lands', async () => {
    setup();
    tap('amount');
    const first = slots()[0];
    const firstDone = done()!;
    expect(card('amount').contains(first)).toBeTrue();

    tap('share');
    // Lit at once; the slot under the first card is still there, collapsing, so nothing below it snaps.
    expect(page.tray.open()).toBe('share');
    expect(page.tray.shown()).toBe('amount');
    expect(field('share').classList.contains('active')).toBeTrue();
    expect(field('amount').classList.contains('active')).toBeFalse();
    expect(slots()).toEqual([first]);
    const collapse = lastOn(first);
    expect((collapse.args[0] as Keyframe[])[1]).toEqual({ height: '0px' });
    expect((collapse.args[1] as KeyframeAnimationOptions).duration).toBe(200);
    expect((collapse.args[1] as KeyframeAnimationOptions).easing).toBe(HOUSE_EASING);
    expect((lastOn(first.querySelector('.field-tray-in')!).args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });
    expect((lastOn(firstDone).args[0] as Keyframe[])[1]).toEqual({ opacity: 0 });

    await finish(collapse.returnValue as Animation);
    expect(page.tray.shown()).toBe('share');
    const second = slots();
    expect(second.length).toBe(1);
    expect(second[0]).not.toBe(first);
    expect(card('share').contains(second[0])).toBeTrue();
    expect(card('amount').querySelector('.field-tray')).toBeNull();
    const grow = lastOn(second[0]);
    expect((grow.args[0] as Keyframe[])[0]).toEqual({ height: '0px' });
    expect((grow.args[1] as KeyframeAnimationOptions).duration).toBe(240);
    // Done moved to the second card and fades in there.
    expect(card('share').contains(done()!)).toBeTrue();
    expect((lastOn(done()!).args[0] as Keyframe[])[1]).toEqual({ opacity: 1 });
    expect(tray()!.field()).toBe('share');
    expect(tray()!.unit()).toBe('percent');

    // And back: the first card's slot mounts before the second's leaves (template order), and its own Done still fades in.
    tap('amount');
    await finish(lastOn(second[0]).returnValue as Animation);
    expect(page.tray.shown()).toBe('amount');
    expect(slots().length).toBe(1);
    expect(card('amount').contains(slots()[0])).toBeTrue();
    expect(card('amount').contains(done()!)).toBeTrue();
    expect((lastOn(done()!).args[0] as Keyframe[])[1]).toEqual({ opacity: 1 });
  });

  it('tapping the leaving field back mid-collapse grows its slot back; a tap outside mid-collapse closes from where it is', () => {
    setup();
    tap('amount');
    const first = slots()[0];
    tap('share');
    tap('amount');
    expect(page.tray.open()).toBe('amount');
    expect(page.tray.shown()).toBe('amount');
    expect(slots()).toEqual([first]);
    expect((lastOn(first).args[1] as KeyframeAnimationOptions).duration).toBe(240);

    tap('share');
    const below = fixture.nativeElement.querySelector('.below') as HTMLElement;
    below.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerId: 1 }));
    below.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, pointerId: 1 }));
    fixture.detectChanges();
    expect(page.tray.open()).toBeNull();
    expect(page.tray.shown()).toBe('amount');
    expect((lastOn(first).args[0] as Keyframe[])[1]).toEqual({ height: '0px' });
  });

  it('re-opening on the other field mid-collapse lets the collapse land, then grows that field\'s slot', async () => {
    setup();
    tap('amount');
    const first = slots()[0];
    tap('amount');
    const collapse = lastOn(first);
    tap('share');
    expect(page.tray.open()).toBe('share');
    expect(page.tray.shown()).toBe('amount');
    expect(slots()).toEqual([first]);
    await finish(collapse.returnValue as Animation);
    expect(page.tray.shown()).toBe('share');
    expect(slots().length).toBe(1);
    expect(card('share').contains(slots()[0])).toBeTrue();
  });

  it('under reduced motion a switch swaps the slots at once', () => {
    setup(true);
    tap('amount');
    tap('share');
    expect(page.tray.shown()).toBe('share');
    expect(slots().length).toBe(1);
    expect(card('share').contains(slots()[0])).toBeTrue();
    expect(animate).not.toHaveBeenCalled();
  });
});
