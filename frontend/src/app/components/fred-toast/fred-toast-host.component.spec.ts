import { ComponentFixture, TestBed, fakeAsync, flush, tick } from '@angular/core/testing';
import {
  DEDUPE_WINDOW_MS, ENTER_MS, FredToastHostComponent, LEAVE_MS, MAX_VISIBLE_TOASTS,
  RESTACK_MS, RESUME_GRACE_MS, SWIPE_DISMISS_PX, TOAST_GAP_PX
} from './fred-toast-host.component';
import { ERROR_TOAST_DURATION_MS, TOAST_DURATION_MS, ToastService } from '../../services/toast.service';

/** What the component asked element.animate for, with a hand on its onfinish. */
interface PlayedAnimation {
  target: Element;
  frames: Keyframe[];
  duration: number;
  onfinish: (() => void) | null;
  cancelled: boolean;
}

describe('FredToastHostComponent', () => {
  let fixture: ComponentFixture<FredToastHostComponent>;
  let service: ToastService;
  let played: PlayedAnimation[];
  let reducedMotion: boolean;

  function cards(): HTMLElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.ft-card'));
  }

  function card(message: string): HTMLElement {
    const match = cards().find(c => c.querySelector('.ft-message')?.textContent?.trim() === message);
    if (!match) throw new Error(`no card reads "${message}"`);
    return match;
  }

  function slotOf(el: HTMLElement): HTMLElement {
    return el.parentElement as HTMLElement;
  }

  function messages(): string[] {
    return cards().map(c => c.querySelector('.ft-message')!.textContent!.trim());
  }

  function animationsOn(el: Element): PlayedAnimation[] {
    return played.filter(a => a.target === el);
  }

  /** Ends every leave animation the way the browser would. */
  function finishLeaves(): void {
    played.filter(a => a.duration === LEAVE_MS && !a.cancelled).forEach(a => a.onfinish?.());
  }

  function pointer(type: string, target: EventTarget, clientY: number): void {
    target.dispatchEvent(new PointerEvent(type, { clientY, clientX: 100, button: 0, bubbles: true, pointerId: 1 }));
  }

  beforeEach(() => {
    // Ionic's Stencil CSS hides the body until ion-app hydrates; no ion-app
    // exists here, so without this every card would measure zero high.
    document.documentElement.classList.add('hydrated');
    played = [];
    reducedMotion = false;
    spyOn(window, 'matchMedia').and.callFake((query: string) => ({
      matches: query.includes('prefers-reduced-motion') && reducedMotion,
      media: query,
    } as MediaQueryList));
    spyOn(Element.prototype, 'animate').and.callFake(function (this: Element, frames: Keyframe[] | PropertyIndexedKeyframes | null, options?: number | KeyframeAnimationOptions) {
      const entry: PlayedAnimation = {
        target: this,
        frames: frames as Keyframe[],
        duration: typeof options === 'number' ? options : Number(options?.duration ?? 0),
        onfinish: null,
        cancelled: false,
      };
      played.push(entry);
      return {
        set onfinish(fn: (() => void) | null) { entry.onfinish = fn; },
        get onfinish() { return entry.onfinish; },
        cancel: () => { entry.cancelled = true; },
      } as unknown as Animation;
    });

    TestBed.configureTestingModule({ imports: [FredToastHostComponent] });
    fixture = TestBed.createComponent(FredToastHostComponent);
    fixture.detectChanges();
    service = TestBed.inject(ToastService);
  });

  afterEach(() => {
    fixture.destroy();
    document.documentElement.classList.remove('hydrated');
  });

  it('starts empty with a polite live region already in place', () => {
    const host = fixture.nativeElement.querySelector('.ft-host') as HTMLElement;

    expect(host.getAttribute('role')).toBe('status');
    expect(host.getAttribute('aria-live')).toBe('polite');
    expect(host.getAttribute('aria-atomic')).toBe('false');
    expect(cards().length).toBe(0);
  });

  it('draws a card with the severity, chip icon, message and title', fakeAsync(() => {
    service.show({ severity: 'success', title: 'Saved', message: 'Your plan is up to date.' });

    const el = card('Your plan is up to date.');
    expect(el.getAttribute('data-severity')).toBe('success');
    expect(el.querySelector('.ft-chip')!.textContent!.trim()).toBe('check_circle');
    expect(el.querySelector('.ft-title')!.textContent!.trim()).toBe('Saved');
    expect(el.getAttribute('aria-live')).toBeNull();
    flush();
  }));

  it('maps each severity to its icon and leaves neutral without a chip', fakeAsync(() => {
    service.show({ severity: 'error', message: 'e' });
    service.show({ severity: 'warning', message: 'w' });
    service.show({ severity: 'info', message: 'i' });
    service.show({ severity: 'neutral', message: 'n' });
    // Three fit; 'e' was the first in and has gone, so only the three newest remain.

    expect(card('w').querySelector('.ft-chip')!.textContent!.trim()).toBe('warning');
    expect(card('i').querySelector('.ft-chip')!.textContent!.trim()).toBe('info');
    expect(card('n').querySelector('.ft-chip')).toBeNull();
    flush();
  }));

  it('turns the one host region assertive while an error shows, with no second region on the card', fakeAsync(() => {
    const host = fixture.nativeElement.querySelector('.ft-host') as HTMLElement;
    service.show({ severity: 'error', message: 'Something broke.' });

    const el = card('Something broke.');
    expect(host.getAttribute('aria-live')).toBe('assertive');
    expect(host.getAttribute('role')).toBe('alert');
    expect(host.getAttribute('aria-atomic')).toBe('false');
    expect(el.getAttribute('aria-live')).toBeNull();
    expect(el.getAttribute('role')).toBeNull();

    el.querySelector<HTMLButtonElement>('.ft-close')!.click();
    expect(host.getAttribute('aria-live')).toBe('polite');
    expect(host.getAttribute('role')).toBe('status');
    flush();
  }));

  it('slides a new card in from above and drops the older ones down by their measured height', fakeAsync(() => {
    service.showToast('First');
    const first = card('First');
    const enter = animationsOn(first)[0];
    expect(enter.duration).toBe(ENTER_MS);
    expect(enter.frames[0]['transform']).toBe('translateY(-12px)');
    expect(enter.frames[1]['transform']).toBe('translateY(0px)');

    service.showToast('Second');
    expect(messages()).toEqual(['Second', 'First']);
    const secondHeight = card('Second').offsetHeight;
    expect(secondHeight).toBeGreaterThan(0);
    const move = animationsOn(slotOf(first)).pop()!;
    expect(move.duration).toBe(RESTACK_MS);
    expect(move.frames[1]['transform']).toBe(`translateY(${secondHeight + TOAST_GAP_PX}px)`);
    expect(move.frames[1]['opacity']).toBe(0.92);
    flush();
  }));

  it('keeps at most three cards and sends the oldest away when a fourth arrives', fakeAsync(() => {
    ['One', 'Two', 'Three', 'Four'].forEach(m => service.showToast(m));

    const oldest = card('One');
    expect(oldest.classList.contains('ft-card--leaving')).toBeTrue();
    expect(animationsOn(oldest).some(a => a.duration === LEAVE_MS)).toBeTrue();

    finishLeaves();
    expect(messages()).toEqual(['Four', 'Three', 'Two']);
    expect(messages().length).toBe(MAX_VISIBLE_TOASTS);
    flush();
  }));

  it('ignores the same message repeated within a second, then allows it again', fakeAsync(() => {
    service.showToast('Saved.');
    service.showToast('Saved.');
    expect(messages()).toEqual(['Saved.']);

    tick(DEDUPE_WINDOW_MS);
    service.showToast('Saved.');
    expect(messages()).toEqual(['Saved.', 'Saved.']);
    flush();
  }));

  it('draws an action button only when asked, and a tap runs the action once and dismisses the card', fakeAsync(() => {
    const run = jasmine.createSpy('run');
    service.show({ severity: 'success', message: 'Saved so far is now $450.', action: { label: 'Undo', run } });
    service.show({ severity: 'success', message: 'Plain.' });

    expect(card('Plain.').querySelector('.ft-action')).toBeNull();
    const button = card('Saved so far is now $450.').querySelector<HTMLButtonElement>('.ft-action')!;
    expect(button.textContent!.trim()).toBe('Undo');
    expect(button.type).toBe('button');

    button.click();
    button.click();
    expect(run).toHaveBeenCalledTimes(1);
    expect(card('Saved so far is now $450.').classList.contains('ft-card--leaving')).toBeTrue();
    flush();
  }));

  it('dismisses itself after its duration and removes the card once the leave ends', fakeAsync(() => {
    service.showToast('Bye soon.');
    tick(TOAST_DURATION_MS - 1);
    expect(card('Bye soon.').classList.contains('ft-card--leaving')).toBeFalse();

    tick(1);
    expect(card('Bye soon.').classList.contains('ft-card--leaving')).toBeTrue();
    expect(cards().length).toBe(1);

    tick(LEAVE_MS + 100);
    expect(cards().length).toBe(0);
  }));

  it('gives an error the longer default duration', fakeAsync(() => {
    service.showToast('Nope.', 'danger');
    tick(TOAST_DURATION_MS);
    expect(card('Nope.').classList.contains('ft-card--leaving')).toBeFalse();

    tick(ERROR_TOAST_DURATION_MS - TOAST_DURATION_MS);
    expect(card('Nope.').classList.contains('ft-card--leaving')).toBeTrue();
    flush();
  }));

  it('closes on the close tap', fakeAsync(() => {
    service.showToast('Tap me away.');
    (card('Tap me away.').querySelector('.ft-close') as HTMLButtonElement).click();

    expect(card('Tap me away.').classList.contains('ft-card--leaving')).toBeTrue();
    finishLeaves();
    expect(cards().length).toBe(0);
    flush();
  }));

  it('holds the timer while a finger rests on the card and resumes with the time left', fakeAsync(() => {
    service.showToast('Hold me.');
    const el = card('Hold me.');
    tick(1000);

    pointer('pointerdown', el, 300);
    tick(10_000);
    expect(el.classList.contains('ft-card--leaving')).toBeFalse();

    pointer('pointerup', window, 300);
    tick(TOAST_DURATION_MS - 1000 - 1);
    expect(el.classList.contains('ft-card--leaving')).toBeFalse();
    tick(1);
    expect(el.classList.contains('ft-card--leaving')).toBeTrue();
    flush();
  }));

  it('never vanishes the instant a long hold lets go', fakeAsync(() => {
    service.showToast('Long hold.');
    const el = card('Long hold.');
    tick(TOAST_DURATION_MS - 10);
    pointer('pointerdown', el, 300);
    tick(60_000);
    pointer('pointerup', window, 300);

    tick(RESUME_GRACE_MS - 1);
    expect(el.classList.contains('ft-card--leaving')).toBeFalse();
    tick(1);
    expect(el.classList.contains('ft-card--leaving')).toBeTrue();
    flush();
  }));

  it('throws the card away on a swipe up past the threshold', fakeAsync(() => {
    service.showToast('Swipe me.');
    const el = card('Swipe me.');

    pointer('pointerdown', el, 300);
    pointer('pointermove', window, 300 - SWIPE_DISMISS_PX);
    expect(el.style.transform).toBe(`translateY(-${SWIPE_DISMISS_PX}px)`);
    pointer('pointerup', window, 300 - SWIPE_DISMISS_PX);

    expect(el.classList.contains('ft-card--leaving')).toBeTrue();
    finishLeaves();
    expect(cards().length).toBe(0);
    flush();
  }));

  it('snaps back from a short swipe and keeps the card', fakeAsync(() => {
    service.showToast('Stay.');
    const el = card('Stay.');

    pointer('pointerdown', el, 300);
    pointer('pointermove', window, 300 - (SWIPE_DISMISS_PX - 1));
    pointer('pointerup', window, 300 - (SWIPE_DISMISS_PX - 1));

    expect(el.classList.contains('ft-card--leaving')).toBeFalse();
    const snap = animationsOn(el).pop()!;
    expect(snap.frames[1]['transform']).toBe('translateY(0px)');
    expect(cards().length).toBe(1);
    flush();
  }));

  it('does not snap a card back once it has started leaving under the finger', fakeAsync(() => {
    ['One', 'Two', 'Three'].forEach(m => service.showToast(m));
    const oldest = card('One');

    pointer('pointerdown', oldest, 300);
    pointer('pointermove', window, 290);
    service.showToast('Four');
    expect(oldest.classList.contains('ft-card--leaving')).toBeTrue();
    const before = animationsOn(oldest).length;

    pointer('pointerup', window, 290);

    expect(animationsOn(oldest).length).toBe(before);
    finishLeaves();
    expect(messages()).toEqual(['Four', 'Three', 'Two']);
    flush();
  }));

  it('ignores a downward drag', fakeAsync(() => {
    service.showToast('Down.');
    const el = card('Down.');

    pointer('pointerdown', el, 300);
    pointer('pointermove', window, 400);
    expect(el.style.transform).toBe('translateY(0px)');
    pointer('pointerup', window, 400);

    expect(el.classList.contains('ft-card--leaving')).toBeFalse();
    flush();
  }));

  it('under reduced motion appears and disappears in place without animating', fakeAsync(() => {
    reducedMotion = true;
    service.showToast('Still.');
    service.showToast('Still too.');
    expect(played.length).toBe(0);
    expect(slotOf(card('Still.')).style.transform).toBe(`translateY(${card('Still too.').offsetHeight + TOAST_GAP_PX}px)`);

    (card('Still.').querySelector('.ft-close') as HTMLButtonElement).click();
    expect(messages()).toEqual(['Still too.']);
    expect(played.length).toBe(0);
    flush();
  }));
});
