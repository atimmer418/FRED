import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { BehaviorSubject } from 'rxjs';
import { FirstTimeTourComponent, TARGET_POLL_MS, TARGET_POLL_MAX_ATTEMPTS } from './first-time-tour.component';
import { FirstTimeTourService, TourStep } from '../../services/first-time-tour.service';

describe('FirstTimeTourComponent', () => {
  let fixture: ComponentFixture<FirstTimeTourComponent>;
  let step$: BehaviorSubject<TourStep | null>;
  let suspended$: BehaviorSubject<boolean>;
  let svc: { next: jasmine.Spy; forceGate: jasmine.Spy; skip: jasmine.Spy };
  let mounted: HTMLElement[] = [];

  function mountTarget(attr: string, w = 52, h = 52): HTMLElement {
    const el = document.createElement('div');
    el.setAttribute('data-tour', attr);
    el.style.cssText = `position:fixed;top:60px;left:20px;width:${w}px;height:${h}px;`;
    document.body.appendChild(el);
    mounted.push(el);
    return el;
  }

  function q(sel: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(sel);
  }

  beforeEach(() => {
    // Ionic's Stencil CSS ships `html:not(.hydrated) body { display: none; }` to
    // avoid a flash of unhydrated content. No <ion-app> hydrates in this component
    // test, so without this the body stays display:none and every real
    // getBoundingClientRect() on a mounted target returns a zero rect.
    document.documentElement.classList.add('hydrated');
    step$ = new BehaviorSubject<TourStep | null>(null);
    suspended$ = new BehaviorSubject<boolean>(false);
    svc = {
      next: jasmine.createSpy('next'),
      forceGate: jasmine.createSpy('forceGate'),
      skip: jasmine.createSpy('skip'),
    };
    TestBed.configureTestingModule({
      imports: [FirstTimeTourComponent],
      providers: [
        { provide: FirstTimeTourService, useValue: { ...svc, step$: step$.asObservable(), suspended$: suspended$.asObservable() } },
      ],
    });
    fixture = TestBed.createComponent(FirstTimeTourComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    mounted.forEach(el => el.remove());
    mounted = [];
    fixture.destroy();
    document.documentElement.classList.remove('hydrated');
  });

  it('renders nothing when the tour is not running', () => {
    expect(q('.ftt-overlay')).toBeNull();
  });

  it('explain step: full backdrop, label, Next and Skip', () => {
    step$.next('tab1');
    fixture.detectChanges();
    expect(q('.ftt-backdrop')).not.toBeNull();
    expect(q('.ftt-panel')).toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('Your portfolio and its insights live here.');
    expect(q('.ftt-next')).not.toBeNull();
    expect(q('.ftt-skip')).not.toBeNull();

    q('.ftt-next')!.click();
    expect(svc.next).toHaveBeenCalled();
  });

  it('gated step with a visible target: four panels, ring, no Next, label from step', fakeAsync(() => {
    mountTarget('profile-avatar');
    step$.next('tab3Avatar');
    fixture.detectChanges();
    tick(TARGET_POLL_MS);
    fixture.detectChanges();

    expect(q('.ftt-backdrop')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.ftt-panel').length).toBe(4);
    expect(q('.ftt-ring')).not.toBeNull();
    expect(q('.ftt-next')).toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('Your settings live here. Tap your pig to open your profile.');

    // Hole is the target rect padded by 8px.
    const ring = q('.ftt-ring')!;
    expect(ring.style.top).toBe('52px');
    expect(ring.style.left).toBe('12px');
    expect(ring.style.width).toBe('68px');
    expect(ring.style.height).toBe('68px');

    tick(2000); // drain the settle re-measure timer
  }));

  it('gated step with no target falls back to explain rendering after the poll deadline', fakeAsync(() => {
    step$.next('profileFreedom');
    fixture.detectChanges();
    tick(TARGET_POLL_MS * TARGET_POLL_MAX_ATTEMPTS + TARGET_POLL_MS);
    fixture.detectChanges();

    expect(q('.ftt-panel')).toBeNull();
    expect(q('.ftt-backdrop')).not.toBeNull();
    expect(q('.ftt-next')).not.toBeNull();
    expect(q('.ftt-label')!.textContent!.trim()).toBe('This is your Freedom Plan. Tap it to see how you get there.');

    q('.ftt-next')!.click();
    expect(svc.forceGate).toHaveBeenCalled();
    expect(svc.next).not.toHaveBeenCalled();
  }));

  it('suspended hides the whole overlay without losing the step', fakeAsync(() => {
    mountTarget('story-chip');
    step$.next('chatStory');
    fixture.detectChanges();
    tick(TARGET_POLL_MS);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).not.toBeNull();

    suspended$.next(true);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).toBeNull();

    suspended$.next(false);
    fixture.detectChanges();
    expect(q('.ftt-overlay')).not.toBeNull();
    tick(2000);
  }));

  it('Skip calls the service on any step', () => {
    step$.next('tab2');
    fixture.detectChanges();
    q('.ftt-skip')!.click();
    expect(svc.skip).toHaveBeenCalled();
  });
});
