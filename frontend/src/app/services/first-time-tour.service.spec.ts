import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { FirstTimeTourService, TOUR_STORAGE_KEY } from './first-time-tour.service';
import { TabActivationService } from './tab-activation.service';

describe('FirstTimeTourService', () => {
  let svc: FirstTimeTourService;
  let routerEvents: Subject<unknown>;
  let navigateByUrl: jasmine.Spy;
  let navigateBack: jasmine.Spy;
  let pop: jasmine.Spy;
  let requestTab: jasmine.Spy;

  beforeEach(() => {
    localStorage.removeItem(TOUR_STORAGE_KEY);
    routerEvents = new Subject<unknown>();
    navigateByUrl = jasmine.createSpy('navigateByUrl').and.returnValue(Promise.resolve(true));
    navigateBack = jasmine.createSpy('navigateBack').and.returnValue(Promise.resolve(true));
    pop = jasmine.createSpy('pop').and.returnValue(Promise.resolve(true));
    requestTab = jasmine.createSpy('requestTab');
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events: routerEvents.asObservable(), navigateByUrl } },
        { provide: NavController, useValue: { navigateBack, pop } },
        { provide: TabActivationService, useValue: { requestTab } },
      ],
    });
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

  it('freedomSheetClosed on profileFreedom pops back to chat and sets chatStory', async () => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();

    svc.freedomSheetClosed();
    await flush();

    expect(svc.isSuspended).toBeFalse();
    // pop() returns to the CACHED TabsPage by its own URL; the pager is then
    // moved to chat. navigateBack('/tabs/chat') would build a second TabsPage.
    expect(pop).toHaveBeenCalled();
    expect(requestTab).toHaveBeenCalledWith('chat');
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.currentStep).toBe('chatStory');
  });

  it('freedomSheetClosed falls back to a route when there is nothing to pop', async () => {
    pop.and.returnValue(Promise.resolve(false));
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');

    svc.freedomSheetClosed();
    await flush();

    expect(requestTab).not.toHaveBeenCalledWith('chat');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/chat');
  });

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

  it('storyChipTapped on chatStory completes and writes the flag; no-op otherwise', async () => {
    svc.startTour();
    svc.storyChipTapped();
    expect(svc.currentStep).toBe('tab1');
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBeNull();

    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.freedomSheetClosed();
    await flush();
    svc.storyChipTapped();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  });

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

  it('forceGate advances gated steps as if the gate passed', async () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    svc.forceGate();
    expect(navigateByUrl).toHaveBeenCalledWith('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');

    svc.forceGate();
    await flush();
    expect(pop).toHaveBeenCalled();
    expect(requestTab).toHaveBeenCalledWith('chat');
    expect(svc.currentStep).toBe('chatStory');

    svc.forceGate();
    expect(svc.currentStep).toBeNull();
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBe('true');
  });

  it('forceGate is ignored on explain steps', () => {
    svc.startTour();
    svc.forceGate();
    expect(svc.currentStep).toBe('tab1');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(requestTab).not.toHaveBeenCalled();
  });
});
