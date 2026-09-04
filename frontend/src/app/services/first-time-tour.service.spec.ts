import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { FirstTimeTourService, TOUR_STORAGE_KEY, TAB3_BEAT_MS, TAB_SETTLE_POLL_MS } from './first-time-tour.service';
import { TabActivationService } from './tab-activation.service';

describe('FirstTimeTourService', () => {
  let svc: FirstTimeTourService;
  let routerEvents: Subject<unknown>;
  let navigateByUrl: jasmine.Spy;
  let navigateBack: jasmine.Spy;
  let pop: jasmine.Spy;
  let requestTab: jasmine.Spy;
  let tabActivation: TabActivationService;

  beforeEach(() => {
    localStorage.removeItem(TOUR_STORAGE_KEY);
    routerEvents = new Subject<unknown>();
    navigateByUrl = jasmine.createSpy('navigateByUrl').and.returnValue(Promise.resolve(true));
    navigateBack = jasmine.createSpy('navigateBack').and.returnValue(Promise.resolve(true));
    pop = jasmine.createSpy('pop').and.returnValue(Promise.resolve(true));
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events: routerEvents.asObservable(), navigateByUrl } },
        { provide: NavController, useValue: { navigateBack, pop } },
        // Real TabActivationService: the transit needs its activeTab settle signal.
        TabActivationService,
      ],
    });
    tabActivation = TestBed.inject(TabActivationService);
    requestTab = spyOn(tabActivation, 'requestTab').and.callThrough();
    svc = TestBed.inject(FirstTimeTourService);
  });

  afterEach(() => localStorage.removeItem(TOUR_STORAGE_KEY));

  function landOn(url: string) {
    routerEvents.next(new NavigationEnd(1, url, url));
  }

  /** Drain the microtasks behind navCtrl.pop().then(...). */
  async function flush() {
    await Promise.resolve();
    await Promise.resolve();
  }

  /** Run the full pop -> tab3 beat -> swipe -> settle transit (fakeAsync only). */
  function completeTransit() {
    flushMicrotasks();               // pop() resolves
    tick(TAB3_BEAT_MS);              // beat on tab3; requestTab('chat') fires
    tabActivation.setActive('chat'); // pager settle signal
    tick(TAB_SETTLE_POLL_MS);        // settle poll notices
  }

  it('starts at tab1 when the flag is unset, and is a no-op when set', () => {
    localStorage.setItem(TOUR_STORAGE_KEY, 'true');
    svc.startTour();
    expect(svc.currentStep).toBeNull();

    localStorage.removeItem(TOUR_STORAGE_KEY);
    svc.startTour();
    expect(svc.currentStep).toBe('tab1');
  });

  // A router move to /tabs/<tab> would build a SECOND TabsPage (Ionic reuses
  // views by stamped URL), whose fresh Tab1Page restarts the tour. The steps
  // move the existing pager through TabActivationService instead.
  it('next walks tab1 -> tab2 -> tab3Avatar by moving the pager, not the router', () => {
    svc.startTour();
    svc.next();
    expect(svc.currentStep).toBe('tab2');
    expect(requestTab).toHaveBeenCalledWith('tab2');
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(requestTab).toHaveBeenCalledWith('tab3');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('startTour is a no-op while the tour is running', () => {
    svc.startTour();
    svc.next();
    expect(svc.currentStep).toBe('tab2');
    // AppLock re-navigates to /tabs/tab1 after a re-auth, which remounts
    // Tab1Page; its startTour() must not rewind a tour already in progress.
    svc.startTour();
    expect(svc.currentStep).toBe('tab2');
  });

  it('next is ignored on gated steps', () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    requestTab.calls.reset();
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(requestTab).not.toHaveBeenCalled();
  });

  it('landing on /my-profile advances tab3Avatar -> profileFreedom, and nothing else', () => {
    svc.startTour();
    landOn('/my-profile');
    expect(svc.currentStep).toBe('tab1');

    svc.next();
    svc.next(); // tab3Avatar
    landOn('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');
  });

  it('freedomSheetClosed rests on tab3, then swipes to chat, then shows the story step', fakeAsync(() => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.suspend();

    svc.freedomSheetClosed();
    flushMicrotasks(); // pop() resolves; the beat on tab3 begins

    // pop() returns to the CACHED TabsPage by its own URL; navigateBack('/tabs/chat')
    // would build a second TabsPage. A dim veil (transit) covers the whole ride.
    expect(pop).toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.isSuspended).toBeFalse();
    expect(svc.isInTransit).toBeTrue();
    expect(svc.currentStep).toBe('profileFreedom');
    expect(requestTab).not.toHaveBeenCalledWith('chat');

    tick(TAB3_BEAT_MS); // the user has had a moment on tab3; now the pager swipes
    expect(requestTab).toHaveBeenCalledWith('chat');
    expect(svc.currentStep).toBe('profileFreedom'); // still waiting for the slide to settle

    tabActivation.setActive('chat'); // pager settle
    tick(TAB_SETTLE_POLL_MS);
    expect(svc.currentStep).toBe('chatStory');
    expect(svc.isSuspended).toBeFalse();
    expect(svc.isInTransit).toBeFalse();
  }));

  it('freedomSheetClosed falls back to a route when there is nothing to pop', fakeAsync(() => {
    pop.and.returnValue(Promise.resolve(false));
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');

    svc.freedomSheetClosed();
    flushMicrotasks();

    expect(requestTab).not.toHaveBeenCalledWith('chat');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/chat');
    // No transit staging on the fallback: the step shows as soon as the route lands.
    expect(svc.currentStep).toBe('chatStory');
    expect(svc.isSuspended).toBeFalse();
    expect(svc.isInTransit).toBeFalse();
  }));

  it('complete during the transit cancels it', fakeAsync(() => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');

    svc.freedomSheetClosed();
    flushMicrotasks(); // beat scheduled
    svc.complete();    // e.g. the flag path ends the tour mid-transit

    tick(TAB3_BEAT_MS + TAB_SETTLE_POLL_MS * 51);
    expect(requestTab).not.toHaveBeenCalledWith('chat');
    expect(svc.currentStep).toBeNull();
    expect(svc.isInTransit).toBeFalse();
  }));

  it('freedomSheetClosed does not advance other steps', async () => {
    svc.startTour();
    svc.freedomSheetClosed();
    await flush();
    expect(pop).not.toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.currentStep).toBe('tab1');
  });

  // The Freedom sheet suspends the overlay. If it dismisses on any other step
  // the suspend must still lift, or the overlay stays hidden for good.
  it('freedomSheetClosed off-step still clears a suspend', () => {
    svc.startTour();
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();

    svc.freedomSheetClosed();

    expect(svc.isSuspended).toBeFalse();
    expect(svc.currentStep).toBe('tab1');
  });

  it('storyChipTapped on chatStory completes and writes the flag; no-op otherwise', fakeAsync(() => {
    svc.startTour();
    svc.storyChipTapped();
    expect(svc.currentStep).toBe('tab1');
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBeNull();

    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.freedomSheetClosed();
    completeTransit();
    svc.storyChipTapped();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  }));

  it('suspend/resume toggle suspended$ without changing the step', () => {
    svc.startTour();
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();
    expect(svc.currentStep).toBe('tab1');
    svc.resume();
    expect(svc.isSuspended).toBeFalse();
  });

  it('suspend is ignored when the tour is not running', () => {
    svc.suspend();
    expect(svc.isSuspended).toBeFalse();
  });

  it('skip writes the flag, clears the step, and does not navigate', () => {
    svc.startTour();
    svc.skip();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(requestTab).not.toHaveBeenCalled();
    expect(pop).not.toHaveBeenCalled();
  });

  it('forceGate advances gated steps as if the gate passed', fakeAsync(() => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    svc.forceGate();
    expect(navigateByUrl).toHaveBeenCalledWith('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');

    svc.forceGate();
    completeTransit();
    expect(pop).toHaveBeenCalled();
    expect(requestTab).toHaveBeenCalledWith('chat');
    expect(svc.currentStep).toBe('chatStory');

    svc.forceGate();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  }));

  it('forceGate is ignored on explain steps', () => {
    svc.startTour();
    svc.forceGate();
    expect(svc.currentStep).toBe('tab1');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(requestTab).not.toHaveBeenCalled();
  });
});
