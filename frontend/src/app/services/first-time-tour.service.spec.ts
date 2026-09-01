import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { NavController } from '@ionic/angular/standalone';
import { Subject } from 'rxjs';
import { FirstTimeTourService, TOUR_STORAGE_KEY } from './first-time-tour.service';

describe('FirstTimeTourService', () => {
  let svc: FirstTimeTourService;
  let routerEvents: Subject<unknown>;
  let navigateByUrl: jasmine.Spy;
  let navigateBack: jasmine.Spy;

  beforeEach(() => {
    localStorage.removeItem(TOUR_STORAGE_KEY);
    routerEvents = new Subject<unknown>();
    navigateByUrl = jasmine.createSpy('navigateByUrl').and.returnValue(Promise.resolve(true));
    navigateBack = jasmine.createSpy('navigateBack').and.returnValue(Promise.resolve(true));
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: { events: routerEvents.asObservable(), navigateByUrl } },
        { provide: NavController, useValue: { navigateBack } },
      ],
    });
    svc = TestBed.inject(FirstTimeTourService);
  });

  afterEach(() => localStorage.removeItem(TOUR_STORAGE_KEY));

  function landOn(url: string) {
    routerEvents.next(new NavigationEnd(1, url, url));
  }

  it('starts at tab1 when the flag is unset, and is a no-op when set', () => {
    localStorage.setItem(TOUR_STORAGE_KEY, 'true');
    svc.startTour();
    expect(svc.currentStep).toBeNull();

    localStorage.removeItem(TOUR_STORAGE_KEY);
    svc.startTour();
    expect(svc.currentStep).toBe('tab1');
  });

  it('next walks tab1 -> tab2 -> tab3Avatar and navigates the pager', () => {
    svc.startTour();
    svc.next();
    expect(svc.currentStep).toBe('tab2');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/tab2');
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(navigateByUrl).toHaveBeenCalledWith('/tabs/tab3');
  });

  it('next is ignored on gated steps', () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    navigateByUrl.calls.reset();
    svc.next();
    expect(svc.currentStep).toBe('tab3Avatar');
    expect(navigateByUrl).not.toHaveBeenCalled();
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

  it('freedomSheetClosed on profileFreedom navigates back to chat and sets chatStory', () => {
    svc.startTour();
    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.suspend();
    expect(svc.isSuspended).toBeTrue();

    svc.freedomSheetClosed();
    expect(svc.isSuspended).toBeFalse();
    expect(navigateBack).toHaveBeenCalledWith('/tabs/chat');
    expect(svc.currentStep).toBe('chatStory');
  });

  it('freedomSheetClosed is a no-op on other steps', () => {
    svc.startTour();
    svc.freedomSheetClosed();
    expect(navigateBack).not.toHaveBeenCalled();
    expect(svc.currentStep).toBe('tab1');
  });

  it('storyChipTapped on chatStory completes and writes the flag; no-op otherwise', () => {
    svc.startTour();
    svc.storyChipTapped();
    expect(svc.currentStep).toBe('tab1');
    expect(localStorage.getItem(TOUR_STORAGE_KEY)).toBeNull();

    svc.next();
    svc.next();
    landOn('/my-profile');
    svc.freedomSheetClosed();
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
  });

  it('forceGate advances gated steps as if the gate passed', () => {
    svc.startTour();
    svc.next();
    svc.next(); // tab3Avatar
    svc.forceGate();
    expect(navigateByUrl).toHaveBeenCalledWith('/my-profile');
    expect(svc.currentStep).toBe('profileFreedom');

    svc.forceGate();
    expect(navigateBack).toHaveBeenCalledWith('/tabs/chat');
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
  });
});
