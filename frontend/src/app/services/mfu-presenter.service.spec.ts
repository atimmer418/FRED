import { MfuPresenterService } from './mfu-presenter.service';

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
