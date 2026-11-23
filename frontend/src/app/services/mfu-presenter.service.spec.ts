import { MfuPresenterService } from './mfu-presenter.service';
import { NavigationEnd } from '@angular/router';
import { Subject, of } from 'rxjs';

/**
 * FRED-217: the Retire button dismisses the MFU with { action: 'exploreRetirement' }, and the
 * presenter (the single owner of MFU dismiss navigation) slides the pager to tab2 and asks the
 * retirement screen for its Education section. The existing updateContribution navigation must not
 * regress.
 */
describe('MfuPresenterService — dismiss-action navigation', () => {
  function build(dismissData: unknown) {
    const modal = {
      present: jasmine.createSpy('present').and.resolveTo(undefined),
      onDidDismiss: jasmine.createSpy('onDidDismiss').and.resolveTo({ data: dismissData }),
    };
    const modalController = { create: jasmine.createSpy('create').and.resolveTo(modal) } as any;
    const appLockService = { isCurrentlyLocked: () => false } as any;
    const router = { navigate: jasmine.createSpy('navigate') } as any;
    const mfuStore = { peekSnapshot: () => null } as any;
    const tabActivation = { requestTab: jasmine.createSpy('requestTab') } as any;
    const retirementSection = { requestSection: jasmine.createSpy('requestSection') } as any;

    const svc = new MfuPresenterService(
      modalController, appLockService, router, mfuStore, tabActivation, retirementSection,
    );
    return { svc, router, tabActivation, retirementSection };
  }

  describe('auto popup', () => {
    function buildAuto(url: string) {
      const modalController = { create: jasmine.createSpy('create') } as any;
      const appLockService = { isCurrentlyLocked: () => false } as any;
      const events = new Subject<unknown>();
      const router = { url, events } as any;
      const mfuStore = {
        ensureAutoSession: () => of({ shouldShow: true, hasMfuHistory: true, data: null }),
        ensureFonts: () => Promise.resolve(),
        // Claim refused, so the test stops at the claim and never builds a modal.
        tryClaimAutoPopup: jasmine.createSpy('tryClaimAutoPopup').and.returnValue(false),
      } as any;
      const svc = new MfuPresenterService(modalController, appLockService, router, mfuStore, {} as any, {} as any);
      return { svc, mfuStore, modalController, events };
    }
    const tick = () => new Promise<void>(resolve => setTimeout(resolve));

    it('holds back while a pushed page is on screen, then presents once the user is back on the tabs', async () => {
      const { svc, mfuStore, events } = buildAuto('/my-profile');

      const pending = svc.presentAutoIfDue();
      await tick();
      expect(mfuStore.tryClaimAutoPopup).not.toHaveBeenCalled();

      // Moving between pushed pages does not release it.
      events.next(new NavigationEnd(1, '/goals/7', '/goals/7'));
      await tick();
      expect(mfuStore.tryClaimAutoPopup).not.toHaveBeenCalled();

      // Back on the tabs, with no new trigger: it goes ahead on its own.
      events.next(new NavigationEnd(2, '/tabs/tab3', '/tabs/tab3'));
      await pending;
      expect(mfuStore.tryClaimAutoPopup).toHaveBeenCalledTimes(1);
    });

    it('goes ahead when a tab is showing', async () => {
      const { svc, mfuStore } = buildAuto('/tabs/tab3');

      await svc.presentAutoIfDue();

      expect(mfuStore.tryClaimAutoPopup).toHaveBeenCalled();
    });
  });

  it('slides to tab2 and opens the Education section on the exploreRetirement action', async () => {
    const { svc, router, tabActivation, retirementSection } = build({ action: 'exploreRetirement' });

    await svc.presentReopen();

    expect(tabActivation.requestTab).toHaveBeenCalledWith('tab2');
    expect(retirementSection.requestSection).toHaveBeenCalledWith('education');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('still routes to recurring-investments on the updateContribution action', async () => {
    const { svc, router, tabActivation } = build({ action: 'updateContribution', currentAmount: 100, boostAmount: 50 });

    await svc.presentReopen();

    expect(router.navigate).toHaveBeenCalledWith(['/recurring-investments'], { queryParams: { suggestedAmount: 150 } });
    expect(tabActivation.requestTab).not.toHaveBeenCalled();
  });
});
