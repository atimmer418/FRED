import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { FirstTimeTourService, TOUR_STORAGE_KEY, TAB3_BEAT_MS, TAB_SETTLE_POLL_MS } from './first-time-tour.service';
import { TabActivationService } from './tab-activation.service';

describe('FirstTimeTourService', () => {
  let svc: FirstTimeTourService;
  let routerEvents: Subject<unknown>;
  let router: { events: unknown; navigateByUrl: jasmine.Spy; url: string };
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
    router = { events: routerEvents.asObservable(), navigateByUrl, url: '/' };
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
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

  /** The router's url moves first, then NavigationEnd fires, as in the real router. */
  function landOn(url: string) {
    router.url = url;
    routerEvents.next(new NavigationEnd(1, url, url));
  }

  /** Walk the tour to the profileFreedom step on /my-profile. */
  function toProfileFreedom() {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
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

  it('freedomPlanLeft rests on tab3, then swipes to chat, then shows the story step', fakeAsync(() => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.suspend();

    svc.freedomPlanLeft();
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

  it('freedomPlanLeft falls back to a route when there is nothing to pop', fakeAsync(() => {
    pop.and.returnValue(Promise.resolve(false));
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');

    svc.freedomPlanLeft();
    flushMicrotasks();

    expect(requestTab).not.toHaveBeenCalledWith('chat');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/chat');
    // No transit staging on the fallback: the step shows as soon as the route lands.
    expect(svc.currentStep).toBe('chatStory');
    expect(svc.isSuspended).toBeFalse();
    expect(svc.isInTransit).toBeFalse();
  }));

  // The Freedom page's ionViewWillLeave reports the exit, then my-profile's ionViewWillEnter
  // reports the same exit again. Only the first may start the ride back to chat.
  it('freedomPlanLeft advances from profileFreedom exactly as freedomSheetClosed did', fakeAsync(() => {
    toProfileFreedom();
    landOn('/freedom');
    expect(svc.currentStep).toBe('profileFreedom');
    landOn('/my-profile');                 // back from the Freedom page

    svc.freedomPlanLeft();                 // the Freedom page's ionViewWillLeave
    svc.freedomPlanLeft();                 // my-profile's ionViewWillEnter
    completeTransit();

    expect(pop).toHaveBeenCalledTimes(1);
    expect(requestTab.calls.allArgs().filter(a => a[0] === 'chat').length).toBe(1);
    expect(svc.currentStep).toBe('chatStory');
    expect(svc.isInTransit).toBeFalse();

    // A late duplicate after the step moved on changes nothing.
    svc.freedomPlanLeft();
    flushMicrotasks();
    expect(pop).toHaveBeenCalledTimes(1);
    expect(svc.currentStep).toBe('chatStory');
  }));

  // A swipe-back fires ionViewWillLeave as the gesture starts, while the route is still /freedom
  // and the gesture can still be cancelled. Popping then would land on my-profile, not tab3.
  it('freedomPlanLeft called while still on /freedom waits for the route to leave it', fakeAsync(() => {
    toProfileFreedom();
    landOn('/freedom');

    svc.freedomPlanLeft();                 // swipe starts
    flushMicrotasks();
    expect(pop).not.toHaveBeenCalled();
    expect(svc.isInTransit).toBeFalse();

    landOn('/freedom');                    // swipe cancelled, the page re-lands
    flushMicrotasks();
    expect(pop).not.toHaveBeenCalled();

    landOn('/my-profile');                 // the back finally completes
    svc.freedomPlanLeft();                 // my-profile's ionViewWillEnter: a no-op now
    completeTransit();
    expect(pop).toHaveBeenCalledTimes(1);
    expect(svc.currentStep).toBe('chatStory');
  }));

  it('a skip while the exit is pending drops it', fakeAsync(() => {
    toProfileFreedom();
    landOn('/freedom');
    svc.freedomPlanLeft();
    svc.skip();
    landOn('/my-profile');
    flushMicrotasks();
    expect(pop).not.toHaveBeenCalled();
    expect(svc.currentStep).toBeNull();
  }));

  it('complete during the transit cancels it', fakeAsync(() => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');

    svc.freedomPlanLeft();
    flushMicrotasks(); // beat scheduled
    svc.complete();    // e.g. the flag path ends the tour mid-transit

    tick(TAB3_BEAT_MS + TAB_SETTLE_POLL_MS * 51);
    expect(requestTab).not.toHaveBeenCalledWith('chat');
    expect(svc.currentStep).toBeNull();
    expect(svc.isInTransit).toBeFalse();
  }));

  it('freedomPlanLeft does not advance other steps', async () => {
    svc.startTour();
    svc.freedomPlanLeft();
    await flush();
    expect(pop).not.toHaveBeenCalled();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.currentStep).toBe('tab1');
  });

  // If anything suspended the overlay and the Freedom page is left on any
  // other step, the suspend must still lift, or the overlay stays hidden for good.
  it('freedomPlanLeft off-step still clears a suspend', () => {
    svc.startTour();
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();

    svc.freedomPlanLeft();

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
    svc.freedomPlanLeft();
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
